# OpenClaw Voice Assistant Tools

This plugin exposes two optional tools for the dedicated `voice-main` agent:

- `android_media_play` binds a query plus optional song title and artist to `media.play_search` on one configured Android node and fixes the target package to Spotify. Supplying structured title/artist fields gives Android enough information to request direct playback rather than an unstructured search.
- `messenger_notifications_read` invokes the Android-side, Messenger-only `notifications.list_package` command and returns only sender, preview, and timestamp. The Android app keeps up to 100 Messenger previews in app-private storage for seven days so a dismissed notification can still be read later.

The model cannot choose a node ID, command name, package name, notification key, or notification action. Use `scripts/configure-voice-main-agent.ps1` to build, install, configure, and verify the plugin.
