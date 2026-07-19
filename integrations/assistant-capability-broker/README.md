# Assistant Capability Broker

This is the non-model-facing foundation for typed assistant plans, proposals,
and receipts. It currently registers one operator-read Gateway status method and
zero model tools.

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
