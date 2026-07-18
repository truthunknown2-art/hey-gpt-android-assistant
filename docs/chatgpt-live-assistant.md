# Hey GPT: ChatGPT Live + Android device commands

This fork adds a compliant handoff from the offline Android wake-word listener
to the **official ChatGPT Android app**. It does not automate ChatGPT's private
APIs, extract session credentials, or use OpenAI Platform API credits for the
Live conversation.

## What to say

Say **“Hey GPT”**, wait for the acknowledgement tone, then either:

- say **“call Mom,” “read my latest text,”** or **“play Kind of Blue on
  Spotify”** for an offline-parsed, phone-local action; or
- say nothing (or say anything outside that narrow command set) to open the
  official ChatGPT app for a Live conversation using the signed-in ChatGPT
  subscription.

Version 0.1 is intentionally two-stage: the wake phrase and command must be two
utterances. One-breath commands such as “Hey GPT, call Mom” require a shared
audio ring buffer and are reserved for a later release. ChatGPT's consumer Live
mode does not currently expose connected-app or node-tool hooks to this Android
app, so phone actions run locally before any ChatGPT handoff.

## One-time phone setup

1. Install the official ChatGPT app (`com.openai.chatgpt`) from Google Play and
   sign in to the ChatGPT account that has Live access.
2. In ChatGPT, open **Settings -> Voice** and enable **Start with Voice** and
   **Background Conversations**. Give ChatGPT microphone and notification
   permission.
3. Install this fork's APK and complete its setup guide. Grant microphone and
   notification permission. Grant SMS read and Phone permission only if those
   commands are wanted.
4. In this app's wake-word settings, set ChatGPT Live to `hey g p t`. Keep a
   separate phrase such as `hey claw` only if the full OpenClaw voice session is
   also wanted.
5. Enable wake-word detection. On Samsung, set this app as the default **Digital
   assistant app**, allow background activity, set battery use to Unrestricted,
   and exclude it from Sleeping apps.
6. Gateway pairing is optional for the Hey GPT local-command and ChatGPT paths.
   Pair the app to OpenClaw only for the full OpenClaw assistant or for remote
   node invocation. Re-approve the node after installing a build that adds new
   commands so the Gateway records the updated command list.

The spelling `g p t` is intentional for the bundled English Vosk model; say it
naturally as “GPT.”

## Mini PC gateway setup

The phone and Mini PC must be on the same private LAN for the configuration
below. OpenClaw can also use a secure `wss://` endpoint through Tailscale Serve
for remote use.

1. Allow the Android-specific commands. Preserve any existing entries in this
   array when applying it to an established gateway:

   ```json5
   {
     gateway: {
       nodes: {
         allowCommands: [
           "phone.call",
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

## Device command contract

The Android node declares these additions after the matching permission is
available:

- `phone.call` with `{ "number": "+15551234567" }`. With Phone permission it
  places the call; otherwise it opens the dialer and returns `requiresTap: true`.
- `media.play_search` with `{ "query": "song or artist", "packageName":
  "com.spotify.music" }`. The package is optional and defaults to Spotify.
- `sms.read_latest` and `sms.read_unread`, using the existing SMS handler and
  Android `READ_SMS` permission.

For “call Mom,” the local path uses the existing `contacts.search` handler and
proceeds only when it resolves to one distinct number, then invokes
`phone.call`. Ambiguous recipients fail closed and produce a notification.

## Lock-screen behavior and limits

- Android/Samsung may block a third-party activity from appearing above the
  secure keyguard. The app makes a best-effort launch and posts a high-priority
  **Unlock to continue in ChatGPT Live** notification as a tap fallback.
- Once ChatGPT Live has started, ChatGPT's own Background Conversations setting
  allows the official session to continue while locked, subject to ChatGPT's
  documented conversation limits.
- The wake listener releases Vosk before opening ChatGPT. It watches Android's
  aggregate recording state and resumes after the external recording has been
  quiet for 3.5 seconds. A 20-second startup grace prevents a failed sign-in or
  disabled Start with Voice setting from leaving wake detection paused forever.
- A cold-boot “Hey GPT” depends on Samsung allowing the foreground wake service
  to restart. Battery optimization and Sleeping apps settings therefore matter.

## Acceptance checklist

- Unlocked: “Hey GPT” opens ChatGPT directly into Live voice.
- Locked: “Hey GPT” opens Live or shows the unlock notification fallback.
- End the Live session: offline wake-word listening returns within a few seconds.
- “Hey GPT” → tone → “call Mom”: one unambiguous contact is dialed/called.
- “Hey GPT” → tone → “read my latest text”: unlocked phones speak the message;
  locked phones speak only the sender and require unlock for message contents.
- “Hey GPT” → tone → “play *song* on Spotify”: Spotify opens and begins the search/play intent.
- Reboot the phone: the wake service remains enabled and survives Samsung power management.
- Stop Wi-Fi: local phone commands still run and the Live route still opens
  ChatGPT; neither depends on Gateway configuration.

## Build and smoke test

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:FIREBASE_ENABLED = 'false'
.\gradlew.bat :app:testStandardDebugUnitTest :app:assembleStandardDebug --no-daemon --max-workers=1
```

The debug-only `ChatGptHandoffProbeActivity` exercises the public launcher and
Play Store fallback without relying on a private ChatGPT activity name.
