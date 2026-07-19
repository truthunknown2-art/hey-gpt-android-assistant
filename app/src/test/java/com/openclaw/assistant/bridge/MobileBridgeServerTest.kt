package com.openclaw.assistant.bridge

import android.content.Context
import com.openclaw.assistant.backend.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Before
import org.junit.Test

class MobileBridgeServerTest {

    private lateinit var context: Context
    private lateinit var config: MobileBridgeConfig
    private lateinit var server: MobileBridgeServer

    @Before fun setUp() {
        context = mockk(relaxed = true)
        every { context.packageName } returns "com.openclaw.assistant.test"
        config = MobileBridgeConfig(InMemorySharedPreferences())
        config.setEnabled(true)
        config.setApprovalMode(BridgeApprovalMode.CONFIRM_MEDIUM_HIGH)
        config.setAllowedCapabilityGroups(setOf("device", "apps", "clipboard.read"))
        config.getOrCreateToken()
        // Use a registry with only the no-side-effect device capability so we don't touch real Android APIs.
        server = MobileBridgeServer(context, config, BridgeRegistry(listOf(StubDeviceCap)))
    }

    @Test fun `health is unauthenticated and returns ok`() = runBlocking {
        val resp = server.dispatch(MobileBridgeServer.HttpRequest("GET", "/health", emptyMap(), ""))
        assertEquals(200, resp.status)
        assertTrue(resp.body.contains("\"status\":\"ok\""))
        assertTrue(resp.body.contains("\"bridge\":true"))
    }

    @Test fun `manifest requires bearer token`() = runBlocking {
        val resp = server.dispatch(MobileBridgeServer.HttpRequest("GET", "/manifest", emptyMap(), ""))
        assertEquals(401, resp.status)
    }

    @Test fun `manifest returns implemented allowed capabilities only`() = runBlocking {
        val token = config.tokenOrNull()!!
        val resp = server.dispatch(MobileBridgeServer.HttpRequest("GET", "/manifest", mapOf("authorization" to "Bearer $token"), ""))
        assertEquals(200, resp.status)
        assertTrue(resp.body.contains("device.info"))
    }

    @Test fun `execute rejects invalid token`() = runBlocking {
        val resp = server.dispatch(MobileBridgeServer.HttpRequest("POST", "/execute", mapOf("authorization" to "Bearer wrong"), "{}"))
        assertEquals(401, resp.status)
    }

    @Test fun `execute unsupported capability returns structured error`() = runBlocking {
        val token = config.tokenOrNull()!!
        val body = """{"requestId":"r1","capability":"nope","arguments":{}}"""
        val resp = server.dispatch(MobileBridgeServer.HttpRequest("POST", "/execute", mapOf("authorization" to "Bearer $token"), body))
        assertEquals(200, resp.status)
        assertTrue(resp.body.contains("\"code\":\"unsupported_capability\""))
        assertTrue(resp.body.contains("\"status\":\"failed\""))
    }

    @Test fun `bearer authenticated HTTP execute remains available independently`() = runBlocking {
        val token = config.tokenOrNull()!!
        val body = """{"requestId":"r2","capability":"device.info","arguments":{}}"""
        val resp = server.dispatch(MobileBridgeServer.HttpRequest("POST", "/execute", mapOf("authorization" to "Bearer $token"), body))
        assertEquals(200, resp.status)
        assertTrue(resp.body.contains("\"status\":\"completed\""))
        assertTrue(resp.body.contains("\"requestId\":\"r2\""))
    }

    @Test fun `medium-risk capability is denied when approval gate denies`() = runBlocking {
        val server = object : MobileBridgeServer(context, config, BridgeRegistry(listOf(StubMediumCap))) {
            override suspend fun approvalGate(
                requestId: String,
                capability: String,
                arguments: JsonObject,
                riskLevel: RiskLevel,
            ) = false
        }
        config.setAllowedCapabilityGroups(setOf("medium"))
        val token = config.tokenOrNull()!!
        val body = """{"requestId":"r3","capability":"medium.do","arguments":{}}"""
        val resp = server.dispatch(MobileBridgeServer.HttpRequest("POST", "/execute", mapOf("authorization" to "Bearer $token"), body))
        assertTrue(resp.body.contains("\"code\":\"approval_denied\""))
    }

    @Test fun `medium-risk capability runs when approval gate approves`() = runBlocking {
        val server = object : MobileBridgeServer(
            context,
            config,
            BridgeRegistry(listOf(StubMediumCap)),
            isDeviceUnlocked = { true },
        ) {
            override suspend fun approvalGate(
                requestId: String,
                capability: String,
                arguments: JsonObject,
                riskLevel: RiskLevel,
            ) = true
        }
        config.setAllowedCapabilityGroups(setOf("medium"))
        val token = config.tokenOrNull()!!
        val body = """{"requestId":"r4","capability":"medium.do","arguments":{}}"""
        val resp = server.dispatch(MobileBridgeServer.HttpRequest("POST", "/execute", mapOf("authorization" to "Bearer $token"), body))
        assertTrue(resp.body.contains("\"status\":\"completed\""))
    }

