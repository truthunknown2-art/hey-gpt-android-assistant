import { createHash, randomUUID } from "node:crypto";
import {
  CONTRACT_VERSION,
  canonicalHash,
  createProposal,
} from "./contract-v1.js";
import {
  EXECUTE_COMMAND as ANDROID_EXECUTE_COMMAND,
  PRESENCE_COMMAND,
} from "./private-read-tools.js";
import { WINDOWS_EXECUTE_COMMAND } from "./windows-node-command.js";

export const WINDOWS_SEARCH_TOOL_NAME = "assistant_windows_files_search";
export const WINDOWS_READ_TOOL_NAME = "assistant_windows_files_read";

const WINDOWS_SEARCH_CAPABILITY = "windows.files.search";
const WINDOWS_READ_CAPABILITY = "windows.files.read";
const NODE_ID_PATTERN = /^[a-f0-9]{64}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HASH_PATTERN = /^sha256:[0-9a-f]{64}$/;
const ERROR_CODE_PATTERN = /^[A-Z0-9_]{1,64}$/;
const PRESENCE_TIMEOUT_MS = 10_000;
const AUTHORIZATION_TIMEOUT_MS = 60_000;
const WINDOWS_COMMAND_TIMEOUT_MS = 30_000;
const MAX_AUTHORIZATION_BYTES = 16 * 1_024;
const MAX_WINDOWS_RESPONSE_BYTES = 512 * 1_024;
const RECEIPT_STATUSES = new Set(["COMPLETED", "DENIED", "CANCELLED", "FAILED", "UNKNOWN"]);
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

class WindowsFilesToolError extends Error {
  constructor(code) {
    super(code);
    this.name = "WindowsFilesToolError";
    this.code = code;
  }
}

function fail(code) {
  throw new WindowsFilesToolError(code);
}

function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function exactKeys(value, required, allowed) {
  const keys = Object.keys(value);
  return required.every((key) => keys.includes(key)) && keys.every((key) => allowed.has(key));
}

function parsePayload(value, maxBytes) {
  if (!isRecord(value)) fail("NODE_RESPONSE_INVALID");
  if (value.ok === false) fail("NODE_COMMAND_REJECTED");
  const candidate = value.payload ?? value.payloadJSON ?? value;
  if (typeof candidate === "string") {
    if (Buffer.byteLength(candidate, "utf8") > maxBytes) fail("NODE_RESPONSE_INVALID");
    try {
      const parsed = JSON.parse(candidate);
      if (!isRecord(parsed)) fail("NODE_RESPONSE_INVALID");
      return parsed;
    } catch (error) {
      if (error instanceof WindowsFilesToolError) throw error;
      fail("NODE_RESPONSE_INVALID");
    }
  }
  if (!isRecord(candidate)) fail("NODE_RESPONSE_INVALID");
  let encoded;
  try {
    encoded = JSON.stringify(candidate);
  } catch {
    fail("NODE_RESPONSE_INVALID");
  }
  if (Buffer.byteLength(encoded, "utf8") > maxBytes) fail("NODE_RESPONSE_INVALID");
  return candidate;
}

function parsePresence(value) {
  const presence = parsePayload(value, MAX_AUTHORIZATION_BYTES);
  if (
    !exactKeys(
      presence,
      ["contractVersion", "presenceLeaseId"],
      new Set(["contractVersion", "presenceLeaseId"]),
    ) ||
    presence.contractVersion !== CONTRACT_VERSION ||
    !UUID_PATTERN.test(presence.presenceLeaseId ?? "")
  ) {
    fail("PRESENCE_RESPONSE_INVALID");
  }
  return presence;
}

function parseAuthorization(value, proposal) {
  const authorization = parsePayload(value, MAX_AUTHORIZATION_BYTES);
  const required = [
    "contractVersion",
    "proposalId",
    "capability",
    "argumentsHash",
    "targetDeviceId",
    "authorized",
  ];
  if (!exactKeys(authorization, required, new Set([...required, "errorCode"]))) {
    fail("AUTHORIZATION_RESPONSE_INVALID");
  }
  if (
    authorization.contractVersion !== CONTRACT_VERSION ||
    authorization.proposalId !== proposal.proposalId ||
    authorization.capability !== WINDOWS_READ_CAPABILITY ||
    authorization.argumentsHash !== proposal.argumentsHash ||
    authorization.targetDeviceId !== proposal.targetDeviceId ||
    typeof authorization.authorized !== "boolean"
  ) {
    fail("AUTHORIZATION_RESPONSE_INVALID");
  }
  if (authorization.authorized) {
    if ("errorCode" in authorization) fail("AUTHORIZATION_RESPONSE_INVALID");
  } else if (!ERROR_CODE_PATTERN.test(authorization.errorCode ?? "")) {
    fail("AUTHORIZATION_RESPONSE_INVALID");
  }
  return authorization;
}

