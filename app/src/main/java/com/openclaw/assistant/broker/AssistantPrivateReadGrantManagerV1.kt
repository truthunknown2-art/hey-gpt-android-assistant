package com.openclaw.assistant.broker

import android.os.SystemClock
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

internal sealed interface AssistantPrivateReadScopeV1 {
    data class Messenger(
        val normalizedSender: String?,
        val maxLimit: Int,
    ) : AssistantPrivateReadScopeV1
}

internal data class AssistantPrivateReadGrantV1(
    val contractVersion: Int = AssistantContractV1.VERSION,
    val capability: AssistantCapabilityV1,
    val voiceSessionKey: String,
    val targetDeviceId: String,
    val scope: AssistantPrivateReadScopeV1?,
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
        scope: AssistantPrivateReadScopeV1? = null,
    ): AssistantPrivateReadGrantV1 {
        require(capability.requiresPrivateReadGrantV1())
        require(capability.acceptsPrivateReadScopeV1(scope))
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
            scope = scope,
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
        scope: AssistantPrivateReadScopeV1?,
    ): Boolean = synchronized(lock) {
        val now = nowElapsedMs()
        removeExpiredLocked(now)
        if (!capability.acceptsPrivateReadScopeV1(scope)) return@synchronized false
        val key = GrantKey(capability, voiceSessionKey.trim(), targetDeviceId.trim())
        grants[key]?.let { grant ->
            now < grant.expiresAtElapsedMs && grant.scope.authorizesPrivateReadScopeV1(scope)
        } == true
    }

    fun isAuthorized(
        capability: AssistantCapabilityV1,
        voiceSessionKey: String,
        targetDeviceId: String,
    ): Boolean = isAuthorized(capability, voiceSessionKey, targetDeviceId, null)

    fun revokeSession(voiceSessionKey: String, targetDeviceId: String) {
        synchronized(lock) {
            grants.entries.removeAll {
                it.key.voiceSessionKey == voiceSessionKey &&
                    it.key.targetDeviceId == targetDeviceId
            }
        }
    }

    fun revokeVoiceSession(voiceSessionKey: String) {
        synchronized(lock) {
            grants.entries.removeAll { it.key.voiceSessionKey == voiceSessionKey }
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
    AssistantCapabilityV1.ANDROID_MESSENGER_NOTIFICATIONS_READ,
    AssistantCapabilityV1.WINDOWS_FILES_READ,
    -> true
    else -> false
}

internal fun AssistantProposalV1.privateReadScopeV1(): AssistantPrivateReadScopeV1? = when (capability) {
    AssistantCapabilityV1.ANDROID_MESSENGER_NOTIFICATIONS_READ ->
        AssistantPrivateReadScopeV1.Messenger(
            normalizedSender = (arguments["sender"] as? JsonPrimitive)?.contentOrNull
                ?.let(::normalizePrivateReadSenderV1),
            maxLimit = (arguments["limit"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_MESSENGER_LIMIT,
        )
    else -> null
}

internal fun normalizePrivateReadSenderV1(sender: String): String = sender
    .trim()
    .lowercase(Locale.ROOT)
    .split(Regex("\\s+"))
    .filter(String::isNotEmpty)
    .joinToString(" ")

private fun AssistantCapabilityV1.acceptsPrivateReadScopeV1(scope: AssistantPrivateReadScopeV1?): Boolean =
    when (this) {
        AssistantCapabilityV1.ANDROID_MESSENGER_NOTIFICATIONS_READ ->
            scope is AssistantPrivateReadScopeV1.Messenger &&
                scope.maxLimit in 1..MAX_MESSENGER_LIMIT &&
                scope.normalizedSender?.isNotEmpty() != false
        else -> scope == null
    }

private fun AssistantPrivateReadScopeV1?.authorizesPrivateReadScopeV1(
    requested: AssistantPrivateReadScopeV1?,
): Boolean = when {
    this == null || requested == null -> this == null && requested == null
    this is AssistantPrivateReadScopeV1.Messenger && requested is AssistantPrivateReadScopeV1.Messenger ->
        normalizedSender == requested.normalizedSender && maxLimit >= requested.maxLimit
    else -> false
}

private const val DEFAULT_MESSENGER_LIMIT = 3
private const val MAX_MESSENGER_LIMIT = 10

internal object AssistantPrivateReadGrants {
    val manager = AssistantPrivateReadGrantManagerV1()
}
