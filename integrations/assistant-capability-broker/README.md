# Assistant Capability Broker

This is the foundation for typed assistant plans, proposals, receipts, private
Android reads/actions, and explicit local memory. It always registers one operator-read
Gateway status method and one operator-read signing-public-key method. Model
tools remain disabled by default.

The service stores privacy-minimized operational state under the plugin state
directory:

- `plans.sqlite`: plans, proposal hashes, idempotency keys, and state;
- `receipts.sqlite`: terminal receipts and bounded result summaries.

Raw proposal arguments are deliberately not stored; signed-envelope identity,
hashes, and state are retained for handoff and audit. Duplicate idempotency keys
return the original proposal only when the immutable identity fields match;
conflicts fail closed. Receipt state is reconciled into the plan ledger after a
restart. Receipt summaries are size-bounded and reject common raw-body,
credential, token, and message fields.

`contract-v1.js` mirrors the Android contract: fixed capability/risk ownership,
strict argument schemas, canonical JSON, SHA-256 argument hashes, proposal
lifetimes, and Ed25519 signing helpers.

When `privateReadsEnabled` is explicitly set with one exact `androidNodeId`, the
broker registers the optional `assistant_contacts_search` descriptor. The setup
script exposes it only through the configured voice agent's exact tool policy.
The tool obtains a live unlocked-presence lease from that node, creates
and durably records a short-lived proposal, signs it with the broker identity,
and invokes only `assistant.execute.v1`. Its voice-session binding is derived
from the configured agent and Android node identity rather than model input or
OpenClaw's descriptor-cached plugin context. Contact names and phone numbers are
spoken by the bound phone voice session; only match count, truncation, status,
and a terminal receipt return to OpenClaw. Lost or malformed execution results
become durable `UNKNOWN` receipts and are never retried automatically.

When `phoneCallsEnabled` is explicitly set for the same agent/node binding, the
broker also registers `assistant_phone_call`. The model supplies only a bounded
contact-name query. The phone resolves exactly one contact locally, shows the
real name and number in a secure unlocked one-shot approval, revalidates the
signed proposal and presence lease after approval, and only then places the
call (or opens the dialer when direct-call permission is unavailable). Only
`placedCall`, `requiresTap`, status, and a terminal receipt return to OpenClaw;
the resolved contact and number remain process-local on Android.

When `smsSendEnabled` is explicitly set, the broker also registers
`assistant_sms_send`. The model supplies a bounded contact query and the exact
message the user dictated. The phone resolves the number locally and displays
the real recipient, number, and complete message in a secure one-shot approval.
After approval it revalidates the signed proposal and unlocked presence, waits
for Android's per-part carrier submission callbacks, and returns only `sent`,
status, and a terminal receipt. Recipient, number, and message are never stored
in the durable ledger or returned in the receipt. A missing callback becomes
`UNKNOWN` and is never retried automatically.

The broker creates one Ed25519 identity in its private state directory and
reuses it across restarts. `assistant.broker.publicKey` returns only the raw
public key, stable key ID, and SHA-256 fingerprint to an authenticated
`operator.read` client. Malformed or mismatched persisted key state fails closed
rather than silently rotating trust.

When `memoryEnabled` is explicitly set, the plugin binds two optional tools to
the unique configured agent workspace (default agent `voice-main`):

- `assistant_memory_remember` stores one user-requested, low-sensitivity fact;
- `assistant_memory_forget` removes one fact by its exact receipt ID.

Memory entries and privacy-minimized receipts are transactionally stored in
`.assistant-memory/memory.sqlite` inside that bound trusted workspace. A
managed section of `MEMORY.md` is rendered atomically so OpenClaw can load it at
session start. Unmanaged `MEMORY.md` content is preserved. Credentials, tokens,
payment data, and security answers are rejected, and ordinary conversation or
private notification content is never harvested automatically. Both tools are
optional and must also be explicitly allowlisted for the target agent.
The activation script pins `memorySearch.provider` to `none`, retaining local
SQLite FTS recall without embedding API calls or API-credit usage.
