# Hey GPT: ChatGPT Live + secure-lock conversation

This fork adds a compliant handoff from the offline Android wake-word listener
to the **official ChatGPT Android app**. It does not automate ChatGPT's private
APIs, extract session credentials, or use OpenAI Platform API credits for the
Live conversation.

## What to say

- When Android is unlocked or trusted-unlocked, say **“Hey GPT”** once. The app
  releases the wake-word microphone and immediately opens official ChatGPT Live;
  speak normally when Live begins listening. No throwaway second phrase is
  required.
- When Android is securely locked, say **“Hey GPT”**, wait for the
  acknowledgement tone, then ask the question. The captured question goes to a
  persistent, tool-free OpenClaw conversation on the Mini PC and its answer is
  spoken with Android TTS.
- Keep **“OpenClaw”** or **“Hey Hermes”** as a separate wake phrase when the
  full agent session and Android actions are wanted instead of ChatGPT Live.

The secure-lock lane is intentionally two-stage: the wake phrase and question
must be separate utterances. One-breath commands require a shared audio ring
buffer and are reserved for a later release. ChatGPT's consumer Live mode does
not expose connected-app or node-tool hooks to this Android app, so the direct
unlocked handoff does not attempt phone actions before opening Live.

## One-time phone setup

1. Install the official ChatGPT app (`com.openai.chatgpt`) from Google Play and
   sign in to the ChatGPT account that has Live access.
2. In ChatGPT, open **Settings -> Voice**, choose **Live**, and enable
   **Background Conversations**. Give ChatGPT microphone and notification
   permission. If your app version exposes **Start with Voice**, enable it as a
   fallback.
3. Install this fork's APK and complete its setup guide. Grant microphone and
   notification permission. Grant device-action permissions only if they are
   wanted on the separate OpenClaw/Hermes assistant route.
4. In this app's wake-word settings, set ChatGPT Live to `hey g p t`. Keep a
   separate phrase such as `hey claw` only if the full OpenClaw voice session is
   also wanted.
5. In Samsung **Settings -> Apps -> Choose default apps -> Digital assistant
   app**, choose **ChatGPT**. Then enable **Hey GPT assistant trigger** under
   **Settings -> Accessibility -> Installed apps**. This trigger is deliberately
   least-privilege: it cannot read screen content and exposes only the standard
   Android Home/corner assistant gesture, not general app-control commands.
6. Enable wake-word detection in this app, allow background activity, set
   battery use to Unrestricted, and exclude it from Sleeping apps.
7. Gateway pairing is optional for unlocked ChatGPT Live, but
   required for conversational answers while the phone remains securely locked.
   Pair the app to OpenClaw using the setup code in the Mini PC section below.

The spelling `g p t` is intentional for the bundled English Vosk model; say it
naturally as “GPT.”

## Mini PC gateway setup

The phone and Mini PC must be on the same private LAN for the configuration
below. OpenClaw can also use a secure `wss://` endpoint through Tailscale Serve
for remote use.

1. Allow only the non-transactional Android commands wanted from the Gateway.
   Phone calls intentionally remain local-only and must not appear in this list:

   ```json5
   {
     gateway: {
       nodes: {
         allowCommands: [
           "media.play_search",
           "sms.read_latest",
           "sms.read_unread"
         ]
       }
     }
   }
   ```

2. Make the WSL gateway listen beyond loopback and restart it:

   ```powershell
   wsl.exe -d OpenClawGateway -- openclaw config set gateway.bind lan
   wsl.exe -d OpenClawGateway -- openclaw gateway restart
   ```

3. From an elevated PowerShell window, create the private-LAN Windows-to-WSL
   forwarding rule:

   ```powershell
   powershell.exe -ExecutionPolicy Bypass -File .\scripts\configure-openclaw-wsl-port.ps1
   ```

   WSL's private address can change after a networking reset. Rerun the script
   if the phone stops reaching the Gateway after a reboot.

4. Generate a mobile setup code using the Windows LAN address, then scan or
   paste it in the app:

   ```powershell
   wsl.exe -d OpenClawGateway -- openclaw qr --public-url ws://WINDOWS_LAN_IP:18789
   ```

   Treat the setup code like a password; do not post it in issues or logs.

5. Create the isolated locked-voice agent. It uses the Mini PC's existing OpenAI
   OAuth/subscription authentication, a stable per-device conversation, and an
   empty effective tool list:

   ```powershell
   .\scripts\configure-locked-voice-agent.ps1
   ```

   The locked lane therefore cannot execute shell commands, browse, change
   files, send messages, or remotely invoke Android actions.

