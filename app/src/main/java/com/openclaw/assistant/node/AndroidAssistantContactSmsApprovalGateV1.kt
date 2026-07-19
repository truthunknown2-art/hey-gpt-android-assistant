package com.openclaw.assistant.node

import android.content.Context
import com.openclaw.assistant.broker.AssistantContactSmsApprovalPromptV1
import com.openclaw.assistant.broker.AssistantContactSmsApprovalRegistryV1
import com.openclaw.assistant.broker.AssistantContactSmsApprovalsV1
import com.openclaw.assistant.broker.AssistantContactSmsPreparationV1

internal class AndroidAssistantContactSmsApprovalGateV1(
    context: Context,
    private val registry: AssistantContactSmsApprovalRegistryV1 = AssistantContactSmsApprovalsV1.registry,
) : AssistantContactSmsApprovalGateV1 {
    private val appContext = context.applicationContext

    override suspend fun request(prepared: AssistantContactSmsPreparationV1.Ready): Boolean {
        val ticket = registry.register(prepared) ?: return false
        val proposalId = ticket.proposalId
        if (!AssistantContactSmsApprovalPromptV1.present(appContext, proposalId)) {
            registry.cancel(proposalId)
            return false
        }
        return try {
            registry.await(ticket)
        } finally {
            AssistantContactSmsApprovalPromptV1.cancel(appContext, proposalId)
        }
    }
}
