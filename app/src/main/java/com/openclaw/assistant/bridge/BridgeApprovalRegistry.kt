package com.openclaw.assistant.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process pending-approval registry. When a medium/high-risk capability
 * arrives, the bridge enqueues a request here and suspends on the returned
 * deferred until the user taps Approve or Deny (via [BridgeApprovalActivity])
 * or the timeout elapses.
 *
 * Why in-process and not Room: approvals are ephemeral; if the app dies the
 * request must fail closed, never auto-approve.
 */
object BridgeApprovalRegistry {
    data class Pending(
        val requestId: String,
        val capability: String,
        val arguments: JsonObject,
        val riskLevel: RiskLevel,
        internal val deferred: CompletableDeferred<Boolean>,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Suspends until the user responds or [timeoutMs] elapses. Defaults to denied on timeout. */
    suspend fun await(
        requestId: String,
        capability: String,
        arguments: JsonObject,
        riskLevel: RiskLevel,
        timeoutMs: Long = 30_000L,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val entry = Pending(requestId, capability, arguments, riskLevel, deferred)
        if (pending.putIfAbsent(requestId, entry) != null) return false
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
        } finally {
            pending.remove(requestId, entry)
        }
    }

    fun snapshot(requestId: String): Pending? = pending[requestId]
    fun pendingIds(): Set<String> = pending.keys.toSet()
    fun respond(requestId: String, approved: Boolean) {
        pending[requestId]?.deferred?.complete(approved)
    }
}
