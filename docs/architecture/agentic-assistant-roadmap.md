# Agentic Assistant Architecture and Roadmap

Status: approved project direction, implementation in progress

This document defines the path from the current Hey GPT voice transport to a
production-usable, auditable OpenClaw assistant spanning Android and Windows.
It is deliberately not a plan to expose raw device control to a language model.

## Product outcome

The finished product must prove agency with complete workflows, not isolated
voice commands:

1. **Prepare me for my next meeting.** Read the next calendar event, resolve
   relevant people, find bounded notes on the PC, perform public web research,
   and speak a sourced brief. The user can inspect the plan and receipts.
2. **Get me ready to leave.** Read calendar and public weather/route context,
   request a Spotify track, prepare an SMS, optionally prepare a PC briefing
   file, obtain exact approval for side effects, execute approved steps, and
   report partial failures honestly.
3. **Research this and leave me a brief.** Research on the Gateway, read only
   configured PC sources, create a cited artifact in the Assistant Inbox after
   approval, verify the written artifact, and make it available on the phone.
4. **Triage and respond.** Summarize retained notification previews and SMS
   metadata, retrieve private bodies only under a short-lived grant, draft a
   response, show the exact recipient and content on the phone, consume a
   single-use approval, send once, and return a delivery-state receipt.

All four workflows must survive ordinary reconnects, use durable context where
appropriate, and stop safely on lock, cancellation, process death, ambiguous
execution, or expired authorization.

## Governing rules

- Voice is a transport and presentation layer, not an authority boundary.
- A wake phrase is not authentication. Secure lock has zero effective tools.
- The OpenClaw `voice-main` agent plans and converses using the existing
  ChatGPT/Codex OAuth subscription. Platform API credits are not a hidden
  fallback.
- The model sees fixed, versioned semantic tools only. It never sees generic
  nodes, raw bridge execution, shell, raw filesystem, generic browser control,
  generic accessibility, Gateway administration, or Phone Link automation.
- Device-side policy is authoritative. Gateway policy can narrow permission,
  never widen what an executor accepts.
- Every externally visible completion claim must be supported by a receipt.
- An ambiguous side effect is `UNKNOWN`; it is never retried automatically.

## Target architecture

```text
Android wake/STT/TTS
        |
        | stable voice session + short-lived unlocked presence lease
        v
OpenClaw voice-main agent
        |
        | exact semantic tools only
        v
Assistant Capability Broker (Gateway plugin)
  - validates versioned contracts
  - creates plans and typed proposals
  - canonicalizes and hashes arguments
  - enforces policy, lease, expiry and idempotency
  - stores plan state and receipts
        |
        +-------------------------+
        |                         |
        v                         v
Android executor             Windows executor
  - native APIs                - bounded roots/actions
  - local lock/presence        - host policy
  - exact approval UI          - exact approval UI
  - atomic ticket consume      - atomic ticket consume
  - result verification        - result verification
        |                         |
        +-----------+-------------+
                    v
             receipts and audit
```

### Voice transport

`HotwordService` owns one unlocked ambient session. It coordinates Vosk,
Android SpeechRecognizer, Gateway turn correlation, Pocket TTS, cancellation,
lock monitoring, and wake-lock-safe recovery. It sends transcripts to one
stable `voice-main` session and speaks only the response correlated to that
turn. Deterministic progress cues are local UI/audio state, not model filler.

### OpenClaw orchestration

`voice-main` owns conversation, planning, web research, and durable memory. Its
allowlist is generated from an exact provisioned capability snapshot. A node
reconnect or capability-version change invalidates that snapshot until reviewed.

### Capability broker

One Gateway plugin, `assistant-capability-broker`, publishes individually typed
tools such as `assistant_calendar_next`, `assistant_pc_search`, and
`assistant_sms_prepare`. It does not publish a model-callable generic execute
method. Tool handlers create a broker-owned proposal containing:

```json
{
  "contractVersion": 1,
  "proposalId": "uuid",
  "planId": "uuid",
  "stepId": "uuid",
  "capability": "android.sms.send",
  "arguments": {},
  "argumentsHash": "sha256-canonical-json",
  "targetDeviceId": "paired-device-id",
  "voiceSessionKey": "stable-session-key",
  "presenceLeaseId": "ephemeral-lease-id",
  "issuedAtMs": 0,
  "expiresAtMs": 0,
  "idempotencyKey": "uuid",
  "risk": "HIGH"
}
```

The broker signs the canonical envelope for the selected executor. The model
cannot supply or alter device IDs, session IDs, risk, expiry, hashes, signatures,
or idempotency keys. Plans and receipts are operational state in local SQLite;
they are separate from conversational memory.

