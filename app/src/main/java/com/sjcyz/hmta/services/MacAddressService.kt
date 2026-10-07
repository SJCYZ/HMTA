package com.sjcyz.hmta.services

import android.util.Log
import com.sjcyz.hmta.IMacAddressService
import com.sjcyz.hmta.utils.TAG
import java.io.File
import java.net.NetworkInterface
import kotlin.system.exitProcess

class MacAddressService: IMacAddressService.Stub() {
    override fun destroy() {
        exit()
    }

    override fun exit() {
        Log.i(TAG, "Exit")
        exitProcess(0)
    }

    override fun getP2pMacAddress(): String? {
        return getMacAddressByName("p2p0")
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun getMacAddressByName(name: String): String? {
        val interfaceName = name.lowercase()
        val ifs = NetworkInterface.getNetworkInterfaces()
        for (intf in ifs) {
            if (intf.name == interfaceName) {
                intf.hardwareAddress?.toHexString(HexFormat {
                    bytes.byteSeparator = ":"
                })?.takeIf(::isValidMac)?.let { return it.lowercase() }
            }
        }

        readSysfs(interfaceName)?.let { return it }
        if (interfaceName.startsWith("p2p")) {
            readP2pGlob()?.let { return it }
            readP2pFromIpAddr()?.let { return it }
            parseWifiFactoryMac()?.let { return it }
        }
        if (interfaceName == "wlan0" || interfaceName == "wlan") {
            readFromIpAddr("wlan0")?.let { return it }
            parseWifiFactoryMac()?.let { return it }
        }
        if (interfaceName == "hci0" || interfaceName == "bt" || interfaceName == "bluetooth") {
            readBtFromSettingsSecure()?.let { return it }
            readBtAdapterAddress()?.let { return it }
            readBtProps()?.let { return it }
        }
        return null
    }

    private fun readSysfs(interfaceName: String): String? = runCatching {
        File("/sys/class/net/$interfaceName/address")
            .takeIf(File::exists)
            ?.readText()
            ?.trim()
            ?.takeIf(::isValidMac)
            ?.lowercase()
    }.getOrNull()

    private fun readP2pGlob(): String? = runCatching {
        File("/sys/class/net").listFiles()?.forEach { directory ->
            if (directory.name.startsWith("p2p")) {
                readSysfs(directory.name)?.let { return it }
            }
        }
        null
    }.getOrNull()

    private fun readP2pFromIpAddr(): String? {
        val sections = runCommand("ip addr show").split(Regex("(?m)^\\d+: "))
        for (section in sections) {
            if (!section.startsWith("p2p")) continue
            MAC_PATTERN.findAfterLinkEther(section)?.let { return it }
        }
        return null
    }

    private fun readFromIpAddr(interfaceName: String): String? =
        MAC_PATTERN.findAfterLinkEther(runCommand("ip addr show $interfaceName"))

    private fun parseWifiFactoryMac(): String? =
        Regex("wifi_sta_factory_mac_address=([0-9a-fA-F:]{17})")
            .find(runCommand("dumpsys wifi"))
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    /** 华为会拦截 settings get，但允许 shell 直接查询 Settings Provider。 */
    private fun readBtFromSettingsSecure(): String? =
        Regex("value=([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})")
            .find(
                runCommand(
                    "content query --uri content://settings/secure/bluetooth_address --projection value",
                ),
            )
            ?.groupValues
            ?.get(1)
            ?.lowercase()

    @Suppress("DEPRECATION", "MissingPermission")
    private fun readBtAdapterAddress(): String? = runCatching {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.address
            ?.takeIf(::isValidMac)
            ?.lowercase()
    }.getOrNull()

    private fun readBtProps(): String? {
        val keys = listOf(
            "ro.boot.btmacaddr",
            "ro.boot.bt.mac",
            "ro.boot.bt_addr",
            "persist.sys.bt_addr",
            "persist.vendor.bt.bdaddr",
            "ro.bt.bdaddr",
            "persist.vendor.service.bdroid.bdaddr",
            "persist.sys.bt.address",
        )
        for (key in keys) {
            MAC_PATTERN.find(runCommand("getprop $key"))
                ?.value
                ?.lowercase()
                ?.let { return it }
        }
        return null
    }

    private fun runCommand(command: String): String = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
    }.getOrDefault("")

    private fun Regex.findAfterLinkEther(value: String): String? =
        Regex("link/ether ([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})")
            .find(value)
            ?.groupValues
            ?.get(1)
            ?.lowercase()
            ?.takeUnless { it == "00:00:00:00:00:00" }

    private fun isValidMac(value: String): Boolean =
        MAC_PATTERN.matches(value.trim()) && value != "00:00:00:00:00:00"

    override fun execCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            listOf(stdout, stderr, "exit=$exitCode")
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } catch (e: Exception) {
            "execCommand 异常: ${e.message}"
        }
    }

    companion object {
        private val MAC_PATTERN = Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")
    }
}
