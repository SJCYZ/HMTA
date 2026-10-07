package com.sjcyz.hmta.nfc.transport

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 方向 B（华为发送 → OPPO 接收）NDEF 卡模拟。
 *
 * OPPO 无操作自动接收的触发链（逆向确认）：
 *  OPPO NFC 读到对端 NDEF（AID D2760000850101）→ 广播
 *  com.oplus.nfc.action.DIRECT_DISCOVERED → NfcDiscoveryService →
 *  protocol.e.c(ndef, PAD, SIMULATION_CARD)：要求记录 MIME="app/ptctouch"，
 *  payload=[header] + AES-GCM 密文(NfcPublishData protobuf) →
 *  解密出 NfcPublishData → 分享应用自动拉起接收确认。
 *
 * 本服务以 HCE 呈现该 NDEF（SELECT + READ BINARY 分页），payload 使用与
 * OPPO 相同的 AES-GCM（key=D78F…，header=0x03，12 字节随机 IV）。
 */
class OppoNdefHceService : HostApduService() {

    companion object {
        const val ACTION_HCE_LOG = "com.sjcyz.hmta.NDEF_HCE_LOG"
        const val EXTRA_LINE = "line"

        // NDEF 呈现模式：0=同品牌 app/ptctouch（0x9953 线路），1=iOS URI（connect.oppo.com）
        const val MODE_SAME_BRAND_PTC = 0
        const val MODE_IOS_URI = 1

        // NDEF（Type 2）AID，OPPO 读卡器 SELECT 后按 ISO 7816 READ BINARY 读内容
        private const val NDEF_AID_V2 = "D2760000850101"
        private const val SELECT_PREFIX = "00A40400"
        private const val READ_BINARY_PREFIX = "00B0"

        // 同品牌 NFC 载荷 AES-GCM 密钥（32 字节，与 OPPO 端一致）
        private val AES_KEY = "D78FE3197EA65E04635262D97A7BBE9DB59D1F660D0F42D3".let { hex ->
            ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }

        // 本机（华为）信息，由 Activity 更新
        @Volatile
        var btMac: String = "02:00:00:00:00:00"

        @Volatile
        var topActivityPackageName: String = "com.sjcyz.hmta"

        // 被对端 SELECT 命中次数（方向 B 触碰门槛：每次发送需一次新的 NFC 触碰）
        @Volatile
        var selectCount: Int = 0

        @Volatile
        // 门控测试结论（2026-08-14）：iOS URI NDEF 不会触发 OPPO 接收（OPPO 弹
        // TechListChooser，无 accessory/iOS 客户端活动）；同品牌 app/ptctouch 才是
        // 可用的华为→OPPO 触发线路，故默认切回同品牌。
        var ndefMode: Int = MODE_SAME_BRAND_PTC

        @Volatile
        var iosCode: String = ""

        @Volatile
        private var ndefBytes: ByteArray? = null

        /** 重建 NDEF 消息（MIME app/ptctouch，payload=header+IV+AES-GCM 密文）。 */
        @Synchronized
        fun buildNdefMessage(): ByteArray {
            ndefBytes?.let { return it }
            if (ndefMode == MODE_IOS_URI) {
                ndefBytes = buildIosUriNdef()
                return ndefBytes!!
            }
            val payload = encryptPublishData(buildNfcPublishData())
            // NDEF MIME 记录：TNF=2（媒体），type="app/ptctouch"
            val type = "app/ptctouch".toByteArray(Charsets.US_ASCII)
            val out = ByteArrayOutputStream()
            // 单记录消息，无 MB/ME 额外位（MB=ME=1 → 0xC1）
            out.write(0xC1)
            out.write(type.size)
            out.write(payload.size)
            out.write(type)
            out.write(payload)
            val msg = out.toByteArray()
            ndefBytes = msg
            return msg
        }

        /** iOS 线路 URI NDEF：与方向 A 中 OPPO 发布的格式一致。 */
        private fun buildIosUriNdef(): ByteArray {
            if (iosCode.isBlank() || iosCode.length != 16) {
                val sb = StringBuilder()
                repeat(16) { sb.append("0123456789ABCDEF"[SecureRandom().nextInt(16)]) }
                iosCode = sb.toString()
            }
            val uri = "connect.oppo.com/oshare/clips/seo?version=1" +
                "&devId=HUAWEI-NFCProbe" +
                "&code=$iosCode" +
                "&devType=3" +
                "&devName=HUAWEI%20Mate"
            val type = byteArrayOf(0x55) // 'U'（NFC Forum well-known URI）
            val payload = uri.toByteArray(Charsets.US_ASCII)
            val out = ByteArrayOutputStream()
            // MB|ME|SR|TNF=1：0xD1
            out.write(0xD1)
            out.write(type.size)
            out.write(payload.size)
            out.write(type)
            out.write(payload)
            val msg = out.toByteArray()
            ndefBytes = msg
            logStatic("iOS URI NDEF 已生成（code=$iosCode，${msg.size} 字节）")
            return msg
        }

        private fun logStatic(line: String) {
            android.util.Log.i("OppoNdefHceService", line)
        }

        /** 强制下次触碰重建（MAC/包名变化时调用）。 */
        @Synchronized
        fun invalidate() {
            ndefBytes = null
        }

        /** NfcPublishData protobuf：1=deviceType,2=connectType,3=btMac,4=btEnabled,
         *  5=wifiEnabled,9=version,17=topActivityPackageName,18=peerPTCVersion。 */
        private fun buildNfcPublishData(): ByteArray {
            val out = ByteArrayOutputStream()
            // field 1 deviceType = 8（PAD，OPPO DIRECT_DISCOVERED 同品牌路径）
            writeVarintField(out, 1, 8)
            // field 2 connectType = 32
            writeVarintField(out, 2, 32)
            // field 3 btMacAddress（string）
            val mac = btMac.lowercase()
            writeBytesField(out, 3, mac.toByteArray(Charsets.US_ASCII))
            // field 4 btEnabled = true
            writeVarintField(out, 4, 1)
            // field 5 wifiEnabled = true
            writeVarintField(out, 5, 1)
            // field 9 version = 1
            writeVarintField(out, 9, 1)
            // field 17 topActivityPackageName
            writeBytesField(out, 17, topActivityPackageName.toByteArray(Charsets.US_ASCII))
            // field 18 peerPTCVersion = 16.35.0
            writeBytesField(out, 18, "16.35.0".toByteArray(Charsets.US_ASCII))
            return out.toByteArray()
        }

        private fun encryptPublishData(plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), GCMParameterSpec(128, iv))
            val enc = cipher.doFinal(plain)
            val out = ByteArrayOutputStream()
            out.write(0x03)          // header（同品牌 publish header）
            out.write(iv)            // 12 字节 IV
            out.write(enc)           // 密文 + 16 字节 GCM tag
            return out.toByteArray()
        }

        private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
            writeVarint(out, (field shl 3).toLong() or 0)
            writeVarint(out, value)
        }

        private fun writeBytesField(out: ByteArrayOutputStream, field: Int, value: ByteArray) {
            writeVarint(out, (field shl 3).toLong() or 2)
            writeVarint(out, value.size.toLong())
            out.write(value)
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v == 0L) {
                    out.write(b)
                    break
                } else {
                    out.write(b or 0x80)
                }
            }
        }
    }

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val hex = commandApdu.toHex()
        log("收到 APDU: $hex")
        return try {
            when {
                hex.startsWith(SELECT_PREFIX) -> {
                    val lc = if (hex.length >= 10) hex.substring(8, 10).toInt(16) else 0
                    val aid = if (lc > 0 && hex.length >= 10 + lc * 2) {
                        hex.substring(10, 10 + lc * 2)
                    } else {
                        ""
                    }
                    if (aid == NDEF_AID_V2) {
                        selectCount++
                        log("SELECT NDEF AID 命中 → 9000")
                        "9000".hexToBytes()
                    } else {
                        log("SELECT 其他 AID → 6A82")
                        "6A82".hexToBytes()
                    }
                }
                hex.startsWith(READ_BINARY_PREFIX) -> {
                    val msg = buildNdefMessage()
                    val off = hex.substring(4, 6).toInt(16) shl 8 or hex.substring(6, 8).toInt(16)
                    val len = if (hex.length >= 10) hex.substring(8, 10).toInt(16) else 0
                    log("READ BINARY off=$off len=$len（NDEF 共 ${msg.size} 字节）")
                    if (off >= msg.size) {
                        "6B00".hexToBytes()
                    } else {
                        val end = minOf(off + len, msg.size)
                        val chunk = msg.copyOfRange(off, end)
                        chunk + "9000".hexToBytes()
                    }
                }
                else -> {
                    log("未知命令 → 6F00")
                    "6F00".hexToBytes()
                }
            }
        } catch (e: Exception) {
            log("APDU 处理异常: ${e.message}")
            "6F00".hexToBytes()
        }
    }

    override fun onDeactivated(reason: Int) {
        val text = when (reason) {
            DEACTIVATION_LINK_LOSS -> "链路丢失"
            DEACTIVATION_DESELECTED -> "被取消选择"
            else -> "未知($reason)"
        }
        log("NDEF HCE 失活: $text")
    }

    private fun log(line: String) {
        sendBroadcast(Intent(ACTION_HCE_LOG).putExtra(EXTRA_LINE, line))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val clean = replace(" ", "").uppercase()
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
