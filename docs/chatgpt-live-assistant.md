# Hey GPT: persistent OpenClaw voice

This fork makes **Hey GPT** an OpenClaw-owned voice conversation. The phone
handles wake detection, speech recognition, and speech playback; the paired
OpenClaw Gateway supplies the GPT agent, persistent history, read-only web tools,
and narrowly scoped signed Android tools. This path uses the Gateway's existing
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
4. Grant only permissions for enabled signed actions. The current contact,
   call, SMS, and calendar slices use contacts, phone, SMS, and calendar access;
   location, camera, and accessibility remain outside the `voice-main` policy.
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
Android node must be connected and advertise the fixed signed commands plus the
explicit legacy media capability; each configuration script
fails closed if its required command surface is unavailable.

Create the curated unlocked voice agent and the secure-lock agent once:

```powershell
.\scripts\configure-voice-main-agent.ps1
.\scripts\configure-locked-voice-agent.ps1
```

The baseline `voice-main` agent has exactly three tools: `web_search`, `web_fetch`,
and `android_media_play`. The Android wrapper is bound to one configured node ID
and owns the command and package selection; the model cannot choose an arbitrary
node command, notification action, or app.
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

Enable the signed private calendar slice after installing and approving the
calendar-capable helper build:

```powershell
.\scripts\enable-assistant-calendar.ps1
```

The script preserves the already enabled memory/contact/call/SMS tools and adds
`assistant_calendar_next` and `assistant_calendar_create`. Upcoming event details
are spoken only on the unlocked phone under the scoped private-read approval.
Every calendar creation shows the locally selected calendar, exact title, and
local schedule in a fresh one-shot phone approval before insertion.

Enable retained Messenger notification previews through the signed private-read
broker, never the legacy raw notification command:

```powershell
.\scripts\enable-assistant-messenger.ps1
```

The first Messenger read in an unlocked voice session presents a 10-minute
on-phone approval. Sender and preview text are spoken only on the phone; Luna
receives only count, truncation, delivery status, and the terminal receipt.

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
It receives named plugin tools that fail closed against one configured,
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
- `messenger_notifications_read` invokes only the broker-signed presence and
  execute commands. Android filters retained notification previews to
  `com.facebook.orca`, requires the scoped private-read grant, and speaks at most
  ten matching previews through the bound local voice session. Up to 100 previews
  remain in app-private storage for seven days. Sender, preview text, timestamp,
  notification keys, package names, and actions never reach the model, Gateway
  transcript, tool details, or durable receipt. The legacy
  `notifications.list_package` command is neither advertised nor dispatchable.
- `assistant_contacts_search` invokes only the broker's signed presence and
  execute commands. The broker fixes the phone identity and live voice session,
  and the Android executor owns approval, provider access, and private speech.
- `assistant_phone_call` and `assistant_sms_send` resolve the contact locally and
  require a fresh secure phone approval for the real recipient and outbound
  action. The SMS path also displays the complete message and waits for carrier
  submission callbacks.
- `assistant_calendar_next` reads a bounded upcoming window and speaks titles and
  times only on the phone. `assistant_calendar_create` requires a fresh secure
  approval for the exact calendar, title, and local schedule; only a boolean
  creation receipt returns to the model.

Read-only web requests use `web_search` and `web_fetch`. Hosted search is
pinned to OpenClaw's managed `codex` provider and its bounded hosted-search
worker, using the existing OpenAI/Codex authentication; there is no generic
browser control in the unlocked voice policy.

Notification access is not a Messenger inbox API. It can answer questions such
as "What is the latest Messenger notification from Sam?" for previews captured
during the retention window, including after notification dismissal. It cannot
recover earlier chats that never generated a captured notification. Full inbox
history would require a separate approved integration.

Windows Phone Link does not automatically become an OpenClaw tool. The deployed
Windows file bridge instead uses a dedicated scheduled-task node with one fixed
`assistant.windows.execute.v1` command. `assistant_windows_files_search` returns
only metadata and opaque root references while the bound phone voice session is
unlocked. `assistant_windows_files_read` accepts only one returned reference and
requires the phone's secure private-read approval before bounded UTF-8 content
leaves the selected root. Both tools recheck the same short-lived Android
presence lease immediately before invoking Windows; a locked phone, expired
lease, or replacement voice session fails closed. The Gateway globally denies generic node shell,
approval-management, and browser-proxy commands.

Windows references reject NTFS alternate-data-stream separators. Search and
read also require a single-link regular file and validate BigInt device/inode
identity before and after descriptor reads, so symbolic links, hard links, and
path replacement fail closed rather than bypassing the selected root.

Provision an already paired dedicated Windows node idempotently with:

