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

internal object AssistantPrivateReadApprovalPromptV1 {
    private const val CHANNEL_ID = "assistant_private_read_approvals"

    fun present(
        context: Context,
        proposalId: String,
        capability: AssistantCapabilityV1,
    ): Boolean {
        val appContext = context.applicationContext
        val intent = Intent(appContext, AssistantPrivateReadApprovalActivityV1::class.java)
            .setData(Uri.parse("openclaw-assistant://private-read/$proposalId"))
            .putExtra(AssistantPrivateReadApprovalActivityV1.EXTRA_PROPOSAL_ID, proposalId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val launched = runCatching { appContext.startActivity(intent) }.isSuccess
        val notified = postNotification(appContext, intent, proposalId, capability)
        return launched || notified
    }

    fun cancel(context: Context, proposalId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(proposalId))
    }

    private fun postNotification(
        context: Context,
        intent: Intent,
        proposalId: String,
        capability: AssistantCapabilityV1,
    ): Boolean {
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
                    context.getString(R.string.assistant_private_read_channel),
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
        val capabilityLabel = when (capability) {
            AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH ->
                context.getString(R.string.assistant_private_read_contacts)
            AssistantCapabilityV1.ANDROID_CALENDAR_NEXT ->
                context.getString(R.string.assistant_private_read_calendar)
            AssistantCapabilityV1.WINDOWS_FILES_READ ->
                context.getString(R.string.assistant_private_read_windows_file)
            else -> capability.wireName
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(context.getString(R.string.assistant_private_read_title))
            .setContentText(context.getString(R.string.assistant_private_read_notification, capabilityLabel))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        return runCatching {
            notifications.notify(notificationId(proposalId), notification)
            true
        }.getOrDefault(false)
    }

    private fun notificationId(proposalId: String): Int = proposalId.hashCode()
}
