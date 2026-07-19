package com.openclaw.assistant.node

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionManagerDisplayNameTest {
    @Test
    fun `node advertises only the fixed signed assistant commands`() {
        assertEquals(
            listOf("assistant.presence.v1", "assistant.execute.v1"),
            ConnectionManager.signedAssistantCommands(),
        )
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
