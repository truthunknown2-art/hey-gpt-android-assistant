package com.openclaw.assistant.node

import com.openclaw.assistant.broker.AssistantCapabilityExecutorV1
import com.openclaw.assistant.broker.AssistantCapabilityV1
import com.openclaw.assistant.broker.AssistantCalendarCreatePreparationV1
import com.openclaw.assistant.broker.AssistantContactCallPreparationV1
import com.openclaw.assistant.broker.AssistantContactSmsPreparationV1
import com.openclaw.assistant.broker.AssistantExecutionOutcomeV1
import com.openclaw.assistant.broker.AssistantPrivateReadGrantManagerV1
import com.openclaw.assistant.broker.AssistantPrivateResultDeliveryV1
import com.openclaw.assistant.broker.AssistantReceiptStatusV1
import com.openclaw.assistant.broker.AssistantSignedProposalDecodeV1
import com.openclaw.assistant.broker.AssistantWireCodecV1
import com.openclaw.assistant.broker.PresenceLeaseManager
import com.openclaw.assistant.broker.ProposalValidationV1
import com.openclaw.assistant.broker.requiresPrivateReadGrantV1
import com.openclaw.assistant.gateway.GatewaySession

internal fun interface AssistantPrivateResultSinkV1 {
    suspend fun deliver(delivery: AssistantPrivateResultDeliveryV1): Boolean
}

internal fun interface AssistantPrivateReadApprovalGateV1 {
    suspend fun request(signed: com.openclaw.assistant.broker.SignedAssistantProposalV1): Boolean
}

internal fun interface AssistantContactCallApprovalGateV1 {
    suspend fun request(prepared: AssistantContactCallPreparationV1.Ready): Boolean
}

internal fun interface AssistantContactSmsApprovalGateV1 {
    suspend fun request(prepared: AssistantContactSmsPreparationV1.Ready): Boolean
}

internal fun interface AssistantCalendarCreateApprovalGateV1 {
    suspend fun request(prepared: AssistantCalendarCreatePreparationV1.Ready): Boolean
}

