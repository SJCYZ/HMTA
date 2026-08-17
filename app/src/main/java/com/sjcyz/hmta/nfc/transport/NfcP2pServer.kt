package com.sjcyz.hmta.nfc.transport

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 发送给 OPPO 的单个文件（方向 B：华为发送 → OPPO 接收）。
 * 小文件用 bytes（内存），大文件用 uri（流式读取，不占内存）。
 */
data class SenderFile(
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val bytes: ByteArray? = null,
    val uri: android.net.Uri? = null
)

/**
 * OShare 8959 传输服务端（华为发送方向）：
 *
 * OPPO 接收方（la.b/la.c）加入发送方热点后，会以 wss://<GO-IP>:8959/websocket
 * 连接发送方（TLS + 信任任意证书），先做 WebSocket 升级，再走
 * `action:0:versionNegotiation?{"versions":[1]}` 协商。
 *
 * 本类实现：TLS（内置自签名证书）+ WebSocket 升级 + 帧解析 + versionNegotiation
 * + sendRequest（真实文件信息）+ /download（ZIP 流）+ status 完成处理。
 * 不传 senderFile 时退化为探针（test.bin）。
 */
class NfcP2pServer(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onProgress: ((done: Long, total: Long) -> Unit)? = null,
    private val senderFiles: List<SenderFile>? = null,
    private val onComplete: ((success: Boolean, reason: String) -> Unit)? = null
) {

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    // 取消发送：置标志 + 关闭当前连接（打断阻塞的 zip 流）
    @Volatile private var cancelled = false
    // 结果只回调一次（完成 / 对端取消 / 本地取消）
    @Volatile private var completed = false
    @Volatile private var currentSocket: Socket? = null
    @Volatile private var cancelReason = "已取消"

    /** 本次传输任务 id：sendRequest 与 /download 共用（OPPO 接收端按此回 status）。 */
    val taskId: String = "hmta-" + System.currentTimeMillis()

    /** 本次待发送文件列表（探针模式为空时退化为 test.bin）。 */
    private val files: List<SenderFile> = senderFiles ?: emptyList()
    private val totalSize: Long = files.sumOf { it.size }

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val sslContext = buildSslContext()
                val raw: ServerSocket = if (sslContext != null) {
                    sslContext.serverSocketFactory.createServerSocket(PORT) as SSLServerSocket
                } else {
                    ServerSocket(PORT)
                }
                serverSocket = raw
                onLog("★ 8959 WSS/HTTP 监听已启动（TLS=${sslContext != null}），等待 OPPO P2P 连接…")
                while (running) {
                    val socket = try {
                        raw.accept()
                    } catch (e: Exception) {
                        if (running) onLog("accept 异常: ${e.message}")
                        break
                    }
                    Thread { handle(socket) }.start()
                }
            } catch (e: Throwable) {
                if (running) onLog("8959 监听异常: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    /** 取消发送：通知 OPPO（status type=3）并中断当前传输，结果回调仅一次。 */
    fun cancel(reason: String = "用户取消") {
        if (cancelled) return
        cancelled = true
        cancelReason = reason
        sendCancelStatus()
        try { currentSocket?.close() } catch (_: Exception) {}
        finish(false, reason)
    }

    /** 结果回调（幂等）：完成或取消只触发一次，避免与 UI 清理重复。 */
    private fun finish(success: Boolean, reason: String) {
        if (completed) return
        completed = true
        onComplete?.invoke(success, reason)
    }

    /** 向 OPPO 发送“取消/拒绝”status（type=3, user refuse），尽力而为。 */
    private fun sendCancelStatus() {
        val output = currentSocket?.getOutputStream() ?: return
        try {
            val payload = org.json.JSONObject()
                .put("taskId", taskId)
                .put("type", 3)
                .put("reason", "user refuse")
                .put("id", taskId)
            sendText(output, "action:0:status?$payload")
        } catch (_: Exception) {
        }
    }

    private fun handle(socket: Socket) {
        currentSocket = socket
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            if (cancelled) {
                onLog("!! 已取消发送，拒绝新连接")
                return
            }

            // 读 HTTP 请求头（TLS 连接由 SSLSocket 自动完成握手）
            val headBytes = ByteArrayOutputStream()
            var headerEnd = -1
            val buf = ByteArray(2048)
            var total = 0
            while (headerEnd < 0) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                headBytes.write(buf, 0, n)
                headerEnd = indexOfHeaderEnd(headBytes)
                if (total > 64 * 1024) break
            }
            if (headerEnd < 0) {
                val head = headBytes.toByteArray()
                onLog(
                    "!! 8959 连接未收到可解析 HTTP 头（收到 ${total} 字节）：" +
                        head.take(64).toByteArray().toHex() + " → 关闭"
                )
                return
            }

            val headerText = String(
                headBytes.toByteArray(), 0, headerEnd, StandardCharsets.ISO_8859_1
            )
            onLog("<< HTTP 来自 ${socket.inetAddress.hostAddress}:${socket.port}")
            onLog("<< 请求头:\n$headerText")

            if (headerText.contains("Upgrade: websocket", ignoreCase = true)) {
                val key = headerText.lines()
                    .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim() ?: ""
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest((key + WS_GUID).toByteArray(StandardCharsets.ISO_8859_1))
                )
                val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
                output.write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                output.flush()
                onLog("★ WebSocket 升级成功，发送 versionNegotiation…")
                // 下载大文件期间 OPPO 长时间无 WS 消息，放宽读超时避免误断
                socket.soTimeout = 120000
                sendText(output, "action:0:versionNegotiation?{\"versions\":[1]}")
                handleWebSocket(input, output)
            } else {
                val firstLine = headerText.lineSequence().firstOrNull() ?: ""
                if (firstLine.startsWith("GET /download")) {
                    onLog("★ OPPO 请求下载文件: $firstLine")
                    val list = if (files.isEmpty()) {
                        listOf(SenderFile("test.bin", "application/octet-stream", 1024, bytes = testBytes()))
                    } else {
                        files
                    }
                    if (list.any { it.uri != null }) {
                        // 含大文件/URI：chunked 流式（ZipOutputStream 直接写 socket，不占内存）
                        onLog("★ 流式返回 zip（${list.size} 个文件，${list.sumOf { it.size }} 字节）…")
                        streamZipChunked(output, list)
                    } else {
                        val zipBytes = buildFileZip(list)
                        val resp = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/zip\r\n" +
                            "Content-Disposition: attachment; filename=\"files.zip\"\r\n" +
                            "Content-Length: ${zipBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        output.write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                        output.write(zipBytes)
                        output.flush()
                        onLog("★ 已返回 zip（${zipBytes.size} 字节，${list.size} 个文件）")
                    }
                } else if (firstLine.startsWith("GET /thumbnail")) {
                    // OPPO 接收端可能先拉缩略图；无缩略图时返回空 200
                    val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    output.write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                    output.flush()
                    onLog("★ OPPO 请求缩略图（返回空）: $firstLine")
                } else {
                    val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: 2\r\n" +
                        "Connection: close\r\n\r\n{}"
                    output.write(resp.toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                    onLog("普通 HTTP 请求已响应 200 {}: $firstLine")
                }
            }
        } catch (e: Throwable) {
            onLog("8959 处理异常: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
            // 取消路径：确保结果回调（cancel() 已回调时由 completed 幂等）
            if (cancelled) finish(false, cancelReason)
        }
    }

    private fun handleWebSocket(input: InputStream, output: OutputStream) {
        var frames = 0
        // 心跳/长传输期间帧数较多，放宽上限（单会话最多 1 万帧）
        while (running && !cancelled && frames < 10000) {
            frames++
            try {
                val b0 = input.read()
                if (b0 < 0) break
                val b1 = input.read()
                if (b1 < 0) break
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = (((input.read() shl 8) or input.read()) and 0xFFFF).toLong()
                } else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or input.read().toLong() }
                }
                val maskKey = if (masked) ByteArray(4).also { readFully(input, it) } else null
                val payload = ByteArray(len.toInt())
                readFully(input, payload)
                if (maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }
                when (opcode) {
                    0x1 -> {
                        val text = String(payload, StandardCharsets.UTF_8)
                        onLog("<< WS 文本帧: $text")
                        handleWsMessage(text, output)
                    }
                    0x8 -> {
                        onLog("<< WS 关闭帧")
                        sendFrame(output, 0x8, ByteArray(0))
                        return
                    }
                    0x9 -> sendFrame(output, 0xA, payload)
                    0xA -> onLog("<< WS pong")
                    else -> onLog("<< WS 帧 opcode=$opcode len=$len")
                }
            } catch (e: java.net.SocketTimeoutException) {
                // 下载期间对端长时间无 WS 消息：超时继续等，不视为断连
                if (!running) break
            }
        }
    }

    /** 解析 OPPO 消息：action:type:name?json（探针阶段仅协商与记录） */
    private fun handleWsMessage(text: String, output: OutputStream) {
        val qIdx = text.indexOf('?')
        val head = if (qIdx >= 0) text.substring(0, qIdx) else text
        val json = if (qIdx >= 0) text.substring(qIdx + 1) else ""
        val parts = head.split(":")
        if (parts.size < 3) {
            onLog("WS 消息格式未知: $text")
            return
        }
        val action = parts[0]
        val type = parts[1]
        val name = parts[2]
        onLog("WS 消息: action=$action type=$type name=$name json=$json")
        if (name == "versionNegotiation") {
            if (action == "ack") {
                onLog("★ 版本协商完成（OPPO 确认 version=1），下发 sendRequest…")
                sendText(output, buildSendRequest())
            } else {
                // OPPO 主动发起协商（异常路径）→ 回 ack
                sendText(output, "ack:$type:versionNegotiation?{\"version\":1}")
            }
            return
        }
        if (name == "sendRequest" && action == "ack") {
            onLog("★ OPPO 已确认 sendRequest（接收确认）")
        }
        if (name == "status") {
            onLog("★ OPPO status: $json")
            val type = try {
                org.json.JSONObject(if (json.isEmpty()) "{}" else json).optInt("type", -1)
            } catch (_: Exception) {
                -1
            }
            val reason = try {
                org.json.JSONObject(if (json.isEmpty()) "{}" else json).optString("reason", "")
            } catch (_: Exception) {
                ""
            }
            // 对端（OPPO 接收方）取消：status type=3 / reason=user refuse → 停止发送
            if (type == 3 || reason.contains("refuse", ignoreCase = true) ||
                reason.contains("cancel", ignoreCase = true)
            ) {
                onLog("!! OPPO 已取消接收（type=$type reason=$reason），停止发送")
                cancel("对方已取消接收")
                return
            }
            // 任何 status 都回 ack（心跳 type=6/7 也要回，否则 OPPO 判定超时）
            sendText(output, "ack:$type:status")
            if (type == 1) {
                onLog("★ OPPO 已接收完成（status type=1）")
                finish(true, "OPPO 已接收完成")
                try { currentSocket?.close() } catch (_: Exception) {}
            }
        }
    }

    /** 构造 sendRequest（镜像 pa/d.a(true) + pa/b.c(0,...)，type=0） */
    private fun buildSendRequest(): String {
        val list = files
        val first = list.firstOrNull()
        val payload = org.json.JSONObject().apply {
            put("id", taskId)
            put("senderId", "HUAWEI-NFCProbe")
            put("senderName", "HUAWEI Mate")
            put("fileName", if (list.size > 1) "${list.size} 个文件" else (first?.fileName ?: "test.bin"))
            put("mimeType", first?.mimeType ?: "application/octet-stream")
            put("fileCount", if (list.isEmpty()) 1 else list.size)
            put("totalSize", if (list.isEmpty()) 1024 else list.sumOf { it.size })
            put("thumbnail", "")
            put("thumbnail_height", 0)
            put("thumbnail_width", 0)
            put("wlan", true)
        }
        return "action:0:sendRequest?$payload"
    }

    /** 生成 zip 文件内容（OPPO 接收端用 ZipInputStream 解压保存；单文件时 entry 名为 fileName）。 */
    private fun buildFileZip(list: List<SenderFile>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val used = mutableSetOf<String>()
            for (file in list) {
                val name = uniqueName(file.fileName, used)
                zos.putNextEntry(ZipEntry(name))
                if (file.bytes != null) {
                    zos.write(file.bytes)
                } else {
                    zos.write(testBytes())
                }
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    /** 大文件流式发送：chunked HTTP + ZipOutputStream 直接写 socket（不整读内存）。 */
    private fun streamZipChunked(output: OutputStream, list: List<SenderFile>) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/zip\r\n" +
            "Content-Disposition: attachment; filename=\"files.zip\"\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
        val chunked = ChunkedOutputStream(output)
        val buffered = java.io.BufferedOutputStream(chunked, 64 * 1024)
        var sent = 0L
        var lastReport = 0L
        try {
            val zos = ZipOutputStream(buffered)
            // 大文件/媒体类不压缩（DEFLATE level 0），避免对已压缩数据做无谓耗时压缩
            zos.setLevel(0)
            val total = list.sumOf { it.size }
            val used = mutableSetOf<String>()
            for (file in list) {
                if (cancelled) throw java.io.IOException("已取消")
                if (file.uri == null) continue
                val name = uniqueName(file.fileName, used)
                zos.putNextEntry(ZipEntry(name))
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } >= 0) {
                        if (cancelled) throw java.io.IOException("已取消")
                        zos.write(buf, 0, n)
                        sent += n
                        if (sent - lastReport >= 1024 * 1024) {
                            lastReport = sent
                            onLog("发送进度: ${sent / 1048576}MB / ${total / 1048576}MB")
                            onProgress?.invoke(sent, total)
                        }
                    }
                } ?: throw java.io.IOException("无法打开文件流: $name")
                zos.closeEntry()
            }
            zos.finish()
        } finally {
            buffered.flush()
            chunked.flush()
            output.write("0\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            output.flush()
        }
        onProgress?.invoke(sent, list.sumOf { it.size })
        onLog("★ 流式发送完成（${list.size} 个文件）")
    }

    /** 探针模式下的测试文件内容（约 1KB 可读文本）。 */
    private fun testBytes(): ByteArray {
        val head = "NFCProbe probe file\n".toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(head)
        out.write(ByteArray(1024 - head.size) { ('A' + (it % 26)).code.toByte() })
        return out.toByteArray()
    }

    /** zip entry 名去重：同名追加 (1)、(2)… */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        var i = 1
        while (true) {
            val dot = name.lastIndexOf('.')
            val cand = if (dot > 0) {
                name.substring(0, dot) + "($i)" + name.substring(dot)
            } else {
                "$name($i)"
            }
            if (used.add(cand)) return cand
            i++
        }
    }

    /**
     * 把写出的数据按 HTTP chunked 帧打包（每次写入即成一帧）。
     * 上层用 BufferedOutputStream(64KB) 包装，保证帧大小适中且内存恒定
     * （此前先攒满再一次性 toByteArray()，大文件直接 OOM）。
     */
    private class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            out.write("%X\r\n".format(len).toByteArray(StandardCharsets.ISO_8859_1))
            out.write(b, off, len)
            out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }

        override fun flush() {
            out.flush()
        }
    }

    private fun sendText(output: OutputStream, text: String) {
        sendFrame(output, 0x1, text.toByteArray(StandardCharsets.UTF_8))
        onLog(">> WS 文本帧: $text")
    }

    private fun sendFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size < 65536 -> {
                output.write(126)
                output.write(payload.size shr 8)
                output.write(payload.size and 0xFF)
            }
            else -> {
                output.write(127)
                var l = payload.size.toLong()
                for (i in 7 downTo 0) output.write(((l shr (i * 8)) and 0xFF).toInt())
            }
        }
        output.write(payload)
        output.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun indexOfHeaderEnd(baos: ByteArrayOutputStream): Int {
        val b = baos.toByteArray()
        if (b.size < 4) return -1
        for (i in 0..b.size - 4) {
            if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() &&
                b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun buildSslContext(): SSLContext? {
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            context.assets.open(KEYSTORE_ASSET).use {
                ks.load(it, KS_PASSWORD.toCharArray())
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, KS_PASSWORD.toCharArray())
            SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, null, null)
            }
        } catch (e: Exception) {
            onLog("TLS 初始化失败（8959 将明文启动）: ${e.message}")
            null
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }

    companion object {
        private const val PORT = 8959
        private const val KEYSTORE_ASSET = "wss.p12"
        private const val KS_PASSWORD = "nfcprobe123"
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
