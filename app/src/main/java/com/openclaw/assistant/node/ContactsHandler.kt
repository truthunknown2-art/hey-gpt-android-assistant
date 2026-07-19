package com.openclaw.assistant.node

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.openclaw.assistant.PermissionRequester
import com.openclaw.assistant.broker.AndroidContactMatchV1
import com.openclaw.assistant.broker.AndroidContactsSearchReadV1
import com.openclaw.assistant.gateway.GatewaySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class ContactsHandler(private val appContext: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var permissionRequester: PermissionRequester? = null

    fun attachPermissionRequester(requester: PermissionRequester) {
        permissionRequester = requester
    }

    private fun hasReadPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.WRITE_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun ensureReadPermission(): Boolean {
        if (hasReadPermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.READ_CONTACTS))
        return results[Manifest.permission.READ_CONTACTS] == true
    }

    private suspend fun ensureWritePermission(): Boolean {
        if (hasWritePermission()) return true
        val requester = permissionRequester ?: return false
        val results = requester.requestIfMissing(listOf(Manifest.permission.WRITE_CONTACTS))
        return results[Manifest.permission.WRITE_CONTACTS] == true
    }

    suspend fun handleSearch(paramsJson: String?): GatewaySession.InvokeResult {
        if (!ensureReadPermission()) {
            return GatewaySession.InvokeResult.error(
                code = "CONTACTS_READ_PERMISSION_REQUIRED",
                message = "CONTACTS_READ_PERMISSION_REQUIRED: grant Contacts read permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        if (params.keys.any { it !in setOf("query", "limit") }) {
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Unknown search argument")
        }
        val queryPrimitive = params["query"] as? JsonPrimitive
        val query = queryPrimitive?.takeIf { it.isString }?.content?.trim().orEmpty()
        val limitElement = params["limit"]
        val limit = if (limitElement == null) {
            10
        } else {
            (limitElement as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        }

        if (query.isEmpty() || limit == null || limit !in 1..10) {
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Query and limit must be valid")
        }

        return when (val result = runCatching { readAssistantContacts(query, limit) }.getOrElse {
            return GatewaySession.InvokeResult.error("CONTACTS_SEARCH_FAILED", "CONTACTS_SEARCH_FAILED")
        }) {
            AndroidContactsSearchReadV1.PermissionRequired -> GatewaySession.InvokeResult.error(
                code = "CONTACTS_READ_PERMISSION_REQUIRED",
                message = "CONTACTS_READ_PERMISSION_REQUIRED: grant Contacts read permission",
            )
            is AndroidContactsSearchReadV1.Success -> {
                val payload = buildJsonObject {
                    put("contacts", buildJsonArray {
                        result.matches.forEach { match ->
                            add(buildJsonObject {
                                put("id", JsonPrimitive(match.contactId))
                                put("name", JsonPrimitive(match.displayName))
                                put("number", JsonPrimitive(match.phoneNumber))
                            })
                        }
                    })
                    put("truncated", JsonPrimitive(result.truncated))
                }
                GatewaySession.InvokeResult.ok(payload.toString())
            }
        }
    }

    internal fun readAssistantContacts(query: String, limit: Int): AndroidContactsSearchReadV1 {
        if (!hasReadPermission()) return AndroidContactsSearchReadV1.PermissionRequired
        require(query.isNotBlank() && query.length <= 100)
        require(limit in 1..10)

        val contacts = mutableListOf<AndroidContactMatchV1>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        val selectionArgs = arrayOf("%${escapeLikePattern(query)}%")
        val cursor = try {
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
            )
        } catch (e: SecurityException) {
            return AndroidContactsSearchReadV1.PermissionRequired
        }

        cursor?.use {
            val nameIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val idIndex = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            while (contacts.size <= limit && it.moveToNext()) {
                val contactId = it.getLong(idIndex)
                val displayName = it.getString(nameIndex).orEmpty().trim()
                val phoneNumber = it.getString(numberIndex).orEmpty().trim()
                if (contactId > 0 && displayName.isNotEmpty() && phoneNumber.isNotEmpty()) {
                    contacts += AndroidContactMatchV1(
                        contactId = contactId.toString(),
                        displayName = displayName,
                        phoneNumber = phoneNumber,
                    )
                }
            }
        }
        return AndroidContactsSearchReadV1.Success(
            matches = contacts.take(limit),
            truncated = contacts.size > limit,
        )
    }

    suspend fun handleAdd(paramsJson: String?): GatewaySession.InvokeResult {
        if (!ensureWritePermission()) {
            return GatewaySession.InvokeResult.error(
                code = "CONTACTS_WRITE_PERMISSION_REQUIRED",
                message = "CONTACTS_WRITE_PERMISSION_REQUIRED: grant Contacts write permission"
            )
        }

        val params = paramsJson?.let {
            try {
                json.parseToJsonElement(it).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Expected JSON object")

        val name = (params["name"] as? JsonPrimitive)?.content ?: ""
        val number = (params["number"] as? JsonPrimitive)?.content ?: ""

        if (name.isEmpty() || number.isEmpty()) {
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Name and number are required")
        }

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build())

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build())

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            .build())

        return try {
            appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            GatewaySession.InvokeResult.ok("""{"ok":true}""")
        } catch (e: Exception) {
            GatewaySession.InvokeResult.error("CONTACTS_ADD_FAILED", "CONTACTS_ADD_FAILED: ${e.message}")
        }
    }

    suspend fun handleUpdate(paramsJson: String?): GatewaySession.InvokeResult {
        if (!ensureWritePermission()) {
            return GatewaySession.InvokeResult.error(
                code = "CONTACTS_WRITE_PERMISSION_REQUIRED",
                message = "CONTACTS_WRITE_PERMISSION_REQUIRED: grant Contacts write permission"
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
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Valid contact ID is required")
        }

        val name = (params["name"] as? JsonPrimitive)?.content
        val number = (params["number"] as? JsonPrimitive)?.content

        if (name.isNullOrEmpty() && number.isNullOrEmpty()) {
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Either name or number is required for update")
        }

        val ops = ArrayList<ContentProviderOperation>()

        if (!name.isNullOrEmpty()) {
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(id.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE))
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build())
        }

        if (!number.isNullOrEmpty()) {
            ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(id.toString(), ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE))
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                .build())
        }

        return try {
            appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            GatewaySession.InvokeResult.ok("""{"ok":true}""")
        } catch (e: Exception) {
            GatewaySession.InvokeResult.error("CONTACTS_UPDATE_FAILED", "CONTACTS_UPDATE_FAILED: ${e.message}")
        }
    }

    suspend fun handleDelete(paramsJson: String?): GatewaySession.InvokeResult {
        if (!ensureWritePermission()) {
            return GatewaySession.InvokeResult.error(
                code = "CONTACTS_WRITE_PERMISSION_REQUIRED",
                message = "CONTACTS_WRITE_PERMISSION_REQUIRED: grant Contacts write permission"
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
            return GatewaySession.InvokeResult.error("INVALID_REQUEST", "Valid contact ID is required")
        }

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(ContentProviderOperation.newDelete(ContactsContract.RawContacts.CONTENT_URI)
            .withSelection("${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(id.toString()))
            .build())

        return try {
            appContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            GatewaySession.InvokeResult.ok("""{"ok":true}""")
        } catch (e: Exception) {
            GatewaySession.InvokeResult.error("CONTACTS_DELETE_FAILED", "CONTACTS_DELETE_FAILED: ${e.message}")
        }
    }

    private fun escapeLikePattern(pattern: String): String =
        pattern.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