    @Test fun `rate limiter rejects after capacity exhausted`() = runBlocking {
        val rl = RateLimiter(capacity = 2, refillIntervalMs = 100_000L) { 0L }
        val server = MobileBridgeServer(context, config, BridgeRegistry(listOf(StubDeviceCap)), rateLimiter = rl)
        val token = config.tokenOrNull()!!
        val headers = mapOf("authorization" to "Bearer $token")
        val req = MobileBridgeServer.HttpRequest("GET", "/manifest", headers, "")
        assertEquals(200, server.dispatch(req).status)
        assertEquals(200, server.dispatch(req).status)
        val limited = server.dispatch(req)
        assertEquals(429, limited.status)
        assertTrue(limited.body.contains("rate_limited"))
    }

    @Test fun `health is not rate limited`() = runBlocking {
        val rl = RateLimiter(capacity = 1, refillIntervalMs = 100_000L) { 0L }
        val server = MobileBridgeServer(context, config, BridgeRegistry(listOf(StubDeviceCap)), rateLimiter = rl)
        val token = config.tokenOrNull()!!
        // Consume the bucket with a manifest call
        server.dispatch(MobileBridgeServer.HttpRequest("GET", "/manifest", mapOf("authorization" to "Bearer $token"), ""))
        // Health is unauthenticated AND unmetered.
        repeat(5) {
            val r = server.dispatch(MobileBridgeServer.HttpRequest("GET", "/health", emptyMap(), ""))
            assertEquals(200, r.status)
        }
    }

    @Test fun `trusted mode bypasses approval`() = runBlocking {
        config.setApprovalMode(BridgeApprovalMode.TRUSTED)
        config.setAllowedCapabilityGroups(setOf("medium"))
        val server = MobileBridgeServer(context, config, BridgeRegistry(listOf(StubMediumCap)))
        val token = config.tokenOrNull()!!
        val body = """{"requestId":"r5","capability":"medium.do","arguments":{}}"""
        val resp = server.dispatch(MobileBridgeServer.HttpRequest("POST", "/execute", mapOf("authorization" to "Bearer $token"), body))
        assertTrue(resp.body.contains("\"status\":\"completed\""))
    }

    @Test fun `trusted mode cannot bypass high-risk approval`() = runBlocking {
        config.setApprovalMode(BridgeApprovalMode.TRUSTED)
        config.setAllowedCapabilityGroups(setOf("high"))
        var approvalCalls = 0
        val server = object : MobileBridgeServer(
            context,
            config,
            BridgeRegistry(listOf(StubHighCap)),
            isDeviceUnlocked = { true },
        ) {
            override suspend fun approvalGate(
                requestId: String,
                capability: String,
                arguments: JsonObject,
                riskLevel: RiskLevel,
            ): Boolean {
                approvalCalls += 1
                return false
            }
        }
        val token = config.tokenOrNull()!!
        val body = """{"requestId":"r6","capability":"calendar.create","arguments":{}}"""

        val resp = server.dispatch(
            MobileBridgeServer.HttpRequest("POST", "/execute", mapOf("authorization" to "Bearer $token"), body),
        )

        assertEquals(1, approvalCalls)
        assertTrue(resp.body.contains("\"code\":\"approval_denied\""))
    }

    @Test fun `locking after high-risk approval prevents execution`() = runBlocking {
        config.setAllowedCapabilityGroups(setOf("high"))
        val highCap = CountingHighCap()
        val server = object : MobileBridgeServer(
            context,
            config,
            BridgeRegistry(listOf(highCap)),
            isDeviceUnlocked = { false },
        ) {
            override suspend fun approvalGate(
                requestId: String,
                capability: String,
                arguments: JsonObject,
                riskLevel: RiskLevel,
            ) = true
        }
        val token = config.tokenOrNull()!!
        val body = """{"requestId":"r7","capability":"calendar.create","arguments":{}}"""

        val resp = server.dispatch(
            MobileBridgeServer.HttpRequest("POST", "/execute", mapOf("authorization" to "Bearer $token"), body),
        )

        assertEquals(0, highCap.executeCount)
        assertTrue(resp.body.contains("\"code\":\"device_locked\""))
    }
}

private object StubDeviceCap : BridgeCapability {
    override val name = "device.info"
    override val description = "stub"
    override val group = "device"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject) =
        buildJsonObject { put("ok", JsonPrimitive(true)) }
}

private object StubMediumCap : BridgeCapability {
    override val name = "medium.do"
    override val description = "stub"
    override val group = "medium"
    override val riskLevel = RiskLevel.MEDIUM
    override suspend fun execute(context: Context, arguments: JsonObject) = buildJsonObject {}
}

private object StubHighCap : BridgeCapability {
    override val name = "calendar.create"
    override val description = "stub"
    override val group = "high"
    override val riskLevel = RiskLevel.HIGH
    override suspend fun execute(context: Context, arguments: JsonObject) = buildJsonObject {}
}

private class CountingHighCap : BridgeCapability {
    override val name = "calendar.create"
    override val description = "stub"
    override val group = "high"
    override val riskLevel = RiskLevel.HIGH
    var executeCount = 0

    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        executeCount += 1
        return buildJsonObject {}
    }
}
