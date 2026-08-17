package com.sjcyz.hmta.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OppoNfcInvitationTest {
    @Test
    fun parsesKnownOppoInvitation() {
        val invitation = OppoNfcInvitationParser.parse(
            "https://connect.oppo.com/oshare/clips/seo?version=1" +
                "&devId=41EEF98C1656&code=0123456789abcdef&devType=3&devName=OnePlus%20Ace",
        )

        requireNotNull(invitation)
        assertEquals("41EEF98C1656", invitation.deviceId)
        assertEquals("0123456789ABCDEF", invitation.randomCode)
        assertEquals("OnePlus Ace", invitation.deviceName)
        assertEquals(3, invitation.deviceType)
        assertEquals(1, invitation.version)
    }

    @Test
    fun rejectsNonOppoOrMalformedInvitation() {
        assertNull(OppoNfcInvitationParser.parse("http://connect.oppo.com/oshare/clips/seo?devId=x&code=0123456789ABCDEF"))
        assertNull(OppoNfcInvitationParser.parse("https://example.com/oshare/clips/seo?devId=x&code=0123456789ABCDEF"))
        assertNull(OppoNfcInvitationParser.parse("https://connect.oppo.com/not-oshare?devId=x&code=0123456789ABCDEF"))
        assertNull(OppoNfcInvitationParser.parse("https://connect.oppo.com/oshare/clips/seo?devId=x&code=short"))
        assertNull(OppoNfcInvitationParser.parse("https://connect.oppo.com/oshare/clips/seo?code=0123456789ABCDEF"))
    }

    @Test
    fun suppressesSameCodeInsideWindow() {
        var now = 1_000L
        val deduplicator = NfcInvitationDeduplicator(windowMillis = 3_000) { now }
        val invitation = requireNotNull(
            OppoNfcInvitationParser.parse(
                "https://connect.oppo.com/oshare/clips/seo?devId=device&code=0123456789ABCDEF",
            ),
        )

        assertTrue(deduplicator.accept(invitation))
        now += 2_999
        assertFalse(deduplicator.accept(invitation))
        now += 1
        assertTrue(deduplicator.accept(invitation))
    }
}
