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
 * OShare 接收端 GATT 客户端（补齐逆向协商步骤）。
 *
 * 完整时序（对应 OPPO d8/v BleServer + lb/a0 加密）：
 *   connectGatt(TRANSPORT_LE) → discoverServices
 *   → 服务发现成功 → 订阅 0x9985（BLE_ACK_DIALOG）通知
 *   → requestMtu(512) → 读 0x9954（登记对端：OPPO 服务端在读请求里
 *     会把会话置为 hasRead=true / transferType=2，之后才接受 0x9953）
 *   → 用本机 EC 公钥构造发送端 JSON（id/ssid/psk/mac/freq/port/key）一次写入 0x9953
 *   → 等 0x9985 通知：Base64(JSON{ssid,psk,ip,port,...})，字段 AES/CBC 加密
 *   → ECDH(本机私钥, OPPO公钥) 派生 AES 密钥解密 → 回调 onWifiInfo
 *
 * 注意：0x9953 必须一次写完完整 JSON（offset=0 会重置服务端缓冲区）。
 *
 * 时序陷阱：0x9954 的 state 字段是 OPPO「全局状态」映射（0=空闲/终态集合
 * IDLE/CANCEL/TIMEOUT…，1=活跃），**不是“接收就绪”信号**——OPPO 超时后
 * 同样返回 0。因此本客户端不做 state 轮询：读 0x9954 后立即写 0x9953，
 * “写成功”只是 BLE 层 ACK，是否真正处理要等 OPPO 加入热点/连接 8959 确认。
 */
