import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { afterEach, describe, it } from "node:test";
import { createProposal } from "../dist/contract-v1.js";
import { BrokerLedger } from "../dist/ledger.js";

const NOW = 1_700_000_000_000;
const PLAN_ID = "22222222-2222-4222-8222-222222222222";
const PROPOSAL_ID = "11111111-1111-4111-8111-111111111111";
const STEP_ID = "33333333-3333-4333-8333-333333333333";
const IDEMPOTENCY_KEY = "44444444-4444-4444-8444-444444444444";
const SNAPSHOT_HASH = `sha256:${"a".repeat(64)}`;
const roots = [];

function root() {
  const value = mkdtempSync(path.join(tmpdir(), "assistant-broker-"));
  roots.push(value);
  return value;
}

function signedProposal() {
  return {
    proposal: createProposal({
      capability: "android.device.status",
      arguments: {},
      targetDeviceId: "paired-device",
      voiceSessionKey: "agent:voice-main:voice-android-device",
      presenceLeaseId: "lease-1",
      planId: PLAN_ID,
      stepId: STEP_ID,
      proposalId: PROPOSAL_ID,
      idempotencyKey: IDEMPOTENCY_KEY,
      nowMs: NOW,
    }),
    signatureKeyId: "gateway-key-1",
    signatureBase64Url: "A".repeat(86),
  };
}

afterEach(() => {
  for (const value of roots.splice(0)) rmSync(value, { recursive: true, force: true });
});

describe("broker ledger", () => {
  it("migrates the pre-signature proposal schema without dropping state", () => {
    const dir = root();
    const legacy = new DatabaseSync(path.join(dir, "plans.sqlite"));
    legacy.exec(`
      CREATE TABLE plans (
        plan_id TEXT PRIMARY KEY,
        voice_session_key TEXT NOT NULL,
        capability_snapshot_hash TEXT NOT NULL,
        state TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL,
        updated_at_ms INTEGER NOT NULL
      );
      CREATE TABLE proposals (
        proposal_id TEXT PRIMARY KEY,
        plan_id TEXT NOT NULL REFERENCES plans(plan_id),
        step_id TEXT NOT NULL,
        capability TEXT NOT NULL,
        arguments_hash TEXT NOT NULL,
        target_device_id TEXT NOT NULL,
        idempotency_key TEXT NOT NULL UNIQUE,
        state TEXT NOT NULL,
        issued_at_ms INTEGER NOT NULL,
        expires_at_ms INTEGER NOT NULL
      );
    `);
    legacy.close();

    const ledger = new BrokerLedger(dir, { nowMs: () => NOW });
    ledger.createPlan({
      planId: PLAN_ID,
      voiceSessionKey: "agent:voice-main:voice-android-device",
      capabilitySnapshotHash: SNAPSHOT_HASH,
    });
    assert.equal(ledger.recordProposal(signedProposal()).duplicate, false);
    assert.deepEqual(ledger.status(), {
      plans: 1,
      pendingProposals: 1,
      terminalReceipts: 0,
      modelToolsRegistered: 0,
    });
    ledger.close();

    const migrated = new DatabaseSync(path.join(dir, "plans.sqlite"));
    const proposalColumns = migrated.prepare("PRAGMA table_info(proposals)").all()
      .map((column) => column.name);
    assert.ok(proposalColumns.includes("signature_key_id"));
    assert.ok(proposalColumns.includes("signature_base64url"));
    assert.equal(migrated.prepare("PRAGMA user_version").get().user_version, 2);
    migrated.close();
  });

  it("persists privacy-minimized plans and terminal receipts across restart", () => {
    const dir = root();
    let ledger = new BrokerLedger(dir, { nowMs: () => NOW });
    ledger.createPlan({
      planId: PLAN_ID,
      voiceSessionKey: "agent:voice-main:voice-android-device",
      capabilitySnapshotHash: SNAPSHOT_HASH,
    });
    const signed = signedProposal();
    ledger.recordProposal(signed);
    ledger.recordReceipt({
      receiptId: "55555555-5555-4555-8555-555555555555",
      proposalId: PROPOSAL_ID,
      planId: PLAN_ID,
      stepId: STEP_ID,
      capability: signed.proposal.capability,
      argumentsHash: signed.proposal.argumentsHash,
      targetDeviceId: signed.proposal.targetDeviceId,
      idempotencyKey: IDEMPOTENCY_KEY,
      status: "COMPLETED",
      startedAtMs: NOW + 1,
      finishedAtMs: NOW + 2,
      resultSummary: { charging: true },
    });
    ledger.close();

    ledger = new BrokerLedger(dir, { nowMs: () => NOW + 3 });
    assert.deepEqual(ledger.status(), {
      plans: 1,
      pendingProposals: 0,
      terminalReceipts: 1,
      modelToolsRegistered: 0,
    });
    assert.equal(ledger.getPlan(PLAN_ID).state, "TERMINAL");
    assert.equal(ledger.getReceiptByProposal(PROPOSAL_ID).result_summary_json, '{"charging":true}');
    ledger.close();
  });

  it("returns exact duplicates and rejects idempotency mutation", () => {
    const ledger = new BrokerLedger(root(), { nowMs: () => NOW });
    ledger.createPlan({
      planId: PLAN_ID,
      voiceSessionKey: "agent:voice-main:voice-android-device",
      capabilitySnapshotHash: SNAPSHOT_HASH,
    });
    const signed = signedProposal();
    assert.equal(ledger.recordProposal(signed).duplicate, false);
    assert.equal(ledger.recordProposal(signed).duplicate, true);
    assert.throws(
      () => ledger.recordProposal({ ...signed, signatureBase64Url: "short" }),
      /signed proposal metadata is invalid/,
    );

    const conflicting = {
      proposal: {
        ...signed.proposal,
        proposalId: "66666666-6666-4666-8666-666666666666",
      },
      signatureKeyId: signed.signatureKeyId,
      signatureBase64Url: signed.signatureBase64Url,
    };
    assert.throws(() => ledger.recordProposal(conflicting), /idempotency key is already bound/);
    ledger.close();
  });

  it("enforces plan ownership and rejects private receipt fields", () => {
    const ledger = new BrokerLedger(root(), { nowMs: () => NOW });
    const signed = signedProposal();
    assert.throws(() => ledger.recordProposal(signed), /FOREIGN KEY constraint failed/);

    ledger.createPlan({
      planId: PLAN_ID,
      voiceSessionKey: "agent:voice-main:voice-android-device",
      capabilitySnapshotHash: SNAPSHOT_HASH,
    });
    ledger.recordProposal(signed);
    assert.throws(
      () => ledger.recordReceipt({
        receiptId: "55555555-5555-4555-8555-555555555555",
        proposalId: PROPOSAL_ID,
        planId: PLAN_ID,
        stepId: STEP_ID,
        capability: signed.proposal.capability,
        argumentsHash: signed.proposal.argumentsHash,
        targetDeviceId: signed.proposal.targetDeviceId,
        idempotencyKey: IDEMPOTENCY_KEY,
        status: "COMPLETED",
        startedAtMs: NOW + 1,
        finishedAtMs: NOW + 2,
        resultSummary: { text: "private body" },
      }),
      /receipt summary field text is not allowed/,
    );
    assert.deepEqual(ledger.status(), {
      plans: 1,
      pendingProposals: 1,
      terminalReceipts: 0,
      modelToolsRegistered: 0,
    });
    ledger.close();
  });
});