### Android executor

Reuse the existing Mobile Bridge capability implementations, permission checks,
approval UI, rate limiting, and activity log behind a shared executor/policy
engine. Do not expose Mobile Bridge HTTP or `mobile_bridge.execute` to
`voice-main`. The paired Android node calls the shared executor directly.

Before side effects are enabled, Mobile Bridge policy must be strengthened:

- validate every argument against a strict schema;
- bind approval to proposal, canonical argument hash, target, session, lease,
  and expiry;
- atomically consume a single-use approval ticket before execution;
- persist idempotency and terminal receipts across process death;
- make cancellation real for cancellable work;
- make `TRUSTED` unable to bypass destructive-action confirmation;
- fail closed when the phone locks or the presence lease expires.

### Windows executor

Create a narrow companion service instead of granting raw `system.run`. It
starts with device/app status, metadata-only file search, and bounded file read.
Readable roots are user-selected; writes are restricted to one user-selected
Assistant Inbox. Every handler canonicalizes paths, rejects traversal and
reparse-point escapes, applies size/type limits, and returns structured receipts.

Phone Link remains a manual convenience. Its mirrored apps are not semantic
APIs and are not used as the primary automation path.

### Presence and confirmations

An unlocked ambient session requests a short-lived presence lease from Android.
The lease is bound to the voice session and device, renewed only while the
session is active and the device remains unlocked, and revoked on lock, stop,
disconnect, or service death.

Low-risk public or metadata reads can execute under a valid lease. Private reads
require a user-created 10-minute grant. Sending, replying, calling, deleting,
posting, purchasing, writing files/calendar/contacts, and settings changes use
an executor-rendered confirmation showing exact canonical arguments. Model text
is never the confirmation payload. Approval tickets expire quickly, are
single-use, and cannot be replayed or applied to mutated arguments.

### Receipts and audit

Each plan and step records timestamps, target, capability version, argument hash,
policy result, approval state, execution state, and a privacy-minimized receipt.
Sensitive bodies are omitted from ordinary logs. Audit data is bounded, locally
stored, exportable, and deletable. `What did you do?` is answered from receipts,
not model recollection.

## Capability matrix

| Capability | Device/route | Risk | Authorization | Reliability boundary |
| --- | --- | --- | --- | --- |
| Public web search/fetch | Gateway hosted search | LOW | Unlocked lease | Sources required; no generic browser |
| Device/app status | Android native | LOW | Unlocked lease | Structured state only |
| App launch | Android package manager | LOW | Unlocked lease, opt-in | Launch is confirmed; app outcome is not |
| Spotify play request | Android media intent | MEDIUM | Unlocked lease, opt-in | Best effort; report launch, not playback |
| Calendar next/metadata | Android provider | LOW | Unlocked lease | Notes/attendees may be private |
| Calendar private fields | Android provider | MEDIUM | 10-minute private-read grant | Provider permissions required |
| Calendar create/update/delete | Android provider | HIGH | Exact one-shot tap | Read back and receipt required |
| Contacts search | Android provider | MEDIUM | 10-minute private-read grant | Exact matches may be ambiguous |
| Contact write/delete | Android provider | HIGH | Exact one-shot tap | Initially disabled |
| SMS sender/count metadata | Android provider | LOW | Unlocked lease | No body returned |
| SMS body read | Android provider | MEDIUM | 10-minute private-read grant | Bounded result and retention |
| SMS send | Android SmsManager | HIGH | Exact one-shot tap | Sent/delivery/UNKNOWN receipt; no auto-retry |
| Call initiation | Android telecom intent | HIGH | Exact one-shot tap | Cannot prove the other party answered |
| Messenger retained preview | Notification history | MEDIUM | 10-minute private-read grant | Seven-day bounded previews, not inbox history |
| Messenger reply | Active RemoteInput action | HIGH | Exact one-shot tap | Only while a genuine action remains active |
| Messenger full history | No supported route | N/A | Not exposed | Honest fallback: open Messenger manually |
| Notification action | Android notification action | HIGH | Exact one-shot tap | Exact active action only |
| Location | Android location | MEDIUM | 10-minute private-read grant | Permission and freshness shown |
| Clipboard read/write | Android native | MEDIUM/HIGH | Grant/read; exact tap/write | Bounded text only initially |
| Camera/photos/screen | Android native | HIGH | Dedicated privacy review and tap | Disabled initially |
| System settings | Android native | HIGH | Exact one-shot tap | Disabled initially |
| PC status | Windows helper | LOW | Unlocked lease | No raw process control |
| PC file metadata search | Windows helper, selected roots | LOW | Unlocked lease | No content returned |
| PC bounded file read | Windows helper, selected roots | MEDIUM | 10-minute private-read grant | Size/type/path limits |
| PC artifact create | Windows helper, Assistant Inbox | HIGH | Exact one-shot tap | Atomic write, hash/read-back receipt |
| Narrow browser navigation | Windows helper | MEDIUM | Exact domain/tool policy | No arbitrary UI or credential entry |
| Phone Link apps | Manual UI only | N/A | Not exposed | Mirroring is not a stable API |

