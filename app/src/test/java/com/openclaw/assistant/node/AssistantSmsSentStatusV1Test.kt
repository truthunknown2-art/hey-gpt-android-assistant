package com.openclaw.assistant.node

import android.app.Activity
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSmsSentStatusV1Test {
    @Test
    fun `all unique parts must succeed before sent is confirmed`() = runTest {
        val registry = AssistantSmsSentStatusRegistryV1()
        val ticket = registry.register("operation", 2)!!
        val result = async { registry.await(ticket) }

        assertTrue(registry.report("operation", 0, Activity.RESULT_OK))
        assertFalse(result.isCompleted)
        assertFalse(registry.report("operation", 0, Activity.RESULT_OK))
        assertTrue(registry.report("operation", 1, Activity.RESULT_OK))
        assertEquals(AssistantSmsCarrierResultV1.SENT, result.await())
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun `one failed part fails the operation and later callbacks are ignored`() = runTest {
        val registry = AssistantSmsSentStatusRegistryV1()
        val ticket = registry.register("operation", 2)!!
        val result = async { registry.await(ticket) }

        assertTrue(registry.report("operation", 0, Activity.RESULT_OK))
        assertTrue(registry.report("operation", 1, Activity.RESULT_CANCELED))
        assertEquals(AssistantSmsCarrierResultV1.FAILED, result.await())
        assertFalse(registry.report("operation", 1, Activity.RESULT_OK))
    }

    @Test
    fun `revocation makes an in-flight carrier result unknown`() = runTest {
        val registry = AssistantSmsSentStatusRegistryV1()
        val ticket = registry.register("operation", 1)!!
        val result = async { registry.await(ticket) }

        registry.revokeAll()

        assertEquals(AssistantSmsCarrierResultV1.UNKNOWN, result.await())
        assertEquals(0, registry.pendingCount())
    }
}
