package com.openclaw.assistant.node

import android.provider.Settings
import com.openclaw.assistant.LocationMode
import com.openclaw.assistant.SecurePrefs
import com.openclaw.assistant.VoiceWakeMode
import com.openclaw.assistant.protocol.OpenClawNotificationsCommand
import com.openclaw.assistant.protocol.OpenClawCapability
import com.openclaw.assistant.protocol.OpenClawBridgeCommand
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ConnectionManagerDisplayNameTest {
    @Test
    fun `node advertises only the fixed signed assistant commands`() {
        assertEquals(
            listOf("assistant.presence.v1", "assistant.execute.v1"),
            ConnectionManager.signedAssistantCommands(),
        )
    }

    @Test
    fun `custom node never advertises raw notification or bridge commands`() {
        val context = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            context.packageName,
        )
        val manager = ConnectionManager(
            prefs = mockk<SecurePrefs>(relaxed = true),
            appContext = context,
            cameraEnabled = { false },
            locationMode = { LocationMode.Off },
            voiceWakeMode = { VoiceWakeMode.Off },
            smsAvailable = { false },
            hasRecordAudioPermission = { false },
            manualTls = { false },
            deviceId = { "device" },
        )

        val commands = manager.buildInvokeCommands()

        assertFalse(OpenClawNotificationsCommand.List.rawValue in commands)
        assertFalse(OpenClawNotificationsCommand.Actions.rawValue in commands)
        OpenClawBridgeCommand.entries.forEach { command ->
            assertFalse(command.rawValue in commands)
        }
        assertFalse(OpenClawCapability.Notifications.rawValue in manager.buildCapabilities())
        assertFalse(OpenClawCapability.Bridge.rawValue in manager.buildCapabilities())
    }

    @Test
    fun `configured node name is preferred over raw device id`() {
        assertEquals(
            "Kitchen S10 Assistant Tools",
            ConnectionManager.assistantToolsDisplayName(
                configuredName = "Kitchen S10",
                deviceId = "0123456789abcdef0123456789abcdef",
            ),
        )
    }

    @Test
    fun `assistant tools suffix is idempotent`() {
        assertEquals(
            "Galaxy S10 Assistant Tools",
            ConnectionManager.assistantToolsDisplayName(
                configuredName = "Galaxy S10 Assistant Tools",
                deviceId = "device-id",
            ),
        )
    }

    @Test
    fun `device id fallback is bounded`() {
        assertEquals(
            "abcdefghijkl Assistant Tools",
            ConnectionManager.assistantToolsDisplayName(
                configuredName = " ",
                deviceId = "abcdefghijklmnopqrstuvwxyz",
            ),
        )
    }
}
