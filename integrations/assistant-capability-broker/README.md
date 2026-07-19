# Assistant Capability Broker

This is the foundation for typed assistant plans, proposals, receipts, and
explicit local memory. It always registers one operator-read Gateway status
status method and one operator-read signing-public-key method. Model tools remain
disabled by default.

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
lifetimes, and Ed25519 signing helpers. No capability becomes usable until a
later phase registers its individual typed tool and executor route.

The broker creates one Ed25519 identity in its private state directory and
reuses it across restarts. `assistant.broker.publicKey` returns only the raw
public key, stable key ID, and SHA-256 fingerprint to an authenticated
`operator.read` client. Malformed or mismatched persisted key state fails closed
rather than silently rotating trust.

When `memoryEnabled` is explicitly set, the plugin registers two optional tools
only for the configured agent (default `voice-main`):

- `assistant_memory_remember` stores one user-requested, low-sensitivity fact;
- `assistant_memory_forget` removes one fact by its exact receipt ID.

Memory entries and privacy-minimized receipts are transactionally stored in
`.assistant-memory/memory.sqlite` inside that agent's trusted workspace. A
managed section of `MEMORY.md` is rendered atomically so OpenClaw can load it at
session start. Unmanaged `MEMORY.md` content is preserved. Credentials, tokens,
payment data, and security answers are rejected, and ordinary conversation or
private notification content is never harvested automatically. Both tools are
optional and must also be explicitly allowlisted for the target agent.
The activation script pins `memorySearch.provider` to `none`, retaining local
SQLite FTS recall without embedding API calls or API-credit usage.
