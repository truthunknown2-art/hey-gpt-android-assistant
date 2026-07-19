package com.openclaw.assistant.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.openclaw.assistant.R
import com.openclaw.assistant.node.NodeRuntime

@Composable
fun AssistantBrokerTrustDialog(
  prompt: NodeRuntime.AssistantBrokerTrustPrompt,
  onAccept: () -> Unit,
  onDecline: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDecline,
    title = {
      Text(
        stringResource(
          if (prompt.changed) {
            R.string.assistant_broker_changed_title
          } else {
            R.string.assistant_broker_trust_title
          },
        ),
      )
    },
    text = {
      Text(
        if (prompt.changed) {
          stringResource(
            R.string.assistant_broker_changed_message,
            prompt.currentFingerprintSha256.orEmpty(),
            prompt.candidateFingerprintSha256,
          )
        } else {
          stringResource(
            R.string.assistant_broker_trust_message,
            prompt.candidateFingerprintSha256,
          )
        },
      )
    },
    confirmButton = {
      TextButton(onClick = if (prompt.changed) onDecline else onAccept) {
        Text(
          stringResource(
            if (prompt.changed) R.string.close else R.string.assistant_broker_trust_confirm,
          ),
        )
      }
    },
    dismissButton = if (prompt.changed) {
      null
    } else {
      {
        TextButton(onClick = onDecline) {
          Text(stringResource(R.string.assistant_broker_trust_deny))
        }
      }
    },
  )
}
