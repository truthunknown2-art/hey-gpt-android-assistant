# Assistant Contract v1

Status: internal foundation; not exposed to the model

Contract v1 defines the envelope shared by the future Gateway capability broker
and device executors. Adding these types does not register a tool or enable a
capability.

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

Medium-risk capabilities still require the future private-read grant. They are
defined here for validation but remain unavailable until that gate exists.

The Android executor currently implements only `android.device.status`. It
accepts the typed signed proposal internally, revalidates the exact device,
voice session, live unlocked-presence lease, proposal lifetime, argument hash,
risk, and pinned Ed25519 signature, then emits a privacy-minimized terminal
receipt. The status summary contains only battery percentage, charging state,
and screen-interactive state. No executor command or model tool is registered
until the Phase 0 physical and exact-head review gates close.

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
Gateway method. Contract v1 intentionally registers zero model tools.
