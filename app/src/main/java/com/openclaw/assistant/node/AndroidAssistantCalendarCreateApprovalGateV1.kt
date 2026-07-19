package com.openclaw.assistant.node

import android.content.Context
import com.openclaw.assistant.broker.AssistantCalendarCreateApprovalPromptV1
import com.openclaw.assistant.broker.AssistantCalendarCreateApprovalRegistryV1
import com.openclaw.assistant.broker.AssistantCalendarCreateApprovalsV1
import com.openclaw.assistant.broker.AssistantCalendarCreatePreparationV1

internal class AndroidAssistantCalendarCreateApprovalGateV1(
    context: Context,
    private val registry: AssistantCalendarCreateApprovalRegistryV1 = AssistantCalendarCreateApprovalsV1.registry,
) : AssistantCalendarCreateApprovalGateV1 {
    private val appContext = context.applicationContext

    override suspend fun request(prepared: AssistantCalendarCreatePreparationV1.Ready): Boolean {
        val ticket = registry.register(prepared) ?: return false
        val proposalId = ticket.proposalId
        if (!AssistantCalendarCreateApprovalPromptV1.present(appContext, proposalId)) {
            registry.cancel(proposalId)
            return false
        }
        return try {
            registry.await(ticket)
        } finally {
            AssistantCalendarCreateApprovalPromptV1.cancel(appContext, proposalId)
        }
    }
}
