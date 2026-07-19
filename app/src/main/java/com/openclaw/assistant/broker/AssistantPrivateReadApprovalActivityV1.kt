package com.openclaw.assistant.broker

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.openclaw.assistant.R
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class AssistantPrivateReadApprovalActivityV1 : ComponentActivity() {
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
        val pending = AssistantPrivateReadApprovals.registry.snapshot(proposalId)
        if (pending == null) {
            finish()
            return
        }
        val message = when (pending.capability) {
            AssistantCapabilityV1.ANDROID_CONTACTS_SEARCH -> {
                val query = pending.arguments["query"]?.jsonPrimitive?.content.orEmpty()
                val limit = pending.arguments["limit"]?.jsonPrimitive?.intOrNull ?: 5
                getString(R.string.assistant_private_read_contacts_message, query, limit)
            }
            else -> getString(R.string.assistant_private_read_generic_message, pending.capability.wireName)
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.assistant_private_read_title)
            .setMessage(message)
            .setPositiveButton(R.string.assistant_private_read_allow) { _, _ -> respond(true) }
            .setNegativeButton(R.string.assistant_private_read_deny) { _, _ -> respond(false) }
            .setOnCancelListener { respond(false) }
            .create()
            .also { it.show() }
    }

    private fun respond(approved: Boolean) {
        if (responded) return
        responded = true
        if (proposalId.isNotBlank()) {
            AssistantPrivateReadApprovals.registry.respond(proposalId, approved)
            AssistantPrivateReadApprovalPromptV1.cancel(this, proposalId)
        }
        finish()
    }

    companion object {
        const val EXTRA_PROPOSAL_ID = "assistant.privateRead.proposalId"
    }
}
