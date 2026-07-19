# OpenClaw Voice Assistant Tools

This plugin exposes two optional tools for the dedicated `voice-main` agent:

- `android_media_play` binds a query, optional title/artist, and an optional strict `spotify:track:<22-character-id>` to `media.play_search` on one configured Android node and fixes the target package to Spotify. Exact tracks use Spotify's media-session URI action after public web resolution; Android confirms both playing state and matching metadata. The standard play-from-search intent remains an unconfirmed fallback.
- `messenger_notifications_read` invokes the Android-side, Messenger-only `notifications.list_package` command and returns only sender, preview, and timestamp. The Android app keeps up to 100 Messenger previews in app-private storage for seven days so a dismissed notification can still be read later.

The model cannot choose a node ID, command name, package name, notification key, or notification action. Use `scripts/configure-voice-main-agent.ps1` to build, install, configure, and verify the plugin.
