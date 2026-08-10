package com.sjcyz.hmta.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSocketMessageTest {
    @Test
    fun parseMessageWithoutPayload() {
        val msg = WebSocketMessage.fromText("ack:12:versionNegotiation")
        assertEquals("ack", msg?.type)
        assertEquals(12, msg?.id)
        assertEquals("versionNegotiation", msg?.name)
        assertNull(msg?.payload)
    }

    @Test
    fun parseMessageWithPayload() {
        val msg = WebSocketMessage.fromText("""action:1:sendRequest?{"fileCount":2}""")
        assertEquals("action", msg?.type)
        assertEquals(1, msg?.id)
        assertEquals("sendRequest", msg?.name)
        assertEquals(2, msg?.payload?.getInt("fileCount"))
    }

    @Test
    fun rejectMalformedInput() {
        assertNull(WebSocketMessage.fromText("not a message"))
        assertNull(WebSocketMessage.fromText("action:abc:sendRequest"))
    }

    @Test
    fun roundTripStatusMessage() {
        val msg = WebSocketMessage.makeStatus(3, "42", 1, "ok")
        val parsed = WebSocketMessage.fromText(msg.toText())
        assertEquals(3, parsed?.id)
        assertEquals("status", parsed?.name)
        assertEquals(1, parsed?.payload?.getInt("type"))
        assertEquals("42", parsed?.payload?.getString("taskId"))
        assertEquals("ok", parsed?.payload?.getString("reason"))
    }

    @Test
    fun customIdOverrides() {
        val msg = WebSocketMessage("action", 1, "sendRequest", null)
        val text = msg.toText(newId = 99)
        assertEquals("action:99:sendRequest", text)
    }
}
