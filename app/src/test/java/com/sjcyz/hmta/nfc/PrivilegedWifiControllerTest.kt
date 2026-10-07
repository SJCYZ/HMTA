package com.sjcyz.hmta.nfc

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegedWifiControllerTest {
    @Test
    fun quotesShellArgumentsWithoutAllowingCommandInjection() {
        assertEquals("'normal ssid'", PrivilegedWifiController.shellQuote("normal ssid"))
        assertEquals("'a'\\''b; reboot'", PrivilegedWifiController.shellQuote("a'b; reboot"))
        assertEquals(
            "cmd wifi connect-network 'a'\\''b; reboot' wpa2 'p\$word' -m -h",
            PrivilegedWifiController.buildConnectCommand("a'b; reboot", "p\$word"),
        )
    }
}
