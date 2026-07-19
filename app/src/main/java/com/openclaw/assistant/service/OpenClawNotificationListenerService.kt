package com.openclaw.assistant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.openclaw.assistant.node.MessengerNotificationHistory
import com.openclaw.assistant.node.NotificationManager

/**
 * Captures notifications for the OpenClaw system.
 * Requires BIND_NOTIFICATION_LISTENER_SERVICE permission and user to enable it in Settings.
 */
class OpenClawNotificationListenerService : NotificationListenerService() {

    private val messengerHistory by lazy { MessengerNotificationHistory(applicationContext) }

    companion object {
        @Volatile var manager: NotificationManager? = null
        @Volatile var instance: OpenClawNotificationListenerService? = null

        /**
         * Returns the currently-active [StatusBarNotification]s, or an empty
         * list if the listener service is not bound. Used by the Mobile
         * Bridge's `notifications.active.list` capability.
         */
        fun activeSnapshot(): List<StatusBarNotification> =
            try { instance?.activeNotifications?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            manager?.onNotificationPosted(it)
            messengerHistory.record(it)
        }
        Log.d("OpenClawNotification", "Notification posted from ${sbn?.packageName}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn?.let { manager?.onNotificationRemoved(it) }
        Log.d("OpenClawNotification", "Notification removed from ${sbn?.packageName}")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        activeNotifications?.forEach { sbn ->
            manager?.onNotificationPosted(sbn)
            messengerHistory.record(sbn)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) instance = null
    }
}