## Durable memory

The stable session is working context, not durable memory. Initialize the native
OpenClaw file-backed store:

```text
workspace-voice-main/
  MEMORY.md
  memory/
    preferences.md
    projects.md
    people.md
    YYYY-MM-DD.md

broker/
  plans.sqlite
  receipts.sqlite
```

`voice-main` receives `memory_search` and `memory_get` plus narrow
`assistant_memory_remember` and `assistant_memory_forget` tools. It receives no
raw filesystem tool. Memory rules:

- automatic extraction from ordinary conversation is disabled initially;
- explicit low-sensitivity `remember this` writes while unlocked;
- sensitive memory writes require an exact on-device confirmation;
- raw SMS, Messenger, contacts, calendar notes, and PC content are not stored
  automatically;
- standard OpenClaw tool results are persistent model/log surfaces, so raw
  private fields remain executor-local and use opaque references or local
  rendering/speech rather than tool `content` or `details`;
- credentials, tokens, payment data, and security answers are never stored;
- forget removes the source entry and index result, then returns a receipt;
- locked voice cannot search or write main durable memory;
- local indexing must work without Platform API credits.

## Latency and observability

Target service levels measured on the physical S10+:

| Stage | Target |
| --- | --- |
| Wake phrase to local acknowledgement | p95 <= 300 ms |
| Speech end to final transcript | p95 <= 2.5 s |
| Multi-step deterministic progress cue | <= 2 s after transcript |
| Simple speech end to first response audio | p95 <= 10 s |
| Pocket TTS text to first audio | p95 <= 800 ms |

Every turn records privacy-safe timing spans for wake, STT start/end, Gateway
send, first event, tool proposal, approval wait, executor start/end, first TTS
byte, playback start, cancellation, and recovery. One correlation ID links the
turn, plan, proposals, and receipts. The diagnostics view must distinguish
offline, denied, expired, unsupported, cancelled, failed, and `UNKNOWN`.

Recovery rules:

- a read-only plan may pause and resume after reconnect if its inputs remain
  valid;
- side effects require a fresh lease and approval after reconnect or restart;
- no stale approval or high-risk plan is restored automatically;
- capability snapshot drift fails closed;
- Pocket TTS failure before playback may use system TTS; duplicate TTS is never
  allowed;
- Gateway unavailability fails quickly and honestly.

## Network and reboot topology

```text
Gateway: loopback only
External route: Tailscale Serve HTTPS/WSS
Pocket TTS: separate tailnet-only Serve path
Phone: outbound WSS node/operator connections
Mobile Bridge HTTP: local-only or disabled
```

Tailnet policy must allow only the assistant phone to the required Gateway/TTS
ports and admin devices to Gateway administration. Do not rely on a broad
tailnet allow-all rule. Gateway and TTS must start without interactive Windows
logon; Android reconnects outbound and re-advertises its versioned capabilities.

## Execution phases

### Phase 0: close the current voice release

Scope:

- serialize ambient teardown and join exact Gateway abort;
- await Android STT and TTS release;
- restart Vosk while the wake lock is held;
- physically delete expired Messenger previews;
- obtain blocker-only Pro review of the exact pushed commit;
- install the exact-head APK and physically test wake, lock transitions,
  cancellation, retained previews, and Spotify launch behavior.

Exit: automated gates pass, Pro returns no code blocker, and required physical
checks pass. This is a prerequisite for all tool expansion.

### Phase 1: broker, local memory, and a read-only agentic slice

Build versioned contracts, presence leases, broker plan/receipt storage, shared
Android executor internals, the read-only Windows helper, safe Android reads,
and explicit local memory tools. Side-effect capabilities remain disabled.