function validReference(value) {
  if (typeof value !== "string" || value.length < 3 || value.length > 1_024) return false;
  const separator = value.indexOf(":");
  if (separator < 1 || separator > 32) return false;
  const alias = value.slice(0, separator);
  const relative = value.slice(separator + 1);
  return /^[a-z][a-z0-9_-]{0,31}$/.test(alias)
    && relative.trim().length > 0
    && !relative.includes("\\")
    && !/[\u0000-\u001f\u007f]/.test(relative)
    && relative.split("/").every((segment) => segment !== "" && segment !== "." && segment !== "..");
}

function parseSearchSummary(value) {
  if (!isRecord(value) || !exactKeys(value, ["matches", "truncated"], new Set(["matches", "truncated"]))) {
    fail("RECEIPT_INVALID");
  }
  if (!Array.isArray(value.matches) || value.matches.length > 50 || typeof value.truncated !== "boolean") {
    fail("RECEIPT_INVALID");
  }
  for (const match of value.matches) {
    if (
      !isRecord(match) ||
      !exactKeys(match, ["path", "bytes", "modifiedAtMs"], new Set(["path", "bytes", "modifiedAtMs"])) ||
      !validReference(match.path) ||
      !Number.isSafeInteger(match.bytes) ||
      match.bytes < 0 ||
      match.bytes > 5 * 1_024 * 1_024 ||
      !Number.isSafeInteger(match.modifiedAtMs) ||
      match.modifiedAtMs < 0
    ) {
      fail("RECEIPT_INVALID");
    }
  }
  return value;
}

function parseReadSummary(value, proposal) {
  const keys = ["path", "content", "bytes", "truncated", "sha256"];
  if (!isRecord(value) || !exactKeys(value, keys, new Set(keys))) fail("RECEIPT_INVALID");
  const encoded = typeof value.content === "string" ? Buffer.from(value.content, "utf8") : null;
  if (
    value.path !== proposal.arguments.path ||
    encoded === null ||
    value.content.includes("\u0000") ||
    !Number.isSafeInteger(value.bytes) ||
    value.bytes !== encoded.length ||
    value.bytes > (proposal.arguments.maxBytes ?? 16 * 1_024) ||
    typeof value.truncated !== "boolean" ||
    !HASH_PATTERN.test(value.sha256 ?? "") ||
    value.sha256 !== `sha256:${createHash("sha256").update(encoded).digest("hex")}`
  ) {
    fail("RECEIPT_INVALID");
  }
  return value;
}

function parseReceipt(value, proposal) {
  const receipt = parsePayload(value, MAX_WINDOWS_RESPONSE_BYTES);
  if (!exactKeys(receipt, RECEIPT_REQUIRED_KEYS, RECEIPT_ALLOWED_KEYS)) fail("RECEIPT_INVALID");
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
    fail("RECEIPT_INVALID");
  }
  if (receipt.status === "COMPLETED") {
    if ("errorCode" in receipt) fail("RECEIPT_INVALID");
    receipt.resultSummary = proposal.capability === WINDOWS_SEARCH_CAPABILITY
      ? parseSearchSummary(receipt.resultSummary)
      : parseReadSummary(receipt.resultSummary, proposal);
  } else if (
    "resultSummary" in receipt ||
    !ERROR_CODE_PATTERN.test(receipt.errorCode ?? "")
  ) {
    fail("RECEIPT_INVALID");
  }
  return receipt;
}

function selectNode(nodes, nodeId, commands, unavailableCode, commandCode) {
  const matches = nodes.filter((node) => node.nodeId === nodeId);
  if (matches.length !== 1 || matches[0].connected === false) fail(unavailableCode);
  if (!commands.every((command) => matches[0].commands?.includes(command))) fail(commandCode);
  return matches[0];
}

