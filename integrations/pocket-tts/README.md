# Pocket TTS voice service

This integration keeps OpenClaw as the conversation and tool brain while using
local Pocket TTS for speech output. The service:

- binds only to WSL loopback on port `18080`;
- is exposed to the phone through the existing Tailscale Serve HTTPS listener
  at `/hey-gpt-tts`;
- requires the `Tailscale-User-Login` identity header injected by Serve;
- accepts one bounded synthesis request at a time; and
- streams 24 kHz, mono, signed 16-bit PCM for low-latency Android playback.

Install or update it from an ordinary PowerShell prompt:

```powershell
.\integrations\pocket-tts\install-pocket-tts.ps1
```

The installer uses an isolated Python environment under
`/home/openclaw/hey-gpt-tts`, installs Pocket TTS `2.1.0`, enables a user
systemd service, and adds the `/hey-gpt-tts` path without replacing the existing
OpenClaw Gateway root handler.

On the phone, open **Settings > Voice mode**, choose **Pocket TTS**, and enter
the HTTPS URL printed by the installer, for example:

```text
https://device.tailnet.ts.net/hey-gpt-tts
```

The app accepts only HTTPS URLs for the exact Pocket TTS service path. If the
service fails before playback starts, Android system TTS is used as a fallback.
