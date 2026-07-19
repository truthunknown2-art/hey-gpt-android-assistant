package com.openclaw.assistant.broker

import android.os.SystemClock
import java.util.UUID

internal data class PresenceLease(
    val contractVersion: Int = AssistantContractV1.VERSION,
    val leaseId: String,
    val voiceSessionKey: String,
    val targetDeviceId: String,
    val issuedAtElapsedMs: Long,
    val expiresAtElapsedMs: Long,
)

internal enum class PresenceLeaseRejection {
    MISSING,
    EXPIRED,
    WRONG_SESSION,
    WRONG_DEVICE,
}

internal sealed interface PresenceLeaseValidation {
    data class Valid(val lease: PresenceLease) : PresenceLeaseValidation
    data class Rejected(val reason: PresenceLeaseRejection) : PresenceLeaseValidation
}

/**
 * Process-local unlocked-presence leases. Elapsed realtime is authoritative so
 * changing the wall clock cannot extend a lease.
 */
internal class PresenceLeaseManager(
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val newLeaseId: () -> String = { UUID.randomUUID().toString() },
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val renewWithinMs: Long = DEFAULT_RENEW_WITHIN_MS,
) {
    private val lock = Any()
    private val leases = mutableMapOf<String, PresenceLease>()

    init {
        require(ttlMs in MIN_TTL_MS..MAX_TTL_MS)
        require(renewWithinMs in 1 until ttlMs)
    }

    fun issue(voiceSessionKey: String, targetDeviceId: String): PresenceLease {
        val session = voiceSessionKey.trim()
        val device = targetDeviceId.trim()
        require(session.isNotEmpty())
        require(device.isNotEmpty())
        val now = nowElapsedMs()
        val lease = PresenceLease(
            leaseId = newLeaseId(),
            voiceSessionKey = session,
            targetDeviceId = device,
            issuedAtElapsedMs = now,
            expiresAtElapsedMs = now + ttlMs,
        )
        synchronized(lock) {
            removeExpiredLocked(now)
            leases.entries.removeAll {
                it.value.voiceSessionKey == session || it.value.targetDeviceId == device
            }
            check(lease.leaseId.isNotBlank() && lease.leaseId !in leases)
            leases[lease.leaseId] = lease
        }
        return lease
    }

    fun validate(
        leaseId: String,
        voiceSessionKey: String,
        targetDeviceId: String,
    ): PresenceLeaseValidation = synchronized(lock) {
        validateLocked(leaseId, voiceSessionKey, targetDeviceId, nowElapsedMs())
    }

    private fun validateLocked(
        leaseId: String,
        voiceSessionKey: String,
        targetDeviceId: String,
        now: Long,
    ): PresenceLeaseValidation {
        val lease = leases[leaseId]
            ?: return PresenceLeaseValidation.Rejected(PresenceLeaseRejection.MISSING)
        if (now >= lease.expiresAtElapsedMs) {
            leases.remove(leaseId)
            return PresenceLeaseValidation.Rejected(PresenceLeaseRejection.EXPIRED)
        }
        if (lease.voiceSessionKey != voiceSessionKey) {
            return PresenceLeaseValidation.Rejected(PresenceLeaseRejection.WRONG_SESSION)
        }
        if (lease.targetDeviceId != targetDeviceId) {
            return PresenceLeaseValidation.Rejected(PresenceLeaseRejection.WRONG_DEVICE)
        }
        return PresenceLeaseValidation.Valid(lease)
    }

    fun validateAndRenew(
        leaseId: String,
        voiceSessionKey: String,
        targetDeviceId: String,
    ): PresenceLeaseValidation = synchronized(lock) {
        val now = nowElapsedMs()
        when (val validation = validateLocked(leaseId, voiceSessionKey, targetDeviceId, now)) {
            is PresenceLeaseValidation.Rejected -> validation
            is PresenceLeaseValidation.Valid -> {
                val lease = validation.lease
                if (lease.expiresAtElapsedMs - now > renewWithinMs) {
                    validation
                } else {
                    val renewed = lease.copy(expiresAtElapsedMs = now + ttlMs)
                    leases[leaseId] = renewed
                    PresenceLeaseValidation.Valid(renewed)
                }
            }
        }
    }

    fun revoke(leaseId: String) {
        synchronized(lock) { leases.remove(leaseId) }
    }

    fun revokeAll() {
        synchronized(lock) { leases.clear() }
    }

    internal fun activeCount(): Int = synchronized(lock) {
        removeExpiredLocked(nowElapsedMs())
        leases.size
    }

    private fun removeExpiredLocked(now: Long) {
        leases.entries.removeAll { now >= it.value.expiresAtElapsedMs }
    }

    companion object {
        private const val MIN_TTL_MS = 5_000L
        private const val MAX_TTL_MS = 60_000L
        private const val DEFAULT_TTL_MS = 15_000L
        private const val DEFAULT_RENEW_WITHIN_MS = 5_000L
    }
}

internal object AssistantPresenceLeases {
    val manager = PresenceLeaseManager()
}
