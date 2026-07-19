import { mkdirSync } from "node:fs";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import {
  ContractError,
  assertProposal,
  canonicalJson,
} from "./contract-v1.js";

const TERMINAL_STATES = new Set(["COMPLETED", "DENIED", "CANCELLED", "FAILED", "UNKNOWN"]);
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HASH_PATTERN = /^sha256:[0-9a-f]{64}$/;
const MAX_RESULT_SUMMARY_BYTES = 16_384;
const PLANS_SCHEMA_VERSION = 2;
const RECEIPTS_SCHEMA_VERSION = 1;

function assertUuid(value, field) {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    throw new ContractError("IDENTIFIER", `${field} must be a canonical lowercase UUID`);
  }
}

function configure(db) {
  db.exec("PRAGMA journal_mode = WAL; PRAGMA synchronous = FULL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;");
}

function assertPrivacyMinimized(value) {
  if (value === null || typeof value === "boolean" || typeof value === "number") return;
  if (typeof value === "string") {
    if (value.length > 500) throw new ContractError("RECEIPT_PRIVACY", "receipt string is too long");
    return;
  }
  if (Array.isArray(value)) {
    value.forEach(assertPrivacyMinimized);
    return;
  }
  if (typeof value !== "object") throw new ContractError("RECEIPT_PRIVACY", "invalid receipt summary value");
  const denied = /^(authorization|body|content|credential|message|password|secret|text|textPreview|token)$/i;
  for (const [key, child] of Object.entries(value)) {
    if (denied.test(key)) throw new ContractError("RECEIPT_PRIVACY", `receipt summary field ${key} is not allowed`);
    assertPrivacyMinimized(child);
  }
}

export class BrokerLedger {
  constructor(rootDir, { nowMs = Date.now } = {}) {
    mkdirSync(rootDir, { recursive: true });
    this.nowMs = nowMs;
    this.plans = new DatabaseSync(path.join(rootDir, "plans.sqlite"));
    this.receipts = new DatabaseSync(path.join(rootDir, "receipts.sqlite"));
    configure(this.plans);
    configure(this.receipts);
    this.#migrate();
    this.reconcile();
  }

