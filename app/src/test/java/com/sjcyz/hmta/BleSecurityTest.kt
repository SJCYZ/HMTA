package com.sjcyz.hmta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class BleSecurityTest {
    @Test
    fun encodedPublicKeyIsValidX509EcKey() {
        val encoded = BleSecurity.getEncodedPublicKey()
        assertNotNull(encoded)
        val raw = Base64.getDecoder().decode(encoded)
        assertTrue(raw.isNotEmpty())
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(raw))
        assertTrue(pub is ECPublicKey)
    }

    @Test
    fun sessionCipherRoundTrip() {
        val pub = BleSecurity.getEncodedPublicKey()
        val cipher = BleSecurity.deriveSessionKey(pub)
        val plain = "hello 互传 / 12345"
        val encrypted = cipher.encrypt(plain)
        assertEquals(plain, cipher.decrypt(encrypted))
    }

    @Test
    fun encryptedPayloadDiffersFromPlain() {
        val pub = BleSecurity.getEncodedPublicKey()
        val cipher = BleSecurity.deriveSessionKey(pub)
        val encrypted = cipher.encrypt("secret")
        assertTrue(encrypted != "secret")
    }
}
