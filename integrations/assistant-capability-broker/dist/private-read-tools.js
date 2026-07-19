import { randomUUID } from "node:crypto";
import {
  CONTRACT_VERSION,
  canonicalHash,
  createProposal,
} from "./contract-v1.js";

export const CONTACTS_TOOL_NAME = "assistant_contacts_search";
export const PRESENCE_COMMAND = "assistant.presence.v1";
export const EXECUTE_COMMAND = "assistant.execute.v1";

const CONTACTS_CAPABILITY = "android.contacts.search";
const NODE_ID_PATTERN = /^[a-f0-9]{64}$/;
const MAX_NODE_PAYLOAD_BYTES = 16 * 1024;
const NODE_COMMAND_TIMEOUT_MS = 120_000;
const PRESENCE_TIMEOUT_MS = 10_000;
const RECEIPT_STATUSES = new Set(["COMPLETED", "DENIED", "CANCELLED", "FAILED", "UNKNOWN"]);
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const RECEIPT_REQUIRED_KEYS = [
  "contractVersion",
  "receiptId",
  "proposalId",
  "planId",
  "stepId",
  "capability",
  "argumentsHash",
  "targetDeviceId",
  "idempotencyKey",
  "status",
  "startedAtMs",
  "finishedAtMs",
];
const RECEIPT_ALLOWED_KEYS = new Set([
  ...RECEIPT_REQUIRED_KEYS,
  "resultSummary",
  "errorCode",
]);

class PrivateReadToolError extends Error {
  constructor(code) {
    super(code);
    this.name = "PrivateReadToolError";
    this.code = code;
  }
}

function asRecord(value, code = "NODE_RESPONSE_INVALID") {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new PrivateReadToolError(code);
  }
  return value;
}

function parseNodePayload(value) {
  const result = asRecord(value);
  if (result.ok === false) throw new PrivateReadToolError("NODE_COMMAND_REJECTED");
  const candidate = result.payload ?? result.payloadJSON ?? result;
  if (typeof candidate === "string") {
    if (Buffer.byteLength(candidate, "utf8") > MAX_NODE_PAYLOAD_BYTES) {
      throw new PrivateReadToolError("NODE_RESPONSE_INVALID");
    }
    try {
      return asRecord(JSON.parse(candidate));
    } catch {
      throw new PrivateReadToolError("NODE_RESPONSE_INVALID");
    }
  }
  const record = asRecord(candidate);
  try {
    if (Buffer.byteLength(JSON.stringify(record), "utf8") > MAX_NODE_PAYLOAD_BYTES) {
      throw new PrivateReadToolError("NODE_RESPONSE_INVALID");
    }
  } catch (error) {
    if (error instanceof PrivateReadToolError) throw error;
    throw new PrivateReadToolError("NODE_RESPONSE_INVALID");
  }
  return record;
}

function exactKeys(value, required, allowed) {
  const keys = Object.keys(value);
  return required.every((key) => keys.includes(key)) && keys.every((key) => allowed.has(key));
}

function parsePresence(value) {
  const presence = parseNodePayload(value);
  if (
    Object.keys(presence).sort().join("\0") !== ["contractVersion", "presenceLeaseId"].sort().join("\0") ||
    presence.contractVersion !== CONTRACT_VERSION ||
    typeof presence.presenceLeaseId !== "string" ||
    !UUID_PATTERN.test(presence.presenceLeaseId)
  ) {
    throw new PrivateReadToolError("PRESENCE_RESPONSE_INVALID");
  }
  return presence;
}

