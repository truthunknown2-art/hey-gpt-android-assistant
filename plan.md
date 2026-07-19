# Agentic OpenClaw Assistant Roadmap

## Target

Deliver a production-usable assistant spanning the S10+ and Windows PC with:

- natural `Hey GPT` voice interaction using subscription-backed Luna;
- persistent, explicit memory;
- reliable native phone and selected desktop actions;
- signed, risk-tiered execution with private results kept off the model path;
- Tailscale-only remote connectivity away from home;
- physical-device acceptance and review in the existing GPT-5.6 Pro conversation.

## Verified Baseline

- [x] Unlocked `Hey GPT` uses a persistent `voice-main` OpenClaw conversation.
- [x] Secure keyguard uses a separate persistent zero-tool lane.
- [x] Both deployed voice agents are pinned to `openai/gpt-5.6-luna`, not Terra.
- [x] Generic node, shell, browser-control, filesystem, and Gateway tools are denied to `voice-main`.
- [x] The S10+ helper node is paired, connected, and advertises only fixed signed broker commands plus explicit legacy capabilities.
- [x] Signed Ed25519 proposals, exact device/session binding, short-lived presence leases, durable plans/receipts, and one-shot approval primitives exist.
- [x] Private contact search is enabled. Names and numbers are spoken only on the unlocked phone; Luna receives only a bounded terminal receipt.
- [x] Explicit remember/forget is enabled in the unique `voice-main` workspace. Sensitive and automatically harvested memory is rejected.
- [x] Spotify exact-track playback, bounded Messenger notification previews, and web search/fetch are available through named tools.
- [x] Signed contact-based calling resolves the recipient on-phone and requires a fresh one-shot approval before launch.
- [x] Exact installed Android build: `4e76bf3`, versionCode 494.

## Acceptance Gates

### 1. Voice And Privacy

- [ ] Verify unlocked wake, multi-turn continuity, barge-in, and phone-only TTS on the physical S10+.
- [ ] Verify first contact read prompts once, approval is scoped to 10 minutes, and the private result is spoken only on the phone.
- [ ] Verify locking during approval, execution, or private TTS cancels delivery and revokes the session.
- [ ] Verify secure-lock wake remains conversational but exposes zero tools and no sensitive memory.
- [ ] Verify remember, recall after a fresh voice session, and forget using non-sensitive test data.

### 2. Native Phone Actions

- [ ] Reverify Spotify authorization and exact playback from voice, including unavailable-track failure.
- [ ] Verify Messenger notification expiry and clearly disclose that read inbox history is not yet supported.
- [ ] Physically verify signed contact-based calling, including ambiguity, denial, lock-during-approval, and successful launch.
- [ ] Add signed SMS compose/send with recipient and body shown on-phone; sending always requires a fresh confirmation.
- [ ] Add calendar reads privately and calendar writes with a fresh confirmation.
- [ ] Move Messenger reads to the same phone-private delivery boundary before supporting message history.

### 3. Selected Windows Actions

- [ ] Define an allowlisted Windows companion protocol; do not expose generic shell, accessibility, or browser automation to Luna.
- [ ] Implement named desktop capabilities only where the underlying app has a stable API or verifiable UI contract.
- [ ] Treat Phone Link as a presentation surface, not as proof that Messenger inbox history has a supported automation API.
- [ ] Add risk-tiered confirmations and durable receipts for every desktop write or outbound communication.

### 4. Connectivity And Recovery

- [ ] Verify Gateway, WSL, helper node, and hotword recovery after Windows and phone reboot.
- [ ] Verify Tailscale Serve from the S10+ over cellular with Wi-Fi disabled.
- [ ] Add and verify an administrator-approved Windows firewall rule blocking direct LAN access to TCP 18789 while preserving Tailscale access.
- [ ] Confirm no public port forwarding, direct LAN listener exposure, or API-credit fallback is active.

### 5. Release

- [ ] Complete repeated and endurance physical tests with latency and failure evidence.
- [ ] Obtain blocker review in the existing GPT-5.6 Pro `Android Voice Assistant Review` conversation.
- [ ] Resolve all blocker findings and rerun exact-head Android, broker, Gateway, reboot, and mobile-data checks.
- [ ] Keep PR #1 draft until every acceptance gate above has authoritative evidence.
