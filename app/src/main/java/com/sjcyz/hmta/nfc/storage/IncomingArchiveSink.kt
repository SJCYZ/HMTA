package com.sjcyz.hmta.nfc.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.OutputStream

interface IncomingArchiveSink {
    fun availableBytes(): Long
    fun open(entryName: String): PendingArchiveEntry
}

interface PendingArchiveEntry {
    val displayName: String
    val output: OutputStream
    fun commit()
    fun abort()
}

object ArchiveEntryName {
    fun sanitize(raw: String): String {
        val normalized = raw.replace('\\', '/')
        val leaf = normalized.substringAfterLast('/').trim()
        val safe = leaf
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "_")
            .replace(Regex("[<>:\"/\\\\|?*]"), "_")
            .trimEnd('.', ' ')
        return safe.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "received.bin"
    }
}

class MediaStoreIncomingArchiveSink(context: Context) : IncomingArchiveSink {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun availableBytes(): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    override fun open(entryName: String): PendingArchiveEntry {
        val displayName = ArchiveEntryName.sanitize(entryName)
        val extension = displayName.substringAfterLast('.', "")
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/HMTA")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建接收文件：$displayName")
        val output = resolver.openOutputStream(uri, "w")
            ?: run {
                resolver.delete(uri, null, null)
                throw IllegalStateException("无法写入接收文件：$displayName")
            }
        return MediaStorePendingEntry(uri, displayName, output)
    }

    private inner class MediaStorePendingEntry(
        private val uri: Uri,
        override val displayName: String,
        override val output: OutputStream,
    ) : PendingArchiveEntry {
        private var finished = false

        override fun commit() {
            if (finished) return
            output.close()
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            finished = true
        }

        override fun abort() {
            if (finished) return
            runCatching { output.close() }
            resolver.delete(uri, null, null)
            finished = true
        }
    }
}
