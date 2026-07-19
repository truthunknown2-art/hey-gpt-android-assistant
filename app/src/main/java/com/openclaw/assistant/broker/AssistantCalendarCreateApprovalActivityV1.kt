package com.openclaw.assistant.broker

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.openclaw.assistant.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class AssistantCalendarCreateApprovalActivityV1 : ComponentActivity() {
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
            AssistantCalendarCreateApprovalsV1.registry.respond(proposalId, false)
            AssistantCalendarCreateApprovalPromptV1.cancel(this, proposalId)
        }
        proposalId = intent?.getStringExtra(EXTRA_PROPOSAL_ID).orEmpty()
        responded = false
        if (proposalId.isBlank() || keyguardManager.isDeviceLocked) {
            respond(false)
            return
        }
        val pending = AssistantCalendarCreateApprovalsV1.registry.snapshot(proposalId)
        if (pending == null) {
            finish()
            return
        }
        val schedule = renderSchedule(pending) ?: run {
            respond(false)
            return
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.assistant_calendar_create_title)
            .setMessage(
                getString(
                    R.string.assistant_calendar_create_message,
                    pending.title,
                    pending.calendarDisplayName,
                    schedule,
                ),
            )
            .setPositiveButton(R.string.assistant_calendar_create_approve) { _, _ -> respond(true) }
            .setNegativeButton(R.string.assistant_calendar_create_deny) { _, _ -> respond(false) }
            .setOnCancelListener { respond(false) }
            .create()
            .also { it.show() }
    }

    private fun renderSchedule(pending: AssistantCalendarCreateApprovalSnapshotV1): String? {
        val zone = ZoneId.systemDefault()
        val start = runCatching { Instant.ofEpochMilli(pending.startEpochMs).atZone(zone) }.getOrNull() ?: return null
        val end = runCatching { Instant.ofEpochMilli(pending.endEpochMs).atZone(zone) }.getOrNull() ?: return null
        return if (pending.allDay) {
            val inclusiveEnd = end.toLocalDate().minusDays(1)
            if (inclusiveEnd == start.toLocalDate()) {
                getString(R.string.assistant_calendar_create_all_day, DATE_FORMAT.format(start))
            } else {
                getString(
                    R.string.assistant_calendar_create_all_day_range,
                    DATE_FORMAT.format(start),
                    inclusiveEnd.format(DATE_FORMAT),
                )
            }
        } else {
            getString(
                R.string.assistant_calendar_create_timed,
                DATE_TIME_FORMAT.format(start),
                DATE_TIME_FORMAT.format(end),
            )
        }
    }

    private fun respond(approved: Boolean) {
        if (responded) return
        responded = true
        if (proposalId.isNotBlank()) {
            AssistantCalendarCreateApprovalsV1.registry.respond(proposalId, approved)
            AssistantCalendarCreateApprovalPromptV1.cancel(this, proposalId)
        }
        finish()
    }

    companion object {
        const val EXTRA_PROPOSAL_ID = "assistant.calendarCreate.proposalId"
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault())
        private val DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
    }
}
