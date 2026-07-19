package com.openclaw.assistant.broker

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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

/** Executes already typed proposals through fixed native capability handlers. */
internal class AssistantCapabilityExecutorV1(
    private val validator: AssistantProposalValidatorV1,
    private val deviceStatusReader: AndroidDeviceStatusReaderV1,
    private val contactsSearchReader: AndroidContactsSearchReaderV1,
    private val privateReadAuthorizer: AssistantPrivateReadAuthorizerV1,
    private val contactCallResolver: AndroidContactCallResolverV1 = AndroidContactCallResolverV1 {
        AndroidContactCallResolutionV1.PermissionRequired
    },
    private val contactCallLauncher: AndroidContactCallLauncherV1 = AndroidContactCallLauncherV1 {
        AndroidContactCallLaunchV1.Failed
    },
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
        AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH -> executeContactsSearch(proposal, startedAtMs)
        AssistantCapabilityV1.ANDROID_PHONE_CALL_CONTACT -> AssistantExecutionOutcomeV1(
            receipt = receipt(
                proposal = proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = startedAtMs,
                errorCode = "CALL_APPROVAL_REQUIRED",
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
