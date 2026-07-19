package com.openclaw.assistant.node

import android.app.Application
import android.content.Context
import com.openclaw.assistant.gateway.GatewaySession
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import android.provider.ContactsContract
import android.database.MatrixCursor
import android.content.ContentResolver
import kotlinx.coroutines.runBlocking
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContactsHandlerTest {
    private val context = mockk<Context>()
    private val contentResolver = mockk<ContentResolver>()
    private val handler = ContactsHandler(context)

    @Test
    fun `handleSearch returns error when permission missing`() = runBlocking {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) } returns PackageManager.PERMISSION_DENIED

        val result = handler.handleSearch("""{"query":"test"}""")

        assertEquals(false, result.ok)
        assertEquals("CONTACTS_READ_PERMISSION_REQUIRED", result.error?.code)
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `handleSearch returns contacts when permission granted`() = runBlocking {
        mockkStatic(ContextCompat::class)
        every { context.contentResolver } returns contentResolver
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) } returns PackageManager.PERMISSION_GRANTED

        val cursor = MatrixCursor(arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        ))
        cursor.addRow(arrayOf("John Doe", "123456", 1L))
        cursor.addRow(arrayOf("John Smith", "654321", 2L))

        every { contentResolver.query(any(), any(), any(), any(), any()) } returns cursor

        val result = handler.handleSearch("""{"query":"John","limit":1}""")

        assertEquals(true, result.ok)
        assertEquals(true, result.payloadJson?.contains("John Doe"))
        assertFalse(result.payloadJson.orEmpty().contains("John Smith"))
        assertEquals(true, result.payloadJson?.contains("\"truncated\":true"))
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `handleSearch escapes SQL LIKE wildcards`() = runBlocking {
        mockkStatic(ContextCompat::class)
        every { context.contentResolver } returns contentResolver
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) } returns PackageManager.PERMISSION_GRANTED

        val cursor = MatrixCursor(arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        ))
        cursor.addRow(arrayOf("100% Cotton", "123456", 1L))

        // We capture the selection args to verify escaping
        val selectionArgsSlot = io.mockk.slot<Array<String>>()
        val selectionSlot = io.mockk.slot<String>()
        every {
            contentResolver.query(
                any(),
                any(),
                capture(selectionSlot),
                capture(selectionArgsSlot),
                any()
            )
        } returns cursor

        val result = handler.handleSearch("""{"query":"100%_Cot\\ton"}""")

        assertEquals(true, result.ok)
        assertEquals(true, selectionSlot.captured.contains("ESCAPE '\\'"))
        assertEquals("%100\\%\\_Cot\\\\ton%", selectionArgsSlot.captured[0])
        unmockkStatic(ContextCompat::class)
    }

    @Test
    fun `handleSearch rejects malformed limits and unknown arguments`() = runBlocking {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) } returns PackageManager.PERMISSION_GRANTED

        val stringLimit = handler.handleSearch("""{"query":"John","limit":"1"}""")
        val booleanLimit = handler.handleSearch("""{"query":"John","limit":true}""")
        val unknown = handler.handleSearch("""{"query":"John","extra":"value"}""")

        assertEquals("INVALID_REQUEST", stringLimit.error?.code)
        assertEquals("INVALID_REQUEST", booleanLimit.error?.code)
        assertEquals("INVALID_REQUEST", unknown.error?.code)
        unmockkStatic(ContextCompat::class)
    }
}
