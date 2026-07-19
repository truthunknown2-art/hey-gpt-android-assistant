package com.openclaw.assistant.broker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

internal data class AssistantPrivateReadApprovalSnapshotV1(
    val proposalId: String,
    val capability: AssistantCapabilityV1,
    val arguments: JsonObject,
    val argumentsHash: String,
    val targetDeviceId: String,
    val voiceSessionKey: String,
    val presenceLeaseId: String,
    val expiresAtMs: Long,
)

internal data class AssistantPrivateReadApprovalTicketV1(
    val proposalId: String,
    internal val decision: CompletableDeferred<Boolean>,
)

/** In-process approval state. Process death, timeout, or duplicate IDs deny. */
internal class AssistantPrivateReadApprovalRegistryV1 {
    private data class Pending(
        val snapshot: AssistantPrivateReadApprovalSnapshotV1,
        val decision: CompletableDeferred<Boolean>,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    fun register(signed: SignedAssistantProposalV1): AssistantPrivateReadApprovalTicketV1? {
        val proposal = signed.proposal
        if (!proposal.capability.requiresPrivateReadGrantV1()) return null
        val item = Pending(
            snapshot = AssistantPrivateReadApprovalSnapshotV1(
                proposalId = proposal.proposalId,
                capability = proposal.capability,
                arguments = proposal.arguments,
                argumentsHash = proposal.argumentsHash,
                targetDeviceId = proposal.targetDeviceId,
                voiceSessionKey = proposal.voiceSessionKey,
                presenceLeaseId = proposal.presenceLeaseId,
                expiresAtMs = proposal.expiresAtMs,
            ),
            decision = CompletableDeferred(),
        )
        synchronized(lock) {
            if (proposal.proposalId in pending) return null
            pending[proposal.proposalId] = item
        }
        return AssistantPrivateReadApprovalTicketV1(proposal.proposalId, item.decision)
    }

    suspend fun await(
        ticket: AssistantPrivateReadApprovalTicketV1,
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

    fun snapshot(proposalId: String): AssistantPrivateReadApprovalSnapshotV1? =
        synchronized(lock) { pending[proposalId]?.snapshot }

    fun respond(proposalId: String, approved: Boolean): Boolean {
        val item = synchronized(lock) { pending.remove(proposalId) } ?: return false
        return item.decision.complete(approved)
    }

    fun cancel(proposalId: String): Boolean = respond(proposalId, false)

    fun revokeSession(voiceSessionKey: String, targetDeviceId: String) {
        val items = synchronized(lock) {
            val matches = pending.values.filter {
                it.snapshot.voiceSessionKey == voiceSessionKey &&
                    it.snapshot.targetDeviceId == targetDeviceId
            }
            pending.entries.removeAll { it.value in matches }
            matches
        }
        items.forEach { it.decision.complete(false) }
    }

    fun revokeAll() {
        val items = synchronized(lock) {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        items.forEach { it.decision.complete(false) }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 45_000L
        private const val MAX_TIMEOUT_MS = 60_000L
    }
}

internal object AssistantPrivateReadApprovals {
    val registry = AssistantPrivateReadApprovalRegistryV1()
}
