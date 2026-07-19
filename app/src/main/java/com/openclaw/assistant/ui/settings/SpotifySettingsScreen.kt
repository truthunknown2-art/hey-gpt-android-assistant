package com.openclaw.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.node.SpotifyAppRemoteConfig
import com.openclaw.assistant.node.SpotifyAppRemoteController
import com.openclaw.assistant.node.SpotifyPlaybackRequest
import kotlinx.coroutines.launch
import java.security.MessageDigest

@Composable
internal fun SpotifySettingsScreen(settings: SettingsRepository) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val controller = remember(context.applicationContext) {
    SpotifyAppRemoteController(context.applicationContext)
  }
  var clientId by rememberSaveable { mutableStateOf(settings.spotifyClientId) }
  var busy by rememberSaveable { mutableStateOf(false) }
  var statusMessage by rememberSaveable { mutableStateOf("") }
  var statusIsError by rememberSaveable { mutableStateOf(false) }
  val normalizedClientId = SpotifyAppRemoteConfig.normalizeClientId(clientId)
  val validClientId = SpotifyAppRemoteConfig.isValidClientId(normalizedClientId)
  val packageName = context.packageName
  val fingerprint = remember(packageName) { signingSha1Fingerprint(context) }

  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(
      text = stringResource(R.string.spotify_setup_intro),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedButton(
      onClick = {
        runCatching {
          context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(SpotifyAppRemoteConfig.DASHBOARD_URL)),
          )
        }.onFailure {
          statusIsError = true
          statusMessage = context.getString(R.string.spotify_dashboard_failed)
        }
      },
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(R.string.spotify_open_dashboard))
    }

    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
      ),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        SpotifySetupValue(
          label = stringResource(R.string.spotify_package_name),
          value = packageName,
          onCopy = { copyToClipboard(context, "Spotify package", packageName) },
        )
        SpotifySetupValue(
          label = stringResource(R.string.spotify_signing_fingerprint),
          value = fingerprint,
          onCopy = { copyToClipboard(context, "Spotify fingerprint", fingerprint) },
        )
        SpotifySetupValue(
          label = stringResource(R.string.spotify_redirect_uri),
          value = SpotifyAppRemoteConfig.REDIRECT_URI,
          onCopy = {
            copyToClipboard(
              context,
              "Spotify redirect URI",
              SpotifyAppRemoteConfig.REDIRECT_URI,
            )
          },
        )
      }
    }

    OutlinedTextField(
      value = clientId,
      onValueChange = {
        clientId = it.take(MAX_CLIENT_ID_INPUT_LENGTH)
        statusMessage = ""
      },
      label = { Text(stringResource(R.string.spotify_client_id)) },
      placeholder = { Text(stringResource(R.string.spotify_client_id_hint)) },
      supportingText = {
        if (clientId.isNotBlank() && !validClientId) {
          Text(stringResource(R.string.spotify_client_id_invalid))
        }
      },
      isError = clientId.isNotBlank() && !validClientId,
      singleLine = true,
      modifier = Modifier.fillMaxWidth(),
    )

    Button(
      onClick = {
        settings.spotifyClientId = normalizedClientId
        busy = true
        statusIsError = false
        statusMessage = context.getString(R.string.spotify_authorizing)
        scope.launch {
          runCatching { controller.authorize(normalizedClientId) }
            .onSuccess {
              statusMessage = context.getString(R.string.spotify_authorized)
            }
            .onFailure {
              statusIsError = true
              statusMessage = context.getString(R.string.spotify_authorization_failed)
            }
          busy = false
        }
      },
      enabled = validClientId && !busy,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(R.string.spotify_save_authorize))
    }

    OutlinedButton(
      onClick = {
        settings.spotifyClientId = normalizedClientId
        busy = true
        statusIsError = false
        statusMessage = context.getString(R.string.spotify_testing)
        scope.launch {
          runCatching {
            controller.playTrack(
              clientId = normalizedClientId,
              request = SpotifyPlaybackRequest(
                query = "Alive Pearl Jam",
                title = "Alive",
                artist = "Pearl Jam",
                spotifyTrackUri = TEST_TRACK_URI,
              ),
            )
          }.onSuccess { receipt ->
            statusIsError = !receipt.playbackConfirmed
            statusMessage = context.getString(
              if (receipt.playbackConfirmed) {
                R.string.spotify_test_confirmed
              } else {
                R.string.spotify_test_unconfirmed
              },
            )
          }.onFailure {
            statusIsError = true
            statusMessage = context.getString(R.string.spotify_authorization_failed)
          }
          busy = false
        }
      },
      enabled = validClientId && !busy,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(stringResource(R.string.spotify_test_playback))
    }

    if (statusMessage.isNotBlank()) {
      Text(
        text = statusMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = if (statusIsError) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.primary
        },
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
  }
}

@Composable
private fun SpotifySetupValue(
  label: String,
  value: String,
  onCopy: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    Text(value, style = MaterialTheme.typography.bodySmall)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      OutlinedButton(onClick = onCopy) {
        Text(stringResource(R.string.spotify_copy))
      }
    }
  }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
  val clipboard = context.getSystemService(ClipboardManager::class.java)
  clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

@Suppress("DEPRECATION")
private fun signingSha1Fingerprint(context: Context): String {
  val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    context.packageManager.getPackageInfo(
      context.packageName,
      PackageManager.GET_SIGNING_CERTIFICATES,
    )
  } else {
    context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
  }
  val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
  } else {
    packageInfo.signatures?.firstOrNull()
  } ?: return "Unavailable"
  return MessageDigest.getInstance("SHA-1")
    .digest(signature.toByteArray())
    .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }
}

private const val MAX_CLIENT_ID_INPUT_LENGTH = 64
private const val TEST_TRACK_URI = "spotify:track:4Qbjmdlv1eZDD1u8SWe1pt"