function terminalReceipt(proposal, startedAtMs, status, errorCode) {
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
    status,
    startedAtMs,
    finishedAtMs: Math.max(startedAtMs, Date.now()),
    errorCode,
  };
}

function controlledCode(error, fallback) {
  return error instanceof WindowsFilesToolError ? error.code : fallback;
}

function receiptForLedger(receipt) {
  if (receipt.status !== "COMPLETED") return receipt;
  const resultSummary = receipt.capability === WINDOWS_SEARCH_CAPABILITY
    ? {
        matchCount: receipt.resultSummary.matches.length,
        truncated: receipt.resultSummary.truncated,
      }
    : {
        bytes: receipt.resultSummary.bytes,
        truncated: receipt.resultSummary.truncated,
        sha256: receipt.resultSummary.sha256,
      };
  return { ...receipt, resultSummary };
}

function resultForModel(receipt) {
  const payload = receipt.status === "COMPLETED"
    ? { status: receipt.status, ...receipt.resultSummary }
    : { status: receipt.status, errorCode: receipt.errorCode };
  return {
    content: [{ type: "text", text: JSON.stringify(payload) }],
    details: payload,
  };
}

async function executeWindowsFiles({
  api,
  ledger,
  signingIdentity,
  androidNodeId,
  windowsNodeId,
  voiceSessionKey,
  capability,
  args,
}) {
  const { nodes } = await api.runtime.nodes.list({ connected: true });
  const phoneCommands = capability === WINDOWS_READ_CAPABILITY
    ? [PRESENCE_COMMAND, ANDROID_EXECUTE_COMMAND]
    : [PRESENCE_COMMAND];
  selectNode(
    nodes,
    androidNodeId,
    phoneCommands,
    "ANDROID_NODE_UNAVAILABLE",
    "ANDROID_SIGNED_COMMAND_UNAVAILABLE",
  );
  selectNode(
    nodes,
    windowsNodeId,
    [WINDOWS_EXECUTE_COMMAND],
    "WINDOWS_NODE_UNAVAILABLE",
    "WINDOWS_SIGNED_COMMAND_UNAVAILABLE",
  );
  const presence = parsePresence(await api.runtime.nodes.invoke({
    nodeId: androidNodeId,
    command: PRESENCE_COMMAND,
    params: {},
    timeoutMs: PRESENCE_TIMEOUT_MS,
    idempotencyKey: randomUUID(),
  }));
  const planId = randomUUID();
  ledger.createPlan({
    planId,
    voiceSessionKey,
    capabilitySnapshotHash: canonicalHash({
      contractVersion: CONTRACT_VERSION,
      capabilities: [capability],
      targetDeviceId: windowsNodeId,
    }),
  });
  const proposal = createProposal({
    capability,
    arguments: args,
    targetDeviceId: windowsNodeId,
    voiceSessionKey,
    presenceLeaseId: presence.presenceLeaseId,
    planId,
    lifetimeMs: 60_000,
  });
  const signed = signingIdentity.sign(proposal);
  ledger.recordProposal(signed);
  const startedAtMs = Date.now();
  let receipt;
  if (capability === WINDOWS_READ_CAPABILITY) {
    try {
      const authorization = parseAuthorization(await api.runtime.nodes.invoke({
        nodeId: androidNodeId,
        command: ANDROID_EXECUTE_COMMAND,
        params: signed,
        timeoutMs: AUTHORIZATION_TIMEOUT_MS,
        idempotencyKey: proposal.idempotencyKey,
      }), proposal);
      if (!authorization.authorized) {
        receipt = terminalReceipt(proposal, startedAtMs, "DENIED", authorization.errorCode);
      }
    } catch (error) {
      receipt = terminalReceipt(
        proposal,
        startedAtMs,
        "UNKNOWN",
        controlledCode(error, "WINDOWS_AUTHORIZATION_UNAVAILABLE"),
      );
    }
  }
  if (!receipt) {
    try {
      receipt = parseReceipt(await api.runtime.nodes.invoke({
        nodeId: windowsNodeId,
        command: WINDOWS_EXECUTE_COMMAND,
        params: signed,
        timeoutMs: WINDOWS_COMMAND_TIMEOUT_MS,
        idempotencyKey: proposal.idempotencyKey,
      }), proposal);
    } catch (error) {
      receipt = terminalReceipt(
        proposal,
        startedAtMs,
        "UNKNOWN",
        controlledCode(error, "WINDOWS_NODE_INVOKE_UNAVAILABLE"),
      );
    }
  }
  ledger.recordReceipt(receiptForLedger(receipt));
  return resultForModel(receipt);
}

