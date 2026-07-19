package com.openclaw.assistant.broker

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal data class AndroidDeviceStatusSummaryV1(
    val batteryLevelPercent: Int?,
    val charging: Boolean,
    val screenInteractive: Boolean,
)

internal fun interface AndroidDeviceStatusReaderV1 {
    fun read(): AndroidDeviceStatusSummaryV1
}

internal data class AndroidCalendarEventV1(
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val allDay: Boolean,
)

internal sealed interface AndroidCalendarNextReadV1 {
    data class Success(
        val events: List<AndroidCalendarEventV1>,
        val truncated: Boolean,
    ) : AndroidCalendarNextReadV1

    data object PermissionRequired : AndroidCalendarNextReadV1
}

internal fun interface AndroidCalendarNextReaderV1 {
    fun read(afterEpochMs: Long, limit: Int): AndroidCalendarNextReadV1
}

internal data class AndroidCalendarCreateTargetV1(
    val calendarId: Long,
    val displayName: String,
)

internal sealed interface AndroidCalendarCreateResolutionV1 {
    data class Ready(val target: AndroidCalendarCreateTargetV1) : AndroidCalendarCreateResolutionV1
    data object PermissionRequired : AndroidCalendarCreateResolutionV1
    data object NotFound : AndroidCalendarCreateResolutionV1
    data object Failed : AndroidCalendarCreateResolutionV1
}

internal fun interface AndroidCalendarCreateResolverV1 {
    fun resolve(): AndroidCalendarCreateResolutionV1
}

internal sealed interface AndroidCalendarCreateWriteV1 {
    data object Created : AndroidCalendarCreateWriteV1
    data object PermissionRequired : AndroidCalendarCreateWriteV1
    data object Invalid : AndroidCalendarCreateWriteV1
    data object Failed : AndroidCalendarCreateWriteV1
}

internal fun interface AndroidCalendarCreateWriterV1 {
    fun create(
        calendarId: Long,
        title: String,
        startEpochMs: Long,
        endEpochMs: Long,
        allDay: Boolean,
    ): AndroidCalendarCreateWriteV1
}

internal data class AndroidContactMatchV1(
    val contactId: String,
    val displayName: String,
    val phoneNumber: String,
)

internal sealed interface AndroidContactsSearchReadV1 {
    data class Success(
        val matches: List<AndroidContactMatchV1>,
        val truncated: Boolean,
    ) : AndroidContactsSearchReadV1

    data object PermissionRequired : AndroidContactsSearchReadV1
}

internal fun interface AndroidContactsSearchReaderV1 {
    fun search(query: String, limit: Int): AndroidContactsSearchReadV1
}

internal data class AndroidContactCallTargetV1(
    val displayName: String,
    val phoneNumber: String,
)

internal sealed interface AndroidContactCallResolutionV1 {
    data class Ready(val target: AndroidContactCallTargetV1) : AndroidContactCallResolutionV1
    data object NotFound : AndroidContactCallResolutionV1
    data object Ambiguous : AndroidContactCallResolutionV1
    data object PermissionRequired : AndroidContactCallResolutionV1
}

internal fun interface AndroidContactCallResolverV1 {
    fun resolve(query: String): AndroidContactCallResolutionV1
}

internal sealed interface AndroidContactCallLaunchV1 {
    data class Launched(
        val placedCall: Boolean,
        val requiresTap: Boolean,
    ) : AndroidContactCallLaunchV1

    data object InvalidNumber : AndroidContactCallLaunchV1
    data object Failed : AndroidContactCallLaunchV1
}

internal fun interface AndroidContactCallLauncherV1 {
    fun launch(phoneNumber: String): AndroidContactCallLaunchV1
}

internal data class AndroidContactSmsTargetV1(
    val displayName: String,
    val phoneNumber: String,
)

internal sealed interface AndroidContactSmsResolutionV1 {
    data class Ready(val target: AndroidContactSmsTargetV1) : AndroidContactSmsResolutionV1
    data object NotFound : AndroidContactSmsResolutionV1
    data object Ambiguous : AndroidContactSmsResolutionV1
    data object PermissionRequired : AndroidContactSmsResolutionV1
}

internal fun interface AndroidContactSmsResolverV1 {
    fun resolve(query: String): AndroidContactSmsResolutionV1
}

internal sealed interface AndroidContactSmsSendV1 {
    data object Sent : AndroidContactSmsSendV1
    data object InvalidNumber : AndroidContactSmsSendV1
    data object PermissionRequired : AndroidContactSmsSendV1
    data object Unavailable : AndroidContactSmsSendV1
    data object Failed : AndroidContactSmsSendV1
    data object StatusUnknown : AndroidContactSmsSendV1
}

internal fun interface AndroidContactSmsSenderV1 {
    suspend fun send(phoneNumber: String, message: String): AndroidContactSmsSendV1
}

internal fun interface AssistantPrivateReadAuthorizerV1 {
    fun isAuthorized(
        capability: AssistantCapabilityV1,
        voiceSessionKey: String,
        targetDeviceId: String,
    ): Boolean
}

