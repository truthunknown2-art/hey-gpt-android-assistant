package com.openclaw.assistant.service

import android.os.Handler
import android.os.Looper
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
    private val historyHandler = Handler(Looper.getMainLooper())
    private val historyPrune = Runnable { scheduleHistoryPrune() }

    companion object {
        private const val PRUNE_SETTLE_MS = 1_000L
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
        scheduleHistoryPrune()
    }

    override fun onDestroy() {
        historyHandler.removeCallbacks(historyPrune)
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            manager?.onNotificationPosted(it)
            messengerHistory.record(it)
            scheduleHistoryPrune()
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
        scheduleHistoryPrune()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        historyHandler.removeCallbacks(historyPrune)
        if (instance == this) instance = null
    }

    private fun scheduleHistoryPrune() {
        historyHandler.removeCallbacks(historyPrune)
        val nextExpiryAt = messengerHistory.nextExpiryAtMs() ?: return
        val delayMs = (nextExpiryAt - System.currentTimeMillis() + PRUNE_SETTLE_MS)
            .coerceAtLeast(PRUNE_SETTLE_MS)
        historyHandler.postDelayed(historyPrune, delayMs)
    }
}
