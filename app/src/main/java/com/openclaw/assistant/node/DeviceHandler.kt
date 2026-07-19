package com.openclaw.assistant.node

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.SecurePrefs
import com.openclaw.assistant.broker.AndroidDeviceStatusSummaryV1
import com.openclaw.assistant.gateway.GatewaySession
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class DeviceHandler(
  private val appContext: Context,
  private val prefs: SecurePrefs,
) {

  internal data class StatusSnapshot(
    val batteryLevel: Int,
    val charging: Boolean,
    val plugged: Int,
    val status: Int,
    val temperatureCelsius: Double,
    val voltage: Int,
    val screenInteractive: Boolean,
    val voiceWakeMode: String,
    val locationMode: String,
    val screenPreventSleep: Boolean,
  )

  fun handleStatus(): GatewaySession.InvokeResult {
    val snapshot = readStatusSnapshot()

    val payload = buildJsonObject {
      put("batteryLevel", JsonPrimitive(snapshot.batteryLevel))
      put("charging", JsonPrimitive(snapshot.charging))
      put("plugged", JsonPrimitive(snapshot.plugged))
      put("status", JsonPrimitive(snapshot.status))
      put("temperature", JsonPrimitive(snapshot.temperatureCelsius))
      put("voltage", JsonPrimitive(snapshot.voltage))
      put("screenInteractive", JsonPrimitive(snapshot.screenInteractive))
      put("voiceWakeMode", JsonPrimitive(snapshot.voiceWakeMode))
      put("locationMode", JsonPrimitive(snapshot.locationMode))
      put("screenPreventSleep", JsonPrimitive(snapshot.screenPreventSleep))
    }
    return GatewaySession.InvokeResult.ok(payload.toString())
  }

  internal fun readAssistantStatus(): AndroidDeviceStatusSummaryV1 {
    val snapshot = readStatusSnapshot()
    return AndroidDeviceStatusSummaryV1(
      batteryLevelPercent = snapshot.batteryLevel.takeIf { it in 0..100 },
      charging = snapshot.charging,
      screenInteractive = snapshot.screenInteractive,
    )
  }

  private fun readStatusSnapshot(): StatusSnapshot {
    val batteryIntent: Intent? = appContext.registerReceiver(
      null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    )
    val rawLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val level = if (rawLevel != -1 && scale > 0) (rawLevel * 100 / scale.toFloat()).toInt() else -1
    val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val temperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1

    val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    return StatusSnapshot(
      batteryLevel = level,
      charging = isCharging,
      plugged = plugged,
      status = status,
      temperatureCelsius = temperature / 10.0,
      voltage = voltage,
      screenInteractive = powerManager.isInteractive,
      voiceWakeMode = prefs.voiceWakeMode.value.rawValue,
      locationMode = prefs.locationMode.value.rawValue,
      screenPreventSleep = prefs.preventSleep.value,
    )
  }

  fun handleInfo(): GatewaySession.InvokeResult {
    val payload = buildJsonObject {
      put("deviceId", JsonPrimitive(prefs.instanceId.value))
      put("name", JsonPrimitive(prefs.displayName.value))
      put("appVersion", JsonPrimitive(BuildConfig.VERSION_NAME))
      put("appBuild", JsonPrimitive(BuildConfig.VERSION_CODE))
      put("androidSdk", JsonPrimitive(Build.VERSION.SDK_INT))
      put("androidVersion", JsonPrimitive(Build.VERSION.RELEASE))
      put("model", JsonPrimitive(Build.MODEL))
      put("manufacturer", JsonPrimitive(Build.MANUFACTURER))
      put("brand", JsonPrimitive(Build.BRAND))
    }
    return GatewaySession.InvokeResult.ok(payload.toString())
  }

  fun handlePermissions(): GatewaySession.InvokeResult {
    val fineLocation = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    val coarseLocation = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    val payload = buildJsonObject {
      put("camera", JsonPrimitive(hasPermission(Manifest.permission.CAMERA)))
      put("microphone", JsonPrimitive(hasPermission(Manifest.permission.RECORD_AUDIO)))
      put("location", buildJsonObject {
        put("fine", JsonPrimitive(fineLocation))
        put("coarse", JsonPrimitive(coarseLocation))
      })
      put("sms", JsonPrimitive(hasPermission(Manifest.permission.SEND_SMS)))
    }
    return GatewaySession.InvokeResult.ok(payload.toString())
  }

  fun handleHealth(): GatewaySession.InvokeResult {
    val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)

    val stat = StatFs(Environment.getDataDirectory().path)

    val payload = buildJsonObject {
      put("status", JsonPrimitive("ok"))
      put("memory", buildJsonObject {
        put("total", JsonPrimitive(memInfo.totalMem))
        put("available", JsonPrimitive(memInfo.availMem))
        put("lowMemory", JsonPrimitive(memInfo.lowMemory))
      })
      put("storage", buildJsonObject {
        put("total", JsonPrimitive(stat.totalBytes))
        put("available", JsonPrimitive(stat.availableBytes))
      })
    }
    return GatewaySession.InvokeResult.ok(payload.toString())
  }

  private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
