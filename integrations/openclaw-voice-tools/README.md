# OpenClaw Voice Assistant Tools

This plugin exposes one optional tool for the dedicated `voice-main` agent:

- `android_media_play` binds a query, optional title/artist, and an optional strict `spotify:track:<22-character-id>` to `media.play_search` on one configured Android node and fixes the target package to Spotify. Exact tracks use the phone's user-authorized Spotify App Remote integration after public web resolution; Android confirms unpaused player state and matching URI or metadata. The standard play-from-search path remains an unconfirmed fallback for non-exact requests.
Messenger previews are deliberately not exposed here. They use the signed capability broker so private text is spoken only by the unlocked phone and never returned to the model. The model cannot choose a node ID, command name, or package name. Use `scripts/configure-voice-main-agent.ps1` to install and verify this plugin.