/** Fixed, signed command boundary. These commands remain unadvertised until release gates close. */
internal class AssistantNodeCommandHandlerV1(
    private val presenceLeases: PresenceLeaseManager,
    private val executor: AssistantCapabilityExecutorV1,
    private val privateReadGrants: AssistantPrivateReadGrantManagerV1,
    private val privateReadApprovalGate: AssistantPrivateReadApprovalGateV1,
    private val contactCallApprovalGate: AssistantContactCallApprovalGateV1 =
        AssistantContactCallApprovalGateV1 { false },
    private val contactSmsApprovalGate: AssistantContactSmsApprovalGateV1 =
        AssistantContactSmsApprovalGateV1 { false },
    private val calendarCreateApprovalGate: AssistantCalendarCreateApprovalGateV1 =
        AssistantCalendarCreateApprovalGateV1 { false },
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
        if (signed.proposal.capability == AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT) {
            return handleContactCall(signed, expectedDeviceId, expectedSessionKey)
        }
        if (signed.proposal.capability == AssistantCapabilityV1.ANDROID_SMS_SEND_CONTACT) {
            return handleContactSms(signed, expectedDeviceId, expectedSessionKey)
        }
        if (signed.proposal.capability == AssistantCapabilityV1.ANDROID_CALENDAR_CREATE) {
            return handleCalendarCreate(signed, expectedDeviceId, expectedSessionKey)
        }
        val phonePrivateRead = signed.proposal.capability in PHONE_PRIVATE_READ_CAPABILITIES
        if (
            phonePrivateRead &&
            signed.proposal.capability.requiresPrivateReadGrantV1() &&
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
        ).deliverPrivateResultOrFail(signed.proposal)
        return sanitizedResult(outcome)
    }

    private suspend fun handleContactCall(
        signed: com.openclaw.assistant.broker.SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedSessionKey: String,
    ): GatewaySession.InvokeResult {
        val prepared = when (val result = executor.prepareContactCall(
            signed = signed,
            expectedDeviceId = expectedDeviceId,
            expectedVoiceSessionKey = expectedSessionKey,
        )) {
            is AssistantContactCallPreparationV1.Terminal -> return sanitizedResult(result.outcome)
            is AssistantContactCallPreparationV1.Ready -> result
        }
        val approved = runCatching { contactCallApprovalGate.request(prepared) }.getOrDefault(false)
        if (!approved) {
            return sanitizedResult(
                executor.cancelPreparedContactCall(prepared, "CALL_APPROVAL_DENIED"),
            )
        }
        if (!securityGate()) {
            return sanitizedResult(
                executor.cancelPreparedContactCall(prepared, "UNLOCKED_PRESENCE_REQUIRED"),
            )
        }
        return sanitizedResult(
            executor.executePreparedContactCall(
                prepared = prepared,
                signed = signed,
                expectedDeviceId = expectedDeviceId,
                expectedVoiceSessionKey = expectedSessionKey,
            ),
        )
    }

    private suspend fun handleContactSms(
        signed: com.openclaw.assistant.broker.SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedSessionKey: String,
    ): GatewaySession.InvokeResult {
        val prepared = when (val result = executor.prepareContactSms(
            signed = signed,
            expectedDeviceId = expectedDeviceId,
            expectedVoiceSessionKey = expectedSessionKey,
        )) {
            is AssistantContactSmsPreparationV1.Terminal -> return sanitizedResult(result.outcome)
            is AssistantContactSmsPreparationV1.Ready -> result
        }
        val approved = runCatching { contactSmsApprovalGate.request(prepared) }.getOrDefault(false)
        if (!approved) {
            return sanitizedResult(
                executor.cancelPreparedContactSms(prepared, "SMS_APPROVAL_DENIED"),
            )
        }
        if (!securityGate()) {
            return sanitizedResult(
                executor.cancelPreparedContactSms(prepared, "UNLOCKED_PRESENCE_REQUIRED"),
            )
        }
        return sanitizedResult(
            executor.executePreparedContactSms(
                prepared = prepared,
                signed = signed,
                expectedDeviceId = expectedDeviceId,
                expectedVoiceSessionKey = expectedSessionKey,
            ),
        )
    }

    private suspend fun handleCalendarCreate(
        signed: com.openclaw.assistant.broker.SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedSessionKey: String,
    ): GatewaySession.InvokeResult {
        val prepared = when (val result = executor.prepareCalendarCreate(
            signed = signed,
            expectedDeviceId = expectedDeviceId,
            expectedVoiceSessionKey = expectedSessionKey,
        )) {
            is AssistantCalendarCreatePreparationV1.Terminal -> return sanitizedResult(result.outcome)
            is AssistantCalendarCreatePreparationV1.Ready -> result
        }
        val approved = runCatching { calendarCreateApprovalGate.request(prepared) }.getOrDefault(false)
        if (!approved) {
            return sanitizedResult(
                executor.cancelPreparedCalendarCreate(prepared, "CALENDAR_APPROVAL_DENIED"),
            )
        }
        if (!securityGate()) {
            return sanitizedResult(
                executor.cancelPreparedCalendarCreate(prepared, "UNLOCKED_PRESENCE_REQUIRED"),
            )
        }
        return sanitizedResult(
            executor.executePreparedCalendarCreate(
                prepared = prepared,
                signed = signed,
                expectedDeviceId = expectedDeviceId,
                expectedVoiceSessionKey = expectedSessionKey,
            ),
        )
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

    private suspend fun AssistantExecutionOutcomeV1.deliverPrivateResultOrFail(
        proposal: com.openclaw.assistant.broker.AssistantProposalV1,
    ): AssistantExecutionOutcomeV1 {
        val private = privateResult ?: return this
        val delivered = securityGate() && runCatching {
            privateResultSink.deliver(
                AssistantPrivateResultDeliveryV1(
                    capability = receipt.capability,
                    voiceSessionKey = proposal.voiceSessionKey,
                    targetDeviceId = proposal.targetDeviceId,
                    result = private,
                ),
            )
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
        private val PHONE_PRIVATE_READ_CAPABILITIES = setOf(
            AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH,
            AssistantCapabilityV1.ANDROID_CALENDAR_NEXT,
        )
    }
}
