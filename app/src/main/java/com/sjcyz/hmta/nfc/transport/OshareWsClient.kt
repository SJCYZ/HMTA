package com.sjcyz.hmta.nfc.transport

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject
import com.sjcyz.hmta.nfc.storage.IncomingArchiveSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OShare 8959 WebSocket 客户端（华为接收方向）。
 *
 * 依据 OPPO 反编译：
 *  - 接收方（ta.b → la.b → la.c）以 wss://<GO-IP>:<port>/websocket 连接发送方；
 *  - OPPO 发送方 8959 服务端在 WebSocket 建立后主动发送
 *    `action:0:versionNegotiation?{"versions":[1]}`；
 *  - 接收方应答 `ack:0:versionNegotiation?{"version":1}`；
 *  - 随后发送方下发 sendRequest（文件列表）/status 等，信封格式：
 *    <action>:<type>:<name>?<json>（action=ack|action）。
 *
 * 本类：TLS 信任任意证书 + WebSocket 客户端握手 + 帧收发 + versionNegotiation 应答。
 */
class OshareWsClient(
    private val host: String,
    private val port: Int,
    private val network: android.net.Network? = null,
    private val onLog: (String) -> Unit,
    private val onProgress: ((done: Long, total: Long) -> Unit)? = null,
    private val onMessage: (action: String, type: String, name: String, json: String) -> Unit,
    private val archiveSink: IncomingArchiveSink,
    private val onCancelled: ((reason: String) -> Unit)? = null,
    private val onFinished: ((success: Boolean, reason: String) -> Unit)? = null,
) {

    @Volatile private var socket: java.net.Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    @Volatile private var closed = false
    // 用户取消：中断进行中的文件下载
    @Volatile private var cancelled = false
    // 对端（OPPO 发送方）主动取消（收到 status type=3 / user refuse）
    @Volatile private var remoteCancelled = false
    // 当前 /download 连接（取消时关闭以打断阻塞读）
    @Volatile private var downloadSocket: java.net.Socket? = null
    // 当前传输任务 id（来自 sendRequest 的 "id" 字段），/download 与 status 都要用它
    @Volatile private var currentTaskId: String? = null
    // 当前任务总大小（sendRequest.totalSize），用于进度与空间预检
    @Volatile private var currentTotalSize: Long = 0
    // 文件已经落盘且完成状态帧已写入；此后对端直接断开也属于成功路径。
    @Volatile private var completionStatusSent = false
    private val completionSent = AtomicBoolean(false)

    private fun finish(success: Boolean, reason: String) {
        if (completionSent.compareAndSet(false, true)) onFinished?.invoke(success, reason)
    }

    /**
     * 连接 + WebSocket 握手；成功后返回 true。
     *
     * OPPO iOSNettyServer（b9/c.java）按对端协议版本决定是否启用 TLS：
     * 对端版本 >= 10015 时用明文 WS（我们上报 version=10302 → 明文），
     * < 10015 或任务为空时用 WSS。因此先试明文，失败再试 TLS。
     */
    fun connect(): Boolean {
        if (connectOnce(useTls = false)) return true
        onLog("== 明文 WS 失败，回退尝试 WSS（TLS）…")
        return connectOnce(useTls = true)
    }

    /** 取消传输：按协议向 OPPO 发 status(type=3, user refuse)，再关闭下载/WS 连接。 */
    fun cancel() {
        cancelled = true
        sendCancelStatus()
        try { downloadSocket?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        finish(false, "用户取消")
    }

    /** 向 OPPO 发送“用户取消/拒绝”status（type=3），尽力而为，失败忽略。 */
    private fun sendCancelStatus() {
        val taskId = currentTaskId ?: return
        try {
            val payload = JSONObject()
                .put("taskId", taskId)
                .put("type", 3)
                .put("reason", "user refuse")
                .put("id", taskId)
            sendText("action:0:status?$payload")
        } catch (_: Exception) {
        }
    }

    private fun connectOnce(useTls: Boolean): Boolean {
        val scheme = if (useTls) "wss" else "ws"
        return try {
            // 支持失败后重连：重置状态（上次失败 close() 会把 closed 置 true）
            closed = false
            cancelled = false
            completionStatusSent = false
            downloadSocket = null
            socket = null
            input = null
            output = null
            val raw = if (useTls) {
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(TRUST_ALL), SecureRandom())
                val s = sslContext.socketFactory.createSocket() as SSLSocket
                s
            } else {
                java.net.Socket()
            }
            // 绑定到热点网络：避免默认网络仍为蜂窝导致 ENETUNREACH
            if (network != null) {
                network.bindSocket(raw)
            }
            raw.connect(InetSocketAddress(host, port), 10000)
            raw.soTimeout = 60000
            if (useTls) {
                (raw as SSLSocket).startHandshake()
            }
            socket = raw
            input = raw.getInputStream()
            output = raw.getOutputStream()

            val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val req = "GET /websocket HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            output!!.write(req.toByteArray(StandardCharsets.ISO_8859_1))
            output!!.flush()

            // 读响应头直到 \r\n\r\n
            val head = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            var headerEnd = -1
            while (headerEnd < 0) {
                val n = input!!.read(buf)
                if (n < 0) break
                head.write(buf, 0, n)
                headerEnd = indexOfHeaderEnd(head)
            }
            if (headerEnd < 0) {
                onLog("!! WebSocket 握手响应不完整: ${head.toByteArray().take(64).toByteArray().toHex()}")
                return false
            }
            val header = String(head.toByteArray(), 0, headerEnd, StandardCharsets.ISO_8859_1)
            onLog("<< ${scheme.uppercase()} 握手响应:\n$header")
            if (!header.startsWith("HTTP/1.1 101")) {
                onLog("!! ${scheme.uppercase()} 握手失败（非 101）")
                return false
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + WS_GUID).toByteArray(StandardCharsets.ISO_8859_1))
            )
            val got = header.lines()
                .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
            if (got != null && got != accept) {
                onLog("!! Sec-WebSocket-Accept 不匹配（got=$got）")
                return false
            }
            onLog("★ ${scheme.uppercase()} 连接成功 $host:$port/websocket")
            true
        } catch (e: Throwable) {
            onLog("!! ${scheme.uppercase()} 连接失败: ${e.message}")
            close()
            false
        }
    }

    /** 持续读帧（阻塞，放后台线程）。 */
    fun readLoop() {
        try {
            while (!closed) {
                try {
                    val b0 = input!!.read()
                    if (b0 < 0) {
                        finishAfterPeerClosed("OPPO 已关闭 WebSocket")
                        break
                    }
                    val b1 = input!!.read()
                    if (b1 < 0) {
                        finishAfterPeerClosed("OPPO WebSocket 帧不完整")
                        break
                    }
                    val opcode = b0 and 0x0F
                    val masked = (b1 and 0x80) != 0
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) {
                        len = (((input!!.read() shl 8) or input!!.read()) and 0xFFFF).toLong()
                    } else if (len == 127L) {
                        len = 0
                        repeat(8) { len = (len shl 8) or input!!.read().toLong() }
                    }
                    val maskKey = if (masked) ByteArray(4).also { readFully(it) } else null
                    val payload = ByteArray(len.toInt())
                    readFully(payload)
                    if (maskKey != null) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                        }
                    }
                    when (opcode) {
                        0x1 -> {
                            val text = String(payload, StandardCharsets.UTF_8)
                            onLog("<< WS: $text")
                            handleMessage(text)
                        }
                        0x8 -> {
                            onLog("<< WS 关闭帧")
                            sendFrame(0x8, ByteArray(0))
                            finishAfterPeerClosed("OPPO 已发送 WebSocket 关闭帧")
                            return
                        }
                        0x9 -> sendFrame(0xA, payload)
                        0xA -> onLog("<< WS pong")
                        else -> onLog("<< WS 帧 opcode=$opcode len=$len")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // 下载/空闲期间 OPPO 可能长时间无 WS 消息：超时继续等，不视为断连
                    if (closed) break
                }
            }
        } catch (e: Throwable) {
            if (!closed) {
                onLog("!! WSS 读循环结束: ${e.message}")
                finishAfterPeerClosed("WebSocket 连接中断")
            }
        } finally {
            close()
        }
    }

    private fun finishAfterPeerClosed(reason: String) {
        if (closed || cancelled || remoteCancelled) return
        if (completionStatusSent) {
            onLog("★ $reason；完成状态已发出，按接收成功处理")
            finish(true, "接收完成")
        } else {
            onLog("!! $reason；文件传输尚未完成")
            finish(false, reason)
        }
    }

    fun sendText(text: String) {
        onLog(">> WS: $text")
        sendFrame(0x1, text.toByteArray(StandardCharsets.UTF_8))
    }

    /** 客户端→服务端帧必须带掩码。 */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: return
        out.write(0x80 or opcode)
        var len = payload.size
        if (len < 126) {
            out.write(0x80 or len)
        } else if (len < 65536) {
            out.write(0x80 or 126)
            out.write(len shr 8)
            out.write(len and 0xFF)
        } else {
            out.write(0x80 or 127)
            for (i in 7 downTo 0) out.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        out.write(mask)
        for (i in payload.indices) {
            out.write(payload[i].toInt() xor mask[i % 4].toInt())
        }
        out.flush()
    }

    /** 解析 OPPO 信封：action:type:name?json，并对 versionNegotiation 应答。 */
    private fun handleMessage(text: String) {
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
        if (name == "versionNegotiation" && action == "action") {
            // 协商：回 ack，版本取 1（与 OPPO la.a.a={1} 一致）
            val peerVersions = try {
                val arr = JSONObject(if (json.isEmpty()) "{}" else json).optJSONArray("versions")
                if (arr != null) (0 until arr.length()).map { arr.getInt(it) } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            val chosen = if (peerVersions.contains(1)) 1 else (peerVersions.firstOrNull() ?: 1)
            onLog("★ versionNegotiation 收到版本 $peerVersions，选择 $chosen")
            sendText("ack:$type:versionNegotiation?{\"version\":$chosen}")
        } else if (name == "sendRequest" && action == "action") {
            // OPPO 发送方下发文件列表 → 华为（接收方）回 ack，然后直接 GET /download
            val sendJson = try {
                JSONObject(if (json.isEmpty()) "{}" else json)
            } catch (_: Exception) {
                null
            }
            val taskId = sendJson?.optString("id")?.takeIf { it.isNotBlank() }
            currentTotalSize = sendJson?.optLong("totalSize", 0) ?: 0
            currentTaskId = taskId
            onLog("★ 收到 sendRequest（文件列表，taskId=$taskId），回 ack 并开始下载")
            sendText("ack:$type:sendRequest")
            taskId?.let { startDownload(it) }
        } else if (name == "status" && action == "action") {
            onLog("★ 收到 status: $json")
            // 对端取消：status type=3 / reason=user refuse → 停止下载并回调
            val st = try {
                JSONObject(if (json.isEmpty()) "{}" else json)
            } catch (_: Exception) {
                null
            }
            val stType = st?.optInt("type", -1) ?: -1
            val stReason = st?.optString("reason", "") ?: ""
            if (stType == 3 || stReason.contains("refuse", ignoreCase = true) ||
                stReason.contains("cancel", ignoreCase = true)
            ) {
                onLog("!! OPPO 已取消传输（type=$stType reason=$stReason），停止下载")
                remoteCancelled = true
                cancelled = true
                try { downloadSocket?.close() } catch (_: Exception) {}
                try { socket?.close() } catch (_: Exception) {}
                onCancelled?.invoke("对方已取消传输")
                finish(false, "对方已取消传输")
                return
            }
            sendText("ack:$type:status")
        } else if (name == "status" && action == "ack") {
            // 对端对“取消”status 的 ack：直接关闭，不算完成
            val st = try {
                JSONObject(if (json.isEmpty()) "{}" else json)
            } catch (_: Exception) {
                null
            }
            val stType = st?.optInt("type", -1) ?: -1
            val stReason = st?.optString("reason", "") ?: ""
            if (stType == 3 || stReason.contains("refuse", ignoreCase = true)) {
                onLog("★ OPPO 已确认取消（type=$stType），关闭 WS 连接…")
                close()
                return
            }
            // OPPO 已确认传输完成 → 主动关闭 WS，触发 OPPO 收起热点/服务器
            onLog("★ OPPO 已确认 status，传输完成，关闭 WS 连接…")
            finish(true, "接收完成")
            Thread {
                Thread.sleep(500)
                close()
            }.start()
        } else if (name == "files" || name == "download_start") {
            // 服务器侧的确认信号（iOS 线路 GET /download 时由服务端触发）
            onLog("★ OPPO 下发 $name 消息，确认下载")
            currentTaskId?.let { startDownload(it) }
        }
        onMessage(action, type, name, json)
    }

    /**
     * GET /download?taskId=<id> → zip 解压落盘 → 回 status(type=1)。
     * iOS 线路服务器按对端版本决定 TLS（我们 version=10302 → 明文），
     * 因此先试明文 HTTP，失败再试 HTTPS。
     */
    private fun startDownload(taskId: String) {
        Thread {
            try {
                if (currentTotalSize > 0) {
                    val free = archiveSink.availableBytes()
                    if (free < currentTotalSize) {
                        onLog(
                            "!! 存储空间不足：文件 ${currentTotalSize / 1048576}MB，" +
                                "目录可用 ${free / 1048576}MB"
                        )
                        finish(false, "存储空间不足")
                        return@Thread
                    }
                }
                var ok = false
                var usedHttps = false
                try {
                    onLog(">> 下载: http://$host:$port/download?taskId=$taskId")
                    ok = downloadOnce(taskId, useTls = false)
                } catch (e: Throwable) {
                    if (cancelled) {
                        onLog(if (remoteCancelled) "!! 对方已取消，下载终止" else "!! 下载已取消")
                        return@Thread
                    }
                    onLog("!! 明文下载失败: ${e.message}，回退 HTTPS…")
                    try {
                        ok = downloadOnce(taskId, useTls = true)
                        usedHttps = true
                    } catch (e2: Throwable) {
                        if (cancelled) {
                            onLog(if (remoteCancelled) "!! 对方已取消，下载终止" else "!! 下载已取消")
                            return@Thread
                        }
                        onLog("!! HTTPS 下载也失败: ${e2.message}")
                    }
                }
                if (!ok) {
                    finish(false, "HTTP/HTTPS 下载均失败")
                    return@Thread
                }
                onLog("★ 文件已保存（${if (usedHttps) "HTTPS" else "HTTP"}），回 status(type=1)…")
                sendText("action:0:status?{\"taskId\":\"$taskId\",\"type\":1}")
                completionStatusSent = true
                // NFCProbe 实机结论：此处不能立即关闭 /download TCP；OPPO 会将其判为
                // “连接中断”。保持到 status ACK（或 WS 兜底结束）后由 close() 统一回收。
                // 兜底：即使收不到 OPPO 的 status ack，也主动关闭连接让 OPPO 收起热点
                Thread {
                    Thread.sleep(2500)
                    if (!closed && !cancelled) {
                        onLog("★ 传输完成，主动关闭 WS 连接（通知 OPPO 收起热点）")
                        finish(true, "接收完成（完成确认超时兜底）")
                        close()
                    }
                }.start()
            } catch (e: Throwable) {
                if (!cancelled) {
                    onLog("!! 文件下载失败: ${e.message}")
                    finish(false, "文件下载失败: ${e.message}")
                }
            }
        }.start()
    }

    /** GET /download 流式落盘：chunked/Content-Length → ZipInputStream → 逐文件写盘（不占内存）。 */
    private fun downloadOnce(taskId: String, useTls: Boolean): Boolean {
        val raw = if (useTls) {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(TRUST_ALL), SecureRandom())
            sslContext.socketFactory.createSocket() as SSLSocket
        } else {
            java.net.Socket()
        }
        downloadSocket = raw
        if (network != null) network.bindSocket(raw)
        raw.connect(InetSocketAddress(host, port), 10000)
        raw.soTimeout = 60000
        if (useTls) (raw as SSLSocket).startHandshake()
        val req = "GET /download?taskId=$taskId HTTP/1.1\r\n" +
            "Host: $host:$port\r\n" +
            "Connection: close\r\n\r\n"
        raw.getOutputStream().write(req.toByteArray(StandardCharsets.ISO_8859_1))
        raw.getOutputStream().flush()
        val input = raw.getInputStream()
        // 读响应头
        val headerBytes = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var headerEnd = -1
        while (headerEnd < 0) {
            if (cancelled) throw java.io.IOException("已取消")
            val n = input.read(buf)
            if (n < 0) throw java.io.IOException("连接中断，未收到响应头")
            headerBytes.write(buf, 0, n)
            headerEnd = indexOfHeaderEnd(headerBytes)
        }
        val hb = headerBytes.toByteArray()
        val headerStr = String(hb, 0, headerEnd, StandardCharsets.ISO_8859_1)
        onLog("<< HTTP 响应头: ${headerStr.lineSequence().firstOrNull()}")
        val isChunked = headerStr.lines().any {
            it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true)
        }
        // 头部之后可能已带部分 body：推回流，再按 chunked/普通流包装
        val after = hb.size - (headerEnd + 4)
        val pb = java.io.PushbackInputStream(input, after + 16)
        if (after > 0) pb.unread(hb, headerEnd + 4, after)
        val bodyIn = if (isChunked) ChunkedInputStream(pb) else pb
        // ZipInputStream 流式解压落盘
        val zis = ZipInputStream(java.io.BufferedInputStream(bodyIn, 64 * 1024))
        var saved = 0
        var totalWritten = 0L
        var lastReport = 0L
        var entry: ZipEntry? = zis.nextEntry
        while (entry != null) {
            if (cancelled) throw java.io.IOException("已取消")
            val name = entry.name
            if (!entry.isDirectory) {
                val pending = archiveSink.open(name)
                try {
                    val wbuf = ByteArray(64 * 1024)
                    var n: Int
                    while (zis.read(wbuf).also { n = it } >= 0) {
                        if (cancelled) throw java.io.IOException("已取消")
                        pending.output.write(wbuf, 0, n)
                        totalWritten += n
                        if (totalWritten - lastReport >= 1024 * 1024) {
                            lastReport = totalWritten
                            onLog(
                                "下载进度: ${totalWritten / 1048576}MB" +
                                    if (currentTotalSize > 0) " / ${currentTotalSize / 1048576}MB" else ""
                            )
                            onProgress?.invoke(totalWritten, currentTotalSize)
                        }
                    }
                    pending.commit()
                } catch (e: Throwable) {
                    pending.abort()
                    throw e
                }
                saved++
                onLog("已保存: Download/HMTA/${pending.displayName}")
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
        onProgress?.invoke(totalWritten, currentTotalSize)
        onLog("★ 下载完成，解压保存 $saved 个文件")
        return true
    }

    /** HTTP chunked 流解码：按块尺寸逐段读取，供 ZipInputStream 流式解压。 */
    private class ChunkedInputStream(private val input: InputStream) : InputStream() {
        private var remaining = 0L
        private var eof = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (eof) return -1
            if (remaining == 0L) {
                if (!nextChunk()) {
                    eof = true
                    return -1
                }
            }
            val toRead = minOf(len.toLong(), remaining).toInt()
            val n = input.read(b, off, toRead)
            if (n < 0) {
                eof = true
                return -1
            }
            remaining -= n
            if (remaining == 0L) skipCrlf()
            return n
        }

        private fun nextChunk(): Boolean {
            val line = readLine() ?: return false
            val semi = line.indexOf(';')
            val hex = (if (semi >= 0) line.substring(0, semi) else line).trim()
            val size = hex.toLongOrNull(16) ?: return false
            if (size == 0L) {
                drainTrailers()
                return false
            }
            remaining = size
            return true
        }

        private fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (c == 13) {
                    if (input.read() != 10) return null
                    return sb.toString()
                }
                sb.append(c.toChar())
            }
        }

        private fun skipCrlf() {
            input.read()
            input.read()
        }

        private fun drainTrailers() {
            while (true) {
                val line = readLine() ?: return
                if (line.isEmpty()) return
            }
        }
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input!!.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    fun close() {
        closed = true
        try { downloadSocket?.close() } catch (_: Exception) {}
        downloadSocket = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val TRUST_ALL = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }
}
