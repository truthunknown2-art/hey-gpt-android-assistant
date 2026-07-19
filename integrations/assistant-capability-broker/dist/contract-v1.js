import {
  createHash,
  randomUUID,
  sign as signBytes,
  verify as verifyBytes,
} from "node:crypto";

export const CONTRACT_VERSION = 1;
export const MAX_PROPOSAL_LIFETIME_MS = 60_000;
export const MAX_CLOCK_SKEW_MS = 5_000;

export const CAPABILITIES = Object.freeze({
  "android.device.status": Object.freeze({ risk: "LOW" }),
  "android.calendar.next": Object.freeze({ risk: "LOW" }),
  "android.contacts.search": Object.freeze({ risk: "MEDIUM" }),
  "android.phone.call_contact": Object.freeze({ risk: "HIGH" }),
  "windows.files.search": Object.freeze({ risk: "LOW" }),
  "windows.files.read": Object.freeze({ risk: "MEDIUM" }),
});

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HASH_PATTERN = /^sha256:[0-9a-f]{64}$/;

export class ContractError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "ContractError";
    this.code = code;
  }
}

function fail(code, message) {
  throw new ContractError(code, message);
}

function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function exactKeys(value, allowed) {
  return Object.keys(value).every((key) => allowed.includes(key));
}

function optionalInteger(value, name, minimum, maximum) {
  if (!(name in value)) return true;
  return Number.isSafeInteger(value[name]) && value[name] >= minimum && value[name] <= maximum;
}

function requiredString(value, name, minimum, maximum) {
  return typeof value[name] === "string"
    && value[name].trim().length >= minimum
    && value[name].trim().length <= maximum;
}

export function validateArguments(capability, args) {
  if (!isRecord(args)) fail("ARGUMENT_SCHEMA", "arguments must be an object");
  let valid = false;
  switch (capability) {
    case "android.device.status":
      valid = Object.keys(args).length === 0;
      break;
    case "android.calendar.next":
      valid = exactKeys(args, ["afterEpochMs", "limit"])
        && optionalInteger(args, "afterEpochMs", 0, Number.MAX_SAFE_INTEGER)
        && optionalInteger(args, "limit", 1, 10);
      break;
    case "android.contacts.search":
      valid = exactKeys(args, ["query", "limit"])
        && requiredString(args, "query", 1, 100)
        && optionalInteger(args, "limit", 1, 10);
      break;
    case "android.phone.call_contact":
      valid = exactKeys(args, ["query"])
        && requiredString(args, "query", 1, 100);
      break;
    case "windows.files.search":
      valid = exactKeys(args, ["query", "limit"])
        && requiredString(args, "query", 1, 200)
        && optionalInteger(args, "limit", 1, 50);
      break;
    case "windows.files.read":
      valid = exactKeys(args, ["path", "maxBytes"])
        && requiredString(args, "path", 1, 1024)
        && optionalInteger(args, "maxBytes", 1, 1_000_000);
      break;
    default:
      fail("CAPABILITY", "unknown capability");
  }
  if (!valid) fail("ARGUMENT_SCHEMA", "arguments do not match the capability schema");
  return true;
}

export function canonicalJson(value) {
  if (value === null || typeof value === "boolean" || typeof value === "string") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value)) fail("CANONICAL_JSON", "numbers must be safe integers");
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }
  if (isRecord(value)) {
    return `{${Object.keys(value).sort().map((key) =>
      `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  }
  fail("CANONICAL_JSON", "unsupported JSON value");
}

export function canonicalHash(value) {
  return `sha256:${createHash("sha256").update(canonicalJson(value), "utf8").digest("hex")}`;
}

function assertUuid(value, field) {
  if (typeof value !== "string" || !UUID_PATTERN.test(value)) {
    fail("IDENTIFIER", `${field} must be a canonical lowercase UUID`);
  }
}

