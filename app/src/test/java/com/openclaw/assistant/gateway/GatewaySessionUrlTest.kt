package com.openclaw.assistant.gateway

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure URL-helper functions in [GatewaySession.Companion].
 *
 * Covers:
 *  - [GatewaySession.buildUrlSuffix]
 *  - [GatewaySession.buildCanvasUrl]
 *  - [GatewaySession.isLoopbackHost]
 *  - [GatewaySession.resolveInvokeResultAckTimeoutMs]
 *  - [GatewaySession.normalizeCanvasHostUrl]
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewaySessionUrlTest {
    @Test
    fun `pending RPC entry is removed when caller is cancelled`() = runTest {
        val deferred = CompletableDeferred<String>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
        pending["request-id"] = deferred

        val job = launch {
            GatewaySession.sendAndAwaitPendingRpc(
                id = "request-id",
                deferred = deferred,
                pendingRequests = pending,
                timeoutMs = 60_000L,
                sendRequest = {},
            )
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue(pending.isEmpty())
    }

    @Test
    fun `pending RPC entry is removed when frame transmission fails`() = runTest {
        val deferred = CompletableDeferred<String>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
        pending["request-id"] = deferred

        runCatching {
            GatewaySession.sendAndAwaitPendingRpc(
                id = "request-id",
                deferred = deferred,
                pendingRequests = pending,
                timeoutMs = 60_000L,
                sendRequest = { error("send failed") },
            )
        }

        assertTrue(pending.isEmpty())
    }


    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun endpoint(
        host: String,
        port: Int,
        lanHost: String? = null,
        tailnetDns: String? = null,
        canvasPort: Int? = null,
    ) = GatewayEndpoint(
        stableId = "test|$host|$port",
        name = "$host:$port",
        host = host,
        port = port,
        lanHost = lanHost,
        tailnetDns = tailnetDns,
        canvasPort = canvasPort,
    )

    private fun uri(s: String) = java.net.URI(s)

    // ---------------------------------------------------------------------------
    // buildUrlSuffix
    // ---------------------------------------------------------------------------

    @Test
    fun `buildUrlSuffix - null returns empty`() {
        assertEquals("", GatewaySession.buildUrlSuffix(null))
    }

    @Test
    fun `buildUrlSuffix - root path only returns empty`() {
        assertEquals("", GatewaySession.buildUrlSuffix(uri("http://host/")))
    }

    @Test
    fun `buildUrlSuffix - sub-path preserved`() {
        assertEquals("/canvas/v2", GatewaySession.buildUrlSuffix(uri("http://host/canvas/v2")))
    }

    @Test
    fun `buildUrlSuffix - query string preserved`() {
        assertEquals("?session=abc", GatewaySession.buildUrlSuffix(uri("http://host?session=abc")))
    }

    @Test
    fun `buildUrlSuffix - path and query combined`() {
        assertEquals("/ui?token=x", GatewaySession.buildUrlSuffix(uri("http://host/ui?token=x")))
    }

    @Test
    fun `buildUrlSuffix - fragment preserved`() {
        assertEquals("#section", GatewaySession.buildUrlSuffix(uri("http://host#section")))
    }

    // ---------------------------------------------------------------------------
    // buildCanvasUrl
    // ---------------------------------------------------------------------------

    @Test
    fun `buildCanvasUrl - https port 443 omitted`() {
        assertEquals("https://example.com/ui", GatewaySession.buildCanvasUrl("https", "example.com", 443, "/ui"))
    }

    @Test
    fun `buildCanvasUrl - http port 80 omitted`() {
        assertEquals("http://example.com/ui", GatewaySession.buildCanvasUrl("http", "example.com", 80, "/ui"))
    }

    @Test
    fun `buildCanvasUrl - non-standard port included`() {
        assertEquals("http://192.168.1.1:18789", GatewaySession.buildCanvasUrl("http", "192.168.1.1", 18789, ""))
    }

    @Test
    fun `buildCanvasUrl - IPv6 host wrapped in brackets`() {
        assertEquals("https://[2001:db8::1]:8443", GatewaySession.buildCanvasUrl("https", "2001:db8::1", 8443, ""))
    }

    @Test
    fun `buildCanvasUrl - port 0 omitted`() {
        assertEquals("https://example.com", GatewaySession.buildCanvasUrl("https", "example.com", 0, ""))
    }

    @Test
    fun `buildOrigin - omits default TLS port`() {
        assertEquals("https://example.com", GatewaySession.buildOrigin("https", "example.com", 443))
    }

    @Test
    fun `buildOrigin - keeps non-standard port`() {
        assertEquals("https://example.com:8443", GatewaySession.buildOrigin("https", "example.com", 8443))
    }

    @Test
    fun `buildOrigin - maps Android emulator host loopback to localhost origin`() {
        assertEquals("http://127.0.0.1:18789", GatewaySession.buildOrigin("http", "10.0.2.2", 18789))
    }

    // ---------------------------------------------------------------------------
    // isLoopbackHost
    // ---------------------------------------------------------------------------

    @Test
    fun `isLoopbackHost - localhost`() = assertTrue(GatewaySession.isLoopbackHost("localhost"))

    @Test
    fun `isLoopbackHost - 127_0_0_1`() = assertTrue(GatewaySession.isLoopbackHost("127.0.0.1"))

    @Test
    fun `isLoopbackHost - IPv6 loopback`() = assertTrue(GatewaySession.isLoopbackHost("::1"))

    @Test
    fun `isLoopbackHost - 0_0_0_0`() = assertTrue(GatewaySession.isLoopbackHost("0.0.0.0"))

    @Test
    fun `isLoopbackHost - Android emulator host loopback`() = assertTrue(GatewaySession.isLoopbackHost("10.0.2.2"))

    @Test
    fun `isLoopbackHost - public domain`() = assertFalse(GatewaySession.isLoopbackHost("example.com"))

    @Test
    fun `isLoopbackHost - null returns false`() = assertFalse(GatewaySession.isLoopbackHost(null))

    @Test
    fun `isLoopbackHost - empty string returns false`() = assertFalse(GatewaySession.isLoopbackHost(""))

    // ---------------------------------------------------------------------------
    // resolveInvokeResultAckTimeoutMs
    // ---------------------------------------------------------------------------

    @Test
    fun `resolveInvokeResultAckTimeoutMs - null returns 15s`() {
        assertEquals(15_000L, GatewaySession.resolveInvokeResultAckTimeoutMs(null))
    }

    @Test
    fun `resolveInvokeResultAckTimeoutMs - short timeout floored at 15s`() {
        // 1000 + 5000 = 6000 < 15000 → 15000
        assertEquals(15_000L, GatewaySession.resolveInvokeResultAckTimeoutMs(1_000L))
    }

    @Test
    fun `resolveInvokeResultAckTimeoutMs - typical timeout adds 5s`() {
        // 30000 + 5000 = 35000, clamped to max 120000 → 35000
        assertEquals(35_000L, GatewaySession.resolveInvokeResultAckTimeoutMs(30_000L))
    }

    @Test
    fun `resolveInvokeResultAckTimeoutMs - very long timeout capped at 120s`() {
        // 200000 + 5000 = 205000 > 120000 → 120000
        assertEquals(120_000L, GatewaySession.resolveInvokeResultAckTimeoutMs(200_000L))
    }

    @Test
    fun `resolveInvokeResultAckTimeoutMs - exactly 10s becomes 15s`() {
        // 10000 + 5000 = 15000 → exactly 15000
        assertEquals(15_000L, GatewaySession.resolveInvokeResultAckTimeoutMs(10_000L))
    }

    // ---------------------------------------------------------------------------
    // normalizeCanvasHostUrl — loopback / no raw URL (fallback path)
    //
    // TLS heuristic: tls = isTlsConnection || endpoint.port == 443 || endpoint.host.contains(".")
    // Note: any dotted IP (192.168.x.x) also satisfies the dot heuristic → tls=true.
    // Use a hostname without dots (e.g. "myserver") to force non-TLS via heuristic.
    // ---------------------------------------------------------------------------

    @Test
    fun `normalizeCanvasHostUrl - null raw uses endpoint host plain hostname without TLS`() {
        // "myserver" has no dots, port != 443, isTlsConnection=false → tls=false
        val ep = endpoint("myserver", 18789)
        val result = GatewaySession.normalizeCanvasHostUrl(null, ep, isTlsConnection = false)
        assertEquals("http://myserver:18789", result)
    }

    @Test
    fun `normalizeCanvasHostUrl - null raw uses endpoint host with explicit TLS`() {
        val ep = endpoint("example.com", 443)
        val result = GatewaySession.normalizeCanvasHostUrl(null, ep, isTlsConnection = true)
        // https port 443 → omitted
        assertEquals("https://example.com", result)
    }

    @Test
    fun `normalizeCanvasHostUrl - null raw prefers tailnetDns over lanHost`() {
        val ep = endpoint("myserver", 18789, lanHost = "lan", tailnetDns = "tailnet")
        val result = GatewaySession.normalizeCanvasHostUrl(null, ep, isTlsConnection = false)
        assertEquals("http://tailnet:18789", result)
    }

    @Test
    fun `normalizeCanvasHostUrl - null raw falls back to lanHost when tailnetDns absent`() {
        val ep = endpoint("myserver", 18789, lanHost = "lan")
        val result = GatewaySession.normalizeCanvasHostUrl(null, ep, isTlsConnection = false)
        assertEquals("http://lan:18789", result)
    }

    @Test
    fun `normalizeCanvasHostUrl - loopback raw uses endpoint plain host`() {
        // isTlsConnection=false, host "myserver" (no dots) → tls=false
        val ep = endpoint("myserver", 18789)
        val result = GatewaySession.normalizeCanvasHostUrl("http://127.0.0.1:18789", ep, isTlsConnection = false)
        assertEquals("http://myserver:18789", result)
    }

    @Test
    fun `normalizeCanvasHostUrl - localhost raw uses endpoint domain host`() {
        // "myserver.local" has dots → tls=true via heuristic
        val ep = endpoint("myserver.local", 18789)
        val result = GatewaySession.normalizeCanvasHostUrl("http://localhost:18789", ep, isTlsConnection = false)
        // tls=true → fallbackScheme=https, fallbackPort=endpoint.port=18789
        assertEquals("https://myserver.local:18789", result)
    }

    // ---------------------------------------------------------------------------
    // normalizeCanvasHostUrl — non-loopback raw URL (TLS rewrite path)
    // ---------------------------------------------------------------------------

    @Test
    fun `normalizeCanvasHostUrl - non-loopback raw non-443 port rewritten to 443 when TLS`() {
        val ep = endpoint("example.com", 443)
        val result = GatewaySession.normalizeCanvasHostUrl(
            "http://canvas.internal:18789",
            ep,
            isTlsConnection = true,
        )
        assertEquals("https://canvas.internal", result)
    }

    @Test
    fun `normalizeCanvasHostUrl - non-loopback raw already 443 returned as-is`() {
        val ep = endpoint("example.com", 443)
        val result = GatewaySession.normalizeCanvasHostUrl(
            "https://canvas.internal:443",
            ep,
            isTlsConnection = true,
        )
        assertEquals("https://canvas.internal:443", result)
    }

    @Test
    fun `normalizeCanvasHostUrl - non-loopback raw no-TLS endpoint returned unchanged`() {
        // host "myserver" (no dots) + non-443 port + isTlsConnection=false → tls=false
        val ep = endpoint("myserver", 18789)
        val raw = "http://canvas.remote:9000"
        val result = GatewaySession.normalizeCanvasHostUrl(raw, ep, isTlsConnection = false)
        assertEquals(raw, result)
    }

    @Test
    fun `normalizeCanvasHostUrl - path and query preserved after TLS rewrite`() {
        val ep = endpoint("example.com", 443)
        val result = GatewaySession.normalizeCanvasHostUrl(
            "http://canvas.internal:18789/ui?token=abc",
            ep,
            isTlsConnection = true,
        )
        assertEquals("https://canvas.internal/ui?token=abc", result)
    }

    @Test
    fun `normalizeCanvasHostUrl - TLS inferred from dot in endpoint host rewrites to 443`() {
        // "my.gateway.com" has dots → tls=true even without isTlsConnection
        val ep = endpoint("my.gateway.com", 18789)
        val result = GatewaySession.normalizeCanvasHostUrl(
            "http://canvas.internal:18789",
            ep,
            isTlsConnection = false,
        )
        // tls=true → rewrite non-loopback port to 443
        assertEquals("https://canvas.internal", result)
    }

    @Test
    fun `normalizeCanvasHostUrl - blank raw returns null`() {
        val ep = endpoint("", 18789)
        assertNull(GatewaySession.normalizeCanvasHostUrl("   ", ep))
    }
}
