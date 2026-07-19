import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { it } from "node:test";
import plugin from "../dist/index.js";

it("registers durable infrastructure and no model tools", async () => {
  let service;
  let gatewayMethod;
  const api = {
    registerService(value) { service = value; },
    registerGatewayMethod(name, handler, options) {
      gatewayMethod = { name, handler, options };
    },
    registerTool() { throw new Error("broker foundation must not register a model tool"); },
  };
  plugin.register(api);

  assert.equal(service.id, "assistant-capability-broker");
  assert.equal(gatewayMethod.name, "assistant.broker.status");
  assert.deepEqual(gatewayMethod.options, { scope: "operator.read" });

  const stateDir = mkdtempSync(path.join(tmpdir(), "assistant-broker-plugin-"));
  try {
    await service.start({ stateDir });
    let response;
    await gatewayMethod.handler({
      respond(ok, payload) { response = { ok, payload }; },
    });
    assert.deepEqual(response, {
      ok: true,
      payload: { plans: 0, pendingProposals: 0, terminalReceipts: 0, modelToolsRegistered: 0 },
    });
    await service.stop();
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});

it("registers agent-bound optional memory tools only when explicitly enabled", async () => {
  let service;
  let gatewayMethod;
  let toolFactory;
  let toolOptions;
  const api = {
    pluginConfig: { memoryEnabled: true, memoryAgentId: "voice-main" },
    registerService(value) { service = value; },
    registerGatewayMethod(name, handler, options) {
      gatewayMethod = { name, handler, options };
    },
    registerTool(factory, options) {
      toolFactory = factory;
      toolOptions = options;
    },
  };
  plugin.register(api);

  assert.deepEqual(toolOptions, {
    names: ["assistant_memory_remember", "assistant_memory_forget"],
    optional: true,
  });
  assert.equal(toolFactory({ agentId: "locked-voice", workspaceDir: "unused" }), null);
  assert.equal(toolFactory({ agentId: "voice-main", workspaceDir: "unused", senderIsOwner: false }), null);

  const stateDir = mkdtempSync(path.join(tmpdir(), "assistant-broker-state-"));
  const workspaceDir = mkdtempSync(path.join(tmpdir(), "assistant-broker-memory-"));
  try {
    await service.start({ stateDir });
    const tools = toolFactory({ agentId: "voice-main", workspaceDir });
    assert.deepEqual(tools.map((tool) => tool.name), [
      "assistant_memory_remember",
      "assistant_memory_forget",
    ]);
    const result = await tools[0].execute("tool-call", {
      category: "preference",
      fact: "Use Luna for voice.",
    });
    assert.equal(result.details.status, "COMPLETED");
    assert.equal(Object.hasOwn(result.details, "fact"), false);

    let response;
    await gatewayMethod.handler({
      respond(ok, payload) { response = { ok, payload }; },
    });
    assert.equal(response.payload.modelToolsRegistered, 2);
    await service.stop();
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
    rmSync(workspaceDir, { recursive: true, force: true });
  }
});
