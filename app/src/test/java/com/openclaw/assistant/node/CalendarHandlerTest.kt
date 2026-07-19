package com.openclaw.assistant.node

import android.app.Application
import android.content.Context
import com.openclaw.assistant.gateway.GatewaySession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.provider.CalendarContract
import android.database.MatrixCursor
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import com.openclaw.assistant.broker.AndroidCalendarCreateResolutionV1
import com.openclaw.assistant.broker.AndroidCalendarCreateWriteV1
import com.openclaw.assistant.broker.AndroidCalendarNextReadV1
import kotlinx.coroutines.runBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import io.mockk.slot
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CalendarHandlerTest {
    private val context = mockk<Context>()
    private val contentResolver = mockk<ContentResolver>()
    private val handler = CalendarHandler(context)

    @Test
    fun `handleEvents returns error when permission missing`() = runBlocking {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) } returns PackageManager.PERMISSION_DENIED

        val result = handler.handleEvents("""{"startTime":"123"}""")

        assertEquals(false, result.ok)
        assertEquals("CALENDAR_READ_PERMISSION_REQUIRED", result.error?.code)
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `handleEvents returns events when permission granted`() = runBlocking {
        mockkStatic(ContextCompat::class)
        every { context.contentResolver } returns contentResolver
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) } returns PackageManager.PERMISSION_GRANTED

        val cursor = MatrixCursor(arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        ))
        cursor.addRow(arrayOf(1L, "Event", 1000L, 2000L))

        every { contentResolver.query(any(), any(), any(), any(), any()) } returns cursor

        val result = handler.handleEvents("""{"startTime":"100"}""")

        assertEquals(true, result.ok)
        assertEquals(true, result.payloadJson?.contains("Event"))
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `assistant next queries bounded Instances and detects truncation`() {
        mockkStatic(ContextCompat::class)
        try {
            every { context.contentResolver } returns contentResolver
            every {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            } returns PackageManager.PERMISSION_GRANTED
            val cursor = MatrixCursor(
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                ),
            ).apply {
                addRow(arrayOf("Dentist", 2_000L, 3_000L, 0))
                addRow(arrayOf("Recurring standup", 4_000L, 5_000L, 0))
            }
            val queriedUri = slot<Uri>()
            val selection = slot<String>()
            every {
                contentResolver.query(capture(queriedUri), any(), capture(selection), any(), any())
            } returns cursor

            val result = handler.readAssistantCalendarNext(1_000L, 1) as AndroidCalendarNextReadV1.Success

            assertEquals(1, result.events.size)
            assertEquals("Dentist", result.events.single().title)
            assertTrue(result.truncated)
            assertTrue(queriedUri.captured.toString().contains("/instances/when/1000/"))
            assertEquals("${CalendarContract.Instances.VISIBLE} = 1", selection.captured)
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }

    @Test
    fun `assistant next returns permission required without provider access`() {
        mockkStatic(ContextCompat::class)
        try {
            every {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
            } returns PackageManager.PERMISSION_DENIED

            assertEquals(
                AndroidCalendarNextReadV1.PermissionRequired,
                handler.readAssistantCalendarNext(1_000L, 5),
            )
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }

    @Test
    fun `assistant create resolves primary writable calendar`() {
        mockkStatic(ContextCompat::class)
        try {
            every { context.contentResolver } returns contentResolver
            every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_GRANTED
            val cursor = MatrixCursor(
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.IS_PRIMARY,
                ),
            ).apply { addRow(arrayOf(42L, "Personal", 1)) }
            every { contentResolver.query(any(), any(), any(), any(), any()) } returns cursor

            val result = handler.resolveAssistantCalendarCreate() as AndroidCalendarCreateResolutionV1.Ready

            assertEquals(42L, result.target.calendarId)
            assertEquals("Personal", result.target.displayName)
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }

    @Test
    fun `assistant create inserts exact timed event values`() {
        mockkStatic(ContextCompat::class)
        try {
            every { context.contentResolver } returns contentResolver
            every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_GRANTED
            val values = slot<ContentValues>()
            every {
                contentResolver.insert(CalendarContract.Events.CONTENT_URI, capture(values))
            } returns ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 99L)

            val result = handler.createAssistantCalendarEvent(
                calendarId = 42L,
                title = "  Dentist  ",
                startEpochMs = 2_000L,
                endEpochMs = 3_000L,
                allDay = false,
            )

            assertEquals(AndroidCalendarCreateWriteV1.Created, result)
            assertEquals(42L, values.captured.getAsLong(CalendarContract.Events.CALENDAR_ID))
            assertEquals("Dentist", values.captured.getAsString(CalendarContract.Events.TITLE))
            assertEquals(2_000L, values.captured.getAsLong(CalendarContract.Events.DTSTART))
            assertEquals(3_000L, values.captured.getAsLong(CalendarContract.Events.DTEND))
            assertEquals(0, values.captured.getAsInteger(CalendarContract.Events.ALL_DAY))
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }

    @Test
    fun `assistant all-day create normalizes to exclusive UTC dates`() {
        mockkStatic(ContextCompat::class)
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Vancouver"))
            every { context.contentResolver } returns contentResolver
            every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_GRANTED
            val values = slot<ContentValues>()
            every {
                contentResolver.insert(CalendarContract.Events.CONTENT_URI, capture(values))
            } returns ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, 99L)
            val start = java.time.Instant.parse("2026-07-20T07:00:00Z").toEpochMilli()
            val end = java.time.Instant.parse("2026-07-22T07:00:00Z").toEpochMilli()

            val result = handler.createAssistantCalendarEvent(42L, "Trip", start, end, true)

            assertEquals(AndroidCalendarCreateWriteV1.Created, result)
            assertEquals("UTC", values.captured.getAsString(CalendarContract.Events.EVENT_TIMEZONE))
            assertEquals(java.time.Instant.parse("2026-07-20T00:00:00Z").toEpochMilli(), values.captured.getAsLong(CalendarContract.Events.DTSTART))
            assertEquals(java.time.Instant.parse("2026-07-22T00:00:00Z").toEpochMilli(), values.captured.getAsLong(CalendarContract.Events.DTEND))
            assertEquals(1, values.captured.getAsInteger(CalendarContract.Events.ALL_DAY))
        } finally {
            TimeZone.setDefault(previousZone)
            unmockkStatic(ContextCompat::class)
        }
    }

    @Test
    fun `assistant create converts provider security failures to permission required`() {
        mockkStatic(ContextCompat::class)
        try {
            every { context.contentResolver } returns contentResolver
            every { ContextCompat.checkSelfPermission(context, any()) } returns PackageManager.PERMISSION_GRANTED
            every { contentResolver.insert(CalendarContract.Events.CONTENT_URI, any()) } throws SecurityException()

            val result = handler.createAssistantCalendarEvent(42L, "Dentist", 2_000L, 3_000L, false)

            assertEquals(AndroidCalendarCreateWriteV1.PermissionRequired, result)
            assertFalse(result == AndroidCalendarCreateWriteV1.Created)
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }
}
