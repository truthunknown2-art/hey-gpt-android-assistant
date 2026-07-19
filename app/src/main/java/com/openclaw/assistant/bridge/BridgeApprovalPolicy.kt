package com.openclaw.assistant.bridge

import com.openclaw.assistant.bridge.grants.DestructiveVerbs

internal object BridgeApprovalPolicy {
    fun requiresPrompt(
        mode: BridgeApprovalMode,
        riskLevel: RiskLevel,
        capability: String,
    ): Boolean {
        if (riskLevel == RiskLevel.HIGH || DestructiveVerbs.isDestructive(capability)) {
            return true
        }
        return when (mode) {
            BridgeApprovalMode.ALWAYS_CONFIRM -> true
            BridgeApprovalMode.CONFIRM_MEDIUM_HIGH -> riskLevel == RiskLevel.MEDIUM
            BridgeApprovalMode.TRUSTED -> false
        }
    }

    fun allowsReusableGrant(riskLevel: RiskLevel, capability: String): Boolean =
        riskLevel != RiskLevel.HIGH && !DestructiveVerbs.isDestructive(capability)
}