function parseContactsSummary(value) {
  const summary = asRecord(value, "RECEIPT_INVALID");
  if (Object.keys(summary).sort().join("\0") !== ["matchCount", "truncated"].sort().join("\0")) {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
  if (!Number.isSafeInteger(summary.matchCount) || summary.matchCount < 0 || summary.matchCount > 10) {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
  if (typeof summary.truncated !== "boolean") throw new PrivateReadToolError("RECEIPT_INVALID");
  return summary;
}

function parseReceipt(value, proposal) {
  const receipt = parseNodePayload(value);
  if (!exactKeys(receipt, RECEIPT_REQUIRED_KEYS, RECEIPT_ALLOWED_KEYS)) {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
  if (
    receipt.contractVersion !== CONTRACT_VERSION ||
    !UUID_PATTERN.test(receipt.receiptId ?? "") ||
    !RECEIPT_STATUSES.has(receipt.status) ||
    !Number.isSafeInteger(receipt.startedAtMs) ||
    !Number.isSafeInteger(receipt.finishedAtMs) ||
    receipt.startedAtMs < 0 ||
    receipt.finishedAtMs < receipt.startedAtMs ||
    receipt.proposalId !== proposal.proposalId ||
    receipt.planId !== proposal.planId ||
    receipt.stepId !== proposal.stepId ||
    receipt.capability !== proposal.capability ||
    receipt.argumentsHash !== proposal.argumentsHash ||
    receipt.targetDeviceId !== proposal.targetDeviceId ||
    receipt.idempotencyKey !== proposal.idempotencyKey
  ) {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
  if (receipt.status === "COMPLETED") {
    if ("errorCode" in receipt) throw new PrivateReadToolError("RECEIPT_INVALID");
    receipt.resultSummary = parseContactsSummary(receipt.resultSummary);
  } else {
    if ("resultSummary" in receipt || !/^[A-Z0-9_]{1,64}$/.test(receipt.errorCode ?? "")) {
      throw new PrivateReadToolError("RECEIPT_INVALID");
    }
  }
  return receipt;
}

function selectNode(nodes, nodeId) {
  const matches = nodes.filter((node) => node.nodeId === nodeId);
  if (matches.length !== 1 || matches[0].connected === false) {
    throw new PrivateReadToolError("ANDROID_NODE_UNAVAILABLE");
  }
  for (const command of [PRESENCE_COMMAND, EXECUTE_COMMAND]) {
    if (!matches[0].commands?.includes(command)) {
      throw new PrivateReadToolError("ANDROID_SIGNED_COMMAND_UNAVAILABLE");
    }
  }
  return matches[0];
}

function unknownReceipt(proposal, startedAtMs, errorCode) {
  return {
    contractVersion: CONTRACT_VERSION,
    receiptId: randomUUID(),
    proposalId: proposal.proposalId,
    planId: proposal.planId,
    stepId: proposal.stepId,
    capability: proposal.capability,
    argumentsHash: proposal.argumentsHash,
    targetDeviceId: proposal.targetDeviceId,
    idempotencyKey: proposal.idempotencyKey,
    status: "UNKNOWN",
    startedAtMs,
    finishedAtMs: Math.max(startedAtMs, Date.now()),
    errorCode,
  };
}

function resultForModel(receipt) {
  const completed = receipt.status === "COMPLETED";
  const payload = {
    status: receipt.status,
    privateDelivery: completed ? "spoken_on_phone" : "not_delivered",
    ...(completed ? {
      matchCount: receipt.resultSummary.matchCount,
      truncated: receipt.resultSummary.truncated,
    } : {
      errorCode: receipt.errorCode,
    }),
  };
  return {
    content: [{ type: "text", text: JSON.stringify(payload) }],
    details: payload,
  };
}

function controlledUnknownCode(error) {
  if (error instanceof PrivateReadToolError) return error.code;
  return "NODE_INVOKE_UNAVAILABLE";
}

export async function searchContactsPrivately({
  api,
  ledger,
  signingIdentity,
  nodeId,
  voiceSessionKey,
  request,
}) {
  const rawQuery = request?.query;
  const query = typeof rawQuery === "string" ? rawQuery.trim() : undefined;
  const limit = request?.limit ?? 5;
  if (typeof query !== "string" || query.length < 1 || query.length > 100) {
    throw new PrivateReadToolError("ARGUMENT_SCHEMA");
  }
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 10) {
    throw new PrivateReadToolError("ARGUMENT_SCHEMA");
  }
  const { nodes } = await api.runtime.nodes.list({ connected: true });
  selectNode(nodes, nodeId);
  const presence = parsePresence(await api.runtime.nodes.invoke({
    nodeId,
    command: PRESENCE_COMMAND,
    params: {},
    timeoutMs: PRESENCE_TIMEOUT_MS,
    idempotencyKey: randomUUID(),
  }));
  const planId = randomUUID();
  const capabilitySnapshotHash = canonicalHash({
    contractVersion: CONTRACT_VERSION,
    capabilities: [CONTACTS_CAPABILITY],
    targetDeviceId: nodeId,
  });
  ledger.createPlan({ planId, voiceSessionKey, capabilitySnapshotHash });
  const proposal = createProposal({
    capability: CONTACTS_CAPABILITY,
    arguments: { query, limit },
    targetDeviceId: nodeId,
    voiceSessionKey,
    presenceLeaseId: presence.presenceLeaseId,
    planId,
    lifetimeMs: 60_000,
  });
  const signed = signingIdentity.sign(proposal);
  ledger.recordProposal(signed);
  const startedAtMs = Date.now();
  let receipt;
  try {
    const response = await api.runtime.nodes.invoke({
      nodeId,
      command: EXECUTE_COMMAND,
      params: signed,
      timeoutMs: NODE_COMMAND_TIMEOUT_MS,
      idempotencyKey: proposal.idempotencyKey,
    });
    receipt = parseReceipt(response, proposal);
  } catch (error) {
    receipt = unknownReceipt(proposal, startedAtMs, controlledUnknownCode(error));
  }
  ledger.recordReceipt(receipt);
  return resultForModel(receipt);
}

export function registerPrivateReadTools(api, state) {
  if (api.pluginConfig?.privateReadsEnabled !== true) return 0;
  const agentId = typeof api.pluginConfig?.privateReadAgentId === "string"
    ? api.pluginConfig.privateReadAgentId
    : "voice-main";
  const nodeId = api.pluginConfig?.androidNodeId;
  if (!NODE_ID_PATTERN.test(nodeId ?? "")) {
    throw new Error("assistant-capability-broker requires one valid androidNodeId for private reads");
  }
  const voiceSessionKey = `agent:${agentId}:voice-android-${nodeId.slice(0, 32)}`;
  api.registerTool({
    name: CONTACTS_TOOL_NAME,
    label: "Search contacts privately",
    description: "Search contacts on the configured unlocked Android phone. Matching names and phone numbers are spoken only on the phone and are never returned to the model. The first private read in a voice session requires an on-phone 10-minute approval.",
    parameters: {
      type: "object",
      required: ["query"],
      properties: {
        query: { type: "string", minLength: 1, maxLength: 100 },
        limit: { type: "integer", minimum: 1, maximum: 10, default: 5 },
      },
      additionalProperties: false,
    },
    execute: async (_toolCallId, request) => {
      const ledger = state.ledger();
      const signingIdentity = state.signingIdentity();
      if (!ledger || !signingIdentity) throw new Error("assistant capability broker is unavailable");
      return searchContactsPrivately({
        api,
        ledger,
        signingIdentity,
        nodeId,
        voiceSessionKey,
        request,
      });
    },
  }, { names: [CONTACTS_TOOL_NAME], optional: true });
  return 1;
}
