# Assistant Contract v1

Status: Phase 1 signed private-read slice; disabled by default

Contract v1 defines the envelope shared by the Gateway capability broker and
device executors. Capabilities remain disabled unless their individual feature
flag, exact agent policy, Gateway command allowlist, and device executor are all
active.

## Presence lease

The unlocked Android voice session owns one process-local lease bound to its
exact OpenClaw session key and paired device ID. The default lease lifetime is
15 seconds. It renews only while the ambient session is active, Android reports
the device unlocked, and Gateway health remains good. Lock, Gateway disconnect,
session stop, service destruction, replacement, or expiry invalidates it.

Android uses elapsed realtime for expiry. Wall-clock changes therefore cannot
extend authorization. A lease ID is opaque and is not accepted as identity by
itself; session and device bindings must also match.

## Signed proposal

A proposal contains:

- contract, proposal, plan, step, and idempotency identifiers;
- one fixed semantic capability and its contract-owned risk;
- strictly validated arguments and `sha256:<lowercase hex>` canonical hash;
- exact target device, voice session, and presence lease bindings;
- issue and expiry timestamps with at most a 60-second lifetime; and
- an external signature key ID and base64url signature.

The Android validator rejects unknown arguments, incorrect JSON types, argument
mutation, risk mutation, wrong target/session/lease, expiry, excessive clock
skew, and invalid signatures before an executor can run.

## Canonical JSON

Objects are encoded with keys in UTF-16 code-unit order; all contract-v1 schema
keys are ASCII. Arrays preserve order. Strings, booleans, null, and integer
primitives use JSON encoding without whitespace. Contract-v1 capability schemas
do not admit floating-point values. The same canonical bytes are used for
argument hashing and proposal signing.

## Receipts

Terminal receipt states are `COMPLETED`, `DENIED`, `CANCELLED`, `FAILED`, and
`UNKNOWN`. `UNKNOWN` is mandatory when a side effect may have been submitted but
cannot be verified. It must never trigger an automatic retry.

The initial recognized capability IDs are intentionally limited to Phase 1
read-only foundations:

- `android.device.status`
- `android.calendar.next`
- `android.contacts.search`
- `windows.files.search`
- `windows.files.read`

Medium-risk capabilities require a fixed 10-minute private-read grant. The
grant store is process-local, bound to the exact capability, voice session, and
device, and revoked whenever the unlocked voice session ends. No grant survives
process death, secure lock, or a new voice session.

The Android executor currently implements `android.device.status` and an
internal `android.contacts.search` adapter. Both revalidate the exact device,
voice session, live unlocked-presence lease, proposal lifetime, argument hash,
risk, and pinned Ed25519 signature. Contact lookup additionally requires a
session/device-bound private-read authorization and Android Contacts permission.
Names and phone numbers exist only in the Android execution outcome and the
bound voice session's transient speech call; the durable receipt stores only
match count and truncation. OpenClaw 2026.7.1 has no private tool-result channel:
tool `content` is model input and `details` is for logs/UI. Therefore private
fields are returned in neither field and are never written to Android chat
state. The configured TTS provider speaks the result synchronously. Local
Android TTS keeps that text on the phone; Pocket TTS sends it to the user's
authenticated HTTPS/Tailscale endpoint, whose server logs only timing and chunk
counts. The model receives `COMPLETED` only after speech finishes. Lock, session
replacement, TTS failure, malformed payload, or lost binding fails closed.

The phone advertises only two fixed broker commands, `assistant.presence.v1`
and `assistant.execute.v1`. They remain unusable until the Gateway allowlist and
broker feature flag are explicitly enabled. Direct callers still cannot create
a valid proposal because Android requires the exact live device, voice session,
presence lease, argument hash, risk, expiry, and pinned broker signature.

The legacy Mobile Bridge now shares the same fail-closed confirmation posture:
`TRUSTED` cannot bypass high-risk or destructive actions, high-risk approvals
are always one-shot, duplicate pending request IDs are rejected, canonical
arguments are rendered by the phone, and locking after approval prevents the
action from executing. These controls do not expose the bridge to `voice-main`.

## Gateway foundation

`integrations/assistant-capability-broker` runs as an OpenClaw background
service. It creates WAL-backed `plans.sqlite` and `receipts.sqlite` ledgers,
enforces immutable idempotency bindings, and reconciles terminal receipt state
after restart. It registers only the operator-read `assistant.broker.status`
and `assistant.broker.publicKey` Gateway methods by default.

The broker also owns a persistent Ed25519 signing identity. Its private key is
stored with restrictive permissions in plugin state and never returned or
logged. The authenticated `assistant.broker.publicKey` operator-read method
returns only the stable key ID, raw public key, and SHA-256 fingerprint for an
explicit phone-side trust decision. Invalid persisted key state fails closed
instead of silently rotating the phone's trust root.

With `privateReadsEnabled=true`, one exact `androidNodeId`, and an exact agent
tool policy, the optional `assistant_contacts_search` tool obtains a live
presence lease, signs and records a 60-second proposal, invokes the fixed Android
executor, validates the strict receipt, and records it. The model supplies only
`query` and a bounded `limit`; agent and node come from trusted configuration,
the stable phone voice-session key is derived from those values, and command,
risk, lease, IDs, and signature come from trusted runtime state. Malformed,
oversized, or privacy-smuggling node results become terminal `UNKNOWN` receipts
and are not retried automatically.

The Android trust store validates that descriptor, stores it only in encrypted
preferences after an explicit trust action, and treats a changed key as a hard
trust conflict. A changed candidate never overwrites the pinned key. Clearing
trust is an explicit local operation and is not available to a model tool.

Explicit durable memory is implemented behind `memoryEnabled=false`. When the
release gates are closed and an operator enables it, the broker binds only the
optional `assistant_memory_remember` and `assistant_memory_forget` tools to the
unique configured agent workspace. Memory facts and privacy-minimized operation
receipts are transactional in a per-workspace SQLite ledger; a managed
`MEMORY.md` section is rendered atomically for OpenClaw startup recall. The renderer preserves all
unmanaged content, escapes marker/fence characters, treats stored facts as data
rather than instructions, rejects known credential/payment/security material,
and never harvests ordinary conversation or private phone content automatically.
The locked agent never receives these tools. Activation explicitly selects the
OpenClaw `none` memory provider, which keeps keyword recall local and prevents
embedding API charges; Codex OAuth is used only for Luna conversation turns.
