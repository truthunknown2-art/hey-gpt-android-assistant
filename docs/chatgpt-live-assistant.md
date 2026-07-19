# Hey GPT: persistent OpenClaw voice

This fork makes **Hey GPT** an OpenClaw-owned voice conversation. The phone
handles wake detection, speech recognition, and speech playback; the paired
OpenClaw Gateway supplies the GPT agent, persistent history, read-only web tools,
and two narrowly scoped Android tools. This path uses the Gateway's existing
OpenAI OAuth configuration and does not enable the paid OpenAI Realtime API.

The official consumer ChatGPT Live app remains available through an explicit
button in Wake Word settings. It is a separate conversation and does not share
OpenClaw history or node tools.

## What to say

- Unlocked or trusted-unlocked: say **Hey GPT**, then speak normally. The app
  enters a continuous `voice-main` session. After each spoken answer it listens
  for the next turn without another wake phrase.
- Securely locked: say **Hey GPT**, wait for the acknowledgement tone, then ask
  one question. The question uses a persistent, tool-free `locked-voice` agent.
  Repeat **Hey GPT** for another locked turn.
- **Hey Claw** and **Hey Hermes** retain their configured random/resume and
  continuous-mode settings.

The spelling `g p t` in settings is intentional for the bundled English Vosk
model; say it naturally as "GPT."

## Why this is not consumer ChatGPT Live

Android does not provide a supported way for one ordinary app to proxy another
app's microphone and voice output as a reliable full-duplex conversation. The
consumer ChatGPT app also does not expose its Live session, memory, or connected
OpenClaw node tools to this app. Exact OpenAI Live audio requires the OpenAI
Realtime Platform API and Platform billing; a ChatGPT subscription is not a
free Realtime audio transport.

The no-Realtime architecture is therefore:

```text
Vosk wake word
  -> Android SpeechRecognizer
  -> correlated OpenClaw Gateway chat turn
  -> isolated voice-main agent with four baseline tools plus gated signed tools
  -> Android TTS
  -> continuous listening
```

Local or Gateway-hosted TTS such as Kokoro/Pocket-TTS can replace Android TTS
later without changing the agent/session architecture.

`HotwordService` owns the complete unlocked voice lifecycle after the wake:
Android recognition, the correlated Gateway turn, TTS, lock monitoring, and the
return to Vosk. The translucent activity is display-only. If One UI blocks that
background activity, the conversation continues by audio instead of failing.
This also lets ChatGPT remain Android's selected digital assistant for the
separate official Live button.

## One-time phone setup

1. Install the APK and complete Gateway pairing.
2. Grant microphone and notification permission.
3. Enable notification-listener access if notification reading is wanted.
4. Contacts, SMS, location, calendar, camera, and accessibility are not required
   by the `voice-main` tool policy.
5. Set the Hey GPT wake phrase to `hey g p t` and enable wake-word detection.
6. On Samsung, allow background activity, set battery use to Unrestricted, and
   exclude the app from Sleeping apps.

The official ChatGPT app and its assistant/accessibility trigger are optional.
Use **Open official ChatGPT Live** in Wake Word settings when a separate
consumer Live conversation is specifically wanted.

## Gateway setup

The phone and Gateway must share a private network path, normally LAN or
Tailscale. Generate and scan a mobile setup code:

```powershell
wsl.exe -d OpenClawGateway -- openclaw qr --public-url ws://WINDOWS_LAN_IP:18789
```

