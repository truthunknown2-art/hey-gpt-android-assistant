import path from "node:path";
import { BrokerLedger } from "./ledger.js";
import { ExplicitMemoryStore } from "./memory.js";

let ledger = null;
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
  api.registerTool((context) => {
    if (
      context.agentId !== memoryAgentId ||
      context.senderIsOwner === false ||
      typeof context.workspaceDir !== "string"
    ) {
      return null;
    }
    const store = memoryStore(context.workspaceDir);
    return [{
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
      execute: async (_toolCallId, request) => jsonResult(store.remember(request)),
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
      execute: async (_toolCallId, request) => jsonResult(store.forget(request)),
    }];
  }, { names: MEMORY_TOOL_NAMES, optional: true });
  return MEMORY_TOOL_NAMES.length;
}

export function registerBroker(api) {
  const modelToolsRegistered = registerMemoryTools(api);
  api.registerService({
    id: "assistant-capability-broker",
    start: async (ctx) => {
      ledger = new BrokerLedger(path.join(ctx.stateDir, "assistant-capability-broker"));
    },
    stop: async () => {
      closeMemoryStores();
      ledger?.close();
      ledger = null;
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
    },
    additionalProperties: false,
  },
  register(api) {
    registerBroker(api);
  },
};
