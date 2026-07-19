package com.openclaw.assistant.broker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

internal object AssistantContractV1 {
    const val VERSION = 1
    const val MAX_PROPOSAL_LIFETIME_MS = 60_000L
    const val MAX_CLOCK_SKEW_MS = 5_000L
    const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
}

internal enum class AssistantRiskV1(val wireName: String) {
    LOW("LOW"),
    MEDIUM("MEDIUM"),
    HIGH("HIGH"),
}

internal enum class AssistantCapabilityV1(
    val wireName: String,
    val risk: AssistantRiskV1,
) {
    ANDROID_DEVICE_STATUS("android.device.status", AssistantRiskV1.LOW),
    ANDROID_CALENDAR_NEXT("android.calendar.next", AssistantRiskV1.LOW),
    ANDROID_CONTACTS_SEARCH("android.contacts.search", AssistantRiskV1.MEDIUM),
    ANDROID_PHONE_CALL_CONTACT("android.phone.call_contact", AssistantRiskV1.HIGH),
    ANDROID_SMS_SEND_CONTACT("android.sms.send_contact", AssistantRiskV1.HIGH),
    WINDOWS_FILES_SEARCH("windows.files.search", AssistantRiskV1.LOW),
    WINDOWS_FILES_READ("windows.files.read", AssistantRiskV1.MEDIUM),
}

internal data class AssistantProposalV1(
    val contractVersion: Int = AssistantContractV1.VERSION,
    val proposalId: String,
    val planId: String,
    val stepId: String,
    val capability: AssistantCapabilityV1,
    val arguments: JsonObject,
    val argumentsHash: String,
    val targetDeviceId: String,
    val voiceSessionKey: String,
    val presenceLeaseId: String,
    val issuedAtMs: Long,
    val expiresAtMs: Long,
    val idempotencyKey: String,
    val risk: AssistantRiskV1,
) {
    fun signaturePayload(): ByteArray = CanonicalJsonV1.encode(
        buildJsonObject {
            put("arguments", arguments)
            put("argumentsHash", argumentsHash)
            put("capability", capability.wireName)
            put("contractVersion", contractVersion)
            put("expiresAtMs", expiresAtMs)
            put("idempotencyKey", idempotencyKey)
            put("issuedAtMs", issuedAtMs)
            put("planId", planId)
            put("presenceLeaseId", presenceLeaseId)
            put("proposalId", proposalId)
            put("risk", risk.wireName)
            put("stepId", stepId)
            put("targetDeviceId", targetDeviceId)
            put("voiceSessionKey", voiceSessionKey)
        },
    ).toByteArray(Charsets.UTF_8)
}

internal data class SignedAssistantProposalV1(
    val proposal: AssistantProposalV1,
    val signatureKeyId: String,
    val signatureBase64Url: String,
)

internal fun interface ProposalSignatureVerifierV1 {
    fun verify(payload: ByteArray, keyId: String, signatureBase64Url: String): Boolean
}

/** Verifies proposals only against explicitly pinned raw Ed25519 public keys. */
internal class PinnedEd25519ProposalSignatureVerifierV1(
    pinnedPublicKeys: Map<String, ByteArray>,
) : ProposalSignatureVerifierV1 {
    private val publicKeys = pinnedPublicKeys.mapValues { (keyId, keyBytes) ->
        require(keyId.matches(KEY_ID_PATTERN))
        require(keyBytes.size == Ed25519PublicKeyParameters.KEY_SIZE)
        Ed25519PublicKeyParameters(keyBytes.copyOf(), 0)
    }

    override fun verify(payload: ByteArray, keyId: String, signatureBase64Url: String): Boolean {
        val publicKey = publicKeys[keyId] ?: return false
        val signature = runCatching { Base64.getUrlDecoder().decode(signatureBase64Url) }
            .getOrNull()
            ?: return false
        if (
            signature.size != ED25519_SIGNATURE_SIZE ||
            Base64.getUrlEncoder().withoutPadding().encodeToString(signature) != signatureBase64Url
        ) {
            return false
        }
        return runCatching {
            val verifier = Ed25519Signer()
            verifier.init(false, publicKey)
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(signature)
        }.getOrDefault(false)
    }

    private companion object {
        val KEY_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        const val ED25519_SIGNATURE_SIZE = 64
    }
}

internal enum class ProposalRejectionV1 {
    CONTRACT_VERSION,
    IDENTIFIER,
    CAPABILITY_RISK,
    ARGUMENT_SCHEMA,
    ARGUMENT_HASH,
    TARGET_DEVICE,
    VOICE_SESSION,
    PROPOSAL_TIME,
    PRESENCE_LEASE,
    SIGNATURE,
}

internal sealed interface ProposalValidationV1 {
    data class Accepted(val proposal: AssistantProposalV1) : ProposalValidationV1
    data class Rejected(val reason: ProposalRejectionV1) : ProposalValidationV1
}

