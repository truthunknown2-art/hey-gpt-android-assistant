package com.openclaw.assistant.node

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.openclaw.assistant.gateway.GatewaySession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PhoneHandler(
  private val context: Context,
  private val json: Json,
  private val invokeErrorFromThrowable: (Throwable) -> Pair<String, String>,
) {
  fun handleCall(paramsJson: String?): GatewaySession.InvokeResult {
    return try {
      val root = paramsJson?.let { json.parseToJsonElement(it).jsonObject }
        ?: return GatewaySession.InvokeResult.error("INVALID_ARGUMENT", "Missing parameters")
      val number = root["number"]?.jsonPrimitive?.content?.trim().orEmpty()
      if (number.isBlank()) {
        return GatewaySession.InvokeResult.error("INVALID_ARGUMENT", "number is required")
      }

      val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
        PackageManager.PERMISSION_GRANTED
      val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
      val intent = Intent(action, Uri.fromParts("tel", number, null)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      GatewaySession.InvokeResult.ok(
        """{"success":true,"placedCall":$canCall,"requiresTap":${!canCall}}"""
      )
    } catch (error: Throwable) {
      val (code, message) = invokeErrorFromThrowable(error)
      GatewaySession.InvokeResult.error(code, message)
    }
  }
}
