package com.openclaw.assistant.broker

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.openclaw.assistant.R

internal class AssistantContactCallApprovalActivityV1 : ComponentActivity() {
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
        proposalId = intent?.getStringExtra(EXTRA_PROPOSAL_ID).orEmpty()
        responded = false
        if (proposalId.isBlank() || keyguardManager.isDeviceLocked) {
            respond(false)
            return
        }
        val pending = AssistantContactCallApprovalsV1.registry.snapshot(proposalId)
        if (pending == null) {
            finish()
            return
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.assistant_contact_call_title)
            .setMessage(
                getString(
                    R.string.assistant_contact_call_message,
                    pending.displayName,
                    pending.phoneNumber,
                ),
            )
            .setPositiveButton(R.string.assistant_contact_call_approve) { _, _ -> respond(true) }
            .setNegativeButton(R.string.assistant_contact_call_deny) { _, _ -> respond(false) }
            .setOnCancelListener { respond(false) }
            .create()
            .also { it.show() }
    }

    private fun respond(approved: Boolean) {
        if (responded) return
        responded = true
        if (proposalId.isNotBlank()) {
            AssistantContactCallApprovalsV1.registry.respond(proposalId, approved)
            AssistantContactCallApprovalPromptV1.cancel(this, proposalId)
        }
        finish()
    }

    companion object {
        const val EXTRA_PROPOSAL_ID = "assistant.contactCall.proposalId"
    }
}