Current implementation status (2026-07-19): exact-head APK `cebaac4` is
deployed, physical wake/STT/Luna/Pocket-TTS recovery passes, and the signed
Windows search/read slice is deployed through a dedicated one-command node and
its own boot-triggered S4U supervisor task.
Expired-presence search fails closed. A physical phone denial returned no
content, while a fresh approval returned bounded content whose byte count and
SHA-256 matched the source. A user-spoken Windows read, repeated Wi-Fi and
mobile-data runs, lock-transition fault injection, meeting-brief fixtures, and
endurance acceptance remain open. Controlled S4U stop/start and repeat
non-elevated provisioning pass. Canonical task/SID validation, persistent
transaction backups, and rollback postcondition checks also pass their focused
failure-injection suite and a live provision transaction. Extended boot-trigger
drift and restored-runtime task/lock/node postconditions are also enforced. A
cold Windows reboot with no login and follow-up Pro review remain open; Phase 1
is not complete.

Vertical slice:

```text
calendar next -> contacts lookup -> PC search/read -> web research
-> spoken meeting brief -> source/receipt view
```

Exit:

- 20/20 fixture meeting briefs;
- 10 physical Wi-Fi runs and 10 mobile-data/Tailscale runs;
- lock at every step cancels subsequent tools;
- plan/receipts survive Gateway restart;
- normal chat state is untouched and private content is absent from logs.

### Phase 2: exact confirmation and side-effect slice

Build canonical request hashing, executor-rendered confirmations, single-use
approval tickets, private-read grants, exact-once receipts, SMS delivery state,
calendar read-back, Spotify launch receipts, and one bounded PC write root.

Vertical slice: implement **Get me ready to leave**. Read-only work may proceed
before confirmation; every side effect waits for its own valid approval.

Required adversarial tests include argument mutation, consumed/expired ticket,
wrong session/device/lease, lock after approval, process death, Gateway restart,
duplicate model tool calls, network loss after submission, and partial approval
of a composite plan.

Exit: mutation and replay are rejected 20/20; fault injection produces zero
duplicate SMS sends; ambiguous submissions become `UNKNOWN`; every completion
claim has a receipt.

### Phase 3: natural conversation and latency

Adopt event-first response correlation with history fallback, prewarm and health
check Pocket TTS, tune Samsung STT endpointing from measurements, add bounded
barge-in, validate Bluetooth routes, and add a local transcription fallback.
Normal voice stays on Luna fast mode; a user-explicit deep-work route may use a
slower planner later.

Exit: no duplicate audio, exact TTS cancellation and one replacement turn,
single ambient controller, Bluetooth recovery, and all latency SLOs met.

### Phase 4: capability expansion

Add one family at a time: active-notification Messenger reply, calls, calendar
and contact writes, app launch, PC artifact creation/open, narrow browser
navigation, clipboard, then camera/photos/screens only after privacy review.
Every family requires a contract, policy, confirmation renderer, receipts,
automated tests, and physical acceptance. There is no generic-device milestone.

### Phase 5: production hardening

Validate a signed release, migration, three Android reboots, three Windows cold
reboots without logon, three APK upgrades, 24-hour screen-off endurance,
Wi-Fi/mobile-data roaming, Tailscale key expiry/relogin, Gateway/TTS restart,
credential rotation, capability drift, audit export/delete, privacy docs, and
memory/receipt disaster recovery.

## Definition of done

- All four end-state workflows pass on the physical S10+ and Windows PC.
- Durable facts survive session reset and reboot; forgotten facts disappear
  from source and index; raw private content is never remembered implicitly.
- Secure lock always yields zero tools and cancels outstanding plans.
- Generic nodes, exec, browser, filesystem, accessibility, and Phone Link are
  absent from the model surface.
- Exact approvals reject mutation, replay, stale lease, wrong target, and
  process death; ambiguous side effects are never retried.
- Fifty consecutive unlocked wake turns do not lose hotword detection.
- Twenty lock transitions at each critical stage and twenty model/TTS
  cancellations recover correctly.
- Three reboots per device, three upgrades, 24 hours screen-off, and ten
  mobile-data/Tailscale runs pass without manual re-pairing.
- Plans and receipts survive Gateway restart and explain partial failure.
- The latency SLOs are met and every failed acceptance run has a complete,
  privacy-safe timeline.

## Immediate next work

1. Obtain blocker-only Pro review for the S4U task migration and finish the
   `cebaac4` Messenger, lock, restart, upgrade, and endurance acceptance.
2. Complete the user-spoken read-only meeting-brief slice and repeated
   Wi-Fi/mobile-data runs before adding side effects.
3. Refactor remaining Mobile Bridge capabilities behind the shared executor
   without widening the Luna tool surface.
4. Expand one receipt-backed phone capability family at a time.