internal data class AssistantExecutionOutcomeV1(
    val receipt: AssistantReceiptV1,
    val privateResult: JsonObject? = null,
)

internal sealed interface AssistantContactCallPreparationV1 {
    data class Ready(
        val proposal: AssistantProposalV1,
        val target: AndroidContactCallTargetV1,
    ) : AssistantContactCallPreparationV1

    data class Terminal(val outcome: AssistantExecutionOutcomeV1) : AssistantContactCallPreparationV1
}

internal sealed interface AssistantContactSmsPreparationV1 {
    data class Ready(
        val proposal: AssistantProposalV1,
        val target: AndroidContactSmsTargetV1,
        val message: String,
    ) : AssistantContactSmsPreparationV1

    data class Terminal(val outcome: AssistantExecutionOutcomeV1) : AssistantContactSmsPreparationV1
}

internal sealed interface AssistantCalendarCreatePreparationV1 {
    data class Ready(
        val proposal: AssistantProposalV1,
        val target: AndroidCalendarCreateTargetV1,
        val title: String,
        val startEpochMs: Long,
        val endEpochMs: Long,
        val allDay: Boolean,
    ) : AssistantCalendarCreatePreparationV1

    data class Terminal(val outcome: AssistantExecutionOutcomeV1) : AssistantCalendarCreatePreparationV1
}

