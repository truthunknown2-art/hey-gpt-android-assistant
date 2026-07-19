package com.openclaw.assistant.broker

import android.os.SystemClock

internal data class AssistantPrivateReadGrantV1(
    val contractVersion: Int = AssistantContractV1.VERSION,
    val capability: AssistantCapabilityV1,
    val voiceSessionKey: String,
    val targetDeviceId: String,
    val issuedAtElapsedMs: Long,
    val expiresAtElapsedMs: Long,
)

/**
 * Process-local private-read grants. A grant is reusable only inside the exact
 * unlocked voice session that created it and never survives process death.
 */
internal class AssistantPrivateReadGrantManagerV1(
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : AssistantPrivateReadAuthorizerV1 {
    private data class GrantKey(
        val capability: AssistantCapabilityV1,
        val voiceSessionKey: String,
        val targetDeviceId: String,
    )

    private val lock = Any()
    private val grants = mutableMapOf<GrantKey, AssistantPrivateReadGrantV1>()

    init {
        require(ttlMs in MIN_TTL_MS..MAX_TTL_MS)
    }

    fun grant(
        capability: AssistantCapabilityV1,
        voiceSessionKey: String,
        targetDeviceId: String,
    ): AssistantPrivateReadGrantV1 {
        require(capability.requiresPrivateReadGrantV1())
        val session = voiceSessionKey.trim()
        val device = targetDeviceId.trim()
        require(session.isNotEmpty())
        require(device.isNotEmpty())
        val now = nowElapsedMs()
        require(now >= 0L && now <= Long.MAX_VALUE - ttlMs)
        val key = GrantKey(capability, session, device)
        val grant = AssistantPrivateReadGrantV1(
            capability = capability,
            voiceSessionKey = session,
            targetDeviceId = device,
            issuedAtElapsedMs = now,
            expiresAtElapsedMs = now + ttlMs,
        )
        synchronized(lock) {
            removeExpiredLocked(now)
            grants[key] = grant
        }
        return grant
    }

    override fun isAuthorized(
        capability: AssistantCapabilityV1,
        voiceSessionKey: String,
        targetDeviceId: String,
    ): Boolean = synchronized(lock) {
        val now = nowElapsedMs()
        removeExpiredLocked(now)
        val key = GrantKey(capability, voiceSessionKey, targetDeviceId)
        grants[key]?.let { now < it.expiresAtElapsedMs } == true
    }

    fun revokeSession(voiceSessionKey: String, targetDeviceId: String) {
        synchronized(lock) {
            grants.entries.removeAll {
                it.key.voiceSessionKey == voiceSessionKey &&
                    it.key.targetDeviceId == targetDeviceId
            }
        }
    }

    fun revoke(
        capability: AssistantCapabilityV1,
        voiceSessionKey: String,
        targetDeviceId: String,
    ): Boolean = synchronized(lock) {
        grants.remove(GrantKey(capability, voiceSessionKey, targetDeviceId)) != null
    }

    fun revokeAll() {
        synchronized(lock) { grants.clear() }
    }

    internal fun activeCount(): Int = synchronized(lock) {
        removeExpiredLocked(nowElapsedMs())
        grants.size
    }

    private fun removeExpiredLocked(now: Long) {
        grants.entries.removeAll { now >= it.value.expiresAtElapsedMs }
    }

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1_000L
        private const val MIN_TTL_MS = 1_000L
        private const val MAX_TTL_MS = DEFAULT_TTL_MS
    }
}

internal fun AssistantCapabilityV1.requiresPrivateReadGrantV1(): Boolean = when (this) {
    AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH,
    AssistantCapabilityV1.ANDROID_CALENDAR_NEXT,
    AssistantCapabilityV1.WINDOWS_FILES_READ,
    -> true
    else -> false
}

internal object AssistantPrivateReadGrants {
    val manager = AssistantPrivateReadGrantManagerV1()
}
