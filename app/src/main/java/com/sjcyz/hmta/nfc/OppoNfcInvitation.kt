package com.sjcyz.hmta.nfc

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class OppoNfcInvitation(
    val deviceId: String,
    val randomCode: String,
    val deviceName: String?,
    val deviceType: Int?,
    val version: Int?,
    val rawUri: String,
)

object OppoNfcInvitationParser {
    private val randomCodePattern = Regex("^[0-9A-Fa-f]{16}$")

    fun parse(rawUri: String?): OppoNfcInvitation? {
        if (rawUri.isNullOrBlank()) return null
        val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.host.equals("connect.oppo.com", ignoreCase = true)) return null
        if (uri.path != "/oshare/clips/seo") return null

        val query = parseQuery(uri.rawQuery ?: return null)
        val code = query["code"] ?: return null
        val deviceId = query["devId"]?.takeIf { it.isNotBlank() } ?: return null
        if (!randomCodePattern.matches(code)) return null

        return OppoNfcInvitation(
            deviceId = deviceId,
            randomCode = code.uppercase(),
            deviceName = query["devName"]?.takeIf { it.isNotBlank() },
            deviceType = query["devType"]?.toIntOrNull(),
            version = query["version"]?.toIntOrNull(),
            rawUri = rawUri,
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String> = buildMap {
        rawQuery.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val separator = pair.indexOf('=')
            val rawKey = if (separator >= 0) pair.substring(0, separator) else pair
            val rawValue = if (separator >= 0) pair.substring(separator + 1) else ""
            val key = decode(rawKey)
            if (key !in this) put(key, decode(rawValue))
        }
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

class NfcInvitationDeduplicator(
    private val windowMillis: Long = 3_000,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var lastCode: String? = null
    private var lastAcceptedAt = Long.MIN_VALUE

    @Synchronized
    fun accept(invitation: OppoNfcInvitation): Boolean {
        val now = nowMillis()
        if (invitation.randomCode == lastCode && now - lastAcceptedAt < windowMillis) {
            return false
        }
        lastCode = invitation.randomCode
        lastAcceptedAt = now
        return true
    }

    @Synchronized
    fun reset() {
        lastCode = null
        lastAcceptedAt = Long.MIN_VALUE
    }
}
