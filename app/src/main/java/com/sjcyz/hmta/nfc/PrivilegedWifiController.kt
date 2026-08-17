package com.sjcyz.hmta.nfc

import com.sjcyz.hmta.utils.ShizukuUtils

data class PrivilegedCommandResult(
    val success: Boolean,
    val output: String,
)

class PrivilegedWifiController {
    suspend fun connect(ssid: String, psk: String): PrivilegedCommandResult {
        require(ssid.isNotBlank()) { "SSID 不能为空" }
        require(psk.isNotBlank()) { "热点密码不能为空" }
        return execute(buildConnectCommand(ssid, psk))
    }

    suspend fun disconnect(): PrivilegedCommandResult =
        execute("cmd wifi disconnect-network")

    private suspend fun execute(command: String): PrivilegedCommandResult {
        val output = ShizukuUtils.execCommand(command) ?: return PrivilegedCommandResult(
            success = false,
            output = "Shizuku 未连接或未授权",
        )
        val success = output.contains("exit=0") &&
            !output.contains("failed", ignoreCase = true) &&
            !output.contains("error", ignoreCase = true) &&
            !output.contains("exception", ignoreCase = true)
        return PrivilegedCommandResult(success, output)
    }

    companion object {
        internal fun buildConnectCommand(ssid: String, psk: String): String =
            "cmd wifi connect-network ${shellQuote(ssid)} wpa2 ${shellQuote(psk)} -m -h"

        internal fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"
    }
}
