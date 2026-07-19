package com.openclaw.assistant.broker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal sealed interface AssistantSignedProposalDecodeV1 {
    data class Success(val signed: SignedAssistantProposalV1) : AssistantSignedProposalDecodeV1
    data class Rejected(val code: String) : AssistantSignedProposalDecodeV1
}

internal object AssistantWireCodecV1 {
    private const val MAX_SIGNED_PROPOSAL_BYTES = 16 * 1_024
    private val json = Json { ignoreUnknownKeys = false }
    private val signedKeys = setOf("proposal", "signatureKeyId", "signatureBase64Url")
    private val proposalKeys = setOf(
        "contractVersion",
        "proposalId",
        "planId",
        "stepId",
        "capability",
        "arguments",
        "argumentsHash",
        "targetDeviceId",
        "voiceSessionKey",
        "presenceLeaseId",
        "issuedAtMs",
        "expiresAtMs",
        "idempotencyKey",
        "risk",
    )

    fun decodeSignedProposal(value: String?): AssistantSignedProposalDecodeV1 {
        if (value == null || value.toByteArray(Charsets.UTF_8).size > MAX_SIGNED_PROPOSAL_BYTES) {
            return AssistantSignedProposalDecodeV1.Rejected("SIGNED_PROPOSAL_MALFORMED")
        }
        val root = runCatching { json.parseToJsonElement(value).jsonObject }.getOrNull()
            ?: return AssistantSignedProposalDecodeV1.Rejected("SIGNED_PROPOSAL_MALFORMED")
        if (root.keys != signedKeys) {
            return AssistantSignedProposalDecodeV1.Rejected("SIGNED_PROPOSAL_SCHEMA")
        }
        val proposal = root["proposal"] as? JsonObject
            ?: return AssistantSignedProposalDecodeV1.Rejected("SIGNED_PROPOSAL_SCHEMA")
        if (proposal.keys != proposalKeys) {
            return AssistantSignedProposalDecodeV1.Rejected("SIGNED_PROPOSAL_SCHEMA")
        }
        return runCatching {
            val capabilityName = proposal.requiredString("capability")
            val riskName = proposal.requiredString("risk")
            val capability = AssistantCapabilityV1.entries.single { it.wireName == capabilityName }
            val risk = AssistantRiskV1.entries.single { it.wireName == riskName }
            AssistantSignedProposalDecodeV1.Success(
                SignedAssistantProposalV1(
                    proposal = AssistantProposalV1(
                        contractVersion = proposal.requiredInt("contractVersion"),
                        proposalId = proposal.requiredString("proposalId"),
                        planId = proposal.requiredString("planId"),
                        stepId = proposal.requiredString("stepId"),
                        capability = capability,
                        arguments = proposal["arguments"] as? JsonObject ?: error("arguments"),
                        argumentsHash = proposal.requiredString("argumentsHash"),
                        targetDeviceId = proposal.requiredString("targetDeviceId"),
                        voiceSessionKey = proposal.requiredString("voiceSessionKey"),
                        presenceLeaseId = proposal.requiredString("presenceLeaseId"),
                        issuedAtMs = proposal.requiredLong("issuedAtMs"),
                        expiresAtMs = proposal.requiredLong("expiresAtMs"),
                        idempotencyKey = proposal.requiredString("idempotencyKey"),
                        risk = risk,
                    ),
                    signatureKeyId = root.requiredString("signatureKeyId"),
                    signatureBase64Url = root.requiredString("signatureBase64Url"),
                ),
            )
        }.getOrElse {
            AssistantSignedProposalDecodeV1.Rejected("SIGNED_PROPOSAL_SCHEMA")
        }
    }

    fun encodeSignedProposal(signed: SignedAssistantProposalV1): String = buildJsonObject {
        put("proposal", signed.proposal.toJson())
        put("signatureKeyId", signed.signatureKeyId)
        put("signatureBase64Url", signed.signatureBase64Url)
    }.toString()

    fun encodePresenceLease(lease: PresenceLease): String = buildJsonObject {
        put("contractVersion", lease.contractVersion)
        put("presenceLeaseId", lease.leaseId)
    }.toString()

    fun encodeReceipt(receipt: AssistantReceiptV1): String {
        require(receipt.resultSummary.hasAllowedSummaryFor(receipt.capability))
        return buildJsonObject {
            put("contractVersion", receipt.contractVersion)
            put("receiptId", receipt.receiptId)
            put("proposalId", receipt.proposalId)
            put("planId", receipt.planId)
            put("stepId", receipt.stepId)
            put("capability", receipt.capability.wireName)
            put("argumentsHash", receipt.argumentsHash)
            put("targetDeviceId", receipt.targetDeviceId)
            put("idempotencyKey", receipt.idempotencyKey)
            put("status", receipt.status.wireName)
            put("startedAtMs", receipt.startedAtMs)
            put("finishedAtMs", receipt.finishedAtMs)
            receipt.resultSummary?.let { put("resultSummary", it) }
            receipt.errorCode?.let { put("errorCode", it) }
        }.toString()
    }

    private fun AssistantProposalV1.toJson(): JsonObject = buildJsonObject {
        put("contractVersion", contractVersion)
        put("proposalId", proposalId)
        put("planId", planId)
        put("stepId", stepId)
        put("capability", capability.wireName)
        put("arguments", arguments)
        put("argumentsHash", argumentsHash)
        put("targetDeviceId", targetDeviceId)
        put("voiceSessionKey", voiceSessionKey)
        put("presenceLeaseId", presenceLeaseId)
        put("issuedAtMs", issuedAtMs)
        put("expiresAtMs", expiresAtMs)
        put("idempotencyKey", idempotencyKey)
        put("risk", risk.wireName)
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

    private fun JsonObject.requiredLong(name: String): Long {
        val value = this[name] as? JsonPrimitive ?: error(name)
        require(!value.isString)
        return value.longOrNull ?: error(name)
    }

    private fun JsonObject?.hasAllowedSummaryFor(capability: AssistantCapabilityV1): Boolean {
        if (this == null) return true
        val allowed = when (capability) {
            AssistantCapabilityV1.ANDROID_DEVICE_STATUS ->
                setOf("batteryLevelPercent", "charging", "screenInteractive")
            AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH -> setOf("matchCount", "truncated")
            else -> emptySet()
        }
        return keys.all { it in allowed }
    }
}
