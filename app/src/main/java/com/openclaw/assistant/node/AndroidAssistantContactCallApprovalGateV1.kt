package com.openclaw.assistant.node

import android.content.Context
import com.openclaw.assistant.broker.AssistantContactCallApprovalPromptV1
import com.openclaw.assistant.broker.AssistantContactCallApprovalRegistryV1
import com.openclaw.assistant.broker.AssistantContactCallApprovalsV1
import com.openclaw.assistant.broker.AssistantContactCallPreparationV1

internal class AndroidAssistantContactCallApprovalGateV1(
    context: Context,
    private val registry: AssistantContactCallApprovalRegistryV1 = AssistantContactCallApprovalsV1.registry,
) : AssistantContactCallApprovalGateV1 {
    private val appContext = context.applicationContext

    override suspend fun request(prepared: AssistantContactCallPreparationV1.Ready): Boolean {
        val ticket = registry.register(prepared) ?: return false
        val proposalId = prepared.proposal.proposalId
        if (!AssistantContactCallApprovalPromptV1.present(appContext, proposalId)) {
            registry.cancel(proposalId)
            return false
        }
        return try {
            registry.await(ticket)
        } finally {
            AssistantContactCallApprovalPromptV1.cancel(appContext, proposalId)
        }
    }
}
