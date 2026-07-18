package com.openclaw.assistant.chatgpt

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistTriggerAccessibilityServiceTest {
    @Test
    fun `three button mode holds center of real navigation bar`() {
        val spec = assistantGestureSpec(
            width = 1080f,
            height = 2280f,
            navigationMode = 0,
            navigationBarHeight = 144f,
        )

        assertEquals(540f, spec.startX, 0.01f)
        assertEquals(2208f, spec.startY, 0.01f)
        assertEquals(spec.startX, spec.endX, 0.01f)
        assertEquals(spec.startY, spec.endY, 0.01f)
        assertEquals(850L, spec.durationMs)
    }

    @Test
    fun `gesture navigation swipes inward from bottom corner`() {
        val spec = assistantGestureSpec(
            width = 1080f,
            height = 2280f,
            navigationMode = 2,
            navigationBarHeight = 144f,
        )

        assertEquals(21.6f, spec.startX, 0.01f)
        assertEquals(2278f, spec.startY, 0.01f)
        assertEquals(270f, spec.endX, 0.01f)
        assertEquals(1778.4f, spec.endY, 0.01f)
        assertEquals(450L, spec.durationMs)
    }
}
