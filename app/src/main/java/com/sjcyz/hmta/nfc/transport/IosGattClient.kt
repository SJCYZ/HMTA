package com.sjcyz.hmta.nfc.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * OPPO iOS 兼容线路 GATT 客户端（华为作为 iOS 对端）。
 *
 * 逆向依据 docs/逆向-iOS协商协议-OPPO兼容.md：
 *  1. 连 OPPO 0x9999 服务，订阅 0x9898 通知
 *  2. 读 0x9895 → {"state","key"(OPPO EC公钥),"version","pv"}
 *  3. ECDH + AES/CBC 派生密钥（Base64(secret) 前 16 字符，IV=0102030405060708）
 *  4. 写 0x9896 init JSON（version>=10302 明文）：key/isFast/version/pv/type/number/dname/rdcode
 *  5. 收 0x9898 {"account_id":enc} → 解密 → 写 {"account_id":enc(任意)}
 *     （OPPO 账号不匹配 → OPPO 弹接收确认，用户确认后继续）
 *  6. 收 0x9898 {"wlan","ip",...} → 写 wlan 确认 → OPPO 建热点
 *  7. 收 0x9898 {"ssid","psk","ip","port"} → 回调 onWifiInfo
 */
@SuppressLint("MissingPermission")
class IosGattClient(
    private val context: Context,
    private val mac: String,
    private val ndefCode: String,
    // 读 band 前的缓冲毫秒数（默认 2s）。注意：必须在 OPPO 用户上滑（iOS 发送任务
    // 创建、BLE 对端已连接）之后才读 band，否则 OPPO 返回 state=1 并自杀。
    private val bandReadDelayMs: Long = 2000L,
    // 预连接完成（MTU 协商结束）回调：BLE 连接保持，等待用户上滑后由外部触发读 band
    private val onReady: (() -> Unit)? = null,
    private val onLog: (String) -> Unit,
    // iOS 线路接收确认：收到 wlan 信息（OPPO 想发文件）后回调，由 UI 弹“接受/拒绝”；
    // 接受后调用 sendAccept()（写 0），拒绝调用 sendReject()（写 4）
    private val onReceiveConfirm: ((sender: String) -> Unit)? = null,
    private val onWifiInfo: (ssid: String, psk: String, ip: String, port: Int) -> Unit = { _, _, _, _ -> },
    private val onFinished: (success: Boolean, reason: String) -> Unit = { _, _ -> }
) {
    companion object {
        val SERVICE = UUID.fromString("00009999-0000-1000-8000-00805f9b34fb")
        val C_9897 = UUID.fromString("00009897-0000-1000-8000-00805f9b34fb") // iOS band 读
        val C_9896 = UUID.fromString("00009896-0000-1000-8000-00805f9b34fb") // iOS 命令写
        val C_9898 = UUID.fromString("00009898-0000-1000-8000-00805f9b34fb") // iOS 通知
        val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val VERSION = "10302"
        const val PV = 6
        const val TIMEOUT_MS = 20000L
        const val CONNECT_TIMEOUT_MS = 8000L
        const val MAX_CONNECT_RETRIES = 2
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connected = false
    @Volatile private var state = 0  // 0=idle 1=band-read 6=account 3=wlan 4=transfer
    private var c9897: BluetoothGattCharacteristic? = null
    private var c9896: BluetoothGattCharacteristic? = null
    private var c9898: BluetoothGattCharacteristic? = null
    // 0x9896 写入队列：Android GATT 必须等上一次写回调完成才能发下一次，
    // 否则写入会被栈丢弃（OPPO 收不到确认导致 ACK_TIMEOUT）
    private val writeQueue = ArrayDeque<Pair<String, String>>()
    private var writeInFlight = false
    private val uiHandler = Handler(Looper.getMainLooper())

    // 华为（客户端）EC 密钥对
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val publicKeyB64: String = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
    private var cbcSecret: SecretKey? = null
    private var oppoPublicKeyB64: String = ""
    private var connectAttempts = 0

    private val timeoutRunnable = Runnable {
        onLog("!! iOS 协商超时（${TIMEOUT_MS / 1000}s）state=$state")
        onFinished(false, "iOS 协商超时（state=$state）")
        disconnect()
    }

    // 连接/服务发现阶段兜底超时：若 onServicesDiscovered 一直不回调（OPPO server 已销毁、
    // BLE 栈挂起等），8 秒后强制结束，避免互斥永久卡死、后续触碰被吞掉。
    private val connectTimeoutRunnable = Runnable {
        if (connectAttempts < MAX_CONNECT_RETRIES) {
            connectAttempts++
            onLog("!! 连接/服务发现超时，重试（$connectAttempts/${MAX_CONNECT_RETRIES}）…")
            try { gatt?.disconnect() } catch (_: Exception) {}
            try { gatt?.close() } catch (_: Exception) {}
            gatt = null
            connected = false
            doConnect()
        } else {
            onLog("!! 连接/服务发现超时（${CONNECT_TIMEOUT_MS / 1000}s）")
            onFinished(false, "连接/服务发现超时")
            disconnect()
        }
    }

    // 单次 0x9896 写入的兜底超时：OPPO 若无响应，强制复位队列继续
    private val writeTimeoutRunnable = Runnable {
        writeInFlight = false
        pumpWriteQueue()
    }

    // MTU 协商完成后延迟读 band：给 OPPO 用户上滑确认留出时间窗口
    private val bandReadRunnable = Runnable {
        onLog(">> 读 0x9897（iOS band）…")
        gatt?.readCharacteristic(c9897)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                onLog("★ 已连接 OPPO iOS server ${gatt.device.address}，服务发现…")
                uiHandler.removeCallbacks(connectTimeoutRunnable)
                uiHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                onLog("GATT 断开 status=$status")
                uiHandler.removeCallbacksAndMessages(null)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != 0) {
                onLog("服务发现失败 status=$status")
                onFinished(false, "服务发现失败")
                return
            }
            val svc = gatt.getService(SERVICE)
            if (svc == null) {
                onLog("!! 未发现 0x9999 服务（可能不是 iOS server）")
                onFinished(false, "未发现 0x9999")
                return
            }
            c9897 = svc.getCharacteristic(C_9897)
            c9896 = svc.getCharacteristic(C_9896)
            c9898 = svc.getCharacteristic(C_9898)
            if (c9897 == null || c9896 == null || c9898 == null) {
                onLog("!! iOS 特征缺失（9897=${c9897 != null} 9896=${c9896 != null} 9898=${c9898 != null}）")
                onFinished(false, "iOS 特征缺失")
                return
            }
            uiHandler.removeCallbacks(connectTimeoutRunnable)
            // 订阅 0x9898 通知
            gatt.setCharacteristicNotification(c9898, true)
            c9898?.getDescriptor(CCCD)?.let { desc ->
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
            // 预连接阶段不启动协商超时：BLE 保持连接等待 OPPO 上滑，由 readBand() 启动
            onLog(">> requestMtu(512)…")
            gatt.requestMtu(512)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            onLog("MTU = $mtu status=$status（BLE 预连接完成）")
            // 不在此读 band：必须等 OPPO 用户上滑（iOS 发送任务创建）后由外部触发，
            // 否则 band 返回 state=1 且 OPPO 直接停止服务
            uiHandler.post { onReady?.invoke() }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            onLog("通知订阅 0x9898 status=$status")
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == C_9897) {
                val v = characteristic.value
                onLog("<< 0x9897 读响应: ${v?.let { String(it, StandardCharsets.UTF_8) }}")
                if (status == 0 && v != null) {
                    try {
                        val raw = String(v, StandardCharsets.UTF_8)
                        // 响应可能被多次读取拼接（多个客户端并发），提取第一个完整 JSON 对象
                        val json = try {
                            JSONObject(raw)
                        } catch (e: Exception) {
                            val start = raw.indexOf('{')
                            val end = raw.indexOf('}', start)
                            if (start >= 0 && end > start) {
                                JSONObject(raw.substring(start, end + 1))
                            } else {
                                throw e
                            }
                        }
                        oppoPublicKeyB64 = json.optString("key", "")
                        val peerVersion = json.optString("version", "")
                        onLog("★ OPPO iOS server: version=$peerVersion key=${oppoPublicKeyB64.length}字符")
                        val st = json.optInt("state", 0)
                        if (st != 0) {
                            // OPPO z()：band state!=0 → NfcIosServer.e() 直接停止服务。
                            // 原因：OPPO 尚处于“非传输”状态且已有任务/选中设备，需用户先上滑确认发送。
                            onLog("!! OPPO 未就绪（band state=$st）——OPPO 需先上滑确认发送，本轮协商终止")
                            onFinished(false, "OPPO 未就绪：请在 OPPO 端上滑确认后再次触碰")
                            return
                        }
                        if (oppoPublicKeyB64.isEmpty()) {
                            onFinished(false, "OPPO 未返回 EC 公钥")
                            return
                        }
                        deriveSecret()
                        state = 1
                        writeInit()
                    } catch (e: Exception) {
                        onLog("0x9897 解析失败: ${e.message}")
                        onFinished(false, "0x9897 解析失败")
                    }
                } else {
                    onLog("0x9897 读取失败 status=$status")
                    onFinished(false, "0x9897 读取失败")
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == C_9898) {
                val raw = String(characteristic.value, StandardCharsets.UTF_8)
                onLog("<< 0x9898 通知: $raw")
                handleNotify(raw)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == C_9896) {
                onLog(">> 0x9896 写入完成 status=$status（state=$state）")
                uiHandler.removeCallbacks(writeTimeoutRunnable)
                writeInFlight = false
                pumpWriteQueue()
            }
        }
    }

    private fun deriveSecret() {
        val keyFactory = KeyFactory.getInstance("EC")
        val peerPub = keyFactory.generatePublic(
            X509EncodedKeySpec(Base64.decode(oppoPublicKeyB64, Base64.DEFAULT))
        )
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(peerPub, true)
        val raw = ka.generateSecret("TlsPremasterSecret")
        val b64 = Base64.encodeToString(raw.encoded, Base64.NO_WRAP)
        val keyStr = if (b64.length > 16) b64.substring(0, 16) else b64.padEnd(16, '0')
        cbcSecret = SecretKeySpec(keyStr.toByteArray(StandardCharsets.UTF_8), "AES")
        onLog("★ ECDH 密钥已派生（AES/CBC，Base64 前 16）")
    }

    /** AES/CBC/PKCS5Padding，IV=0102030405060708（镜像 lb/a0.g/f） */
    private fun aesCbcEncrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, cbcSecret, IvParameterSpec("0102030405060708".toByteArray(StandardCharsets.UTF_8)))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun aesCbcDecrypt(b64: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, cbcSecret, IvParameterSpec("0102030405060708".toByteArray(StandardCharsets.UTF_8)))
        return String(cipher.doFinal(Base64.decode(b64, Base64.DEFAULT)), StandardCharsets.UTF_8)
    }

    /** 写 init JSON（version>=10302 明文，rdcode=NDEF code） */
    private fun writeInit() {
        val json = JSONObject().apply {
            put("key", publicKeyB64)
            put("isFast", false)
            put("version", VERSION)
            put("pv", PV)
            put("type", "file/*")
            put("number", 1)
            put("dname", "HUAWEI Mate")
            put("rdcode", ndefCode)
        }.toString()
        write9896(json, "init")
    }

    /** 写 account_id（任意值加密；OPPO 账号不匹配会弹接收确认，用户确认后继续） */
    private fun writeAccount() {
        val fakeAccount = "nfcprobe-" + System.currentTimeMillis()
        val enc = aesCbcEncrypt(fakeAccount)
        val json = JSONObject().put("account_id", enc).toString()
        write9896(json, "account")
    }

    /** 写 wlan 确认（触发 OPPO 建热点） */
    private fun writeWlanConfirm() {
        // OPPO q()：写非 3/4/5/6/7 整数 → G0() 建热点；写 {"wlan"} → r() 检查
        write9896("{\"wlan\":\"wlan\"}", "wlan")
    }

    /**
     * 加入 OPPO 热点后回写本机 IP/端口（iOS 线路关键步骤）。
     *
     * OPPO d8/v.java q()（N=3 写处理）：收到含 "ip" 的 JSON →
     * 移除 20s 热点等待定时器 → l() → N=4 → 启动 HttpHotspotServer
     * （iOSNettyServer 绑定 0.0.0.0:port）→ 下发 {"reuse","port",...}。
     * 服务器启动后华为才能连上 WSS；不回写则 OPPO 一直等客户端直至超时。
     */
    fun sendIpPort(ip: String, port: Int) {
        if (gatt == null || c9896 == null) {
            onLog("!! 回写 ip/port 失败：GATT 已断开")
            return
        }
        val json = JSONObject().apply {
            put("ip", ip)
            put("port", port)
        }.toString()
        onLog("★ 回写本机 ip/port → OPPO（触发其启动 HTTP 服务器）")
        write9896(json, "ip_port")
    }

    /** iOS 线路接收确认：接受（写 0，OPPO 建热点继续）。 */
    fun sendAccept() {
        if (state == 3) {
            onLog("★ 用户接受接收，写热点确认(0)…")
            write9896("0", "hotspot_ack")
        }
    }

    /** iOS 线路接收确认：拒绝（写 4，OPPO hotspot_reject 取消）。 */
    fun sendReject() {
        if (state == 3) {
            onLog("★ 用户拒绝接收，写 4…")
            write9896("4", "hotspot_reject")
        }
    }

    private fun write9896(json: String, tag: String) {
        writeQueue.add(json to tag)
        pumpWriteQueue()
    }

    /** 串行发送 0x9896 写入（等待上一次 onCharacteristicWrite 回调后再发下一次） */
    private fun pumpWriteQueue() {
        if (writeInFlight) return
        val g = gatt ?: return
        val ch = c9896 ?: return
        val item = writeQueue.removeFirstOrNull() ?: return
        writeInFlight = true
        ch.value = item.first.toByteArray(StandardCharsets.UTF_8)
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        onLog(">> 0x9896 ${item.second}: ${item.first}")
        uiHandler.removeCallbacks(writeTimeoutRunnable)
        uiHandler.postDelayed(writeTimeoutRunnable, 5000L)
        val ok = try {
            g.writeCharacteristic(ch)
        } catch (e: Exception) {
            onLog("写 0x9896 异常: ${e.message}")
            false
        }
        if (!ok) {
            writeInFlight = false
            pumpWriteQueue()
        }
    }

    /** 处理 0x9898 通知（状态机推进） */
    private fun handleNotify(raw: String) {
        try {
            val json = JSONObject(raw)
            when {
                // OPPO init 后第一条通知（softap.a.c）：{"ip","dname","key","number","type","pv","OLiveSF","OLivePF"}
                // 期望华为回写确认（接受=0，拒绝=4/7）；OPPO 随后建热点并下发 ssid/psk
                (json.has("OLiveSF") || json.has("OLivePF")) || (json.has("dname") && json.has("key") && json.has("type")) -> {
                    val sender = json.optString("dname")
                    onLog("★ 收到 OPPO 发送请求（dname=$sender type=${json.optString("type")} number=${json.optString("number")}），等待用户确认…")
                    state = 3
                    onReceiveConfirm?.invoke(sender)
                    // 无确认回调（旧路径/超时）时自动接受，避免流程卡死
                    if (onReceiveConfirm == null) write9896("0", "hotspot_ack")
                }
                // 热点凭据：{"ssid","psk","ip","port"}（softap.a.a）
                json.has("ssid") -> {
                    val ssid = tryDecrypt(json.optString("ssid"))
                    val psk = tryDecrypt(json.optString("psk"))
                    val ip = tryDecrypt(json.optString("ip"))
                    val port = tryDecrypt(json.optString("port")).toIntOrNull() ?: 8959
                    onLog("★ OPPO 热点凭据: ssid=$ssid psk=$psk ip=$ip port=$port")
                    uiHandler.removeCallbacks(timeoutRunnable)
                    state = 4
                    onWifiInfo(ssid, psk, ip, port)
                }
                // 回写 ip/port 后 OPPO 启动服务器并下发 {"reuse","port","reuse_socket","vender","device_type"}
                json.has("reuse") || json.has("reuse_socket") -> {
                    onLog("★ OPPO 服务器已启动（reuse 消息：${raw.take(120)}）")
                }
                // wlan 信息：{"wlan","ip",...}（softap.a.d）→ 写 wlan 确认
                json.has("wlan") -> {
                    val ip = tryDecrypt(json.optString("ip"))
                    onLog("★ OPPO wlan 信息: ip=$ip，写 wlan 确认…")
                    state = 3
                    writeWlanConfirm()
                }
                // account_id：解密后回写（任意值）
                json.has("account_id") -> {
                    val dec = tryDecrypt(json.optString("account_id"))
                    onLog("★ 收到 account_id（解密=${dec.take(20)}…），回写…")
                    state = 6
                    writeAccount()
                }
                else -> onLog("0x9898 未知消息: $raw")
            }
        } catch (e: Exception) {
            onLog("0x9898 解析失败: ${e.message}")
        }
    }

    private fun tryDecrypt(v: String): String {
        if (v.isEmpty()) return ""
        return try {
            aesCbcDecrypt(v)
        } catch (e: Exception) {
            onLog("字段解密失败（按明文）: ${e.message}")
            v
        }
    }

    fun connect() {
        connectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = manager.adapter
            if (adapter == null || !adapter.isEnabled) {
                onLog("蓝牙未开启")
                return
            }
            val device = adapter.getRemoteDevice(mac)
            onLog("正在连接 OPPO iOS server: $mac（TRANSPORT_LE）…")
            uiHandler.removeCallbacks(connectTimeoutRunnable)
            uiHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS)
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            onLog("连接异常: ${e.message}")
        }
    }

    fun disconnect() {
        uiHandler.removeCallbacksAndMessages(null)
        writeQueue.clear()
        writeInFlight = false
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        connected = false
    }

    /** 外部触发读 band：OPPO 用户上滑（发送任务创建）后调用，delayMs 为读 band 前缓冲 */
    fun readBand(delayMs: Long = bandReadDelayMs) {
        if (gatt == null || c9897 == null) {
            onLog("readBand 失败：GATT 未就绪")
            onFinished(false, "GATT 未就绪")
            return
        }
        uiHandler.removeCallbacks(timeoutRunnable)
        uiHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        uiHandler.removeCallbacks(bandReadRunnable)
        uiHandler.postDelayed(bandReadRunnable, delayMs)
    }
}
