package com.openclaw.assistant.chatgpt

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openclaw.assistant.R

/**
 * Opens the official ChatGPT Android app without depending on private activities,
 * deep links, web APIs, or a Platform API key.
 *
 * In ChatGPT, enable Settings -> Voice -> Start with Voice so its normal launcher
 * activity begins a Live conversation. Background Conversations lets that official
 * session continue after the phone is locked.
 */
object ChatGptLiveLauncher {
    const val CHATGPT_PACKAGE = "com.openai.chatgpt"

    private const val TAG = "ChatGptLiveLauncher"
    private const val CHANNEL_ID = "chatgpt_live_handoff"
    private const val LOCKSCREEN_NOTIFICATION_ID = 5601
    private const val PLAY_STORE_WEB_URL =
        "https://play.google.com/store/apps/details?id=$CHATGPT_PACKAGE"

    enum class Result {
        LAUNCHED,
        STORE_OPENED,
        FAILED
    }

    fun launch(context: Context): Result {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(CHATGPT_PACKAGE)
        if (launchIntent == null) {
            return openStore(context)
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        )

        return try {
            // Use the app's normal exported launcher activity. startVoiceActivity
            // is intended for voice-compatible activities and is not a reliable
            // universal launcher for third-party ACTION_MAIN activities.
            context.startActivity(launchIntent)
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                if (!postFallback(context, launchIntent, locked = true)) {
                    Log.w(TAG, "ChatGPT launched while locked, but notification permission is unavailable")
                }
            }
            Result.LAUNCHED
        } catch (error: Exception) {
            Log.e(TAG, "Unable to open the official ChatGPT app", error)
            postFallback(context, launchIntent, locked = true)
            Result.FAILED
        }
    }

    /** Posts a user-controlled retry when Android accepted a launch but no recording appeared. */
    fun postFallbackNotification(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(CHATGPT_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$CHATGPT_PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val locked = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        return postFallback(context, launchIntent, locked)
    }

    private fun postFallback(context: Context, launchIntent: Intent, locked: Boolean): Boolean {
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot post ChatGPT fallback: notification permission is not granted")
            return false
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.chatgpt_handoff_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(context.getString(
                if (locked) R.string.chatgpt_unlock_title else R.string.chatgpt_start_title
            ))
            .setContentText(context.getString(
                if (locked) R.string.chatgpt_unlock_body else R.string.chatgpt_start_body
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        return runCatching {
            notificationManager.notify(LOCKSCREEN_NOTIFICATION_ID, notification)
            true
        }.getOrElse {
            Log.w(TAG, "Unable to post ChatGPT lock-screen fallback", it)
            false
        }
    }

    private fun openStore(context: Context): Result {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$CHATGPT_PACKAGE")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(marketIntent)
            Result.STORE_OPENED
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Result.STORE_OPENED
            } catch (error: Exception) {
                Log.e(TAG, "Unable to open ChatGPT or its Play Store page", error)
                Result.FAILED
            }
        }
    }
}
