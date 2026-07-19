import path from "node:path";
import { BrokerLedger } from "./ledger.js";
import { ExplicitMemoryStore } from "./memory.js";
import { BrokerSigningIdentityV1 } from "./signing-identity-v1.js";
import { registerPrivateReadTools } from "./private-read-tools.js";
import { registerWindowsFileTools } from "./windows-files-tools.js";
import { registerWindowsNodeHostCommand } from "./windows-node-command.js";

let ledger = null;
let signingIdentity = null;
const memoryStores = new Map();

const MEMORY_TOOL_NAMES = ["assistant_memory_remember", "assistant_memory_forget"];

function jsonResult(payload) {
  return {
    content: [{ type: "text", text: JSON.stringify(payload, null, 2) }],
    details: payload,
  };
}

function closeMemoryStores() {
  for (const store of memoryStores.values()) store.close();
  memoryStores.clear();
}

function memoryStore(workspaceDir) {
  const key = path.resolve(workspaceDir);
  let store = memoryStores.get(key);
  if (!store) {
    store = new ExplicitMemoryStore(key);
    memoryStores.set(key, store);
  }
  return store;
}

function registerMemoryTools(api) {
  if (api.pluginConfig?.memoryEnabled !== true) return 0;
  const memoryAgentId = typeof api.pluginConfig?.memoryAgentId === "string"
    ? api.pluginConfig.memoryAgentId
    : "voice-main";
  const agentMatches = (api.config?.agents?.list ?? []).filter((agent) => agent?.id === memoryAgentId);
  const workspaceDir = agentMatches.length === 1 && typeof agentMatches[0].workspace === "string"
    ? agentMatches[0].workspace.trim()
    : "";
  if (!workspaceDir) {
    throw new Error("assistant-capability-broker requires one configured memory agent workspace");
  }
  const tools = [{
    name: "assistant_memory_remember",
    label: "Remember explicit fact",
    description: "Persist one low-sensitivity fact only when the user explicitly asks to remember it. Never store message bodies, notification content, credentials, payment data, security answers, tokens, or facts inferred automatically.",
    parameters: {
      type: "object",
      required: ["category", "fact"],
      properties: {
        category: { type: "string", enum: ["preference", "person", "project", "decision", "other"] },
        fact: { type: "string", minLength: 1, maxLength: 500 },
      },
      additionalProperties: false,
    },
    execute: async (_toolCallId, request) => jsonResult(memoryStore(workspaceDir).remember(request)),
  }, {
    name: "assistant_memory_forget",
    label: "Forget explicit fact",
    description: "Forget one previously stored explicit memory by its exact memory ID and return a durable receipt.",
    parameters: {
      type: "object",
      required: ["memoryId"],
      properties: {
        memoryId: { type: "string", pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" },
      },
      additionalProperties: false,
    },
    execute: async (_toolCallId, request) => jsonResult(memoryStore(workspaceDir).forget(request)),
  }];
  for (const tool of tools) {
    api.registerTool(tool, { name: tool.name, optional: true });
  }
  return MEMORY_TOOL_NAMES.length;
}

export function registerBroker(api) {
  const modelToolsRegistered = registerMemoryTools(api) + registerPrivateReadTools(api, {
    ledger: () => ledger,
    signingIdentity: () => signingIdentity,
  }) + registerWindowsFileTools(api, {
    ledger: () => ledger,
    signingIdentity: () => signingIdentity,
  });
  registerWindowsNodeHostCommand(api);
  api.registerService({
    id: "assistant-capability-broker",
    start: async (ctx) => {
      const brokerStateDir = path.join(ctx.stateDir, "assistant-capability-broker");
      signingIdentity = new BrokerSigningIdentityV1(brokerStateDir);
      ledger = new BrokerLedger(brokerStateDir);
    },
    stop: async () => {
      closeMemoryStores();
      ledger?.close();
      ledger = null;
      signingIdentity = null;
    },
  });
  api.registerGatewayMethod(
    "assistant.broker.status",
    async ({ respond }) => {
      if (!ledger) {
        respond(false, { error: "broker_not_running" });
        return;
      }
      respond(true, ledger.status(modelToolsRegistered));
    },
    { scope: "operator.read" },
  );
  api.registerGatewayMethod(
    "assistant.broker.publicKey",
    async ({ respond }) => {
      if (!signingIdentity) {
        respond(false, { error: "broker_not_running" });
        return;
      }
      respond(true, signingIdentity.publicDescriptor());
    },
    { scope: "operator.read" },
  );
}

export default {
  id: "assistant-capability-broker",
  name: "Assistant Capability Broker",
  description: "Durable typed plans and receipts for the agentic assistant.",
  configSchema: {
    type: "object",
    properties: {
      memoryEnabled: { type: "boolean", default: false },
      memoryAgentId: { type: "string", pattern: "^[a-z0-9][a-z0-9_-]{0,63}$", default: "voice-main" },
      privateReadsEnabled: { type: "boolean", default: false },
      phoneCallsEnabled: { type: "boolean", default: false },
      smsSendEnabled: { type: "boolean", default: false },
      calendarReadsEnabled: { type: "boolean", default: false },
      calendarWritesEnabled: { type: "boolean", default: false },
      messengerReadsEnabled: { type: "boolean", default: false },
      privateReadAgentId: { type: "string", pattern: "^[a-z0-9][a-z0-9_-]{0,63}$", default: "voice-main" },
      androidNodeId: { type: "string", pattern: "^[a-f0-9]{64}$" },
      windowsFileSearchEnabled: { type: "boolean", default: false },
      windowsFileReadEnabled: { type: "boolean", default: false },
      windowsFileAgentId: { type: "string", pattern: "^[a-z0-9][a-z0-9_-]{0,63}$", default: "voice-main" },
      windowsNodeId: { type: "string", pattern: "^[a-f0-9]{64}$" },
      windowsNodeEnabled: { type: "boolean", default: false },
      windowsVoiceSessionKey: { type: "string", minLength: 1, maxLength: 256 },
      windowsBrokerKeyId: { type: "string", pattern: "^[A-Za-z0-9._-]{1,128}$" },
      windowsBrokerPublicKeyBase64Url: { type: "string", pattern: "^[A-Za-z0-9_-]{43}$" },
      windowsReadRoots: {
        type: "object",
        minProperties: 1,
        maxProperties: 10,
        propertyNames: { pattern: "^[a-z][a-z0-9_-]{0,31}$" },
        additionalProperties: { type: "string", minLength: 3 },
      },
      windowsReadExtensions: {
        type: "array",
        minItems: 1,
        maxItems: 100,
        items: { type: "string", pattern: "^\\.[A-Za-z0-9]{1,12}$" },
      },
    },
    additionalProperties: false,
  },
  register(api) {
    registerBroker(api);
  },
};
