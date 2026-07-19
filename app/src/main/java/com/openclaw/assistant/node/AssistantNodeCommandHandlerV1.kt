package com.openclaw.assistant.node

import com.openclaw.assistant.broker.AssistantCapabilityExecutorV1
import com.openclaw.assistant.broker.AssistantCapabilityV1
import com.openclaw.assistant.broker.AssistantExecutionOutcomeV1
import com.openclaw.assistant.broker.AssistantPrivateReadGrantManagerV1
import com.openclaw.assistant.broker.AssistantReceiptStatusV1
import com.openclaw.assistant.broker.AssistantSignedProposalDecodeV1
import com.openclaw.assistant.broker.AssistantWireCodecV1
import com.openclaw.assistant.broker.PresenceLeaseManager
import com.openclaw.assistant.broker.ProposalValidationV1
import com.openclaw.assistant.gateway.GatewaySession
import kotlinx.serialization.json.JsonObject

internal fun interface AssistantPrivateResultSinkV1 {
    fun deliver(capability: AssistantCapabilityV1, result: JsonObject): Boolean
}

internal fun interface AssistantPrivateReadApprovalGateV1 {
    suspend fun request(signed: com.openclaw.assistant.broker.SignedAssistantProposalV1): Boolean
}

/** Fixed, signed command boundary. These commands remain unadvertised until release gates close. */
internal class AssistantNodeCommandHandlerV1(
    private val presenceLeases: PresenceLeaseManager,
    private val executor: AssistantCapabilityExecutorV1,
    private val privateReadGrants: AssistantPrivateReadGrantManagerV1,
    private val privateReadApprovalGate: AssistantPrivateReadApprovalGateV1,
    private val securityGate: () -> Boolean,
    private val privateResultSink: AssistantPrivateResultSinkV1,
) {
    fun handlePresence(): GatewaySession.InvokeResult {
        if (!securityGate()) return presenceRequired()
        val lease = presenceLeases.current() ?: return presenceRequired()
        return GatewaySession.InvokeResult.ok(AssistantWireCodecV1.encodePresenceLease(lease))
    }

    suspend fun handleExecute(paramsJson: String?): GatewaySession.InvokeResult {
        if (!securityGate()) return presenceRequired()
        val lease = presenceLeases.current() ?: return presenceRequired()
        val signed = when (val decoded = AssistantWireCodecV1.decodeSignedProposal(paramsJson)) {
            is AssistantSignedProposalDecodeV1.Success -> decoded.signed
            is AssistantSignedProposalDecodeV1.Rejected -> return GatewaySession.InvokeResult.error(
                decoded.code,
                "Signed assistant proposal was rejected",
            )
        }
        val expectedDeviceId = lease.targetDeviceId
        val expectedSessionKey = lease.voiceSessionKey
        if (
            signed.proposal.capability == AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH &&
            !privateReadGrants.isAuthorized(
                signed.proposal.capability,
                expectedSessionKey,
                expectedDeviceId,
            )
        ) {
            val initialValidation = executor.validate(signed, expectedDeviceId, expectedSessionKey)
            if (initialValidation is ProposalValidationV1.Accepted) {
                val approved = runCatching { privateReadApprovalGate.request(signed) }.getOrDefault(false)
                if (!approved) {
                    return sanitizedResult(
                        executor.execute(signed, expectedDeviceId, expectedSessionKey)
                            .withErrorCode("PRIVATE_READ_APPROVAL_DENIED"),
                    )
                }
                if (!securityGate()) {
                    return sanitizedResult(
                        executor.execute(signed, expectedDeviceId, expectedSessionKey)
                            .withErrorCode("UNLOCKED_PRESENCE_REQUIRED"),
                    )
                }
                if (executor.validate(signed, expectedDeviceId, expectedSessionKey) is ProposalValidationV1.Accepted) {
                    privateReadGrants.grant(
                        signed.proposal.capability,
                        expectedSessionKey,
                        expectedDeviceId,
                    )
                }
            }
        }
        val outcome = executor.execute(
            signed = signed,
            expectedDeviceId = expectedDeviceId,
            expectedVoiceSessionKey = expectedSessionKey,
        ).deliverPrivateResultOrFail()
        return sanitizedResult(outcome)
    }

    private fun AssistantExecutionOutcomeV1.withErrorCode(code: String): AssistantExecutionOutcomeV1 = copy(
        receipt = receipt.copy(errorCode = code),
        privateResult = null,
    )

    private fun sanitizedResult(outcome: AssistantExecutionOutcomeV1): GatewaySession.InvokeResult = runCatching {
        GatewaySession.InvokeResult.ok(AssistantWireCodecV1.encodeReceipt(outcome.receipt))
    }.getOrElse {
        GatewaySession.InvokeResult.error("RECEIPT_POLICY_REJECTED", "Assistant receipt was rejected")
    }

    private fun AssistantExecutionOutcomeV1.deliverPrivateResultOrFail(): AssistantExecutionOutcomeV1 {
        val private = privateResult ?: return this
        val delivered = securityGate() && runCatching {
            privateResultSink.deliver(receipt.capability, private)
        }.getOrDefault(false)
        if (delivered) return copy(privateResult = null)
        return copy(
            receipt = receipt.copy(
                status = AssistantReceiptStatusV1.FAILED,
                resultSummary = null,
                errorCode = "PRIVATE_RESULT_DELIVERY_UNAVAILABLE",
            ),
            privateResult = null,
        )
    }

    private fun presenceRequired(): GatewaySession.InvokeResult = GatewaySession.InvokeResult.error(
        "UNLOCKED_PRESENCE_REQUIRED",
        "An active unlocked voice session is required",
    )

    companion object {
        const val PRESENCE_COMMAND = "assistant.presence.v1"
        const val EXECUTE_COMMAND = "assistant.execute.v1"
    }
}