export async function searchWindowsFiles(options) {
  const query = typeof options.request?.query === "string" ? options.request.query.trim() : "";
  const limit = options.request?.limit ?? 20;
  if (!query || query.length > 200 || !Number.isSafeInteger(limit) || limit < 1 || limit > 50) {
    fail("ARGUMENT_SCHEMA");
  }
  return executeWindowsFiles({
    ...options,
    capability: WINDOWS_SEARCH_CAPABILITY,
    args: { query, limit },
  });
}

export async function readWindowsFile(options) {
  const path = options.request?.path;
  const maxBytes = options.request?.maxBytes ?? 16 * 1_024;
  if (
    !validReference(path) ||
    !Number.isSafeInteger(maxBytes) ||
    maxBytes < 1 ||
    maxBytes > 65_536
  ) {
    fail("ARGUMENT_SCHEMA");
  }
  return executeWindowsFiles({
    ...options,
    capability: WINDOWS_READ_CAPABILITY,
    args: { path, maxBytes },
  });
}

export function registerWindowsFileTools(api, state) {
  const searchEnabled = api.pluginConfig?.windowsFileSearchEnabled === true;
  const readEnabled = api.pluginConfig?.windowsFileReadEnabled === true;
  if (!searchEnabled && !readEnabled) return 0;
  const androidNodeId = api.pluginConfig?.androidNodeId;
  const windowsNodeId = api.pluginConfig?.windowsNodeId;
  if (!NODE_ID_PATTERN.test(androidNodeId ?? "") || !NODE_ID_PATTERN.test(windowsNodeId ?? "")) {
    throw new Error("assistant-capability-broker requires valid androidNodeId and windowsNodeId values");
  }
  const agentId = typeof api.pluginConfig?.windowsFileAgentId === "string"
    ? api.pluginConfig.windowsFileAgentId
    : "voice-main";
  const voiceSessionKey = `agent:${agentId}:voice-android-${androidNodeId.slice(0, 32)}`;
  const execute = (operation) => async (_toolCallId, request) => {
    const ledger = state.ledger();
    const signingIdentity = state.signingIdentity();
    if (!ledger || !signingIdentity) throw new Error("assistant capability broker is unavailable");
    return operation({
      api,
      ledger,
      signingIdentity,
      androidNodeId,
      windowsNodeId,
      voiceSessionKey,
      request,
    });
  };
  let registered = 0;
  if (searchEnabled) {
    api.registerTool({
      name: WINDOWS_SEARCH_TOOL_NAME,
      label: "Search approved Windows folders",
      description: "Search file names and metadata only inside user-selected Windows roots while the paired phone is unlocked. Returns opaque root references, never absolute paths, and cannot run shell commands.",
      parameters: {
        type: "object",
        required: ["query"],
        properties: {
          query: { type: "string", minLength: 1, maxLength: 200 },
          limit: { type: "integer", minimum: 1, maximum: 50, default: 20 },
        },
        additionalProperties: false,
      },
      execute: execute(searchWindowsFiles),
    }, { names: [WINDOWS_SEARCH_TOOL_NAME], optional: true });
    registered += 1;
  }
  if (readEnabled) {
    api.registerTool({
      name: WINDOWS_READ_TOOL_NAME,
      label: "Read an approved Windows text file",
      description: "Read bounded UTF-8 content from one opaque path returned by Windows file search. The paired phone must be unlocked and the first read in the voice session requires a secure 10-minute on-phone approval.",
      parameters: {
        type: "object",
        required: ["path"],
        properties: {
          path: { type: "string", minLength: 3, maxLength: 1024 },
          maxBytes: { type: "integer", minimum: 1, maximum: 65_536, default: 16_384 },
        },
        additionalProperties: false,
      },
      execute: execute(readWindowsFile),
    }, { names: [WINDOWS_READ_TOOL_NAME], optional: true });
    registered += 1;
  }
  return registered;
}
