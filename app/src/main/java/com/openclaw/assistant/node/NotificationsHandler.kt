package com.openclaw.assistant.node

import android.content.Context
import android.provider.Settings
import com.openclaw.assistant.PermissionRequester
import com.openclaw.assistant.gateway.GatewaySession
import com.openclaw.assistant.service.OpenClawNotificationListenerService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

class NotificationsHandler internal constructor(
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val messengerHistory: MessengerNotificationHistory = MessengerNotificationHistory(context),
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var permissionRequester: PermissionRequester? = null

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    fun isServiceEnabled(): Boolean {
        val enabledPackages = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabledPackages?.contains(context.packageName) == true
    }

    suspend fun handleList(): GatewaySession.InvokeResult {
        notificationPermissionError()?.let { return it }

        val notifications = notificationManager.getActiveNotifications()
        val payload = buildJsonObject {
            put("notifications", buildJsonArray {
                notifications.forEach { sbn ->
                    add(buildJsonObject {
                        put("key", JsonPrimitive(sbn.key))
                        put("packageName", JsonPrimitive(sbn.packageName))
                        put("title", JsonPrimitive(sbn.notification.extras.getCharSequence("android.title")?.toString().orEmpty()))
                        put("text", JsonPrimitive(sbn.notification.extras.getCharSequence("android.text")?.toString().orEmpty()))
                        put("postTime", JsonPrimitive(sbn.postTime))
                    })
                }
            })
        }
        return GatewaySession.InvokeResult.ok(payload.toString())
    }

    /** Returns a privacy-minimized Messenger-only view for ambient voice. */
    suspend fun handleMessengerList(): GatewaySession.InvokeResult {
        notificationPermissionError()?.let { return it }

        val payload = buildJsonObject {
            put("notifications", buildJsonArray {
                val active = notificationManager.getActiveNotifications()
                    .asSequence()
                    .filter { it.packageName == MESSENGER_PACKAGE }
                    .map { sbn ->
                        MessengerNotificationPreview(
                            sender = sbn.notification.extras
                                .getCharSequence("android.title")
                                ?.toString()
                                .orEmpty(),
                            textPreview = sbn.notification.extras
                                .getCharSequence("android.text")
                                ?.toString()
                                .orEmpty(),
                            timestamp = sbn.postTime,
                        )
                    }
                (active + messengerHistory.recent().asSequence())
                    .distinct()
                    .sortedByDescending { it.timestamp }
                    .take(MAX_VOICE_NOTIFICATIONS)
                    .forEach { preview ->
                        add(buildJsonObject {
                            put(
                                "sender",
                                JsonPrimitive(preview.sender.take(MAX_NOTIFICATION_TEXT_CHARS)),
                            )
                            put(
                                "textPreview",
                                JsonPrimitive(preview.textPreview.take(MAX_NOTIFICATION_TEXT_CHARS)),
                            )
                            put("timestamp", JsonPrimitive(preview.timestamp))
                        })
                    }
            })
        }
        return GatewaySession.InvokeResult.ok(payload.toString())
    }

    private suspend fun notificationPermissionError(): GatewaySession.InvokeResult? {
        if (isServiceEnabled()) return null
        permissionRequester?.requestNotificationAccess()
        return GatewaySession.InvokeResult.error(
            code = "NOTIFICATIONS_PERMISSION_REQUIRED",
            message = "NOTIFICATIONS_PERMISSION_REQUIRED: enable notification access in Settings > Notification Access, then try again",
        )
    }

    suspend fun handleActions(paramsJson: String?): GatewaySession.InvokeResult {
        if (!isServiceEnabled()) {
            permissionRequester?.requestNotificationAccess()
            return GatewaySession.InvokeResult.error(
                code = "NOTIFICATIONS_PERMISSION_REQUIRED",
                message = "NOTIFICATIONS_PERMISSION_REQUIRED: enable notification access in Settings > Notification Access, then try again"
            )
        }

        val service = OpenClawNotificationListenerService.instance
            ?: return GatewaySession.InvokeResult.error("SERVICE_UNAVAILABLE", "Notification Listener Service not running")

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val key = (params["key"] as? JsonPrimitive)?.content ?: ""
        val action = (params["action"] as? JsonPrimitive)?.content ?: ""

        if (key.isEmpty() || action.isEmpty()) {
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Key and action are required")
        }

        return when (action.lowercase()) {
            "dismiss" -> {
                try {
                    service.cancelNotification(key)
                    GatewaySession.InvokeResult.ok("""{"ok":true}""")
                } catch (e: Exception) {
                    GatewaySession.InvokeResult.error("ACTION_FAILED", "Failed to dismiss: ${e.message}")
                }
            }
            "dismiss_all" -> {
                try {
                    service.cancelAllNotifications()
                    GatewaySession.InvokeResult.ok("""{"ok":true}""")
                } catch (e: Exception) {
                    GatewaySession.InvokeResult.error("ACTION_FAILED", "Failed to dismiss all: ${e.message}")
                }
            }
            "open" -> {
                val sbn = notificationManager.getNotification(key)
                if (sbn != null) {
                    try {
                        sbn.notification.contentIntent.send()
                        GatewaySession.InvokeResult.ok("""{"ok":true}""")
                    } catch (e: Exception) {
                        GatewaySession.InvokeResult.error("ACTION_FAILED", "Failed to open: ${e.message}")
                    }
                } else {
                    GatewaySession.InvokeResult.error("NOT_FOUND", "Notification not found")
                }
            }
            "reply" -> GatewaySession.InvokeResult.error("NOT_IMPLEMENTED", "Reply action not yet implemented")
            else -> GatewaySession.InvokeResult.error("INVALID_REQUEST", "Unsupported action: $action")
        }
    }

    companion object {
        const val MESSENGER_PACKAGE = "com.facebook.orca"
        private const val MAX_VOICE_NOTIFICATIONS = 20
        private const val MAX_NOTIFICATION_TEXT_CHARS = 500
    }
}
