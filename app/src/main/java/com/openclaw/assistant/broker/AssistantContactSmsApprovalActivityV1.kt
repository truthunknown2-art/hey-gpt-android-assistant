package com.openclaw.assistant.broker

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.openclaw.assistant.R

internal class AssistantContactSmsApprovalActivityV1 : ComponentActivity() {
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private var proposalId = ""
    private var responded = false
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setFinishOnTouchOutside(false)
        showProposal(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showProposal(intent)
    }

    override fun onResume() {
        super.onResume()
        if (keyguardManager.isDeviceLocked) respond(false)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        if (isFinishing && !responded) respond(false)
        super.onDestroy()
    }

    private fun showProposal(intent: Intent?) {
        dialog?.dismiss()
        if (proposalId.isNotBlank() && !responded) {
            AssistantContactSmsApprovalsV1.registry.respond(proposalId, false)
            AssistantContactSmsApprovalPromptV1.cancel(this, proposalId)
        }
        proposalId = intent?.getStringExtra(EXTRA_PROPOSAL_ID).orEmpty()
        responded = false
        if (proposalId.isBlank() || keyguardManager.isDeviceLocked) {
            respond(false)
            return
        }
        val pending = AssistantContactSmsApprovalsV1.registry.snapshot(proposalId)
        if (pending == null) {
            finish()
            return
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.assistant_contact_sms_title)
            .setMessage(
                getString(
                    R.string.assistant_contact_sms_message,
                    pending.displayName,
                    pending.phoneNumber,
                    pending.message,
                ),
            )
            .setPositiveButton(R.string.assistant_contact_sms_approve) { _, _ -> respond(true) }
            .setNegativeButton(R.string.assistant_contact_sms_deny) { _, _ -> respond(false) }
            .setOnCancelListener { respond(false) }
            .create()
            .also { it.show() }
    }

    private fun respond(approved: Boolean) {
        if (responded) return
        responded = true
        if (proposalId.isNotBlank()) {
            AssistantContactSmsApprovalsV1.registry.respond(proposalId, approved)
            AssistantContactSmsApprovalPromptV1.cancel(this, proposalId)
        }
        finish()
    }

    companion object {
        const val EXTRA_PROPOSAL_ID = "assistant.contactSms.proposalId"
    }
}
