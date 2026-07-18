# OpenClaw Voice Assistant Tools

This plugin exposes two optional tools for the dedicated `voice-main` agent:

- `android_media_play` binds a text query to `media.play_search` on one configured Android node and fixes the target package to Spotify.
- `messenger_notifications_read` invokes the Android-side, Messenger-only `notifications.list_package` command and returns only sender, preview, and timestamp.

The model cannot choose a node ID, command name, package name, notification key, or notification action. Use `scripts/configure-voice-main-agent.ps1` to build, install, configure, and verify the plugin.
