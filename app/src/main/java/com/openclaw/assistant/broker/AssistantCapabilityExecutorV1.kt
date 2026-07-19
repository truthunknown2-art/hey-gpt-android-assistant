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

/** Executes already typed proposals through fixed native capability handlers. */
internal class AssistantCapabilityExecutorV1(
    private val validator: AssistantProposalValidatorV1,
    private val deviceStatusReader: AndroidDeviceStatusReaderV1,
    private val contactsSearchReader: AndroidContactsSearchReaderV1,
    private val privateReadAuthorizer: AssistantPrivateReadAuthorizerV1,
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
        else -> AssistantExecutionOutcomeV1(
            receipt = receipt(
                proposal = proposal,
                status = AssistantReceiptStatusV1.DENIED,
                startedAtMs = startedAtMs,
                errorCode = "CAPABILITY_NOT_IMPLEMENTED",
            ),
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
