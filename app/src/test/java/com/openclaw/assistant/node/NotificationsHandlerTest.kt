package com.openclaw.assistant.node

import android.app.Application
import android.app.Notification
import android.content.Context
import com.openclaw.assistant.gateway.GatewaySession
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.service.notification.StatusBarNotification
import android.provider.Settings
import kotlinx.coroutines.runBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NotificationsHandlerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val notificationManager = mockk<NotificationManager>()
    private val handler = NotificationsHandler(context, notificationManager)

    @Test
    fun `handleList returns error when service disabled`() = runBlocking {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")

        val result = handler.handleList()

        assertEquals(false, result.ok)
        assertEquals("NOTIFICATIONS_PERMISSION_REQUIRED", result.error?.code)
    }

    @Test
    fun `handleList returns notifications when service enabled`() = runBlocking {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            context.packageName
        )

        val sbn = mockk<StatusBarNotification>()
        val notification = Notification.Builder(context, "test-channel")
            .setContentTitle("Title")
            .setContentText("Text")
            .build()
        every { sbn.key } returns "test_key"
        every { sbn.packageName } returns "com.test"
        every { sbn.postTime } returns 12345L
        every { sbn.notification } returns notification

        every { notificationManager.getActiveNotifications() } returns listOf(sbn)

        val result = handler.handleList()

        assertEquals(true, result.ok)
        val json = result.payloadJson ?: ""
        assertEquals(true, json.contains("test_key"))
    }

    @Test
    fun `Messenger list filters on device and exposes no action identifiers`() = runBlocking {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            context.packageName,
        )

        val messenger = notification(
            packageName = NotificationsHandler.MESSENGER_PACKAGE,
            key = "private_messenger_key",
            title = "Alex",
            text = "Are you free later?",
            postTime = 12345L,
        )
        val unrelated = notification(
            packageName = "com.example.mail",
            key = "private_mail_key",
            title = "Mail sender",
            text = "Secret mail",
            postTime = 67890L,
        )
        every { notificationManager.getActiveNotifications() } returns listOf(messenger, unrelated)

        val result = handler.handleMessengerList()
        val payload = result.payloadJson.orEmpty()

        assertTrue(result.ok)
        assertTrue(payload.contains("Alex"))
        assertTrue(payload.contains("Are you free later?"))
        assertTrue(payload.contains("12345"))
        assertFalse(payload.contains("Secret mail"))
        assertFalse(payload.contains("private_messenger_key"))
        assertFalse(payload.contains("packageName"))
        assertFalse(payload.contains("action"))
    }

    @Test
    fun `Messenger list retains a recent preview after notification is dismissed`() = runBlocking {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            context.packageName,
        )
        val now = 2_000_000_000_000L
        val history = MessengerNotificationHistory(context) { now }
        history.record("Jen Thorndale", "See you at seven", now - 1_000)
        every { notificationManager.getActiveNotifications() } returns emptyList()

        val result = NotificationsHandler(context, notificationManager, history).handleMessengerList()
        val payload = result.payloadJson.orEmpty()

        assertTrue(result.ok)
        assertTrue(payload.contains("Jen Thorndale"))
        assertTrue(payload.contains("See you at seven"))
    }

    @Test
    fun `Messenger history drops previews older than seven days`() {
        val now = 2_000_000_000_000L
        val history = MessengerNotificationHistory(context) { now }
        history.record("Old sender", "Old preview", now - 8L * 24 * 60 * 60 * 1000)

        assertFalse(history.recent().any { it.sender == "Old sender" })
    }

    private fun notification(
        packageName: String,
        key: String,
        title: String,
        text: String,
        postTime: Long,
    ): StatusBarNotification {
        val sbn = mockk<StatusBarNotification>()
        val notification = Notification.Builder(context, "test-channel")
            .setContentTitle(title)
            .setContentText(text)
            .build()
        every { sbn.key } returns key
        every { sbn.packageName } returns packageName
        every { sbn.postTime } returns postTime
        every { sbn.notification } returns notification
        return sbn
    }
}
