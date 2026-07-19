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
