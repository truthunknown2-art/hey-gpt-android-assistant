package com.openclaw.assistant.broker

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal data class AndroidDeviceStatusSummaryV1(
    val batteryLevelPercent: Int?,
    val charging: Boolean,
    val screenInteractive: Boolean,
)

internal fun interface AndroidDeviceStatusReaderV1 {
    fun read(): AndroidDeviceStatusSummaryV1
}

/** Executes already typed proposals through fixed native capability handlers. */
internal class AssistantCapabilityExecutorV1(
    private val validator: AssistantProposalValidatorV1,
    private val deviceStatusReader: AndroidDeviceStatusReaderV1,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val newReceiptId: () -> String = { UUID.randomUUID().toString() },
) {
    fun execute(
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): AssistantReceiptV1 {
        val startedAtMs = safeNow()
        return when (val validation = validator.validate(
            signed = signed,
            expectedDeviceId = expectedDeviceId,
            expectedVoiceSessionKey = expectedVoiceSessionKey,
        )) {
            is ProposalValidationV1.Rejected -> receipt(
                proposal = signed.proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = startedAtMs,
                errorCode = "PROPOSAL_${validation.reason.name}",
            )
            is ProposalValidationV1.Accepted -> executeAccepted(validation.proposal, startedAtMs)
        }
    }

    private fun executeAccepted(
        proposal: AssistantProposalV1,
        startedAtMs: Long,
    ): AssistantReceiptV1 = when (proposal.capability) {
        AssistantCapabilityV1.ANDROID_DEVICE_STATUS -> runCatching {
            deviceStatusReader.read().toResultSummary()
        }.fold(
            onSuccess = { summary ->
                receipt(
                    proposal = proposal,
                    status = AssistantReceiptStatusV1.COMPLETED,
                    startedAtMs = startedAtMs,
                    resultSummary = summary,
                )
            },
            onFailure = {
                receipt(
                    proposal = proposal,
                    status = AssistantReceiptStatusV1.FAILED,
                    startedAtMs = startedAtMs,
                    errorCode = "DEVICE_STATUS_READ_FAILED",
                )
            },
        )
        else -> receipt(
            proposal = proposal,
            status = AssistantReceiptStatusV1.DENIED,
            startedAtMs = startedAtMs,
            errorCode = "CAPABILITY_NOT_IMPLEMENTED",
        )
    }

    private fun receipt(
        proposal: AssistantProposalV1,
        status: AssistantReceiptStatusV1,
        startedAtMs: Long,
        resultSummary: JsonObject? = null,
        errorCode: String? = null,
    ): AssistantReceiptV1 = AssistantReceiptV1(
        receiptId = newReceiptId().also { require(isCanonicalUuid(it)) },
        proposalId = proposal.proposalId,
        planId = proposal.planId,
        stepId = proposal.stepId,
        capability = proposal.capability,
        argumentsHash = proposal.argumentsHash,
        targetDeviceId = proposal.targetDeviceId,
        idempotencyKey = proposal.idempotencyKey,
        status = status,
        startedAtMs = startedAtMs,
        finishedAtMs = maxOf(startedAtMs, safeNow()),
        resultSummary = resultSummary,
        errorCode = errorCode,
    )

    private fun safeNow(): Long = nowEpochMs().coerceIn(0L, AssistantContractV1.MAX_SAFE_INTEGER)

    private fun isCanonicalUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
}

private fun AndroidDeviceStatusSummaryV1.toResultSummary(): JsonObject = buildJsonObject {
    put(
        "batteryLevelPercent",
        batteryLevelPercent?.takeIf { it in 0..100 }?.let(::JsonPrimitive) ?: JsonNull,
    )
    put("charging", JsonPrimitive(charging))
    put("screenInteractive", JsonPrimitive(screenInteractive))
}
