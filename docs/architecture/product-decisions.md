# Agentic Assistant Product Decisions

These defaults are adopted for implementation unless the user explicitly
changes them. They favor a useful unlocked assistant without treating a wake
phrase as identity proof.

| Decision | Adopted default | Status |
| --- | --- | --- |
| Private reads | Sender/title/count metadata may be immediate while unlocked. Bodies, PC file contents, full calendar notes, contacts, and durable-memory reads require a user-created 10-minute grant. | Adopted |
| Memory writes | Only explicit `remember this` requests. No automatic extraction until a review UI exists. | Adopted |
| Cross-lane memory | Securely locked voice remains separate and cannot access main durable memory. | Adopted |
| Spotify assurance | Resolve only public Spotify track pages, pass a strict track URI to Android's media session, and require playing state plus matching metadata before claiming playback. Search intent remains an unconfirmed fallback. | Adopted |
| High-risk confirmation | Visible on-phone tap for SMS, calls, replies, calendar/contact writes, and PC writes. Biometric is reserved for future account, security, purchase, or destructive-file actions; those remain disabled initially. | Adopted |
| PC file roots | Default deny. The user selects exact readable roots and one writable Assistant Inbox through a local UI; no entire-drive access. | Adopted |
| Offline Gateway | Hey GPT fails closed and states that OpenClaw is unavailable. It does not silently become a different local assistant. | Adopted |
| Depth versus latency | All phone conversation lanes use Luna. Unlocked ordinary voice uses fast mode; a user-explicit deep-research route may use a slower planner later. | Adopted |
| Messenger | Retained notification previews and genuine active `RemoteInput` replies only. No claim of full inbox access. | Adopted |
| Phone Link | Manual convenience only, not an executor or capability bus. | Adopted |

Any change to these defaults must update the capability policy, confirmation
tests, threat model, and physical acceptance matrix before release.
