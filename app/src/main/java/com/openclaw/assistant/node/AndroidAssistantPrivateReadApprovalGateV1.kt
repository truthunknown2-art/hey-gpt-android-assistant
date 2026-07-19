package com.openclaw.assistant.node

import android.content.Context
import com.openclaw.assistant.broker.AssistantPrivateReadApprovalPromptV1
import com.openclaw.assistant.broker.AssistantPrivateReadApprovalRegistryV1
import com.openclaw.assistant.broker.AssistantPrivateReadApprovals
import com.openclaw.assistant.broker.SignedAssistantProposalV1

internal class AndroidAssistantPrivateReadApprovalGateV1(
    context: Context,
    private val registry: AssistantPrivateReadApprovalRegistryV1 = AssistantPrivateReadApprovals.registry,
) : AssistantPrivateReadApprovalGateV1 {
    private val appContext = context.applicationContext

    override suspend fun request(signed: SignedAssistantProposalV1): Boolean {
        val ticket = registry.register(signed) ?: return false
        val proposal = signed.proposal
        if (!AssistantPrivateReadApprovalPromptV1.present(
                appContext,
                proposal.proposalId,
                proposal.capability,
            )
        ) {
            registry.cancel(proposal.proposalId)
            return false
        }
        return try {
            registry.await(ticket)
        } finally {
            AssistantPrivateReadApprovalPromptV1.cancel(appContext, proposal.proposalId)
        }
    }
}