```powershell
pwsh -File .\scripts\enable-assistant-windows-files.ps1 `
  -NodeId <WINDOWS_NODE_ID> `
  -AndroidNodeId <ANDROID_ASSISTANT_NODE_ID> `
  -BrokerKeyId <BROKER_PUBLIC_KEY_ID> `
  -BrokerPublicKeyBase64Url <BROKER_PUBLIC_KEY>
```

The script validates that the existing node launcher belongs to the dedicated
state directory, removes the legacy detached Gateway launch hook, and creates
one owned `\OpenClaw\Agentic Windows Node Supervisor` S4U task with a delayed
boot trigger, restart policy, and no interactive-logon dependency. The existing
node logon launcher uses Task Scheduler COM only to start that same task when it
is not already running. The persistent node supervisor retries the node after
failure and holds an exclusive state lock; Windows autologon is not required.
Provisioning rejects altered task identity, action arguments, and extra
triggers. It repairs drift in the boot delay, restart policy, execution limit,
battery policy, enabled state, and other boot-critical settings before
validating the exclusive lock and connected node command.
Provisioning terminates only process trees
rooted in that dedicated `node.cmd`, stages and tests the plugin before atomic
replacement, pins the Windows node to the Android session key's 32-character
device suffix and the broker public key, preserves only the `documents`
read-root alias by default, restarts both sides, and fails unless the connected
node advertises exactly the fixed command. Before mutation it snapshots the
Windows launchers, owned-task XML, supervisor, Windows node config, Gateway
config, and broker stage. Local task and launcher snapshots are persisted in a
transaction-scoped directory rather than held only in process memory. Any later
failure restores them before the node is restarted, then verifies restored file
hashes, scheduled-task XML, and task absence when no prior task existed.
If rollback itself fails, every available snapshot is preserved and the Windows
node remains stopped until manual recovery.

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
- A calendar read prompts once per scoped private-read session and speaks event
  details only on the phone.
- Calendar creation denial or a lock transition inserts nothing; approval
  inserts the exact event once.
- Windows filename search fails without the active unlocked voice session and
  returns metadata plus opaque references only when the session is live.
- The first Windows content read in a voice session shows the exact on-phone
  approval; denial returns no content, approval returns only bounded UTF-8 text,
  and traversal, denied names, links, binary files, and oversize reads fail.
- Contact calling and SMS require fresh on-phone approval for every outbound
  action.
- Securely locked Hey GPT uses only `agent:locked-voice:*` and cannot call tools.
- Locking during listening, request processing, or TTS ends the main session.
- The explicit Live button opens the official ChatGPT app exactly once.
- Closing a session releases the mic and resumes wake-word detection.

### Physical evidence (2026-07-19)

- Exact-head full APK `cebaac4` is installed on the S10+. The custom node is
  connected with no raw notification or Mobile Bridge commands, and Pro returned
  `READY FOR PHYSICAL MESSENGER ACCEPTANCE`; that positive on-phone approval and
  offline-spoken Messenger test remains open.
- Exact-head full APK `d168059` was installed in place on the S10+ without
  losing pairing or settings. Both unit-test variants and the full APK build
  passed before installation.
- Physical phone-speaker playback produced a real Vosk wake, Android STT
  result, Luna turn, Pocket TTS response, bounded soft-error retries, clean
  `idle_timeout`, and automatic return to `Hotword listening started`.
- Process death and relaunch preserved pairing and restored both foreground
  services and Vosk listening.
- Windows filename search failed after the unlocked lease expired and succeeded
  while the physical unlocked voice lease was live, returning metadata and one
  opaque reference without content.
- The first Windows read rendered the exact opaque reference and 16,384-byte
  limit in the secure phone dialog. Denial returned only
  `PRIVATE_READ_APPROVAL_DENIED`. A fresh request approved on the phone returned
  492 bytes with `truncated=false`; its SHA-256 matched the source file exactly.
- The Windows read request was submitted directly to the same persistent Luna
  session while the physical voice lease was active because Samsung echo
  cancellation removes command audio played by the phone to itself. A normal
  spoken wake/STT/Luna/TTS turn is proven separately; a user-spoken Windows read
  remains an open end-to-end acceptance run.
- Mobile-data/Tailscale acceptance remains open because the test S10+ reported
  Bell out of service and had no usable cellular underlay during the test.
- The signed Windows node was migrated from a detached Gateway child to its own
  boot-triggered S4U task. A controlled stop/start, task lock, privacy-safe
  lifecycle log, exact node reconnect, and a subsequent non-elevated idempotent
  provisioning run passed. A cold Windows reboot with no user login remains open.

## Build and smoke test

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:FIREBASE_ENABLED = 'false'
& 'C:\Program Files\Git\bin\bash.exe' -lc `
  "./gradlew :app:testStandardDebugUnitTest :app:assembleStandardDebug --no-daemon --max-workers=1"
```
