package com.openclaw.assistant.broker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.R

internal object AssistantCalendarCreateApprovalPromptV1 {
    private const val CHANNEL_ID = "assistant_calendar_create_approvals"

    fun present(context: Context, proposalId: String): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(appContext, AssistantCalendarCreateApprovalActivityV1::class.java)
            .setData(Uri.parse("openclaw-assistant://calendar-create/$proposalId"))
            .putExtra(AssistantCalendarCreateApprovalActivityV1.EXTRA_PROPOSAL_ID, proposalId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val launched = runCatching { appContext.startActivity(intent) }.isSuccess
        val notified = postNotification(appContext, intent, proposalId)
        return launched || notified
    }

    fun cancel(context: Context, proposalId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(proposalId))
    }

    private fun postNotification(context: Context, intent: Intent, proposalId: String): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.assistant_calendar_create_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(proposalId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(context.getString(R.string.assistant_calendar_create_title))
            .setContentText(context.getString(R.string.assistant_calendar_create_notification))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        return runCatching {
            notifications.notify(notificationId(proposalId), notification)
            true
        }.getOrDefault(false)
    }

    private fun notificationId(proposalId: String): Int = proposalId.hashCode() xor CHANNEL_ID.hashCode()
}
