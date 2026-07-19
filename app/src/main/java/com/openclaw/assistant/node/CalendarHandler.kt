package com.openclaw.assistant.node

import android.Manifest
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.openclaw.assistant.PermissionRequester
import com.openclaw.assistant.broker.AndroidCalendarCreateResolutionV1
import com.openclaw.assistant.broker.AndroidCalendarCreateTargetV1
import com.openclaw.assistant.broker.AndroidCalendarCreateWriteV1
import com.openclaw.assistant.broker.AndroidCalendarEventV1
import com.openclaw.assistant.broker.AndroidCalendarNextReadV1
import com.openclaw.assistant.gateway.GatewaySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.TimeZone
import java.time.Instant
import java.time.ZoneOffset

class CalendarHandler(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var permissionRequester: PermissionRequester? = null

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    private fun hasReadPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getFirstWritableCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val cursor = appContext.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    internal fun readAssistantCalendarNext(
        afterEpochMs: Long,
        limit: Int,
    ): AndroidCalendarNextReadV1 {
        if (!hasReadPermission()) return AndroidCalendarNextReadV1.PermissionRequired
        if (afterEpochMs < 0 || limit !in 1..10) return AndroidCalendarNextReadV1.Success(emptyList(), false)
        val rangeEnd = (afterEpochMs + ASSISTANT_CALENDAR_WINDOW_MS)
            .coerceAtMost(MAX_SAFE_INTEGER)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, afterEpochMs)
            ContentUris.appendId(builder, rangeEnd)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val cursor = try {
            appContext.contentResolver.query(
                uri,
                projection,
                "${CalendarContract.Instances.VISIBLE} = 1",
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )
        } catch (_: SecurityException) {
            return AndroidCalendarNextReadV1.PermissionRequired
        }
        val events = mutableListOf<AndroidCalendarEventV1>()
        cursor?.use {
            val titleIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val startIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayIndex = it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            while (events.size <= limit && it.moveToNext()) {
                val start = it.getLong(startIndex)
                val end = it.getLong(endIndex)
                if (start >= afterEpochMs && end > start) {
                    events += AndroidCalendarEventV1(
                        title = it.getString(titleIndex).orEmpty(),
                        startEpochMs = start,
                        endEpochMs = end,
                        allDay = it.getInt(allDayIndex) == 1,
                    )
                }
            }
        }
        return AndroidCalendarNextReadV1.Success(
            events = events.take(limit),
            truncated = events.size > limit,
        )
    }

    internal fun resolveAssistantCalendarCreate(): AndroidCalendarCreateResolutionV1 {
        if (!hasReadPermission() || !hasWritePermission()) {
            return AndroidCalendarCreateResolutionV1.PermissionRequired
        }
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection =
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val cursor = try {
            appContext.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
            )
        } catch (_: SecurityException) {
            return AndroidCalendarCreateResolutionV1.PermissionRequired
        } catch (_: Throwable) {
            return AndroidCalendarCreateResolutionV1.Failed
        }
        return cursor?.use {
            if (!it.moveToFirst()) return@use AndroidCalendarCreateResolutionV1.NotFound
            val calendarId = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
            val displayName = it.getString(
                it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            ).orEmpty()
            if (calendarId <= 0) {
                AndroidCalendarCreateResolutionV1.Failed
            } else {
                AndroidCalendarCreateResolutionV1.Ready(
                    AndroidCalendarCreateTargetV1(calendarId, displayName),
                )
            }
        } ?: AndroidCalendarCreateResolutionV1.NotFound
    }

    internal fun createAssistantCalendarEvent(
        calendarId: Long,
        title: String,
        startEpochMs: Long,
        endEpochMs: Long,
        allDay: Boolean,
    ): AndroidCalendarCreateWriteV1 {
        if (!hasReadPermission() || !hasWritePermission()) {
            return AndroidCalendarCreateWriteV1.PermissionRequired
        }
        val normalizedTitle = title.trim()
        if (
            calendarId <= 0 ||
            normalizedTitle.isEmpty() ||
            normalizedTitle.length > 200 ||
            startEpochMs < 0 ||
            endEpochMs <= startEpochMs ||
            endEpochMs - startEpochMs > MAX_CALENDAR_DURATION_MS
        ) {
            return AndroidCalendarCreateWriteV1.Invalid
        }
        val (storedStart, storedEnd, timeZone) = if (allDay) {
            val startDate = Instant.ofEpochMilli(startEpochMs).atZone(TimeZone.getDefault().toZoneId()).toLocalDate()
            val endDate = Instant.ofEpochMilli(endEpochMs).atZone(TimeZone.getDefault().toZoneId()).toLocalDate()
            if (!endDate.isAfter(startDate)) return AndroidCalendarCreateWriteV1.Invalid
            Triple(
                startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                "UTC",
            )
        } else {
            Triple(startEpochMs, endEpochMs, TimeZone.getDefault().id)
        }
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, normalizedTitle)
            put(CalendarContract.Events.DTSTART, storedStart)
            put(CalendarContract.Events.DTEND, storedEnd)
            put(CalendarContract.Events.EVENT_TIMEZONE, timeZone)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
        }
        return try {
            if (appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) != null) {
                AndroidCalendarCreateWriteV1.Created
            } else {
                AndroidCalendarCreateWriteV1.Failed
            }
        } catch (_: SecurityException) {
            AndroidCalendarCreateWriteV1.PermissionRequired
        } catch (_: Throwable) {
            AndroidCalendarCreateWriteV1.Failed
        }
    }

    private suspend fun ensureReadPermission(): Boolean {
        if (hasReadPermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.READ_CALENDAR))
        return results[Manifest.permission.READ_CALENDAR] == true
    }

    private suspend fun ensureWritePermission(): Boolean {
        if (hasWritePermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.WRITE_CALENDAR))
        return results[Manifest.permission.WRITE_CALENDAR] == true
    }

    suspend fun handleEvents(paramsJson: String?): GatewaySession.InvokeResult {
        if (!ensureReadPermission()) {
            return GatewaySession.InvokeResult.error(
                code = "CALENDAR_READ_PERMISSION_REQUIRED",
                message = "CALENDAR_READ_PERMISSION_REQUIRED: grant Calendar read permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val startTime = (params["startTime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: System.currentTimeMillis()
        val endTime = (params["endTime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: (startTime + 86400000)

        val events = mutableListOf<JsonObject>()
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND
        )
        val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?)"
        val selectionArgs = arrayOf(startTime.toString(), endTime.toString())
        val cursor = try {
            appContext.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
        } catch (e: SecurityException) {
            return GatewaySession.InvokeResult.error(
                code = "CALENDAR_READ_PERMISSION_REQUIRED",
                message = "CALENDAR_READ_PERMISSION_REQUIRED: ${e.message}"
            )
        }

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(CalendarContract.Events._ID)
            val titleIndex = it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val startIndex = it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
            val endIndex = it.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
            var count = 0
            while (it.moveToNext() && count < 10) {
                val id = it.getLong(idIndex)
                val title = it.getString(titleIndex)
                val start = it.getLong(startIndex)
                val end = it.getLong(endIndex)
                events.add(buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("title", JsonPrimitive(title))
                    put("startTime", JsonPrimitive(start))
                    put("endTime", JsonPrimitive(end))
                })
                count++
            }
        }

        val payload = buildJsonObject {
            put("events", buildJsonArray {
                events.forEach { add(it) }
            })
        }
        return GatewaySession.InvokeResult.ok(payload.toString())
    }

    suspend fun handleAdd(paramsJson: String?): GatewaySession.InvokeResult {
        if (!ensureWritePermission()) {
            return GatewaySession.InvokeResult.error(
                code = "CALENDAR_WRITE_PERMISSION_REQUIRED",
                message = "CALENDAR_WRITE_PERMISSION_REQUIRED: grant Calendar write permission"
            )
        }

        val calendarId = getFirstWritableCalendarId() ?: return GatewaySession.InvokeResult.error("CALENDAR_NOT_FOUND", "No writable calendar found")

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val title = (params["title"] as? JsonPrimitive)?.content ?: ""
        val startTime = (params["startTime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "startTime is required")
        val endTime = (params["endTime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: (startTime + 3600000)

        if (title.isEmpty()) {
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Title is required")
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTime)
            put(CalendarContract.Events.DTEND, endTime)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return try {
            val uri = appContext.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                GatewaySession.InvokeResult.ok("""{"ok":true,"id":${uri.lastPathSegment}}""")
            } else {
                GatewaySession.InvokeResult.error("CALENDAR_ADD_FAILED", "CALENDAR_ADD_FAILED: insert returned null")
            }
        } catch (e: Exception) {
            GatewaySession.InvokeResult.error("CALENDAR_ADD_FAILED", "CALENDAR_ADD_FAILED: ${e.message}")
        }
    }

    suspend fun handleUpdate(paramsJson: String?): GatewaySession.InvokeResult {
        if (!ensureWritePermission()) {
            return GatewaySession.InvokeResult.error(
                code = "CALENDAR_WRITE_PERMISSION_REQUIRED",
                message = "CALENDAR_WRITE_PERMISSION_REQUIRED: grant Calendar write permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val id = (params["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (id == null) {
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Valid event ID is required")
        }

        val title = (params["title"] as? JsonPrimitive)?.content
        val startTime = (params["startTime"] as? JsonPrimitive)?.content?.toLongOrNull()
        val endTime = (params["endTime"] as? JsonPrimitive)?.content?.toLongOrNull()

        if (title.isNullOrEmpty() && startTime == null && endTime == null) {
             return GatewaySession.InvokeResult.error("INVALID_REQUEST", "At least one of title, startTime, or endTime is required for update")
        }

        val values = ContentValues().apply {
            if (!title.isNullOrEmpty()) put(CalendarContract.Events.TITLE, title)
            if (startTime != null) put(CalendarContract.Events.DTSTART, startTime)
            if (endTime != null) put(CalendarContract.Events.DTEND, endTime)
        }

        val selection = "${CalendarContract.Events._ID}=?"
        val selectionArgs = arrayOf(id.toString())

        return try {
            val rows = appContext.contentResolver.update(CalendarContract.Events.CONTENT_URI, values, selection, selectionArgs)
            if (rows > 0) {
                 GatewaySession.InvokeResult.ok("""{"ok":true}""")
            } else {
                 GatewaySession.InvokeResult.error("CALENDAR_UPDATE_FAILED", "Event not found or no changes made")
            }
        } catch (e: Exception) {
            GatewaySession.InvokeResult.error("CALENDAR_UPDATE_FAILED", "CALENDAR_UPDATE_FAILED: ${e.message}")
        }
    }

    suspend fun handleDelete(paramsJson: String?): GatewaySession.InvokeResult {
        if (!ensureWritePermission()) {
            return GatewaySession.InvokeResult.error(
                code = "CALENDAR_WRITE_PERMISSION_REQUIRED",
                message = "CALENDAR_WRITE_PERMISSION_REQUIRED: grant Calendar write permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val id = (params["id"] as? JsonPrimitive)?.content?.toLongOrNull()
        if (id == null) {
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Valid event ID is required")
        }

        val selection = "${CalendarContract.Events._ID}=?"
        val selectionArgs = arrayOf(id.toString())

        return try {
            val rows = appContext.contentResolver.delete(CalendarContract.Events.CONTENT_URI, selection, selectionArgs)
             if (rows > 0) {
                 GatewaySession.InvokeResult.ok("""{"ok":true}""")
             } else {
                 GatewaySession.InvokeResult.error("CALENDAR_DELETE_FAILED", "Event not found")
             }
        } catch (e: Exception) {
            GatewaySession.InvokeResult.error("CALENDAR_DELETE_FAILED", "CALENDAR_DELETE_FAILED: ${e.message}")
        }
    }

    private companion object {
        const val ASSISTANT_CALENDAR_WINDOW_MS = 31L * 24L * 60L * 60L * 1_000L
        const val MAX_CALENDAR_DURATION_MS = 31L * 24L * 60L * 60L * 1_000L
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}