  #migrate() {
    this.plans.exec(`
      CREATE TABLE IF NOT EXISTS plans (
        plan_id TEXT PRIMARY KEY,
        voice_session_key TEXT NOT NULL,
        capability_snapshot_hash TEXT NOT NULL,
        state TEXT NOT NULL,
        created_at_ms INTEGER NOT NULL,
        updated_at_ms INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS proposals (
        proposal_id TEXT PRIMARY KEY,
        plan_id TEXT NOT NULL REFERENCES plans(plan_id),
        step_id TEXT NOT NULL,
        capability TEXT NOT NULL,
        arguments_hash TEXT NOT NULL,
        target_device_id TEXT NOT NULL,
        idempotency_key TEXT NOT NULL UNIQUE,
        signature_key_id TEXT NOT NULL,
        signature_base64url TEXT NOT NULL,
        state TEXT NOT NULL,
        issued_at_ms INTEGER NOT NULL,
        expires_at_ms INTEGER NOT NULL
      );
      CREATE INDEX IF NOT EXISTS proposals_plan_id ON proposals(plan_id);
    `);
    const proposalColumns = new Set(
      this.plans.prepare("PRAGMA table_info(proposals)").all().map((column) => column.name),
    );
    if (!proposalColumns.has("signature_key_id")) {
      this.plans.exec("ALTER TABLE proposals ADD COLUMN signature_key_id TEXT");
    }
    if (!proposalColumns.has("signature_base64url")) {
      this.plans.exec("ALTER TABLE proposals ADD COLUMN signature_base64url TEXT");
    }
    this.plans.exec(`PRAGMA user_version = ${PLANS_SCHEMA_VERSION}`);
    this.receipts.exec(`
      CREATE TABLE IF NOT EXISTS receipts (
        receipt_id TEXT PRIMARY KEY,
        proposal_id TEXT NOT NULL UNIQUE,
        plan_id TEXT NOT NULL,
        step_id TEXT NOT NULL,
        capability TEXT NOT NULL,
        arguments_hash TEXT NOT NULL,
        target_device_id TEXT NOT NULL,
        idempotency_key TEXT NOT NULL UNIQUE,
        status TEXT NOT NULL,
        started_at_ms INTEGER NOT NULL,
        finished_at_ms INTEGER NOT NULL,
        result_summary_json TEXT,
        error_code TEXT
      );
      CREATE INDEX IF NOT EXISTS receipts_plan_id ON receipts(plan_id);
    `);
    this.receipts.exec(`PRAGMA user_version = ${RECEIPTS_SCHEMA_VERSION}`);
  }

  createPlan({ planId, voiceSessionKey, capabilitySnapshotHash }) {
    assertUuid(planId, "planId");
    if (typeof voiceSessionKey !== "string" || !voiceSessionKey.trim()) {
      throw new ContractError("VOICE_SESSION", "voice session key is required");
    }
    if (typeof capabilitySnapshotHash !== "string" || !HASH_PATTERN.test(capabilitySnapshotHash)) {
      throw new ContractError("CAPABILITY_SNAPSHOT", "capability snapshot hash is invalid");
    }
    const now = this.nowMs();
    const existing = this.plans.prepare("SELECT * FROM plans WHERE plan_id = ?").get(planId);
    if (existing) {
      if (existing.voice_session_key !== voiceSessionKey
        || existing.capability_snapshot_hash !== capabilitySnapshotHash) {
        throw new ContractError("PLAN_CONFLICT", "plan ID already has different immutable fields");
      }
      return { duplicate: true, planId };
    }
    this.plans.prepare(`
      INSERT INTO plans(plan_id, voice_session_key, capability_snapshot_hash, state, created_at_ms, updated_at_ms)
      VALUES (?, ?, ?, 'ACTIVE', ?, ?)
    `).run(planId, voiceSessionKey, capabilitySnapshotHash, now, now);
    return { duplicate: false, planId };
  }

  recordProposal(signedProposal) {
    if (typeof signedProposal?.signatureKeyId !== "string"
      || !/^[A-Za-z0-9._-]{1,128}$/.test(signedProposal.signatureKeyId)
      || typeof signedProposal?.signatureBase64Url !== "string"
      || !/^[A-Za-z0-9_-]{43,256}$/.test(signedProposal.signatureBase64Url)) {
      throw new ContractError("SIGNATURE", "signed proposal metadata is invalid");
    }
    const proposal = assertProposal(signedProposal.proposal, { nowMs: this.nowMs() });
    const existing = this.plans.prepare(
      "SELECT * FROM proposals WHERE idempotency_key = ?",
    ).get(proposal.idempotencyKey);
    if (existing) {
      const same = existing.proposal_id === proposal.proposalId
        && existing.plan_id === proposal.planId
        && existing.step_id === proposal.stepId
        && existing.capability === proposal.capability
        && existing.arguments_hash === proposal.argumentsHash
        && existing.target_device_id === proposal.targetDeviceId
        && existing.signature_key_id === signedProposal.signatureKeyId
        && existing.signature_base64url === signedProposal.signatureBase64Url;
      if (!same) {
        throw new ContractError("IDEMPOTENCY_CONFLICT", "idempotency key is already bound to another proposal");
      }
      return { duplicate: true, proposalId: existing.proposal_id };
    }
    this.plans.prepare(`
      INSERT INTO proposals(
        proposal_id, plan_id, step_id, capability, arguments_hash,
        target_device_id, idempotency_key, signature_key_id, signature_base64url,
        state, issued_at_ms, expires_at_ms
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
    `).run(
      proposal.proposalId,
      proposal.planId,
      proposal.stepId,
      proposal.capability,
      proposal.argumentsHash,
      proposal.targetDeviceId,
      proposal.idempotencyKey,
      signedProposal.signatureKeyId,
      signedProposal.signatureBase64Url,
      proposal.issuedAtMs,
      proposal.expiresAtMs,
    );
    return { duplicate: false, proposalId: proposal.proposalId };
  }

  recordReceipt(receipt) {
    this.#validateReceipt(receipt);
    const proposal = this.plans.prepare("SELECT * FROM proposals WHERE proposal_id = ?").get(receipt.proposalId);
    if (!proposal) throw new ContractError("PROPOSAL_MISSING", "receipt proposal does not exist");
    const immutableMatch = proposal.plan_id === receipt.planId
      && proposal.step_id === receipt.stepId
      && proposal.capability === receipt.capability
      && proposal.arguments_hash === receipt.argumentsHash
      && proposal.target_device_id === receipt.targetDeviceId
      && proposal.idempotency_key === receipt.idempotencyKey;
    if (!immutableMatch) throw new ContractError("RECEIPT_CONFLICT", "receipt does not match its proposal");

    const summaryJson = receipt.resultSummary === undefined || receipt.resultSummary === null
      ? null
      : canonicalJson(receipt.resultSummary);
    if (receipt.resultSummary !== undefined && receipt.resultSummary !== null) {
      if (typeof receipt.resultSummary !== "object" || Array.isArray(receipt.resultSummary)) {
        throw new ContractError("RECEIPT_PRIVACY", "receipt result summary must be an object");
      }
      assertPrivacyMinimized(receipt.resultSummary);
    }
    if (summaryJson !== null && Buffer.byteLength(summaryJson, "utf8") > MAX_RESULT_SUMMARY_BYTES) {
      throw new ContractError("RECEIPT_SIZE", "receipt result summary is too large");
    }
    const existing = this.receipts.prepare("SELECT * FROM receipts WHERE proposal_id = ?").get(receipt.proposalId);
    if (existing) {
      const same = existing.receipt_id === receipt.receiptId
        && existing.status === receipt.status
        && existing.finished_at_ms === receipt.finishedAtMs
        && existing.result_summary_json === summaryJson
        && existing.error_code === (receipt.errorCode ?? null);
      if (!same) throw new ContractError("RECEIPT_CONFLICT", "proposal already has a different terminal receipt");
      this.#applyReceiptState(receipt.proposalId, receipt.planId, receipt.status, receipt.finishedAtMs);
      return { duplicate: true, receiptId: existing.receipt_id };
    }

    this.receipts.prepare(`
      INSERT INTO receipts(
        receipt_id, proposal_id, plan_id, step_id, capability, arguments_hash,
        target_device_id, idempotency_key, status, started_at_ms, finished_at_ms,
        result_summary_json, error_code
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      receipt.receiptId,
      receipt.proposalId,
      receipt.planId,
      receipt.stepId,
      receipt.capability,
      receipt.argumentsHash,
      receipt.targetDeviceId,
      receipt.idempotencyKey,
      receipt.status,
      receipt.startedAtMs,
      receipt.finishedAtMs,
      summaryJson,
      receipt.errorCode ?? null,
    );
    this.#applyReceiptState(receipt.proposalId, receipt.planId, receipt.status, receipt.finishedAtMs);
    return { duplicate: false, receiptId: receipt.receiptId };
  }

  #validateReceipt(receipt) {
    if (typeof receipt !== "object" || receipt === null || Array.isArray(receipt)) {
      throw new ContractError("RECEIPT", "receipt must be an object");
    }
    for (const field of ["receiptId", "proposalId", "planId", "stepId", "idempotencyKey"]) {
      assertUuid(receipt[field], field);
    }
    if (!TERMINAL_STATES.has(receipt.status)) throw new ContractError("RECEIPT_STATUS", "invalid receipt status");
    if (!HASH_PATTERN.test(receipt.argumentsHash)) throw new ContractError("ARGUMENT_HASH", "invalid argument hash");
    if (!Number.isSafeInteger(receipt.startedAtMs) || !Number.isSafeInteger(receipt.finishedAtMs)
      || receipt.startedAtMs < 0 || receipt.finishedAtMs < receipt.startedAtMs) {
      throw new ContractError("RECEIPT_TIME", "invalid receipt timing");
    }
    for (const field of ["capability", "targetDeviceId"]) {
      if (typeof receipt[field] !== "string" || !receipt[field].trim()) {
        throw new ContractError("RECEIPT", `${field} is required`);
      }
    }
    if (receipt.errorCode !== undefined && receipt.errorCode !== null
      && !/^[A-Z0-9_]{1,64}$/.test(receipt.errorCode)) {
      throw new ContractError("RECEIPT", "error code is invalid");
    }
  }

  #applyReceiptState(proposalId, planId, status, finishedAtMs) {
    this.plans.prepare("UPDATE proposals SET state = ? WHERE proposal_id = ?").run(status, proposalId);
    const remaining = this.plans.prepare(
      "SELECT COUNT(*) AS count FROM proposals WHERE plan_id = ? AND state = 'PENDING'",
    ).get(planId).count;
    this.plans.prepare("UPDATE plans SET state = ?, updated_at_ms = ? WHERE plan_id = ?")
      .run(remaining === 0 ? "TERMINAL" : "ACTIVE", finishedAtMs, planId);
  }

  reconcile() {
    const rows = this.receipts.prepare(
      "SELECT proposal_id, plan_id, status, finished_at_ms FROM receipts",
    ).all();
    for (const row of rows) {
      this.#applyReceiptState(row.proposal_id, row.plan_id, row.status, row.finished_at_ms);
    }
    return rows.length;
  }

  status() {
    return {
      plans: this.plans.prepare("SELECT COUNT(*) AS count FROM plans").get().count,
      pendingProposals: this.plans.prepare(
        "SELECT COUNT(*) AS count FROM proposals WHERE state = 'PENDING'",
      ).get().count,
      terminalReceipts: this.receipts.prepare("SELECT COUNT(*) AS count FROM receipts").get().count,
      modelToolsRegistered: 0,
    };
  }

  getPlan(planId) {
    return this.plans.prepare("SELECT * FROM plans WHERE plan_id = ?").get(planId) ?? null;
  }

  getReceiptByProposal(proposalId) {
    return this.receipts.prepare("SELECT * FROM receipts WHERE proposal_id = ?").get(proposalId) ?? null;
  }

  close() {
    this.receipts.close();
    this.plans.close();
  }
}
