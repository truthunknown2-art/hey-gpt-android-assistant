package com.openclaw.assistant.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeApprovalPolicyTest {
    @Test fun `trusted mode still prompts for every high-risk action`() {
        assertTrue(
            BridgeApprovalPolicy.requiresPrompt(
                BridgeApprovalMode.TRUSTED,
                RiskLevel.HIGH,
                "calendar.create",
            ),
        )
        assertFalse(BridgeApprovalPolicy.allowsReusableGrant(RiskLevel.HIGH, "calendar.create"))
    }

    @Test fun `destructive names prompt and cannot reuse grants regardless of declared risk`() {
        assertTrue(
            BridgeApprovalPolicy.requiresPrompt(
                BridgeApprovalMode.TRUSTED,
                RiskLevel.LOW,
                "sms.send",
            ),
        )
        assertFalse(BridgeApprovalPolicy.allowsReusableGrant(RiskLevel.LOW, "sms.send"))
    }

    @Test fun `trusted mode may bypass ordinary medium reads`() {
        assertFalse(
            BridgeApprovalPolicy.requiresPrompt(
                BridgeApprovalMode.TRUSTED,
                RiskLevel.MEDIUM,
                "notifications.list",
            ),
        )
        assertTrue(BridgeApprovalPolicy.allowsReusableGrant(RiskLevel.MEDIUM, "notifications.list"))
    }
}
