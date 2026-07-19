package com.openclaw.assistant.node

import android.app.Application
import android.app.Notification
import android.content.Context
import com.openclaw.assistant.gateway.GatewaySession
import com.openclaw.assistant.broker.AndroidMessengerNotificationsReadV1
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import android.service.notification.StatusBarNotification
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    fun `signed Messenger reader filters sender and returns typed private previews`() {
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            context.packageName,
        )
        val now = 2_000_000_000_000L
        val history = MessengerNotificationHistory(context) { now }
        history.record("Jen Thorndale", "See you at seven", now - 1_000)
        history.record("Alex", "Unrelated preview", now - 500)
        every { notificationManager.getActiveNotifications() } returns emptyList()

        val result = NotificationsHandler(context, notificationManager, history)
            .readAssistantMessengerNotifications("Jen", 1) as AndroidMessengerNotificationsReadV1.Success

        assertEquals(1, result.notifications.size)
        assertEquals("Jen Thorndale", result.notifications.single().sender)
        assertEquals("See you at seven", result.notifications.single().textPreview)
        assertFalse(result.truncated)
    }

    @Test
    fun `signed Messenger reader requires notification access`() {
        Settings.Secure.putString(context.contentResolver, "enabled_notification_listeners", "")

        val result = handler.readAssistantMessengerNotifications(null, 3)

        assertEquals(AndroidMessengerNotificationsReadV1.PermissionRequired, result)
    }

    @Test
    fun `Messenger history drops previews older than seven days`() {
        var now = 2_000_000_000_000L
        val preferences = context.getSharedPreferences(
            "messenger-history-prune-test",
            Context.MODE_PRIVATE,
        ).also { it.edit().clear().commit() }
        val history = MessengerNotificationHistory(preferences) { now }
        history.record("Old sender", "Old preview", now)
        assertTrue(preferences.all.values.joinToString().contains("Old sender"))

        now += 8L * 24 * 60 * 60 * 1000
        history.pruneExpired()

        assertFalse(history.recent().any { it.sender == "Old sender" })
        assertFalse(preferences.all.values.joinToString().contains("Old sender"))
        assertFalse(preferences.contains("previews"))
    }

    @Test
    fun `Messenger history serializes writes across separate instances`() {
        val now = 2_000_000_000_000L
        val preferences = context.getSharedPreferences(
            "messenger-history-concurrency-test",
            Context.MODE_PRIVATE,
        ).also { it.edit().clear().commit() }
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val complete = CountDownLatch(20)

        try {
            repeat(20) { index ->
                executor.execute {
                    try {
                        start.await()
                        MessengerNotificationHistory(preferences) { now }
                            .record("Sender $index", "Preview $index", now + index)
                    } finally {
                        complete.countDown()
                    }
                }
            }
            start.countDown()

            assertTrue(complete.await(10, TimeUnit.SECONDS))
            val retained = MessengerNotificationHistory(preferences) { now }.recent(limit = 20)
            assertEquals(20, retained.map { it.sender }.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `Messenger history deletes malformed persisted content`() {
        val preferences = context.getSharedPreferences(
            "messenger-history-malformed-test",
            Context.MODE_PRIVATE,
        ).also {
            it.edit().clear().putString("previews", "not valid json").commit()
        }

        MessengerNotificationHistory(preferences).pruneExpired()

        assertFalse(preferences.contains("previews"))
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
