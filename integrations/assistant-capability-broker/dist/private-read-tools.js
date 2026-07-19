import { randomUUID } from "node:crypto";
import {
  CONTRACT_VERSION,
  canonicalHash,
  createProposal,
} from "./contract-v1.js";

export const CONTACTS_TOOL_NAME = "assistant_contacts_search";
export const CONTACT_CALL_TOOL_NAME = "assistant_phone_call";
export const CONTACT_SMS_TOOL_NAME = "assistant_sms_send";
export const CALENDAR_NEXT_TOOL_NAME = "assistant_calendar_next";
export const CALENDAR_CREATE_TOOL_NAME = "assistant_calendar_create";
export const PRESENCE_COMMAND = "assistant.presence.v1";
export const EXECUTE_COMMAND = "assistant.execute.v1";

const CONTACTS_CAPABILITY = "android.contacts.search";
const CONTACT_CALL_CAPABILITY = "android.phone.call_contact";
const CONTACT_SMS_CAPABILITY = "android.sms.send_contact";
const CALENDAR_NEXT_CAPABILITY = "android.calendar.next";
const CALENDAR_CREATE_CAPABILITY = "android.calendar.create";
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

function parseContactCallSummary(value) {
  const summary = asRecord(value, "RECEIPT_INVALID");
  if (Object.keys(summary).sort().join("\0") !== ["placedCall", "requiresTap"].sort().join("\0")) {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
  if (typeof summary.placedCall !== "boolean" || typeof summary.requiresTap !== "boolean") {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
  if (summary.placedCall === summary.requiresTap) throw new PrivateReadToolError("RECEIPT_INVALID");
  return summary;
}

function parseContactSmsSummary(value) {
  const summary = asRecord(value, "RECEIPT_INVALID");
  if (Object.keys(summary).sort().join("\0") !== ["sent"].sort().join("\0")) {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
  if (summary.sent !== true) throw new PrivateReadToolError("RECEIPT_INVALID");
  return summary;
}

function parseCalendarNextSummary(value) {
  const summary = asRecord(value, "RECEIPT_INVALID");
  if (Object.keys(summary).sort().join("\0") !== ["eventCount", "truncated"].sort().join("\0")) {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
  if (!Number.isSafeInteger(summary.eventCount) || summary.eventCount < 0 || summary.eventCount > 10) {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
  if (typeof summary.truncated !== "boolean") throw new PrivateReadToolError("RECEIPT_INVALID");
  return summary;
}

function parseCalendarCreateSummary(value) {
  const summary = asRecord(value, "RECEIPT_INVALID");
  if (Object.keys(summary).sort().join("\0") !== ["created"].sort().join("\0") || summary.created !== true) {
    throw new PrivateReadToolError("RECEIPT_INVALID");
  }
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
    if (proposal.capability === CONTACTS_CAPABILITY) {
      receipt.resultSummary = parseContactsSummary(receipt.resultSummary);
    } else if (proposal.capability === CONTACT_CALL_CAPABILITY) {
      receipt.resultSummary = parseContactCallSummary(receipt.resultSummary);
    } else if (proposal.capability === CONTACT_SMS_CAPABILITY) {
      receipt.resultSummary = parseContactSmsSummary(receipt.resultSummary);
    } else if (proposal.capability === CALENDAR_NEXT_CAPABILITY) {
      receipt.resultSummary = parseCalendarNextSummary(receipt.resultSummary);
    } else if (proposal.capability === CALENDAR_CREATE_CAPABILITY) {
      receipt.resultSummary = parseCalendarCreateSummary(receipt.resultSummary);
    } else {
      throw new PrivateReadToolError("RECEIPT_INVALID");
    }
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
  let completedSummary = {};
  if (completed) {
    switch (receipt.capability) {
      case CONTACT_CALL_CAPABILITY:
        completedSummary = {
          placedCall: receipt.resultSummary.placedCall,
          requiresTap: receipt.resultSummary.requiresTap,
        };
        break;
      case CONTACT_SMS_CAPABILITY:
        completedSummary = { sent: receipt.resultSummary.sent };
        break;
      case CALENDAR_CREATE_CAPABILITY:
        completedSummary = { created: receipt.resultSummary.created };
        break;
      case CALENDAR_NEXT_CAPABILITY:
        completedSummary = {
          privateDelivery: "spoken_on_phone",
          eventCount: receipt.resultSummary.eventCount,
          truncated: receipt.resultSummary.truncated,
        };
        break;
      case CONTACTS_CAPABILITY:
        completedSummary = {
          privateDelivery: "spoken_on_phone",
          matchCount: receipt.resultSummary.matchCount,
          truncated: receipt.resultSummary.truncated,
        };
        break;
      default:
        throw new PrivateReadToolError("RECEIPT_INVALID");
    }
  }
  const privateRead = receipt.capability === CONTACTS_CAPABILITY || receipt.capability === CALENDAR_NEXT_CAPABILITY;
  const payload = {
    status: receipt.status,
    ...completedSummary,
    ...(!completed ? {
      ...(privateRead ? { privateDelivery: "not_delivered" } : {}),
      errorCode: receipt.errorCode,
    } : {}),
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

export async function callContactWithApproval({
  api,
  ledger,
  signingIdentity,
  nodeId,
  voiceSessionKey,
  request,
}) {
  const rawQuery = request?.query;
  const query = typeof rawQuery === "string" ? rawQuery.trim() : undefined;
  if (typeof query !== "string" || query.length < 1 || query.length > 100) {
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
    capabilities: [CONTACT_CALL_CAPABILITY],
    targetDeviceId: nodeId,
  });
  ledger.createPlan({ planId, voiceSessionKey, capabilitySnapshotHash });
  const proposal = createProposal({
    capability: CONTACT_CALL_CAPABILITY,
    arguments: { query },
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

export async function sendContactSmsWithApproval({
  api,
  ledger,
  signingIdentity,
  nodeId,
  voiceSessionKey,
  request,
}) {
  const rawQuery = request?.query;
  const query = typeof rawQuery === "string" ? rawQuery.trim() : undefined;
  const message = request?.message;
  if (typeof query !== "string" || query.length < 1 || query.length > 100) {
    throw new PrivateReadToolError("ARGUMENT_SCHEMA");
  }
  if (typeof message !== "string" || message.trim().length < 1 || message.length > 1_000) {
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
    capabilities: [CONTACT_SMS_CAPABILITY],
    targetDeviceId: nodeId,
  });
  ledger.createPlan({ planId, voiceSessionKey, capabilitySnapshotHash });
  const proposal = createProposal({
    capability: CONTACT_SMS_CAPABILITY,
    arguments: { query, message },
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

export async function readCalendarPrivately({
  api,
  ledger,
  signingIdentity,
  nodeId,
  voiceSessionKey,
  request,
}) {
  const afterEpochMs = request?.afterEpochMs ?? Date.now();
  const limit = request?.limit ?? 5;
  if (!Number.isSafeInteger(afterEpochMs) || afterEpochMs < 0) {
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
    capabilities: [CALENDAR_NEXT_CAPABILITY],
    targetDeviceId: nodeId,
  });
  ledger.createPlan({ planId, voiceSessionKey, capabilitySnapshotHash });
  const proposal = createProposal({
    capability: CALENDAR_NEXT_CAPABILITY,
    arguments: { afterEpochMs, limit },
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

export async function createCalendarEventWithApproval({
  api,
  ledger,
  signingIdentity,
  nodeId,
  voiceSessionKey,
  request,
}) {
  const rawTitle = request?.title;
  const title = typeof rawTitle === "string" ? rawTitle.trim() : undefined;
  const startEpochMs = request?.startEpochMs;
  const endEpochMs = request?.endEpochMs;
  const allDay = request?.allDay ?? false;
  const now = Date.now();
  if (typeof title !== "string" || title.length < 1 || title.length > 200) {
    throw new PrivateReadToolError("ARGUMENT_SCHEMA");
  }
  if (
    !Number.isSafeInteger(startEpochMs) ||
    !Number.isSafeInteger(endEpochMs) ||
    startEpochMs < now - 5 * 60_000 ||
    startEpochMs > now + 5 * 366 * 24 * 60 * 60 * 1_000 ||
    endEpochMs <= startEpochMs ||
    endEpochMs - startEpochMs > 31 * 24 * 60 * 60 * 1_000 ||
    typeof allDay !== "boolean"
  ) {
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
    capabilities: [CALENDAR_CREATE_CAPABILITY],
    targetDeviceId: nodeId,
  });
  ledger.createPlan({ planId, voiceSessionKey, capabilitySnapshotHash });
  const proposal = createProposal({
    capability: CALENDAR_CREATE_CAPABILITY,
    arguments: { title, startEpochMs, endEpochMs, allDay },
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
  const contactsEnabled = api.pluginConfig?.privateReadsEnabled === true;
  const callsEnabled = api.pluginConfig?.phoneCallsEnabled === true;
  const smsEnabled = api.pluginConfig?.smsSendEnabled === true;
  const calendarReadsEnabled = api.pluginConfig?.calendarReadsEnabled === true;
  const calendarWritesEnabled = api.pluginConfig?.calendarWritesEnabled === true;
  if (!contactsEnabled && !callsEnabled && !smsEnabled && !calendarReadsEnabled && !calendarWritesEnabled) {
    return 0;
  }
  const agentId = typeof api.pluginConfig?.privateReadAgentId === "string"
    ? api.pluginConfig.privateReadAgentId
    : "voice-main";
  const nodeId = api.pluginConfig?.androidNodeId;
  if (!NODE_ID_PATTERN.test(nodeId ?? "")) {
    throw new Error("assistant-capability-broker requires one valid androidNodeId for private reads");
  }
  const voiceSessionKey = `agent:${agentId}:voice-android-${nodeId.slice(0, 32)}`;
  let registered = 0;
  if (contactsEnabled) {
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
    registered += 1;
  }
  if (callsEnabled) {
    api.registerTool({
      name: CONTACT_CALL_TOOL_NAME,
      label: "Call a contact with approval",
      description: "Resolve one contact privately on the configured unlocked Android phone, show the real recipient on-phone, and place the call only after a fresh one-shot approval. Contact names and phone numbers never return to the model.",
      parameters: {
        type: "object",
        required: ["query"],
        properties: {
          query: { type: "string", minLength: 1, maxLength: 100 },
        },
        additionalProperties: false,
      },
      execute: async (_toolCallId, request) => {
        const ledger = state.ledger();
        const signingIdentity = state.signingIdentity();
        if (!ledger || !signingIdentity) throw new Error("assistant capability broker is unavailable");
        return callContactWithApproval({
          api,
          ledger,
          signingIdentity,
          nodeId,
          voiceSessionKey,
          request,
        });
      },
    }, { names: [CONTACT_CALL_TOOL_NAME], optional: true });
    registered += 1;
  }
  if (smsEnabled) {
    api.registerTool({
      name: CONTACT_SMS_TOOL_NAME,
      label: "Send a text message with approval",
      description: "Resolve one contact privately on the configured unlocked Android phone, show the real recipient and full message on-phone, and send only after a fresh one-shot approval. The phone number never returns to the model.",
      parameters: {
        type: "object",
        required: ["query", "message"],
        properties: {
          query: { type: "string", minLength: 1, maxLength: 100 },
          message: { type: "string", minLength: 1, maxLength: 1_000 },
        },
        additionalProperties: false,
      },
      execute: async (_toolCallId, request) => {
        const ledger = state.ledger();
        const signingIdentity = state.signingIdentity();
        if (!ledger || !signingIdentity) throw new Error("assistant capability broker is unavailable");
        return sendContactSmsWithApproval({
          api,
          ledger,
          signingIdentity,
          nodeId,
          voiceSessionKey,
          request,
        });
      },
    }, { names: [CONTACT_SMS_TOOL_NAME], optional: true });
    registered += 1;
  }
  if (calendarReadsEnabled) {
    api.registerTool({
      name: CALENDAR_NEXT_TOOL_NAME,
      label: "Read upcoming calendar events privately",
      description: "Read up to ten upcoming events in the next 31 days from the configured unlocked Android phone. Titles and times are spoken only on the phone and never returned to the model. The first calendar read in a voice session requires an on-phone 10-minute approval.",
      parameters: {
        type: "object",
        properties: {
          afterEpochMs: { type: "integer", minimum: 0 },
          limit: { type: "integer", minimum: 1, maximum: 10, default: 5 },
        },
        additionalProperties: false,
      },
      execute: async (_toolCallId, request) => {
        const ledger = state.ledger();
        const signingIdentity = state.signingIdentity();
        if (!ledger || !signingIdentity) throw new Error("assistant capability broker is unavailable");
        return readCalendarPrivately({ api, ledger, signingIdentity, nodeId, voiceSessionKey, request });
      },
    }, { names: [CALENDAR_NEXT_TOOL_NAME], optional: true });
    registered += 1;
  }
  if (calendarWritesEnabled) {
    api.registerTool({
      name: CALENDAR_CREATE_TOOL_NAME,
      label: "Create a calendar event with approval",
      description: "Create one event on the configured unlocked Android phone only after a fresh on-phone approval showing the exact calendar, title, and local date/time. Use epoch milliseconds, set allDay only for an explicit all-day request, and never claim creation unless created=true is returned.",
      parameters: {
        type: "object",
        required: ["title", "startEpochMs", "endEpochMs"],
        properties: {
          title: { type: "string", minLength: 1, maxLength: 200 },
          startEpochMs: { type: "integer", minimum: 0 },
          endEpochMs: { type: "integer", minimum: 1 },
          allDay: { type: "boolean", default: false },
        },
        additionalProperties: false,
      },
      execute: async (_toolCallId, request) => {
        const ledger = state.ledger();
        const signingIdentity = state.signingIdentity();
        if (!ledger || !signingIdentity) throw new Error("assistant capability broker is unavailable");
        return createCalendarEventWithApproval({
          api, ledger, signingIdentity, nodeId, voiceSessionKey, request,
        });
      },
    }, { names: [CALENDAR_CREATE_TOOL_NAME], optional: true });
    registered += 1;
  }
  return registered;
}
