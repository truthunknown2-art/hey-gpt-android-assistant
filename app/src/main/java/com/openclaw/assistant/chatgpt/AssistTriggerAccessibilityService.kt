package com.openclaw.assistant.chatgpt

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.provider.Settings
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

internal data class AssistGestureSpec(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long,
)

internal fun assistantGestureSpec(
    width: Float,
    height: Float,
    navigationMode: Int,
    navigationBarHeight: Float,
): AssistGestureSpec = if (navigationMode == 2) {
    AssistGestureSpec(
        startX = width * 0.02f,
        startY = height - 2f,
        endX = width * 0.25f,
        endY = height * 0.78f,
        durationMs = 450L,
    )
} else {
    AssistGestureSpec(
        startX = width / 2f,
        startY = height - navigationBarHeight / 2f,
        endX = width / 2f,
        endY = height - navigationBarHeight / 2f,
        durationMs = 850L,
    )
}

/**
 * A deliberately minimal accessibility service whose only operation is the
 * standard Android assistant gesture. It cannot read windows or inspect text,
 * and its implementation exposes no arbitrary tap/swipe interface.
 */
class AssistTriggerAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Hey GPT assistant trigger connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AssistTriggerService"

        @Volatile
        private var instance: AssistTriggerAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun triggerSystemAssistant(): Boolean {
            val service = instance ?: return false
            // Accessibility service resources report the app content area on
            // some Samsung builds, excluding the navigation bar. The assistant
            // affordance lives inside that bar, so use the real display size.
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            service.getSystemService(WindowManager::class.java)
                .defaultDisplay
                .getRealMetrics(metrics)
            val width = metrics.widthPixels.toFloat()
            val height = metrics.heightPixels.toFloat()
            val navigationMode = runCatching {
                Settings.Secure.getInt(service.contentResolver, "navigation_mode", 0)
            }.getOrDefault(0)

            val navigationBarHeight = service.resources.getIdentifier(
                "navigation_bar_height",
                "dimen",
                "android",
            ).takeIf { it != 0 }
                ?.let(service.resources::getDimensionPixelSize)
                ?.toFloat()
                ?: (height * 0.06f)
            val spec = assistantGestureSpec(
                width = width,
                height = height,
                navigationMode = navigationMode,
                navigationBarHeight = navigationBarHeight,
            )
            val path = Path().apply {
                moveTo(spec.startX, spec.startY)
                if (spec.endX != spec.startX || spec.endY != spec.startY) {
                    lineTo(spec.endX, spec.endY)
                }
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, spec.durationMs))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }
    }
}
