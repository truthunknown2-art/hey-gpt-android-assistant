import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { it } from "node:test";
import plugin from "../dist/index.js";

it("registers durable infrastructure and no model tools by default", async () => {
  let service;
  const gatewayMethods = new Map();
  const api = {
    registerService(value) { service = value; },
    registerGatewayMethod(name, handler, options) {
      gatewayMethods.set(name, { name, handler, options });
    },
    registerTool() { throw new Error("broker foundation must not register a model tool"); },
  };
  plugin.register(api);

  assert.equal(service.id, "assistant-capability-broker");
  assert.deepEqual([...gatewayMethods.keys()], [
    "assistant.broker.status",
    "assistant.broker.publicKey",
  ]);
  assert.deepEqual(gatewayMethods.get("assistant.broker.status").options, { scope: "operator.read" });
  assert.deepEqual(gatewayMethods.get("assistant.broker.publicKey").options, { scope: "operator.read" });

  const stateDir = mkdtempSync(path.join(tmpdir(), "assistant-broker-plugin-"));
  try {
    await service.start({ stateDir });
    let response;
    await gatewayMethods.get("assistant.broker.status").handler({
      respond(ok, payload) { response = { ok, payload }; },
    });
    assert.deepEqual(response, {
      ok: true,
      payload: { plans: 0, pendingProposals: 0, terminalReceipts: 0, modelToolsRegistered: 0 },
    });
    await gatewayMethods.get("assistant.broker.publicKey").handler({
      respond(ok, payload) { response = { ok, payload }; },
    });
    assert.equal(response.ok, true);
    assert.equal(response.payload.algorithm, "Ed25519");
    assert.match(response.payload.keyId, /^assistant-v1-[0-9a-f]{24}$/);
    assert.equal(Buffer.from(response.payload.publicKeyBase64Url, "base64url").length, 32);
    assert.equal(Object.hasOwn(response.payload, "privateKeyPkcs8Base64Url"), false);
    await service.stop();
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
  }
});

it("registers agent-bound optional memory tools only when explicitly enabled", async () => {
  let service;
  const gatewayMethods = new Map();
  let toolFactory;
  let toolOptions;
  const api = {
    pluginConfig: { memoryEnabled: true, memoryAgentId: "voice-main" },
    registerService(value) { service = value; },
    registerGatewayMethod(name, handler, options) {
      gatewayMethods.set(name, { name, handler, options });
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
    await gatewayMethods.get("assistant.broker.status").handler({
      respond(ok, payload) { response = { ok, payload }; },
    });
    assert.equal(response.payload.modelToolsRegistered, 2);
    await service.stop();
  } finally {
    rmSync(stateDir, { recursive: true, force: true });
    rmSync(workspaceDir, { recursive: true, force: true });
  }
});
