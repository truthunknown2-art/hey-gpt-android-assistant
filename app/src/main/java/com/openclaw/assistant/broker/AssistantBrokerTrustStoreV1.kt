package com.openclaw.assistant.broker

import com.openclaw.assistant.SecurePrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.util.Base64

internal data class AssistantBrokerPublicKeyV1(
    val contractVersion: Int,
    val algorithm: String,
    val keyId: String,
    val publicKeyBase64Url: String,
    val fingerprintSha256: String,
) {
    fun rawPublicKey(): ByteArray = Base64.getUrlDecoder().decode(publicKeyBase64Url)
}

internal sealed interface AssistantBrokerPinResultV1 {
    data class Pinned(val key: AssistantBrokerPublicKeyV1) : AssistantBrokerPinResultV1
    data class AlreadyPinned(val key: AssistantBrokerPublicKeyV1) : AssistantBrokerPinResultV1
    data class Changed(
        val current: AssistantBrokerPublicKeyV1,
        val candidate: AssistantBrokerPublicKeyV1,
    ) : AssistantBrokerPinResultV1
}

/** Encrypted trust-on-first-explicit-use store for the broker signing key. */
internal class AssistantBrokerTrustStoreV1(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
    private val remove: (String) -> Unit,
) {
    constructor(prefs: SecurePrefs) : this(prefs::getString, prefs::putString, prefs::remove)

    fun load(): AssistantBrokerPublicKeyV1? {
        val keyId = read(KEY_ID) ?: return null
        val publicKey = read(PUBLIC_KEY) ?: error("Pinned broker trust is incomplete")
        val fingerprint = read(FINGERPRINT) ?: error("Pinned broker trust is incomplete")
        return validate(
            AssistantBrokerPublicKeyV1(
                contractVersion = AssistantContractV1.VERSION,
                algorithm = ALGORITHM,
                keyId = keyId,
                publicKeyBase64Url = publicKey,
                fingerprintSha256 = fingerprint,
            ),
        )
    }

    fun pin(candidate: AssistantBrokerPublicKeyV1): AssistantBrokerPinResultV1 {
        val valid = validate(candidate)
        val current = load()
        if (current != null) {
            return if (current == valid) {
                AssistantBrokerPinResultV1.AlreadyPinned(current)
            } else {
                AssistantBrokerPinResultV1.Changed(current, valid)
            }
        }
        write(KEY_ID, valid.keyId)
        write(PUBLIC_KEY, valid.publicKeyBase64Url)
        write(FINGERPRINT, valid.fingerprintSha256)
        return AssistantBrokerPinResultV1.Pinned(valid)
    }

    fun signatureVerifier(): ProposalSignatureVerifierV1? = load()?.let { pinned ->
        PinnedEd25519ProposalSignatureVerifierV1(mapOf(pinned.keyId to pinned.rawPublicKey()))
    }

    fun clear() {
        remove(KEY_ID)
        remove(PUBLIC_KEY)
        remove(FINGERPRINT)
    }

    companion object {
        private const val ALGORITHM = "Ed25519"
        private const val KEY_ID = "assistant.broker.signing.keyId.v1"
        private const val PUBLIC_KEY = "assistant.broker.signing.publicKey.v1"
        private const val FINGERPRINT = "assistant.broker.signing.fingerprint.v1"
        private val json = Json { ignoreUnknownKeys = false }
        private val exactKeys = setOf(
            "contractVersion",
            "algorithm",
            "keyId",
            "publicKeyBase64Url",
            "fingerprintSha256",
        )
        private val keyIdPattern = Regex("assistant-v1-[0-9a-f]{24}")
        private val fingerprintPattern = Regex("sha256:[0-9a-f]{64}")

        fun decodeGatewayResponse(payloadJson: String): AssistantBrokerPublicKeyV1 {
            require(payloadJson.toByteArray(Charsets.UTF_8).size <= 4 * 1_024)
            val root = json.parseToJsonElement(payloadJson).jsonObject
            require(root.keys == exactKeys)
            return validate(
                AssistantBrokerPublicKeyV1(
                    contractVersion = root.requiredInt("contractVersion"),
                    algorithm = root.requiredString("algorithm"),
                    keyId = root.requiredString("keyId"),
                    publicKeyBase64Url = root.requiredString("publicKeyBase64Url"),
                    fingerprintSha256 = root.requiredString("fingerprintSha256"),
                ),
            )
        }

        private fun validate(candidate: AssistantBrokerPublicKeyV1): AssistantBrokerPublicKeyV1 {
            require(candidate.contractVersion == AssistantContractV1.VERSION)
            require(candidate.algorithm == ALGORITHM)
            require(candidate.keyId.matches(keyIdPattern))
            require(candidate.fingerprintSha256.matches(fingerprintPattern))
            val raw = runCatching { Base64.getUrlDecoder().decode(candidate.publicKeyBase64Url) }
                .getOrElse { throw IllegalArgumentException("Broker public key is invalid") }
            require(raw.size == 32)
            require(Base64.getUrlEncoder().withoutPadding().encodeToString(raw) == candidate.publicKeyBase64Url)
            val digest = MessageDigest.getInstance("SHA-256").digest(raw)
            val expectedFingerprint = "sha256:" + digest.joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            require(MessageDigest.isEqual(
                expectedFingerprint.toByteArray(Charsets.US_ASCII),
                candidate.fingerprintSha256.toByteArray(Charsets.US_ASCII),
            ))
            require(candidate.keyId == "assistant-v1-${expectedFingerprint.removePrefix("sha256:").take(24)}")
            return candidate
        }

        private fun JsonObject.requiredString(name: String): String {
            val value = this[name] as? JsonPrimitive ?: error(name)
            require(value.isString)
            return value.content
        }

        private fun JsonObject.requiredInt(name: String): Int {
            val value = this[name] as? JsonPrimitive ?: error(name)
            require(!value.isString)
            return value.intOrNull ?: error(name)
        }
    }
}
