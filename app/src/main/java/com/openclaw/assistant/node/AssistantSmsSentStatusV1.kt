package com.openclaw.assistant.node

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal enum class AssistantSmsCarrierResultV1 {
    SENT,
    FAILED,
    UNKNOWN,
}

internal data class AssistantSmsSentTicketV1(
    val operationId: String,
    internal val result: CompletableDeferred<AssistantSmsCarrierResultV1>,
)

/** Tracks only opaque operation/part IDs; recipient and message never enter broadcast state. */
internal class AssistantSmsSentStatusRegistryV1 {
    private data class Pending(
        val partCount: Int,
        val successfulParts: MutableSet<Int>,
        val result: CompletableDeferred<AssistantSmsCarrierResultV1>,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    fun register(operationId: String, partCount: Int): AssistantSmsSentTicketV1? {
        if (operationId.isBlank() || partCount !in 1..MAX_PARTS) return null
        val item = Pending(partCount, mutableSetOf(), CompletableDeferred())
        synchronized(lock) {
            if (operationId in pending) return null
            pending[operationId] = item
        }
        return AssistantSmsSentTicketV1(operationId, item.result)
    }

    fun report(operationId: String, partIndex: Int, resultCode: Int): Boolean {
        val completion = synchronized(lock) {
            val item = pending[operationId] ?: return false
            if (partIndex !in 0 until item.partCount || partIndex in item.successfulParts) return false
            if (resultCode != Activity.RESULT_OK) {
                pending.remove(operationId)
                item.result to AssistantSmsCarrierResultV1.FAILED
            } else {
                item.successfulParts += partIndex
                if (item.successfulParts.size == item.partCount) {
                    pending.remove(operationId)
                    item.result to AssistantSmsCarrierResultV1.SENT
                } else {
                    null
                }
            }
        }
        return completion?.let { (deferred, value) -> deferred.complete(value) } ?: true
    }

    suspend fun await(
        ticket: AssistantSmsSentTicketV1,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): AssistantSmsCarrierResultV1 {
        require(timeoutMs in 1..MAX_TIMEOUT_MS)
        return try {
            withTimeoutOrNull(timeoutMs) { ticket.result.await() } ?: AssistantSmsCarrierResultV1.UNKNOWN
        } finally {
            synchronized(lock) {
                pending[ticket.operationId]?.takeIf { it.result === ticket.result }?.let {
                    pending.remove(ticket.operationId)
                }
            }
        }
    }

    fun cancel(ticket: AssistantSmsSentTicketV1, result: AssistantSmsCarrierResultV1) {
        val item = synchronized(lock) {
            pending[ticket.operationId]
                ?.takeIf { it.result === ticket.result }
                ?.also { pending.remove(ticket.operationId) }
        }
        item?.result?.complete(result)
    }

    fun revokeAll() {
        val items = synchronized(lock) {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        items.forEach { it.result.complete(AssistantSmsCarrierResultV1.UNKNOWN) }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_TIMEOUT_MS = 45_000L
        private const val MAX_PARTS = 20
    }
}

internal object AssistantSmsSentStatusesV1 {
    val registry = AssistantSmsSentStatusRegistryV1()
}

internal class AssistantSmsSentReceiverV1 : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ACTION) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID).orEmpty()
        val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
        AssistantSmsSentStatusesV1.registry.report(operationId, partIndex, resultCode)
    }

    companion object {
        const val ACTION = "com.openclaw.assistant.action.ASSISTANT_SMS_SENT_V1"
        const val EXTRA_OPERATION_ID = "assistant.sms.operationId"
        const val EXTRA_PART_INDEX = "assistant.sms.partIndex"
    }
}
