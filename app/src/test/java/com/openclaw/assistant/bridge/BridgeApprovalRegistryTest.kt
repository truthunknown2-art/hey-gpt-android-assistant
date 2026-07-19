package com.openclaw.assistant.bridge

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeApprovalRegistryTest {
    @Test fun `duplicate pending request id fails closed without replacing original`() = runBlocking {
        val first = async {
            BridgeApprovalRegistry.await(
                requestId = "same-request",
                capability = "calendar.create",
                arguments = buildJsonObject {},
                riskLevel = RiskLevel.HIGH,
                timeoutMs = 1_000L,
            )
        }
        while ("same-request" !in BridgeApprovalRegistry.pendingIds()) yield()

        val duplicate = BridgeApprovalRegistry.await(
            requestId = "same-request",
            capability = "sms.send",
            arguments = buildJsonObject {},
            riskLevel = RiskLevel.HIGH,
            timeoutMs = 1L,
        )
        BridgeApprovalRegistry.respond("same-request", true)

        assertFalse(duplicate)
        assertTrue(first.await())
    }
}