Treat the setup code like a password. For a WSL LAN deployment, refresh the
Windows-to-WSL forwarding rule after a networking reset:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\configure-openclaw-wsl-port.ps1
```

For a non-loopback Gateway, set `gateway.controlUi.allowedOrigins` to the exact
LAN and tailnet URLs used to open the dashboard, and configure
`gateway.auth.rateLimit`. Do not enable Host-header origin fallback. Confirm the
result with `openclaw security audit --deep`.

Build and install this fork before configuring the voice agents. The custom
Android node must be connected and advertise both `media.play_search` and
`notifications.list_package`; the configuration script fails closed otherwise.

Create the curated unlocked voice agent and the secure-lock agent once:

```powershell
.\scripts\configure-voice-main-agent.ps1
.\scripts\configure-locked-voice-agent.ps1
```

The baseline `voice-main` agent has exactly four tools: `web_search`, `web_fetch`,
`android_media_play`, and `messenger_notifications_read`. The Android wrappers
are bound to one configured node ID and own the command and package selection;
the model cannot choose an arbitrary node command, notification action, or app.
The agent cannot access generic browser, node, memory, shell/runtime, filesystem,
messaging, Gateway administration, scheduling, or cross-session tools.

After the signed contact slice is installed and physically trusted, enable it
with:

```powershell
.\scripts\enable-assistant-contact-search.ps1
```

This adds exactly one semantic tool, `assistant_contacts_search`, while keeping
generic `nodes` denied. The first lookup in an unlocked voice session presents
an exact on-phone approval for a 10-minute, session-bound private-read grant.
Matching names and numbers are spoken by the phone and never returned to the
model, Gateway transcript, tool `details`, or durable receipt.

The locked agent must retain an empty effective tool list. It cannot browse,
execute shell commands, read private stores, send messages, or invoke Android
actions.

## Sessions and turn correlation

Unlocked Hey GPT derives a stable key from the app's durable device identity:

```text
agent:voice-main:voice-android-<sanitized-device-id>
```

The key overrides `resumeLatestSession` only for this voice profile and does not
switch the app's normal chat session. The route is always continuous; other wake
targets retain their configured behavior.

Each turn performs a baseline `chat.history`, sends a unique idempotency key,
records the server `runId`, and accepts only the assistant mirror correlated to
that exact prompt. Timeout or cancellation aborts that exact run. This prevents
old history, bounded history, another client, or a delayed run from being spoken
as the current answer.

## Android tools

The unlocked `voice-main` agent does not receive OpenClaw's generic `nodes` tool.
It receives two optional plugin tools that fail closed against one configured,
connected Android node:

- `android_media_play` invokes only `media.play_search` with Spotify fixed as
  the package. For exact tracks, the model resolves a public Spotify track page
  and supplies only a strict `spotify:track:<22-character-id>` plus bounded title
  and artist fields. Android uses the user-authorized Spotify App Remote SDK and
  reports success only after observing an unpaused PlayerState with matching URI
  or metadata. The public client ID is configured locally on the phone; no client
  secret or Web API token reaches OpenClaw. Structured media-session search
  remains an unconfirmed fallback for non-exact requests.

Spotify exact-track playback needs one-time phone setup under **Settings >
Spotify control**. Register the displayed Android package and SHA-1 fingerprint
in Spotify's Developer Dashboard, allowlist the displayed redirect URI, paste
the public client ID, and tap **Save and authorize**. The App Remote SDK requests
only Spotify's built-in remote-control authorization.
- `messenger_notifications_read` invokes only `notifications.list_package`.
  Android filters to `com.facebook.orca` before returning sender, a bounded text
  preview, and timestamp. Up to 100 previews are retained in app-private storage
  for seven days; at most 20 are returned. Expired previews are deleted on read,
  notification-listener startup, and a scheduled next-expiry cleanup. Notification
  keys, package names, and actions never reach the model.
- `assistant_contacts_search` invokes only the broker's signed presence and
  execute commands. The broker fixes the phone identity and live voice session,
  and the Android executor owns approval, provider access, and private speech.

Read-only web requests use `web_search` and `web_fetch`. Hosted search is
pinned to OpenClaw's managed `codex` provider and its bounded hosted-search
worker, using the existing OpenAI/Codex authentication; there is no generic
browser control in the unlocked voice policy.

Notification access is not a Messenger inbox API. It can answer questions such
as "What is the latest Messenger notification from Sam?" for previews captured
during the retention window, including after notification dismissal. It cannot
recover earlier chats that never generated a captured notification. Full inbox
history would require a separate approved integration.

Windows Phone Link does not automatically become an OpenClaw tool. It can remain
a manual convenience; automating it would require a separately connected
Windows node or a narrowly scoped desktop capability.

`phone.call` remains unavailable to remote agent sessions until an explicit
on-device confirmation flow exists.

## Lock transition policy

The unlocked `voice-main` agent has tools, so its session is valid only while
`KeyguardManager.isDeviceLocked == false`. The app checks before listening,
after final recognition, immediately before `chat.send`, before each TTS chunk,
after screen-off settling, and with an active-session monitor.

If a secure lock appears, the app discards captured speech, cancels listening
and TTS, aborts and joins the in-flight correlated Gateway run, then waits for
Android STT and TTS audio resources to be released. It publishes the voice session
as inactive, performs the bounded synchronous hotword-restart attempt while the
wake lock is still held, and releases the wake lock last. It never forwards that
speech into the locked agent because doing so would change both context and
authorization policy.

Trusted-unlocked screen-off remains valid while Android reports the device as
not securely locked. A later transition to secure lock terminates the main lane.

## Acceptance checklist

- Unlocked Hey GPT starts the OpenClaw voice session, not the ChatGPT app; the
  display-only overlay is optional and blocked UI continues headlessly.
- A second turn without another wake phrase retains the first turn's context.
- A later Hey GPT wake on the same installation reuses the same main voice key.
- "Play a Spotify song" sends structured track/artist fields to the node
  advertising `media.play_search` and physically starts the requested track.
- A Messenger preview remains readable after notification dismissal when
  notification access was enabled when it arrived.
- Securely locked Hey GPT uses only `agent:locked-voice:*` and cannot call tools.
- Locking during listening, request processing, or TTS ends the main session.
- The explicit Live button opens the official ChatGPT app exactly once.
- Closing a session releases the mic and resumes wake-word detection.

## Build and smoke test

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:FIREBASE_ENABLED = 'false'
& 'C:\Program Files\Git\bin\bash.exe' -lc `
  "./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug --no-daemon --max-workers=1"
```
