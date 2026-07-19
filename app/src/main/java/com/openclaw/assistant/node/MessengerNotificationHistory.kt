package com.openclaw.assistant.node

import android.content.Context
import android.content.SharedPreferences
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class MessengerNotificationPreview(
    val sender: String,
    val textPreview: String,
    val timestamp: Long,
)

/** App-private, bounded history of Messenger notification previews. */
internal class MessengerNotificationHistory(
    private val preferences: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    constructor(
        context: Context,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
        now = now,
    )

    fun record(notification: StatusBarNotification) {
        if (notification.packageName != NotificationsHandler.MESSENGER_PACKAGE) return
        record(
            sender = notification.notification.extras
                .getCharSequence("android.title")
                ?.toString()
                .orEmpty(),
            textPreview = notification.notification.extras
                .getCharSequence("android.text")
                ?.toString()
                .orEmpty(),
            timestamp = notification.postTime,
        )
    }

    internal fun record(sender: String, textPreview: String, timestamp: Long) {
        val sanitized = MessengerNotificationPreview(
            sender = sender.trim().take(MAX_NOTIFICATION_TEXT_CHARS),
            textPreview = textPreview.trim().take(MAX_NOTIFICATION_TEXT_CHARS),
            timestamp = timestamp,
        )
        if (sanitized.sender.isBlank() && sanitized.textPreview.isBlank()) return

        synchronized(STORAGE_LOCK) {
            val retained = readStored()
                .filter { it.timestamp >= retentionCutoff() }
                .toMutableList()
            if (retained.none { it == sanitized }) retained += sanitized
            writeStored(
                retained.sortedByDescending { it.timestamp }.take(MAX_HISTORY_ENTRIES),
                synchronous = false,
            )
        }
    }

    fun recent(limit: Int = MAX_RETURNED_ENTRIES): List<MessengerNotificationPreview> =
        synchronized(STORAGE_LOCK) {
            val retained = pruneExpiredLocked()
                .sortedByDescending { it.timestamp }
            retained.take(limit.coerceIn(0, MAX_RETURNED_ENTRIES))
        }

    /** Removes expired previews from disk and returns the retained entries. */
    fun pruneExpired(): List<MessengerNotificationPreview> = synchronized(STORAGE_LOCK) {
        pruneExpiredLocked()
    }

    fun nextExpiryAtMs(): Long? = synchronized(STORAGE_LOCK) {
        pruneExpiredLocked().minOfOrNull { it.timestamp + RETENTION_MS }
    }

    private fun pruneExpiredLocked(): List<MessengerNotificationPreview> {
        val stored = readStored()
        val retained = stored
            .filter { it.timestamp >= retentionCutoff() }
            .sortedByDescending { it.timestamp }
            .take(MAX_HISTORY_ENTRIES)
        if (retained.size != stored.size) writeStored(retained, synchronous = true)
        return retained
    }

    private fun readStored(): List<MessengerNotificationPreview> {
        val raw = preferences.getString(HISTORY_KEY, null) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val item = element.jsonObject
                val sender = item["sender"]?.jsonPrimitive?.content.orEmpty()
                val textPreview = item["textPreview"]?.jsonPrimitive?.content.orEmpty()
                val timestamp = item["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: return@mapNotNull null
                MessengerNotificationPreview(sender, textPreview, timestamp)
            }
        }.onFailure {
            Log.w(TAG, "Discarding malformed Messenger notification history")
            preferences.edit().remove(HISTORY_KEY).commit()
        }.getOrDefault(emptyList())
    }

    private fun writeStored(
        entries: List<MessengerNotificationPreview>,
        synchronous: Boolean,
    ) {
        val editor = preferences.edit()
        if (entries.isEmpty()) {
            val removal = editor.remove(HISTORY_KEY)
            if (synchronous && !removal.commit()) {
                Log.w(TAG, "Failed to remove Messenger notification history")
            } else if (!synchronous) {
                removal.apply()
            }
            return
        }
        val encoded = buildJsonArray {
            entries.forEach { entry ->
                add(buildJsonObject {
                    put("sender", JsonPrimitive(entry.sender))
                    put("textPreview", JsonPrimitive(entry.textPreview))
                    put("timestamp", JsonPrimitive(entry.timestamp))
                })
            }
        }.toString()
        val update = editor.putString(HISTORY_KEY, encoded)
        if (synchronous && !update.commit()) {
            Log.w(TAG, "Failed to persist Messenger notification history")
        } else if (!synchronous) {
            update.apply()
        }
    }

    private fun retentionCutoff(): Long = now() - RETENTION_MS

    companion object {
        private const val TAG = "MessengerNotificationHistory"
        private const val PREFERENCES_NAME = "messenger_notification_history"
        private const val HISTORY_KEY = "previews"
        private const val MAX_HISTORY_ENTRIES = 100
        private const val MAX_RETURNED_ENTRIES = 20
        private const val MAX_NOTIFICATION_TEXT_CHARS = 500
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private val STORAGE_LOCK = Any()
    }
}