## Separate device-action route

`Hey GPT` never executes device actions. Use the separate OpenClaw/Hermes wake
route for approved agent tools and Android actions. The Android node declares
these additions after the matching permission is available:

- `media.play_search` with `{ "query": "song or artist", "packageName":
  "com.spotify.music" }`. The package is optional and defaults to Spotify.
- `sms.read_latest` and `sms.read_unread`, using the existing SMS handler and
  Android `READ_SMS` permission.

`phone.call` remains unavailable to remote agent sessions. A future on-device
confirmation flow is required before a separate assistant route can safely
place calls.

## Lock-screen behavior and limits

- On the tested current ChatGPT Android build, its voice-interaction service
  reports `supportsLaunchFromKeyguard=false`. There is no standards-compliant
  intent, gesture, or accessibility action that overrides that service contract.
- When Android reports `KeyguardManager.isDeviceLocked == true`, the captured
  question immediately uses the isolated OpenClaw locked-voice agent. The answer
  is Android TTS, not official ChatGPT Live audio. A distinct second tone marks
  this locked lane. Repeating **Hey GPT** starts the next turn in the same
  persistent context. Each reply is correlated to the exact `chat.send` turn
  using OpenClaw's stable mirror identity; stale history is never spoken. A
  locked turn is capped at 60 seconds and its exact server run is aborted on
  timeout or cancellation. A three-minute maximum partial wake lock covers the
  secure-lock route and TTS so the CPU cannot suspend mid-answer; it is released
  in every exit path.
- When the device is unlocked or trusted-unlocked, the wake listener uses Android's public
  global Assist action to invoke ChatGPT as the selected digital assistant. It
  does not wait for a second captured utterance, name private ChatGPT activities,
  or inspect/automate ChatGPT's UI.
- Automatic local TTS prefers Google Speech Services when it is installed, then
  ranks installed voices by exact locale, declared quality, and natural network
  voice availability. An explicit TTS engine selected in Settings still wins.
- If the locked OpenClaw connection is unavailable, the app says so immediately
  and posts an **Unlock to continue in ChatGPT Live** notification.
- Samsung Smart Lock / Extend Unlock can make `isDeviceLocked` false with the
  screen off. In that state the app wakes the display, asks Android to dismiss
  the already-trusted keyguard, and invokes official ChatGPT Live. Treat this as
  an optional convenience/security tradeoff and prefer a trusted watch or
  headset over a place or on-body rule. It never bypasses PIN or biometric auth.
- ChatGPT's Background Conversations setting can keep a full-app voice session
  alive while locked, but it does not grant secure-keyguard launch permission.
- The wake listener releases Vosk before opening ChatGPT. It watches Android
  recording session IDs and resumes after the new external recording has been
  quiet for 3.5 seconds. A 20-second startup grace prevents a failed sign-in,
  missing assistant selection, or disabled trigger from leaving wake detection
  paused forever.
- A cold-boot “Hey GPT” depends on Samsung allowing the foreground wake service
  to restart. Battery optimization and Sleeping apps settings therefore matter.

## Acceptance checklist

- Unlocked: “Hey GPT” opens ChatGPT directly into Live voice.
- Securely locked, Gateway online: “Hey GPT” → question receives a spoken
  OpenClaw answer, and another “Hey GPT” continues the same context.
- Securely locked, Gateway offline: the phone immediately asks for unlock and
  shows the ChatGPT notification; there is no 20-second dead wait.
- End the Live session: offline wake-word listening returns within a few seconds.
- “Hey GPT” never calls, texts, or runs Android commands. Use the separate
  OpenClaw/Hermes voice session for agent tools and device actions.
- Reboot the phone: the wake service remains enabled and survives Samsung power management.
- Stop Wi-Fi: the unlocked Live route still opens ChatGPT; the securely locked
  OpenClaw conversation correctly reports that its Gateway is unavailable.

## Build and smoke test

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:FIREBASE_ENABLED = 'false'
.\gradlew.bat :app:testStandardDebugUnitTest :app:assembleStandardDebug --no-daemon --max-workers=1
```

The debug-only `ChatGptHandoffProbeActivity` exercises the public Android Assist
handoff and Play Store/launcher fallbacks without relying on a private ChatGPT
activity name.