internal class AssistantProposalValidatorV1(
    private val presenceLeases: PresenceLeaseManager,
    private val signatureVerifier: ProposalSignatureVerifierV1,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    fun validate(
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): ProposalValidationV1 {
        val proposal = signed.proposal
        if (proposal.contractVersion != AssistantContractV1.VERSION) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.CONTRACT_VERSION)
        }
        if (!proposal.hasValidIdentifiers() || signed.signatureKeyId.isBlank() || signed.signatureBase64Url.isBlank()) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.IDENTIFIER)
        }
        if (proposal.risk != proposal.capability.risk) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.CAPABILITY_RISK)
        }
        if (!AssistantArgumentsV1.validate(proposal.capability, proposal.arguments)) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.ARGUMENT_SCHEMA)
        }
        if (!constantTimeEquals(proposal.argumentsHash, CanonicalJsonV1.sha256(proposal.arguments))) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.ARGUMENT_HASH)
        }
        if (proposal.targetDeviceId != expectedDeviceId) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.TARGET_DEVICE)
        }
        if (proposal.voiceSessionKey != expectedVoiceSessionKey) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.VOICE_SESSION)
        }
        val now = nowEpochMs()
        if (
            proposal.issuedAtMs !in 0..AssistantContractV1.MAX_SAFE_INTEGER ||
            proposal.expiresAtMs !in 0..AssistantContractV1.MAX_SAFE_INTEGER ||
            proposal.issuedAtMs > now + AssistantContractV1.MAX_CLOCK_SKEW_MS ||
            proposal.expiresAtMs <= now ||
            proposal.expiresAtMs <= proposal.issuedAtMs ||
            proposal.expiresAtMs - proposal.issuedAtMs > AssistantContractV1.MAX_PROPOSAL_LIFETIME_MS
        ) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.PROPOSAL_TIME)
        }
        if (!signatureVerifier.verify(
                proposal.signaturePayload(),
                signed.signatureKeyId,
                signed.signatureBase64Url,
            )
        ) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.SIGNATURE)
        }
        val lease = presenceLeases.validate(
            proposal.presenceLeaseId,
            proposal.voiceSessionKey,
            proposal.targetDeviceId,
        )
        if (lease !is PresenceLeaseValidation.Valid) {
            return ProposalValidationV1.Rejected(ProposalRejectionV1.PRESENCE_LEASE)
        }
        return ProposalValidationV1.Accepted(proposal)
    }

    private fun AssistantProposalV1.hasValidIdentifiers(): Boolean =
        listOf(proposalId, planId, stepId, idempotencyKey).all(::isUuid) &&
            presenceLeaseId.isNotBlank() &&
            targetDeviceId.isNotBlank() &&
            voiceSessionKey.isNotBlank()

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(Charsets.US_ASCII)
        val rightBytes = right.toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(leftBytes, rightBytes)
    }
}

internal enum class AssistantReceiptStatusV1(val wireName: String) {
    COMPLETED("COMPLETED"),
    DENIED("DENIED"),
    CANCELLED("CANCELLED"),
    FAILED("FAILED"),
    UNKNOWN("UNKNOWN"),
}

internal data class AssistantReceiptV1(
    val contractVersion: Int = AssistantContractV1.VERSION,
    val receiptId: String,
    val proposalId: String,
    val planId: String,
    val stepId: String,
    val capability: AssistantCapabilityV1,
    val argumentsHash: String,
    val targetDeviceId: String,
    val idempotencyKey: String,
    val status: AssistantReceiptStatusV1,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val resultSummary: JsonObject? = null,
    val errorCode: String? = null,
)

internal object CanonicalJsonV1 {
    fun encode(element: JsonElement): String = when (element) {
        JsonNull -> "null"
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
                "${JsonPrimitive(key)}:${encode(value)}"
            }
        is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") { encode(it) }
        is JsonPrimitive -> element.toString()
    }

    fun sha256(element: JsonElement): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encode(element).toByteArray(Charsets.UTF_8))
        return buildString(HASH_PREFIX.length + digest.size * 2) {
            append(HASH_PREFIX)
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private const val HASH_PREFIX = "sha256:"
    private const val HEX = "0123456789abcdef"
}

private object AssistantArgumentsV1 {
    fun validate(capability: AssistantCapabilityV1, arguments: JsonObject): Boolean = when (capability) {
        AssistantCapabilityV1.ANDROID_DEVICE_STATUS -> arguments.isEmpty()
        AssistantCapabilityV1.ANDROID_CALENDAR_NEXT ->
            arguments.hasOnly("afterEpochMs", "limit") &&
                arguments.optionalLong("afterEpochMs", 0L..AssistantContractV1.MAX_SAFE_INTEGER) &&
                arguments.optionalLong("limit", 1L..10L)
        AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH ->
            arguments.hasOnly("query", "limit") &&
                arguments.requiredString("query", 1..100) &&
                arguments.optionalLong("limit", 1L..10L)
        AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT ->
            arguments.hasOnly("query") &&
                arguments.requiredString("query", 1..100)
        AssistantCapabilityV1.ANDROID_SMS_SEND_CONTACT ->
            arguments.hasOnly("query", "message") &&
                arguments.requiredString("query", 1..100) &&
                arguments.requiredString("message", 1..1_000) &&
                arguments.rawStringLength("message") in 1..1_000
        AssistantCapabilityV1.WINDOWS_FILES_SEARCH ->
            arguments.hasOnly("query", "limit") &&
                arguments.requiredString("query", 1..200) &&
                arguments.optionalLong("limit", 1L..50L)
        AssistantCapabilityV1.WINDOWS_FILES_READ ->
            arguments.hasOnly("path", "maxBytes") &&
                arguments.requiredString("path", 1..1024) &&
                arguments.optionalLong("maxBytes", 1L..1_000_000L)
    }

    private fun JsonObject.hasOnly(vararg names: String): Boolean = keys.all { it in names }

    private fun JsonObject.requiredString(name: String, length: IntRange): Boolean {
        val value = this[name] as? JsonPrimitive ?: return false
        return value.isString && value.content.trim().length in length
    }

    private fun JsonObject.optionalLong(name: String, range: LongRange): Boolean {
        val value = this[name] ?: return true
        val primitive = value as? JsonPrimitive ?: return false
        return !primitive.isString && primitive.booleanOrNull == null && primitive.longOrNull in range
    }

    private fun JsonObject.rawStringLength(name: String): Int {
        val value = this[name] as? JsonPrimitive ?: return -1
        return if (value.isString) value.content.length else -1
    }
}