export function assertProposal(proposal, { nowMs = Date.now() } = {}) {
  if (!isRecord(proposal)) fail("PROPOSAL", "proposal must be an object");
  const capability = CAPABILITIES[proposal.capability];
  if (proposal.contractVersion !== CONTRACT_VERSION) fail("CONTRACT_VERSION", "unsupported contract version");
  if (!capability) fail("CAPABILITY", "unknown capability");
  for (const field of ["proposalId", "planId", "stepId", "idempotencyKey"]) {
    assertUuid(proposal[field], field);
  }
  for (const field of ["targetDeviceId", "voiceSessionKey", "presenceLeaseId"]) {
    if (typeof proposal[field] !== "string" || !proposal[field].trim()) {
      fail("IDENTIFIER", `${field} is required`);
    }
  }
  if (proposal.risk !== capability.risk) fail("CAPABILITY_RISK", "risk does not match capability");
  validateArguments(proposal.capability, proposal.arguments);
  if (!HASH_PATTERN.test(proposal.argumentsHash)
    || proposal.argumentsHash !== canonicalHash(proposal.arguments)) {
    fail("ARGUMENT_HASH", "argument hash mismatch");
  }
  if (!Number.isSafeInteger(proposal.issuedAtMs) || !Number.isSafeInteger(proposal.expiresAtMs)
    || proposal.issuedAtMs < 0 || proposal.expiresAtMs <= nowMs
    || proposal.issuedAtMs > nowMs + MAX_CLOCK_SKEW_MS
    || proposal.expiresAtMs <= proposal.issuedAtMs
    || proposal.expiresAtMs - proposal.issuedAtMs > MAX_PROPOSAL_LIFETIME_MS) {
    fail("PROPOSAL_TIME", "proposal timing is invalid");
  }
  return proposal;
}

export function createProposal({
  capability,
  arguments: args,
  targetDeviceId,
  voiceSessionKey,
  presenceLeaseId,
  planId = randomUUID(),
  stepId = randomUUID(),
  proposalId = randomUUID(),
  idempotencyKey = randomUUID(),
  nowMs = Date.now(),
  lifetimeMs = 30_000,
}) {
  const definition = CAPABILITIES[capability];
  if (!definition) fail("CAPABILITY", "unknown capability");
  validateArguments(capability, args);
  const proposal = {
    contractVersion: CONTRACT_VERSION,
    proposalId,
    planId,
    stepId,
    capability,
    arguments: args,
    argumentsHash: canonicalHash(args),
    targetDeviceId,
    voiceSessionKey,
    presenceLeaseId,
    issuedAtMs: nowMs,
    expiresAtMs: nowMs + lifetimeMs,
    idempotencyKey,
    risk: definition.risk,
  };
  return assertProposal(proposal, { nowMs });
}

export function proposalSignaturePayload(proposal) {
  return Buffer.from(canonicalJson(proposal), "utf8");
}

export function signProposal(proposal, signatureKeyId, privateKey) {
  assertProposal(proposal, { nowMs: proposal.issuedAtMs });
  if (typeof signatureKeyId !== "string" || !signatureKeyId.trim()) {
    fail("SIGNATURE", "signature key ID is required");
  }
  return {
    proposal,
    signatureKeyId,
    signatureBase64Url: signBytes(null, proposalSignaturePayload(proposal), privateKey).toString("base64url"),
  };
}

export function verifySignedProposal(signed, {
  publicKey,
  nowMs = Date.now(),
  expectedDeviceId,
  expectedVoiceSessionKey,
} = {}) {
  if (!isRecord(signed) || typeof signed.signatureKeyId !== "string"
    || typeof signed.signatureBase64Url !== "string") {
    fail("SIGNATURE", "signed proposal is malformed");
  }
  const proposal = assertProposal(signed.proposal, { nowMs });
  if (expectedDeviceId !== undefined && proposal.targetDeviceId !== expectedDeviceId) {
    fail("TARGET_DEVICE", "wrong target device");
  }
  if (expectedVoiceSessionKey !== undefined && proposal.voiceSessionKey !== expectedVoiceSessionKey) {
    fail("VOICE_SESSION", "wrong voice session");
  }
  if (!verifyBytes(
    null,
    proposalSignaturePayload(proposal),
    publicKey,
    Buffer.from(signed.signatureBase64Url, "base64url"),
  )) {
    fail("SIGNATURE", "proposal signature is invalid");
  }
  return proposal;
}