/** Executes already typed proposals through fixed native capability handlers. */
internal class AssistantCapabilityExecutorV1(
    private val validator: AssistantProposalValidatorV1,
    private val deviceStatusReader: AndroidDeviceStatusReaderV1,
    private val calendarNextReader: AndroidCalendarNextReaderV1 = AndroidCalendarNextReaderV1 { _, _ ->
        AndroidCalendarNextReadV1.PermissionRequired
    },
    private val contactsSearchReader: AndroidContactsSearchReaderV1,
    private val privateReadAuthorizer: AssistantPrivateReadAuthorizerV1,
    private val contactCallResolver: AndroidContactCallResolverV1 = AndroidContactCallResolverV1 {
        AndroidContactCallResolutionV1.PermissionRequired
    },
    private val contactCallLauncher: AndroidContactCallLauncherV1 = AndroidContactCallLauncherV1 {
        AndroidContactCallLaunchV1.Failed
    },
    private val contactSmsResolver: AndroidContactSmsResolverV1 = AndroidContactSmsResolverV1 {
        AndroidContactSmsResolutionV1.PermissionRequired
    },
    private val contactSmsSender: AndroidContactSmsSenderV1 = AndroidContactSmsSenderV1 { _, _ ->
        AndroidContactSmsSendV1.Failed
    },
    private val calendarCreateResolver: AndroidCalendarCreateResolverV1 = AndroidCalendarCreateResolverV1 {
        AndroidCalendarCreateResolutionV1.PermissionRequired
    },
    private val calendarCreateWriter: AndroidCalendarCreateWriterV1 =
        AndroidCalendarCreateWriterV1 { _, _, _, _, _ -> AndroidCalendarCreateWriteV1.Failed },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val newReceiptId: () -> String = { UUID.randomUUID().toString() },
) {
    fun validate(
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): ProposalValidationV1 = validator.validate(
        signed = signed,
        expectedDeviceId = expectedDeviceId,
        expectedVoiceSessionKey = expectedVoiceSessionKey,
    )

    fun execute(
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): AssistantExecutionOutcomeV1 {
        val startedAtMs = safeNow()
        return when (val validation = validate(
            signed = signed,
            expectedDeviceId = expectedDeviceId,
            expectedVoiceSessionKey = expectedVoiceSessionKey,
        )) {
            is ProposalValidationV1.Rejected -> AssistantExecutionOutcomeV1(
                receipt = receipt(
                    proposal = signed.proposal,
                    status = AssistantReceiptStatusV1.DENIED,
                    startedAtMs = startedAtMs,
                    errorCode = "PROPOSAL_${validation.reason.name}",
                ),
            )
            is ProposalValidationV1.Accepted -> executeAccepted(validation.proposal, startedAtMs)
        }
    }

    private fun executeAccepted(
        proposal: AssistantProposalV1,
        startedAtMs: Long,
    ): AssistantExecutionOutcomeV1 = when (proposal.capability) {
        AssistantCapabilityV1.ANDROID_DEVICE_STATUS -> runCatching {
            deviceStatusReader.read().toResultSummary()
        }.fold(
            onSuccess = { summary ->
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = proposal,
                        status = AssistantReceiptStatusV1.COMPLETED,
                        startedAtMs = startedAtMs,
                        resultSummary = summary,
                    ),
                )
            },
            onFailure = {
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = proposal,
                        status = AssistantReceiptStatusV1.FAILED,
                        startedAtMs = startedAtMs,
                        errorCode = "DEVICE_STATUS_READ_FAILED",
                    ),
                )
            },
        )
        AssistantCapabilityV1.ANDROID_CALENDAR_NEXT -> executeCalendarNext(proposal, startedAtMs)
        AssistantCapabilityV1.ANDROID_CALENDAR_CREATE -> AssistantExecutionOutcomeV1(
            receipt = receipt(
                proposal = proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = startedAtMs,
                errorCode = "CALENDAR_APPROVAL_REQUIRED",
            ),
        )
        AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH -> executeContactsSearch(proposal, startedAtMs)
        AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT -> AssistantExecutionOutcomeV1(
            receipt = receipt(
                proposal = proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = startedAtMs,
                errorCode = "CALL_APPROVAL_REQUIRED",
            ),
        )
        AssistantCapabilityV1.ANDROID_SMS_SEND_CONTACT -> AssistantExecutionOutcomeV1(
            receipt = receipt(
                proposal = proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = startedAtMs,
                errorCode = "SMS_APPROVAL_REQUIRED",
            ),
        )
        else -> AssistantExecutionOutcomeV1(
            receipt = receipt(
                proposal = proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = startedAtMs,
                errorCode = "CAPABILITY_NOT_IMPLEMENTED",
            ),
        )
    }

    fun prepareContactCall(
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): AssistantContactCallPreparationV1 {
        val startedAtMs = safeNow()
        val proposal = when (val validation = validate(signed, expectedDeviceId, expectedVoiceSessionKey)) {
            is ProposalValidationV1.Accepted -> validation.proposal
            is ProposalValidationV1.Rejected -> return AssistantContactCallPreparationV1.Terminal(
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = signed.proposal,
                        status = AssistantReceiptStatusV1.DENIED,
                        startedAtMs = startedAtMs,
                        errorCode = "PROPOSAL_${validation.reason.name}",
                    ),
                ),
            )
        }
        if (proposal.capability != AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT) {
            return AssistantContactCallPreparationV1.Terminal(
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = proposal,
                        status = AssistantReceiptStatusV1.DENIED,
                        startedAtMs = startedAtMs,
                        errorCode = "CAPABILITY_NOT_IMPLEMENTED",
                    ),
                ),
            )
        }
        val query = proposal.arguments.getValue("query").jsonPrimitive.content.trim()
        val resolution = runCatching { contactCallResolver.resolve(query) }.getOrElse {
            return AssistantContactCallPreparationV1.Terminal(
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = proposal,
                        status = AssistantReceiptStatusV1.FAILED,
                        startedAtMs = startedAtMs,
                        errorCode = "CONTACT_RESOLUTION_FAILED",
                    ),
                ),
            )
        }
        return when (resolution) {
            AndroidContactCallResolutionV1.NotFound -> contactCallTerminal(
                proposal,
                startedAtMs,
                "CONTACT_NOT_FOUND",
            )
            AndroidContactCallResolutionV1.Ambiguous -> contactCallTerminal(
                proposal,
                startedAtMs,
                "CONTACT_AMBIGUOUS",
            )
            AndroidContactCallResolutionV1.PermissionRequired -> contactCallTerminal(
                proposal,
                startedAtMs,
                "CONTACTS_READ_PERMISSION_REQUIRED",
            )
            is AndroidContactCallResolutionV1.Ready -> runCatching {
                AssistantContactCallPreparationV1.Ready(
                    proposal = proposal,
                    target = resolution.target.normalized(),
                )
            }.getOrElse {
                contactCallTerminal(proposal, startedAtMs, "CONTACT_RESULT_INVALID")
            }
        }
    }

    fun cancelPreparedContactCall(
        prepared: AssistantContactCallPreparationV1.Ready,
        errorCode: String,
    ): AssistantExecutionOutcomeV1 {
        require(errorCode.matches(Regex("[A-Z0-9_]{1,64}")))
        return AssistantExecutionOutcomeV1(
            receipt = receipt(
                proposal = prepared.proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = safeNow(),
                errorCode = errorCode,
            ),
        )
    }

    fun executePreparedContactCall(
        prepared: AssistantContactCallPreparationV1.Ready,
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): AssistantExecutionOutcomeV1 {
        val startedAtMs = safeNow()
        val validation = validate(signed, expectedDeviceId, expectedVoiceSessionKey)
        if (validation is ProposalValidationV1.Rejected) {
            return AssistantExecutionOutcomeV1(
                receipt = receipt(
                    proposal = signed.proposal,
                    status = AssistantReceiptStatusV1.DENIED,
                    startedAtMs = startedAtMs,
                    errorCode = "PROPOSAL_${validation.reason.name}",
                ),
            )
        }
        val proposal = (validation as ProposalValidationV1.Accepted).proposal
        if (
            proposal.capability != AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT ||
            proposal.proposalId != prepared.proposal.proposalId ||
            proposal.argumentsHash != prepared.proposal.argumentsHash ||
            proposal.targetDeviceId != prepared.proposal.targetDeviceId ||
            proposal.voiceSessionKey != prepared.proposal.voiceSessionKey
        ) {
            return AssistantExecutionOutcomeV1(
                receipt = receipt(
                    proposal = proposal,
                    status = AssistantReceiptStatusV1.DENIED,
                    startedAtMs = startedAtMs,
                    errorCode = "PREPARED_CALL_MISMATCH",
                ),
            )
        }
        return runCatching { contactCallLauncher.launch(prepared.target.phoneNumber) }.fold(
            onSuccess = { result ->
                when (result) {
                    is AndroidContactCallLaunchV1.Launched -> AssistantExecutionOutcomeV1(
                        receipt = receipt(
                            proposal = proposal,
                            status = AssistantReceiptStatusV1.COMPLETED,
                            startedAtMs = startedAtMs,
                            resultSummary = buildJsonObject {
                                put("placedCall", JsonPrimitive(result.placedCall))
                                put("requiresTap", JsonPrimitive(result.requiresTap))
                            },
                        ),
                    )
                    AndroidContactCallLaunchV1.InvalidNumber -> AssistantExecutionOutcomeV1(
                        receipt = receipt(
                            proposal = proposal,
                            status = AssistantReceiptStatusV1.DENIED,
                            startedAtMs = startedAtMs,
                            errorCode = "CONTACT_NUMBER_INVALID",
                        ),
                    )
                    AndroidContactCallLaunchV1.Failed -> AssistantExecutionOutcomeV1(
                        receipt = receipt(
                            proposal = proposal,
                            status = AssistantReceiptStatusV1.FAILED,
                            startedAtMs = startedAtMs,
                            errorCode = "CALL_LAUNCH_FAILED",
                        ),
                    )
                }
            },
            onFailure = {
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = proposal,
                        status = AssistantReceiptStatusV1.FAILED,
                        startedAtMs = startedAtMs,
                        errorCode = "CALL_LAUNCH_FAILED",
                    ),
                )
            },
        )
    }

    private fun contactCallTerminal(
        proposal: AssistantProposalV1,
        startedAtMs: Long,
        errorCode: String,
    ): AssistantContactCallPreparationV1.Terminal = AssistantContactCallPreparationV1.Terminal(
        AssistantExecutionOutcomeV1(
            receipt = receipt(
                proposal = proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = startedAtMs,
                errorCode = errorCode,
            ),
        ),
    )

    fun prepareContactSms(
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): AssistantContactSmsPreparationV1 {
        val startedAtMs = safeNow()
        val proposal = when (val validation = validate(signed, expectedDeviceId, expectedVoiceSessionKey)) {
            is ProposalValidationV1.Accepted -> validation.proposal
            is ProposalValidationV1.Rejected -> return AssistantContactSmsPreparationV1.Terminal(
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = signed.proposal,
                        status = AssistantReceiptStatusV1.DENIED,
                        startedAtMs = startedAtMs,
                        errorCode = "PROPOSAL_${validation.reason.name}",
                    ),
                ),
            )
        }
        if (proposal.capability != AssistantCapabilityV1.ANDROID_SMS_SEND_CONTACT) {
            return contactSmsTerminal(proposal, startedAtMs, "CAPABILITY_NOT_IMPLEMENTED")
        }
        val query = proposal.arguments.getValue("query").jsonPrimitive.content.trim()
        val message = proposal.arguments.getValue("message").jsonPrimitive.content
        val resolution = runCatching { contactSmsResolver.resolve(query) }.getOrElse {
            return contactSmsTerminal(proposal, startedAtMs, "CONTACT_RESOLUTION_FAILED")
        }
        return when (resolution) {
            AndroidContactSmsResolutionV1.NotFound ->
                contactSmsTerminal(proposal, startedAtMs, "CONTACT_NOT_FOUND")
            AndroidContactSmsResolutionV1.Ambiguous ->
                contactSmsTerminal(proposal, startedAtMs, "CONTACT_AMBIGUOUS")
            AndroidContactSmsResolutionV1.PermissionRequired ->
                contactSmsTerminal(proposal, startedAtMs, "CONTACTS_READ_PERMISSION_REQUIRED")
            is AndroidContactSmsResolutionV1.Ready -> runCatching {
                AssistantContactSmsPreparationV1.Ready(
                    proposal = proposal,
                    target = resolution.target.normalized(),
                    message = message,
                )
            }.getOrElse {
                contactSmsTerminal(proposal, startedAtMs, "CONTACT_RESULT_INVALID")
            }
        }
    }

    fun cancelPreparedContactSms(
        prepared: AssistantContactSmsPreparationV1.Ready,
        errorCode: String,
    ): AssistantExecutionOutcomeV1 {
        require(errorCode.matches(Regex("[A-Z0-9_]{1,64}")))
        return AssistantExecutionOutcomeV1(
            receipt = receipt(
                proposal = prepared.proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = safeNow(),
                errorCode = errorCode,
            ),
        )
    }

    suspend fun executePreparedContactSms(
        prepared: AssistantContactSmsPreparationV1.Ready,
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): AssistantExecutionOutcomeV1 {
        val startedAtMs = safeNow()
        val validation = validate(signed, expectedDeviceId, expectedVoiceSessionKey)
        if (validation is ProposalValidationV1.Rejected) {
            return AssistantExecutionOutcomeV1(
                receipt = receipt(
                    proposal = signed.proposal,
                    status = AssistantReceiptStatusV1.DENIED,
                    startedAtMs = startedAtMs,
                    errorCode = "PROPOSAL_${validation.reason.name}",
                ),
            )
        }
        val proposal = (validation as ProposalValidationV1.Accepted).proposal
        val proposalMessage = proposal.arguments["message"]?.jsonPrimitive?.content
        if (
            proposal.capability != AssistantCapabilityV1.ANDROID_SMS_SEND_CONTACT ||
            proposal.proposalId != prepared.proposal.proposalId ||
            proposal.argumentsHash != prepared.proposal.argumentsHash ||
            proposal.targetDeviceId != prepared.proposal.targetDeviceId ||
            proposal.voiceSessionKey != prepared.proposal.voiceSessionKey ||
            proposalMessage != prepared.message
        ) {
            return AssistantExecutionOutcomeV1(
                receipt = receipt(
                    proposal = proposal,
                    status = AssistantReceiptStatusV1.DENIED,
                    startedAtMs = startedAtMs,
                    errorCode = "PREPARED_SMS_MISMATCH",
                ),
            )
        }
        return runCatching {
            contactSmsSender.send(prepared.target.phoneNumber, prepared.message)
        }.fold(
            onSuccess = { result ->
                when (result) {
                    AndroidContactSmsSendV1.Sent -> AssistantExecutionOutcomeV1(
                        receipt = receipt(
                            proposal = proposal,
                            status = AssistantReceiptStatusV1.COMPLETED,
                            startedAtMs = startedAtMs,
                            resultSummary = buildJsonObject { put("sent", JsonPrimitive(true)) },
                        ),
                    )
                    AndroidContactSmsSendV1.InvalidNumber -> contactSmsOutcome(
                        proposal, startedAtMs, AssistantReceiptStatusV1.DENIED, "CONTACT_NUMBER_INVALID",
                    )
                    AndroidContactSmsSendV1.PermissionRequired -> contactSmsOutcome(
                        proposal, startedAtMs, AssistantReceiptStatusV1.DENIED, "SMS_SEND_PERMISSION_REQUIRED",
                    )
                    AndroidContactSmsSendV1.Unavailable -> contactSmsOutcome(
                        proposal, startedAtMs, AssistantReceiptStatusV1.FAILED, "SMS_UNAVAILABLE",
                    )
                    AndroidContactSmsSendV1.Failed -> contactSmsOutcome(
                        proposal, startedAtMs, AssistantReceiptStatusV1.FAILED, "SMS_SEND_FAILED",
                    )
                    AndroidContactSmsSendV1.StatusUnknown -> contactSmsOutcome(
                        proposal, startedAtMs, AssistantReceiptStatusV1.UNKNOWN, "SMS_SEND_STATUS_UNKNOWN",
                    )
                }
            },
            onFailure = {
                contactSmsOutcome(
                    proposal, startedAtMs, AssistantReceiptStatusV1.FAILED, "SMS_SEND_FAILED",
                )
            },
        )
    }

    private fun contactSmsTerminal(
        proposal: AssistantProposalV1,
        startedAtMs: Long,
        errorCode: String,
    ): AssistantContactSmsPreparationV1.Terminal = AssistantContactSmsPreparationV1.Terminal(
        contactSmsOutcome(proposal, startedAtMs, AssistantReceiptStatusV1.DENIED, errorCode),
    )

    private fun contactSmsOutcome(
        proposal: AssistantProposalV1,
        startedAtMs: Long,
        status: AssistantReceiptStatusV1,
        errorCode: String,
    ): AssistantExecutionOutcomeV1 = AssistantExecutionOutcomeV1(
        receipt = receipt(
            proposal = proposal,
            status = status,
            startedAtMs = startedAtMs,
            errorCode = errorCode,
        ),
    )

    fun prepareCalendarCreate(
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): AssistantCalendarCreatePreparationV1 {
        val startedAtMs = safeNow()
        val proposal = when (val validation = validate(signed, expectedDeviceId, expectedVoiceSessionKey)) {
            is ProposalValidationV1.Accepted -> validation.proposal
            is ProposalValidationV1.Rejected -> return AssistantCalendarCreatePreparationV1.Terminal(
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = signed.proposal,
                        status = AssistantReceiptStatusV1.DENIED,
                        startedAtMs = startedAtMs,
                        errorCode = "PROPOSAL_${validation.reason.name}",
                    ),
                ),
            )
        }
        if (proposal.capability != AssistantCapabilityV1.ANDROID_CALENDAR_CREATE) {
            return calendarCreateTerminal(proposal, startedAtMs, "CAPABILITY_NOT_IMPLEMENTED")
        }
        val title = proposal.arguments.getValue("title").jsonPrimitive.content.trim()
        val startEpochMs = proposal.arguments.getValue("startEpochMs").jsonPrimitive.longOrNull
            ?: return calendarCreateTerminal(proposal, startedAtMs, "CALENDAR_ARGUMENTS_INVALID")
        val endEpochMs = proposal.arguments.getValue("endEpochMs").jsonPrimitive.longOrNull
            ?: return calendarCreateTerminal(proposal, startedAtMs, "CALENDAR_ARGUMENTS_INVALID")
        val allDay = proposal.arguments["allDay"]?.jsonPrimitive?.booleanOrNull ?: false
        if (allDay && !validAllDayRange(startEpochMs, endEpochMs)) {
            return calendarCreateTerminal(proposal, startedAtMs, "CALENDAR_ARGUMENTS_INVALID")
        }
        val resolution = runCatching { calendarCreateResolver.resolve() }.getOrElse {
            return calendarCreateTerminal(proposal, startedAtMs, "CALENDAR_RESOLUTION_FAILED")
        }
        return when (resolution) {
            AndroidCalendarCreateResolutionV1.PermissionRequired ->
                calendarCreateTerminal(proposal, startedAtMs, "CALENDAR_PERMISSION_REQUIRED")
            AndroidCalendarCreateResolutionV1.NotFound ->
                calendarCreateTerminal(proposal, startedAtMs, "CALENDAR_NOT_FOUND")
            AndroidCalendarCreateResolutionV1.Failed ->
                calendarCreateTerminal(proposal, startedAtMs, "CALENDAR_RESOLUTION_FAILED")
            is AndroidCalendarCreateResolutionV1.Ready -> runCatching {
                AssistantCalendarCreatePreparationV1.Ready(
                    proposal = proposal,
                    target = resolution.target.normalized(),
                    title = title,
                    startEpochMs = startEpochMs,
                    endEpochMs = endEpochMs,
                    allDay = allDay,
                )
            }.getOrElse {
                calendarCreateTerminal(proposal, startedAtMs, "CALENDAR_RESULT_INVALID")
            }
        }
    }

    fun cancelPreparedCalendarCreate(
        prepared: AssistantCalendarCreatePreparationV1.Ready,
        errorCode: String,
    ): AssistantExecutionOutcomeV1 {
        require(errorCode.matches(Regex("[A-Z0-9_]{1,64}")))
        return calendarCreateOutcome(
            prepared.proposal,
            safeNow(),
            AssistantReceiptStatusV1.DENIED,
            errorCode,
        )
    }

    fun executePreparedCalendarCreate(
        prepared: AssistantCalendarCreatePreparationV1.Ready,
        signed: SignedAssistantProposalV1,
        expectedDeviceId: String,
        expectedVoiceSessionKey: String,
    ): AssistantExecutionOutcomeV1 {
        val startedAtMs = safeNow()
        val validation = validate(signed, expectedDeviceId, expectedVoiceSessionKey)
        if (validation is ProposalValidationV1.Rejected) {
            return calendarCreateOutcome(
                signed.proposal,
                startedAtMs,
                AssistantReceiptStatusV1.DENIED,
                "PROPOSAL_${validation.reason.name}",
            )
        }
        val proposal = (validation as ProposalValidationV1.Accepted).proposal
        val args = proposal.arguments
        if (
            proposal.capability != AssistantCapabilityV1.ANDROID_CALENDAR_CREATE ||
            proposal.proposalId != prepared.proposal.proposalId ||
            proposal.argumentsHash != prepared.proposal.argumentsHash ||
            proposal.targetDeviceId != prepared.proposal.targetDeviceId ||
            proposal.voiceSessionKey != prepared.proposal.voiceSessionKey ||
            args["title"]?.jsonPrimitive?.content?.trim() != prepared.title ||
            args["startEpochMs"]?.jsonPrimitive?.longOrNull != prepared.startEpochMs ||
            args["endEpochMs"]?.jsonPrimitive?.longOrNull != prepared.endEpochMs ||
            (args["allDay"]?.jsonPrimitive?.booleanOrNull ?: false) != prepared.allDay
        ) {
            return calendarCreateOutcome(
                proposal,
                startedAtMs,
                AssistantReceiptStatusV1.DENIED,
                "PREPARED_CALENDAR_MISMATCH",
            )
        }
        return runCatching {
            calendarCreateWriter.create(
                prepared.target.calendarId,
                prepared.title,
                prepared.startEpochMs,
                prepared.endEpochMs,
                prepared.allDay,
            )
        }.fold(
            onSuccess = { result ->
                when (result) {
                    AndroidCalendarCreateWriteV1.Created -> AssistantExecutionOutcomeV1(
                        receipt = receipt(
                            proposal = proposal,
                            status = AssistantReceiptStatusV1.COMPLETED,
                            startedAtMs = startedAtMs,
                            resultSummary = buildJsonObject { put("created", JsonPrimitive(true)) },
                        ),
                    )
                    AndroidCalendarCreateWriteV1.PermissionRequired -> calendarCreateOutcome(
                        proposal, startedAtMs, AssistantReceiptStatusV1.DENIED, "CALENDAR_PERMISSION_REQUIRED",
                    )
                    AndroidCalendarCreateWriteV1.Invalid -> calendarCreateOutcome(
                        proposal, startedAtMs, AssistantReceiptStatusV1.DENIED, "CALENDAR_ARGUMENTS_INVALID",
                    )
                    AndroidCalendarCreateWriteV1.Failed -> calendarCreateOutcome(
                        proposal, startedAtMs, AssistantReceiptStatusV1.FAILED, "CALENDAR_CREATE_FAILED",
                    )
                }
            },
            onFailure = {
                calendarCreateOutcome(
                    proposal, startedAtMs, AssistantReceiptStatusV1.FAILED, "CALENDAR_CREATE_FAILED",
                )
            },
        )
    }

    private fun calendarCreateTerminal(
        proposal: AssistantProposalV1,
        startedAtMs: Long,
        errorCode: String,
    ): AssistantCalendarCreatePreparationV1.Terminal = AssistantCalendarCreatePreparationV1.Terminal(
        calendarCreateOutcome(proposal, startedAtMs, AssistantReceiptStatusV1.DENIED, errorCode),
    )

    private fun calendarCreateOutcome(
        proposal: AssistantProposalV1,
        startedAtMs: Long,
        status: AssistantReceiptStatusV1,
        errorCode: String,
    ): AssistantExecutionOutcomeV1 = AssistantExecutionOutcomeV1(
        receipt = receipt(
            proposal = proposal,
            status = status,
            startedAtMs = startedAtMs,
            errorCode = errorCode,
        ),
    )

    private fun executeCalendarNext(
        proposal: AssistantProposalV1,
        startedAtMs: Long,
    ): AssistantExecutionOutcomeV1 {
        val authorized = runCatching {
            privateReadAuthorizer.isAuthorized(
                capability = proposal.capability,
                voiceSessionKey = proposal.voiceSessionKey,
                targetDeviceId = proposal.targetDeviceId,
            )
        }.getOrDefault(false)
        if (!authorized) {
            return AssistantExecutionOutcomeV1(
                receipt = receipt(
                    proposal = proposal,
                    status = AssistantReceiptStatusV1.DENIED,
                    startedAtMs = startedAtMs,
                    errorCode = "PRIVATE_READ_GRANT_REQUIRED",
                ),
            )
        }
        val afterEpochMs = proposal.arguments["afterEpochMs"]?.jsonPrimitive?.longOrNull ?: startedAtMs
        val limit = proposal.arguments["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CALENDAR_LIMIT
        return runCatching { calendarNextReader.read(afterEpochMs, limit) }.fold(
            onSuccess = { result ->
                when (result) {
                    AndroidCalendarNextReadV1.PermissionRequired -> AssistantExecutionOutcomeV1(
                        receipt = receipt(
                            proposal = proposal,
                            status = AssistantReceiptStatusV1.DENIED,
                            startedAtMs = startedAtMs,
                            errorCode = "CALENDAR_READ_PERMISSION_REQUIRED",
                        ),
                    )
                    is AndroidCalendarNextReadV1.Success -> {
                        val normalized = result.events.take(limit).map(AndroidCalendarEventV1::normalized)
                        val truncated = result.truncated || result.events.size > limit
                        AssistantExecutionOutcomeV1(
                            receipt = receipt(
                                proposal = proposal,
                                status = AssistantReceiptStatusV1.COMPLETED,
                                startedAtMs = startedAtMs,
                                resultSummary = buildJsonObject {
                                    put("eventCount", JsonPrimitive(normalized.size))
                                    put("truncated", JsonPrimitive(truncated))
                                },
                            ),
                            privateResult = buildJsonObject {
                                put("events", buildJsonArray {
                                    normalized.forEach { event ->
                                        add(buildJsonObject {
                                            put("title", JsonPrimitive(event.title))
                                            put("startEpochMs", JsonPrimitive(event.startEpochMs))
                                            put("endEpochMs", JsonPrimitive(event.endEpochMs))
                                            put("allDay", JsonPrimitive(event.allDay))
                                        })
                                    }
                                })
                            },
                        )
                    }
                }
            },
            onFailure = {
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = proposal,
                        status = AssistantReceiptStatusV1.FAILED,
                        startedAtMs = startedAtMs,
                        errorCode = "CALENDAR_READ_FAILED",
                    ),
                )
            },
        )
    }

    private fun executeContactsSearch(
        proposal: AssistantProposalV1,
        startedAtMs: Long,
    ): AssistantExecutionOutcomeV1 {
        val authorized = runCatching {
            privateReadAuthorizer.isAuthorized(
                capability = proposal.capability,
                voiceSessionKey = proposal.voiceSessionKey,
                targetDeviceId = proposal.targetDeviceId,
            )
        }.getOrDefault(false)
        if (!authorized) {
            return AssistantExecutionOutcomeV1(
                receipt = receipt(
                    proposal = proposal,
                    status = AssistantReceiptStatusV1.DENIED,
                    startedAtMs = startedAtMs,
                    errorCode = "PRIVATE_READ_GRANT_REQUIRED",
                ),
            )
        }

        val query = proposal.arguments.getValue("query").jsonPrimitive.content.trim()
        val limit = proposal.arguments["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CONTACT_LIMIT
        return runCatching { contactsSearchReader.search(query, limit) }.fold(
            onSuccess = { result ->
                when (result) {
                    AndroidContactsSearchReadV1.PermissionRequired -> AssistantExecutionOutcomeV1(
                        receipt = receipt(
                            proposal = proposal,
                            status = AssistantReceiptStatusV1.DENIED,
                            startedAtMs = startedAtMs,
                            errorCode = "CONTACTS_READ_PERMISSION_REQUIRED",
                        ),
                    )
                    is AndroidContactsSearchReadV1.Success -> {
                        val normalized = result.matches.take(limit).map(AndroidContactMatchV1::normalized)
                        val truncated = result.truncated || result.matches.size > limit
                        AssistantExecutionOutcomeV1(
                            receipt = receipt(
                                proposal = proposal,
                                status = AssistantReceiptStatusV1.COMPLETED,
                                startedAtMs = startedAtMs,
                                resultSummary = buildJsonObject {
                                    put("matchCount", JsonPrimitive(normalized.size))
                                    put("truncated", JsonPrimitive(truncated))
                                },
                            ),
                            privateResult = buildJsonObject {
                                put("contacts", buildJsonArray {
                                    normalized.forEach { match ->
                                        add(buildJsonObject {
                                            put("contactId", JsonPrimitive(match.contactId))
                                            put("displayName", JsonPrimitive(match.displayName))
                                            put("phoneNumber", JsonPrimitive(match.phoneNumber))
                                        })
                                    }
                                })
                            },
                        )
                    }
                }
            },
            onFailure = {
                AssistantExecutionOutcomeV1(
                    receipt = receipt(
                        proposal = proposal,
                        status = AssistantReceiptStatusV1.FAILED,
                        startedAtMs = startedAtMs,
                        errorCode = "CONTACTS_SEARCH_FAILED",
                    ),
                )
            },
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

    private companion object {
        const val DEFAULT_CONTACT_LIMIT = 5
        const val DEFAULT_CALENDAR_LIMIT = 5
    }
}

private fun AndroidDeviceStatusSummaryV1.toResultSummary(): JsonObject = buildJsonObject {
    put(
        "batteryLevelPercent",
        batteryLevelPercent?.takeIf { it in 0..100 }?.let(::JsonPrimitive) ?: JsonNull,
    )
    put("charging", JsonPrimitive(charging))
    put("screenInteractive", JsonPrimitive(screenInteractive))
}

private fun AndroidContactMatchV1.normalized(): AndroidContactMatchV1 {
    val normalizedId = contactId.trim()
    val normalizedName = displayName.trim()
    val normalizedNumber = phoneNumber.trim()
    require(normalizedId.matches(Regex("[0-9]{1,32}")))
    require(normalizedName.length in 1..200)
    require(normalizedNumber.length in 1..100)
    return copy(
        contactId = normalizedId,
        displayName = normalizedName,
        phoneNumber = normalizedNumber,
    )
}

private fun AndroidContactCallTargetV1.normalized(): AndroidContactCallTargetV1 {
    val normalizedName = displayName.trim()
    val normalizedNumber = phoneNumber.trim()
    require(normalizedName.length in 1..200)
    require(normalizedNumber.length in 1..100)
    return copy(displayName = normalizedName, phoneNumber = normalizedNumber)
}

private fun AndroidContactSmsTargetV1.normalized(): AndroidContactSmsTargetV1 {
    val normalizedName = displayName.trim()
    val normalizedNumber = phoneNumber.trim()
    require(normalizedName.length in 1..200)
    require(normalizedNumber.length in 1..100)
    return copy(displayName = normalizedName, phoneNumber = normalizedNumber)
}

private fun AndroidCalendarEventV1.normalized(): AndroidCalendarEventV1 {
    val normalizedTitle = title.trim().ifEmpty { "Untitled event" }
    require(normalizedTitle.length <= 200)
    require(startEpochMs in 0..AssistantContractV1.MAX_SAFE_INTEGER)
    require(endEpochMs in 1..AssistantContractV1.MAX_SAFE_INTEGER)
    require(endEpochMs > startEpochMs)
    return copy(title = normalizedTitle)
}

private fun AndroidCalendarCreateTargetV1.normalized(): AndroidCalendarCreateTargetV1 {
    val normalizedName = displayName.trim().ifEmpty { "Calendar" }
    require(calendarId > 0)
    require(normalizedName.length <= 200)
    return copy(displayName = normalizedName)
}

private fun validAllDayRange(startEpochMs: Long, endEpochMs: Long): Boolean = runCatching {
    val zone = ZoneId.systemDefault()
    Instant.ofEpochMilli(endEpochMs).atZone(zone).toLocalDate().isAfter(
        Instant.ofEpochMilli(startEpochMs).atZone(zone).toLocalDate(),
    )
}.getOrDefault(false)
