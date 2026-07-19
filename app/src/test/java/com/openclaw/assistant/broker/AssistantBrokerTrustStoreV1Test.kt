package com.openclaw.assistant.broker

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class AssistantBrokerTrustStoreV1Test {
    @Test
    fun `decodes validates and explicitly pins one public key`() {
        val backing = mutableMapOf<String, String>()
        val store = store(backing)
        val candidate = AssistantBrokerTrustStoreV1.decodeGatewayResponse(payload(KEY_A))

        val result = store.pin(candidate)

        assertTrue(result is AssistantBrokerPinResultV1.Pinned)
        assertEquals(candidate, store.load())
        assertArrayEquals(KEY_A, store.load()!!.rawPublicKey())
        assertNotNull(store.signatureVerifier())
        assertFalse(backing.values.any { it.contains("PRIVATE") })
    }

    @Test
    fun `same key is idempotent but changed key is rejected without overwrite`() {
        val backing = mutableMapOf<String, String>()
        val store = store(backing)
        val first = AssistantBrokerTrustStoreV1.decodeGatewayResponse(payload(KEY_A))
        val changed = AssistantBrokerTrustStoreV1.decodeGatewayResponse(payload(KEY_B))
        store.pin(first)

        assertTrue(store.pin(first) is AssistantBrokerPinResultV1.AlreadyPinned)
        val result = store.pin(changed)

        assertTrue(result is AssistantBrokerPinResultV1.Changed)
        assertEquals(first, store.load())
    }

    @Test
    fun `invalid fingerprint unknown fields and noncanonical key fail closed`() {
        val good = payload(KEY_A)
        val wrongFingerprint = good.replace(fingerprint(KEY_A), "sha256:${"0".repeat(64)}")
        val unknownField = good.dropLast(1) + ",\"privateKey\":\"no\"}"
        val paddedKey = Base64.getUrlEncoder().encodeToString(KEY_A)

        assertFails { AssistantBrokerTrustStoreV1.decodeGatewayResponse(wrongFingerprint) }
        assertFails { AssistantBrokerTrustStoreV1.decodeGatewayResponse(unknownField) }
        assertFails { AssistantBrokerTrustStoreV1.decodeGatewayResponse(payload(KEY_A, paddedKey)) }
    }

    @Test
    fun `incomplete stored trust fails closed and clear removes every field`() {
        val backing = mutableMapOf<String, String>()
        val store = store(backing)
        val candidate = AssistantBrokerTrustStoreV1.decodeGatewayResponse(payload(KEY_A))
        store.pin(candidate)
        backing.entries.removeIf { it.key.contains("publicKey") }

        assertFails { store.load() }
        store.clear()
        assertTrue(backing.isEmpty())
        assertNull(store.load())
    }

    private fun store(backing: MutableMap<String, String>) = AssistantBrokerTrustStoreV1(
        read = backing::get,
        write = { key, value -> backing[key] = value },
        remove = { backing.remove(it) },
    )

    private fun payload(raw: ByteArray, encoded: String = base64(raw)): String = buildJsonObject {
        put("contractVersion", 1)
        put("algorithm", "Ed25519")
        put("keyId", "assistant-v1-${fingerprint(raw).removePrefix("sha256:").take(24)}")
        put("publicKeyBase64Url", encoded)
        put("fingerprintSha256", fingerprint(raw))
    }.toString()

    private fun fingerprint(raw: ByteArray): String = "sha256:" +
        MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    private fun base64(raw: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }

    private companion object {
        val KEY_A = ByteArray(32) { it.toByte() }
        val KEY_B = ByteArray(32) { (it + 1).toByte() }
    }
}
