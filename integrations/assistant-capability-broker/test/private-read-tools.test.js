import assert from "node:assert/strict";
import { createPublicKey } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { verifySignedProposal } from "../dist/contract-v1.js";
import { BrokerLedger } from "../dist/ledger.js";
import {
  CONTACTS_TOOL_NAME,
  EXECUTE_COMMAND,
  PRESENCE_COMMAND,
  registerPrivateReadTools,
  searchContactsPrivately,
} from "../dist/private-read-tools.js";
import { BrokerSigningIdentityV1 } from "../dist/signing-identity-v1.js";

const NODE_ID = "a".repeat(64);
const SESSION = "agent:voice-main:voice-android-device";
const SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");

function receiptFor(proposal, overrides = {}) {
  const now = Date.now();
  return {
    contractVersion: 1,
    receiptId: "55555555-5555-4555-8555-555555555555",
    proposalId: proposal.proposalId,
    planId: proposal.planId,
    stepId: proposal.stepId,
    capability: proposal.capability,
    argumentsHash: proposal.argumentsHash,
    targetDeviceId: proposal.targetDeviceId,
    idempotencyKey: proposal.idempotencyKey,
    status: "COMPLETED",
    startedAtMs: now,
    finishedAtMs: now,
    resultSummary: { matchCount: 1, truncated: false },
    ...overrides,
  };
}

function fixture({ executeResponse } = {}) {
  const stateDir = mkdtempSync(path.join(tmpdir(), "assistant-private-read-"));
  const ledger = new BrokerLedger(stateDir);
  const signingIdentity = new BrokerSigningIdentityV1(stateDir);
  const calls = [];
  const api = {
    pluginConfig: {
      privateReadsEnabled: true,
      privateReadAgentId: "voice-main",
      androidNodeId: NODE_ID,
    },
    runtime: {
      nodes: {
        list: async () => ({
          nodes: [{
            nodeId: NODE_ID,
            connected: true,
            commands: [PRESENCE_COMMAND, EXECUTE_COMMAND],
          }],
        }),
        invoke: async (request) => {
          calls.push(request);
          if (request.command === PRESENCE_COMMAND) {
            return {
              ok: true,
              payload: {
                contractVersion: 1,
                presenceLeaseId: "66666666-6666-4666-8666-666666666666",
              },
            };
          }
          const signed = request.params;
          const descriptor = signingIdentity.publicDescriptor();
          const publicKey = createPublicKey({
            key: Buffer.concat([
              SPKI_PREFIX,
              Buffer.from(descriptor.publicKeyBase64Url, "base64url"),
            ]),
            format: "der",
            type: "spki",
          });
          verifySignedProposal(signed, {
            publicKey,
            expectedDeviceId: NODE_ID,
            expectedVoiceSessionKey: SESSION,
          });
          return executeResponse?.(signed.proposal) ?? {
            ok: true,
            payload: receiptFor(signed.proposal),
          };
        },
      },
    },
  };
  return {
    api,
    ledger,
    signingIdentity,
    calls,
    close() {
      ledger.close();
      rmSync(stateDir, { recursive: true, force: true });
    },
  };
}

describe("signed private-read tools", () => {
  it("binds the tool to voice-main owner context and the configured Android node", async () => {
    const subject = fixture();
    let factory;
    let options;
    subject.api.registerTool = (value, valueOptions) => {
      factory = value;
      options = valueOptions;
    };
    try {
      assert.equal(registerPrivateReadTools(subject.api, {
        ledger: () => subject.ledger,
        signingIdentity: () => subject.signingIdentity,
      }), 1);
      assert.deepEqual(options, { names: [CONTACTS_TOOL_NAME], optional: true });
      assert.equal(factory({ agentId: "main", sessionKey: SESSION }), null);
      assert.equal(factory({ agentId: "voice-main", sessionKey: SESSION, senderIsOwner: false }), null);
      assert.equal(factory({ agentId: "voice-main", sessionKey: "agent:main:wrong" }), null);

      const tool = factory({ agentId: "voice-main", sessionKey: SESSION, senderIsOwner: true });
      const result = await tool.execute("tool-call", { query: "Jen", limit: 1 });

      assert.equal(result.details.status, "COMPLETED");
      assert.equal(result.details.privateDelivery, "spoken_on_phone");
      assert.equal(result.details.matchCount, 1);
      assert.equal(subject.calls.length, 2);
      assert.deepEqual(subject.calls.map((call) => call.command), [PRESENCE_COMMAND, EXECUTE_COMMAND]);
      assert.equal(subject.calls[1].nodeId, NODE_ID);
      assert.equal(subject.calls[1].params.proposal.voiceSessionKey, SESSION);
      assert.deepEqual(subject.calls[1].params.proposal.arguments, { query: "Jen", limit: 1 });
      assert.equal(subject.ledger.status(1).terminalReceipts, 1);
      assert.equal(JSON.stringify(result).includes("Jen"), false);
    } finally {
      subject.close();
    }
  });

  it("turns a malformed or privacy-smuggling node receipt into UNKNOWN", async () => {
    const subject = fixture({
      executeResponse: (proposal) => ({
        ok: true,
        payload: {
          ...receiptFor(proposal),
          privateResult: { displayName: "Jen", phoneNumber: "+1 250 555 0100" },
        },
      }),
    });
    try {
      const result = await searchContactsPrivately({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: { query: "Jen" },
      });

      assert.deepEqual(result.details, {
        status: "UNKNOWN",
        privateDelivery: "not_delivered",
        errorCode: "RECEIPT_INVALID",
      });
      assert.equal(JSON.stringify(result).includes("Thorndale"), false);
      assert.equal(subject.ledger.status().pendingProposals, 0);
      assert.equal(subject.ledger.status().terminalReceipts, 1);
    } finally {
      subject.close();
    }
  });

  it("does not create a plan when unlocked presence is unavailable", async () => {
    const subject = fixture();
    subject.api.runtime.nodes.invoke = async (request) => {
      subject.calls.push(request);
      return { ok: false, error: { code: "UNLOCKED_PRESENCE_REQUIRED" } };
    };
    try {
      await assert.rejects(
        searchContactsPrivately({
          api: subject.api,
          ledger: subject.ledger,
          signingIdentity: subject.signingIdentity,
          nodeId: NODE_ID,
          voiceSessionKey: SESSION,
          request: { query: "Jen" },
        }),
        /NODE_COMMAND_REJECTED/,
      );
      assert.deepEqual(subject.ledger.status(), {
        plans: 0,
        pendingProposals: 0,
        terminalReceipts: 0,
        modelToolsRegistered: 0,
      });
    } finally {
      subject.close();
    }
  });

  it("rejects malformed model arguments before touching the node", async () => {
    const subject = fixture();
    try {
      await assert.rejects(
        searchContactsPrivately({
          api: subject.api,
          ledger: subject.ledger,
          signingIdentity: subject.signingIdentity,
          nodeId: NODE_ID,
          voiceSessionKey: SESSION,
          request: { query: 123 },
        }),
        /ARGUMENT_SCHEMA/,
      );
      assert.equal(subject.calls.length, 0);
    } finally {
      subject.close();
    }
  });
});