@SuppressLint("MissingPermission")
class OshareGattClient(
    private val context: Context,
    private val mac: String,
    private val onLog: (String) -> Unit,
    private val onFinished: (success: Boolean, reason: String) -> Unit = { _, _ -> },
    private val onWifiInfo: (ssid: String, psk: String, freq: Int, ip: String, port: Int) -> Unit = { _, _, _, _, _ -> },
    // 本机（接收端）自建热点的凭据：加密后随 0x9953 发给 OPPO，OPPO 作为 HTTP 客户端回连
    private val ourMac: String = "",
    private val ourSsid: String = "",
    private val ourPsk: String = "",
    private val ourFreq: Int = 2412,
    // 接收端模式：华为作为接收方，写 ka.c.h（id/mac/freq/port，不带热点凭据），
    // 促使 OPPO 发送方自己建热点并通过 0x9985 通知下发凭据（OPPO→华为方向）
    private val receiverMode: Boolean = false,
    private val ourBtMac: String = "",
    // 实验：连上后只读 0x9954 登记对端，不写 0x9953，直接等 0x9985/0x9997 通知
    private val skipWrite9953: Boolean = false
) {
    companion object {
        val SERVICE_COMMON = UUID.fromString("00009955-0000-1000-8000-00805f9b34fb")
        val CHAR_STATUS = UUID.fromString("00009954-0000-1000-8000-00805f9b34fb")
        val CHAR_P2P = UUID.fromString("00009953-0000-1000-8000-00805f9b34fb")
        // 完整协商服务 0xbb15（逆向 d8/v 实测注册位置），含 0x9985 通知特征
        val CHAR_ACK_DIALOG = UUID.fromString("00009985-0000-1000-8000-00805f9b34fb")
        val CHAR_UPDATE = UUID.fromString("00009997-0000-1000-8000-00805f9b34fb")
        val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val REQUEST_MTU = 512
        const val MTU_RETRY_MAX = 3
        const val RETRY_DELAY_MS = 1800L
        const val WRITE_TIMEOUT_MS = 12000L
    }

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connected = false
    @Volatile private var servicesDiscovered = false
    @Volatile private var mtu = 23
    @Volatile private var mtuDone = false
    @Volatile private var statusReadDone = false
    @Volatile private var jsonWritten = false
    @Volatile private var wifiInfoReceived = false
    private var mtuRetries = 0
    private var statusReadRetries = 0
    private var writeTimeoutPending = false
    private var p2pChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    // ---- ECDH 客户端密钥对（镜像 lb/a0.j()） ----
    private val keyPair: KeyPair = try {
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    } catch (e: Exception) {
        onLog("EC 密钥对生成失败: ${e.message}")
        KeyPairGenerator.getInstance("EC").generateKeyPair()
    }
    val publicKeyB64: String =
        Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
    /** 原始 ECDH 共享密钥（AES/CTR，0x9953 发送用，镜像 lb/a0.d/r） */
    private var rawSecretKey: SecretKey? = null
    /** Base64 截断派生密钥（AES/CBC，0x9985 收 WiFi 信息备用） */
    private var cbcSecretKey: SecretKey? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                onLog("★ GATT 已连接 ${gatt.device.address}（LE），开始服务发现…")
                try { gatt.discoverServices() } catch (e: Exception) {
                    onLog("服务发现异常: ${e.message}")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                onLog("GATT 已断开（status=$status）")
                uiHandler.removeCallbacksAndMessages(null)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != 0) {
                onLog("服务发现失败 status=$status")
                return
            }
            servicesDiscovered = true
            onLog("服务发现完成：")
            gatt.services.forEach { svc ->
                svc.characteristics.forEach { ch ->
                    when (ch.uuid) {
                        CHAR_P2P -> p2pChar = ch
                        CHAR_STATUS -> statusChar = ch
                    }
                }
            }
            if (statusChar == null || p2pChar == null) {
                onLog("!! 未发现 0x9955（0x9954/0x9953），连接对象可能不是 OShare 发送端")
                return
            }
            // 先订阅 0x9985 通知，避免漏掉 OPPO 下发的 WiFi 信息
            enableAckNotifications(gatt)
            requestMtuWithRetry(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@OshareGattClient.mtu = mtu
            mtuDone = true
            onLog("MTU = $mtu (status=$status)")
            proceedAfterMtu()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int
        ) {
            onLog("<< 通知订阅 ${descriptor.characteristic.uuid} status=$status")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic
        ) {
            when (characteristic.uuid) {
                CHAR_ACK_DIALOG -> handleAckDialog(characteristic.value)
                CHAR_UPDATE -> onLog("<< 0x9997 更新通知: ${characteristic.value?.toHex()}")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (characteristic.uuid == CHAR_STATUS) {
                val v = characteristic.value
                onLog("<< 读取 0x9954 status=$status: ${v?.toHex() ?: ""}")
                if (status == 0) {
                    statusReadDone = true
                    // 读 0x9954 本身完成“登记对端”（OPPO d8/v 在读请求里置
                    // hasRead=true、transferType=2），之后立即写 0x9953。
                    parseStatusResponse(v)
                    if (receiverMode && skipWrite9953) {
                        onLog("实验模式：不写 0x9953，等待 OPPO 主动下发通知（0x9985/0x9997）…")
                        uiHandler.removeCallbacks(writeTimeoutRunnable)
                        uiHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
                    } else {
                        writeJsonOnce()
                    }
                } else if (statusReadRetries < 2) {
                    statusReadRetries++
                    onLog("读取 0x9954 失败 status=$status，${RETRY_DELAY_MS}ms 后重试…")
                    uiHandler.postDelayed({
                        if (connected) {
                            try { gatt.readCharacteristic(statusChar ?: return@postDelayed) } catch (e: Exception) {
                                onLog("重读 0x9954 异常: ${e.message}")
                            }
                        }
                    }, RETRY_DELAY_MS)
                } else {
                    onLog("读取 0x9954 多次失败，尝试直接写 0x9953")
                    if (receiverMode && skipWrite9953) {
                        onLog("实验模式：不写 0x9953，直接等待通知…")
                        uiHandler.removeCallbacks(writeTimeoutRunnable)
                        uiHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
                    } else {
                        writeJsonOnce()
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int
        ) {
            if (characteristic.uuid == CHAR_P2P) {
                jsonWritten = true
                val bytes = characteristic.value?.size ?: 0
                onLog(">> 写 0x9953 完成 status=$status（$bytes 字节，MTU=$mtu）")
                uiHandler.removeCallbacks(writeTimeoutRunnable)
                if (status != 0) {
                    onLog("!! 写入失败 status=$status")
                    onFinished(false, "写 0x9953 失败 status=$status")
                } else {
                    onLog("★ 0x9953 写入成功（BLE ACK）")
                    if (receiverMode) {
                        onLog("接收端模式：等待 OPPO 下发热点凭据（0x9985 通知）…")
                        wifiInfoReceived = false
                        uiHandler.removeCallbacks(writeTimeoutRunnable)
                        uiHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
                    } else {
                        onLog("（0x9985 是发送方→接收方通道，本方向不需要等它；")
                        onLog("  真正的处理确认 = OPPO 加入本机热点 + 连接 8959，由 8959 服务器侧观察）")
                        uiHandler.removeCallbacks(writeTimeoutRunnable)
                        onFinished(true, "0x9953 已写入（BLE ACK），等待 OPPO 加入热点并连接 8959")
                    }
                }
            }
        }
    }

    private val writeTimeoutRunnable = Runnable {
        if (receiverMode) {
            if (!wifiInfoReceived && connected) {
                onLog("!! 等待 OPPO 热点凭据超时（${WRITE_TIMEOUT_MS / 1000}s 无 0x9985/0x9997 通知）")
                onLog("   诊断：OPPO 服务器路径可能期望 ka.c.i（本机热点凭据）而非 ka.c.h；")
                onLog("   或 OPPO 走的是 RFCOMM/AgentClient 通道（本机未实现）。")
                onFinished(false, "OPPO 未下发通知（0x9985/0x9997 超时）")
                disconnect()
            }
        } else if (!jsonWritten && connected) {
            onLog("!! 写 0x9953 后 ${WRITE_TIMEOUT_MS / 1000} 秒无回调")
            onLog(">> 诊断：重读 0x9954 观察 OPPO 会话状态…")
            try {
                gatt?.readCharacteristic(statusChar ?: return@Runnable)
            } catch (e: Exception) {
                onLog("重读 0x9954 异常: ${e.message}")
            }
            onFinished(
                false,
                "OPPO 应用层未处理 0x9953（BLE ACK 不代表处理成功）：请确认 OPPO 已完成标准 NFC 交换并确认接收"
            )
            disconnect()
        }
    }

    /** 解析 0x9954 响应：{"state","mac","key"}，用对端 EC 公钥派生共享密钥 */
    private fun parseStatusResponse(v: ByteArray?): Int {
        if (v == null) return -1
        try {
            val json = JSONObject(String(v, StandardCharsets.UTF_8))
            val state = json.optInt("state", -1)
            val peerMac = json.optString("mac", "")
            val peerKey = json.optString("key", "")
            onLog(
                "★ OPPO 状态: state=$state mac=$peerMac key=${
                    if (peerKey.isEmpty()) "(空)" else "EC公钥(" + peerKey.length + "字符)"
                }"
            )
            if (peerKey.isNotEmpty()) {
                rawSecretKey = ecdhSharedSecret(peerKey)
                cbcSecretKey = deriveCbcKey(rawSecretKey!!)
                onLog("★ ECDH 共享密钥已派生（0x9953 加密 / 0x9985 解密就绪）")
            } else {
                onLog("OPPO 未返回 EC 公钥，0x9953 将明文发送")
            }
            return state
        } catch (e: Exception) {
            onLog("0x9954 响应解析失败: ${e.message}")
            return -1
        }
    }

    /** ECDH：本机私钥 + 对端公钥 → TlsPremasterSecret（镜像 lb/a0.h） */
    private fun ecdhSharedSecret(peerPubB64: String): SecretKey {
        val keyFactory = KeyFactory.getInstance("EC")
        val peerPub = keyFactory.generatePublic(
            X509EncodedKeySpec(Base64.decode(peerPubB64, Base64.DEFAULT))
        )
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(peerPub, true)
        return ka.generateSecret("TlsPremasterSecret")
    }

    /** iOS/CBC 路径密钥：Base64(共享密钥) 前 16 字符（镜像 lb/a0.m） */
    private fun deriveCbcKey(raw: SecretKey): SecretKey {
        val b64 = Base64.encodeToString(raw.encoded, Base64.NO_WRAP)
        val keyStr = if (b64.length > 16) b64.substring(0, 16) else b64.padEnd(16, '0')
        return SecretKeySpec(keyStr.toByteArray(StandardCharsets.UTF_8), "AES")
    }

    /** 0x9953 发送方向加密：AES/CTR/NoPadding，IV=0102030405060708（镜像 lb/a0.d） */
    private fun aesCtrEncrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            rawSecretKey,
            IvParameterSpec("0102030405060708".toByteArray(StandardCharsets.UTF_8))
        )
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    /** 0x9985 接收方向解密：AES/CBC/PKCS5Padding（镜像 lb/a0.s） */
    private fun aesCbcDecrypt(b64: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            cbcSecretKey,
            IvParameterSpec("0102030405060708".toByteArray(StandardCharsets.UTF_8))
        )
        return String(cipher.doFinal(Base64.decode(b64, Base64.DEFAULT)), StandardCharsets.UTF_8)
    }

    /** 处理 0x9985 通知：Base64(JSON 或状态串) */
    private fun handleAckDialog(value: ByteArray?) {
        if (value == null) return
        val raw = String(value, StandardCharsets.UTF_8)
        onLog("<< 0x9985 通知: $raw")
        val decoded = try {
            String(Base64.decode(raw, Base64.DEFAULT), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            raw
        }
        when {
            decoded == "ok" -> {
                onLog("OPPO 确认 ok")
                uiHandler.removeCallbacks(writeTimeoutRunnable)
            }
            decoded == "no" -> {
                onFinished(false, "OPPO 拒绝（no）")
                disconnect()
            }
            decoded == "time" -> {
                onFinished(false, "OPPO 超时（time）")
                disconnect()
            }
            decoded.startsWith("{") -> parseWifiInfoJson(decoded)
            else -> onLog("0x9985 内容未识别: $decoded")
        }
    }

    private fun parseWifiInfoJson(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            fun field(key: String): String {
                val v = json.optString(key, "")
                if (v.isEmpty()) return ""
                if (cbcSecretKey == null) return v
                return try {
                    aesCbcDecrypt(v)
                } catch (e: Exception) {
                    onLog("字段 $key 解密失败（按明文）: ${e.message}")
                    v
                }
            }
            val ssid = field("ssid")
            val psk = field("psk")
            val ip = field("ip")
            val portStr = field("port")
            val freq = json.optInt("freq", 0)
            val isFast = json.optInt("isFast", 0)
            val port = portStr.toIntOrNull() ?: 8959
            onLog("★ OPPO WiFi 信息: ssid=$ssid psk=$psk ip=$ip port=$port freq=$freq isFast=$isFast")
            uiHandler.removeCallbacks(writeTimeoutRunnable)
            wifiInfoReceived = true
            if (ssid.isNotEmpty() && psk.isNotEmpty()) {
                onWifiInfo(ssid, psk, freq, ip, port)
            } else {
                onLog("WiFi 信息缺少 ssid/psk，可能解密失败或 OPPO 未建组")
            }
        } catch (e: Exception) {
            onLog("WiFi 信息解析失败: ${e.message}")
        }
    }

    private fun enableAckNotifications(gatt: BluetoothGatt) {
        gatt.services.forEach { svc ->
            svc.characteristics.forEach { ch ->
                if (ch.uuid == CHAR_ACK_DIALOG || ch.uuid == CHAR_UPDATE) {
                    try {
                        gatt.setCharacteristicNotification(ch, true)
                        val cccd = ch.getDescriptor(CCCD)
                        if (cccd != null) {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(cccd)
                            onLog(">> 已订阅通知 ${ch.uuid}（写 CCCD）")
                        }
                    } catch (e: Exception) {
                        onLog("订阅 ${ch.uuid} 异常: ${e.message}")
                    }
                }
            }
        }
    }

    fun connect() {
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val adapter = manager.adapter
            if (adapter == null || !adapter.isEnabled) {
                onLog("蓝牙未开启")
                return
            }
            val device = adapter.getRemoteDevice(mac)
            onLog("正在连接 OPPO server: $mac（TRANSPORT_LE）…")
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            onLog("连接异常: ${e.message}")
        }
    }

    fun disconnect() {
        uiHandler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        connected = false
        servicesDiscovered = false
        mtuDone = false
        statusReadDone = false
        jsonWritten = false
        writeTimeoutPending = false
    }

    private fun requestMtuWithRetry(gatt: BluetoothGatt) {
        mtuRetries = 0
        doRequestMtu(gatt)
    }

    private fun doRequestMtu(gatt: BluetoothGatt) {
        try {
            val ok = gatt.requestMtu(REQUEST_MTU)
            onLog("requestMtu($REQUEST_MTU) ret=$ok")
        } catch (e: Exception) {
            onLog("requestMtu 异常: ${e.message}")
            proceedAfterMtu()
            return
        }
        uiHandler.postDelayed({
            if (!mtuDone && connected && mtuRetries < MTU_RETRY_MAX) {
                mtuRetries++
                onLog("onMtuChanged 未回调（第 $mtuRetries 次），重新 requestMtu…")
                doRequestMtu(gatt)
            } else if (!mtuDone && connected) {
                onLog("onMtuChanged 始终未回调，按当前 MTU=$mtu 继续")
                proceedAfterMtu()
            }
        }, RETRY_DELAY_MS)
    }

    private fun proceedAfterMtu() {
        if (statusReadDone) return
        val g = gatt ?: return
        val sc = statusChar
        if (sc == null) {
            onLog("0x9954 特征未就绪，无法登记对端")
            return
        }
        onLog(">> 读 0x9954（登记对端）…")
        try { g.readCharacteristic(sc) } catch (e: Exception) {
            onLog("读取 0x9954 异常: ${e.message}")
        }
    }

    /** 一次写完整个 JSON：接收端自建热点凭据（ssid/psk/mac 用 ECDH 密钥 AES/CTR 加密） */
    private fun writeJsonOnce() {
        val g = gatt ?: return
        val ch = p2pChar
        if (ch == null) {
            onLog("0x9953 特征未就绪，无法写入")
            return
        }
        val mac = if (ourMac.isNotEmpty()) ourMac else macPlaceholder()
        val json = if (receiverMode) {
            // ka.c.h：接收端→发送端，{"id","mac","freq","port"}，明文
            // id/mac 用本机真实蓝牙 MAC（与 NFC payload 一致，便于 OPPO 匹配）
            val macId = ourBtMac.ifBlank { mac }
            "{\"id\":\"$macId\",\"mac\":\"$macId\",\"freq\":0,\"port\":8959}"
        } else if (ourSsid.isNotEmpty() && ourPsk.isNotEmpty() && rawSecretKey != null) {
            val encMac = aesCtrEncrypt(mac)
            val encSsid = aesCtrEncrypt(ourSsid)
            val encPsk = aesCtrEncrypt(ourPsk)
            "{\"id\":\"$mac\",\"mac\":\"$encMac\",\"freq\":$ourFreq,\"port\":8959," +
                "\"ssid\":\"$encSsid\",\"psk\":\"$encPsk\",\"key\":\"$publicKeyB64\"}"
        } else {
            "{\"id\":\"$mac\",\"mac\":\"$mac\",\"freq\":0,\"port\":8959,\"key\":\"$publicKeyB64\"}"
        }
        val data = json.toByteArray(StandardCharsets.UTF_8)
        onLog(">> 写 0x9953 完整 JSON（${data.size} 字节，MTU=$mtu）: ${json.take(120)}…")
        ch.value = data
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            val ok = g.writeCharacteristic(ch)
            if (ok) {
                writeTimeoutPending = true
                uiHandler.removeCallbacks(writeTimeoutRunnable)
                uiHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS)
            } else {
                onLog("!! writeCharacteristic 返回 false")
                onFinished(false, "writeCharacteristic 返回 false")
            }
        } catch (e: Exception) {
            onLog("写入异常: ${e.message}")
            onFinished(false, "写入异常: ${e.message}")
        }
    }

    private fun macPlaceholder(): String {
        // 优先取 WiFi Direct/网卡真实 MAC（p2p0/wlan0），避免 02:00:00:00:00:00
        try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { intf ->
                if (intf.name == "p2p0" || intf.name == "wlan0") {
                    val hw = intf.hardwareAddress ?: return@forEach
                    if (hw.size == 6 && hw.any { it != 0.toByte() }) {
                        return hw.joinToString("") { "%02X".format(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
            val a = manager.adapter?.address
            if (!a.isNullOrBlank() && a != "02:00:00:00:00:00") a.replace(":", "") else "020000000000"
        } catch (e: Exception) {
            "020000000000"
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
