package com.openclaw.assistant.broker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal data class AssistantCalendarCreateApprovalSnapshotV1(
    val proposalId: String,
    val argumentsHash: String,
    val targetDeviceId: String,
    val voiceSessionKey: String,
    val presenceLeaseId: String,
    val expiresAtMs: Long,
    val calendarDisplayName: String,
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val allDay: Boolean,
)

internal data class AssistantCalendarCreateApprovalTicketV1(
    val proposalId: String,
    internal val decision: CompletableDeferred<Boolean>,
)

/** One-shot process-local approval; calendar details are never persisted. */
internal class AssistantCalendarCreateApprovalRegistryV1 {
    private data class Pending(
        val snapshot: AssistantCalendarCreateApprovalSnapshotV1,
        val decision: CompletableDeferred<Boolean>,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    fun register(prepared: AssistantCalendarCreatePreparationV1.Ready): AssistantCalendarCreateApprovalTicketV1? {
        val proposal = prepared.proposal
        if (proposal.capability != AssistantCapabilityV1.ANDROID_CALENDAR_CREATE) return null
        val item = Pending(
            snapshot = AssistantCalendarCreateApprovalSnapshotV1(
                proposalId = proposal.proposalId,
                argumentsHash = proposal.argumentsHash,
                targetDeviceId = proposal.targetDeviceId,
                voiceSessionKey = proposal.voiceSessionKey,
                presenceLeaseId = proposal.presenceLeaseId,
                expiresAtMs = proposal.expiresAtMs,
                calendarDisplayName = prepared.target.displayName,
                title = prepared.title,
                startEpochMs = prepared.startEpochMs,
                endEpochMs = prepared.endEpochMs,
                allDay = prepared.allDay,
            ),
            decision = CompletableDeferred(),
        )
        synchronized(lock) {
            if (proposal.proposalId in pending) return null
            pending[proposal.proposalId] = item
        }
        return AssistantCalendarCreateApprovalTicketV1(proposal.proposalId, item.decision)
    }

    suspend fun await(
        ticket: AssistantCalendarCreateApprovalTicketV1,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        require(timeoutMs in 1..MAX_TIMEOUT_MS)
        return try {
            withTimeoutOrNull(timeoutMs) { ticket.decision.await() } ?: false
        } finally {
            synchronized(lock) {
                pending[ticket.proposalId]?.takeIf { it.decision === ticket.decision }?.let {
                    pending.remove(ticket.proposalId)
                }
            }
        }
    }

    fun snapshot(proposalId: String): AssistantCalendarCreateApprovalSnapshotV1? =
        synchronized(lock) { pending[proposalId]?.snapshot }

    fun respond(proposalId: String, approved: Boolean): Boolean {
        val item = synchronized(lock) { pending.remove(proposalId) } ?: return false
        return item.decision.complete(approved)
    }

    fun cancel(proposalId: String): Boolean = respond(proposalId, false)

    fun revokeAll() {
        val items = synchronized(lock) {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        items.forEach { it.decision.complete(false) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 45_000L
        private const val MAX_TIMEOUT_MS = 60_000L
    }
}

internal object AssistantCalendarCreateApprovalsV1 {
    val registry = AssistantCalendarCreateApprovalRegistryV1()
}
