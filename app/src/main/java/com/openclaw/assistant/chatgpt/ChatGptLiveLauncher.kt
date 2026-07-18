package com.openclaw.assistant.chatgpt

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
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
                postLockscreenFallback(context, launchIntent)
            }
            Result.LAUNCHED
        } catch (error: Exception) {
            Log.e(TAG, "Unable to open the official ChatGPT app", error)
            postLockscreenFallback(context, launchIntent)
            Result.FAILED
        }
    }

    private fun postLockscreenFallback(context: Context, launchIntent: Intent) {
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
            .setContentTitle(context.getString(R.string.chatgpt_unlock_title))
            .setContentText(context.getString(R.string.chatgpt_unlock_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        runCatching { notificationManager.notify(LOCKSCREEN_NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Unable to post ChatGPT lock-screen fallback", it) }
    }

    private fun openStore(context: Context): Result {
        val marketIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$CHATGPT_PACKAGE")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(marketIntent)
            Result.STORE_OPENED
        } catch (_: ActivityNotFoundException) {
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
