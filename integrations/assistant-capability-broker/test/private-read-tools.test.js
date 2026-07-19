import assert from "node:assert/strict";
import { createPublicKey } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { verifySignedProposal } from "../dist/contract-v1.js";
import { BrokerLedger } from "../dist/ledger.js";
import {
  CALENDAR_CREATE_TOOL_NAME,
  CALENDAR_NEXT_TOOL_NAME,
  CONTACT_CALL_TOOL_NAME,
  CONTACT_SMS_TOOL_NAME,
  CONTACTS_TOOL_NAME,
  EXECUTE_COMMAND,
  MESSENGER_NOTIFICATIONS_TOOL_NAME,
  PRESENCE_COMMAND,
  callContactWithApproval,
  createCalendarEventWithApproval,
  readCalendarPrivately,
  readMessengerNotificationsPrivately,
  registerPrivateReadTools,
  searchContactsPrivately,
  sendContactSmsWithApproval,
} from "../dist/private-read-tools.js";
import { BrokerSigningIdentityV1 } from "../dist/signing-identity-v1.js";

const NODE_ID = "a".repeat(64);
const SESSION = `agent:voice-main:voice-android-${NODE_ID.slice(0, 32)}`;
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

function fixture({
  executeResponse,
  phoneCallsEnabled = false,
  smsSendEnabled = false,
  calendarReadsEnabled = false,
  calendarWritesEnabled = false,
  messengerReadsEnabled = false,
} = {}) {
  const stateDir = mkdtempSync(path.join(tmpdir(), "assistant-private-read-"));
  const ledger = new BrokerLedger(stateDir);
  const signingIdentity = new BrokerSigningIdentityV1(stateDir);
  const calls = [];
  const api = {
    pluginConfig: {
      privateReadsEnabled: true,
      phoneCallsEnabled,
      smsSendEnabled,
      calendarReadsEnabled,
      calendarWritesEnabled,
      messengerReadsEnabled,
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
  it("binds the tool to the configured voice agent session and Android node", async () => {
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
      // OpenClaw caches plugin descriptors without sessionKey. The static optional descriptor
      // derives the stable phone voice session from trusted agent/node config instead.
      const tool = factory;
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

  it("calls one locally resolved contact through a high-risk signed proposal", async () => {
    const subject = fixture({
      executeResponse: (proposal) => ({
        ok: true,
        payload: receiptFor(proposal, {
          resultSummary: { placedCall: true, requiresTap: false },
        }),
      }),
    });
    try {
      const result = await callContactWithApproval({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: { query: "Jen" },
      });

      assert.deepEqual(result.details, {
        status: "COMPLETED",
        placedCall: true,
        requiresTap: false,
      });
      const proposal = subject.calls[1].params.proposal;
      assert.equal(proposal.capability, "android.phone.call_contact");
      assert.equal(proposal.risk, "HIGH");
      assert.deepEqual(proposal.arguments, { query: "Jen" });
      assert.equal(JSON.stringify(result).includes("Jen"), false);
      assert.equal(subject.ledger.status().terminalReceipts, 1);
    } finally {
      subject.close();
    }
  });

  it("registers the optional call tool only when explicitly enabled", () => {
    const subject = fixture({ phoneCallsEnabled: true });
    const names = [];
    subject.api.registerTool = (tool) => names.push(tool.name);
    try {
      assert.equal(registerPrivateReadTools(subject.api, {
        ledger: () => subject.ledger,
        signingIdentity: () => subject.signingIdentity,
      }), 2);
      assert.deepEqual(names.sort(), [CONTACTS_TOOL_NAME, CONTACT_CALL_TOOL_NAME].sort());
    } finally {
      subject.close();
    }
  });

  it("rejects a contradictory call disposition receipt", async () => {
    const subject = fixture({
      executeResponse: (proposal) => ({
        ok: true,
        payload: receiptFor(proposal, {
          resultSummary: { placedCall: true, requiresTap: true },
        }),
      }),
    });
    try {
      const result = await callContactWithApproval({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: { query: "Jen" },
      });

      assert.deepEqual(result.details, {
        status: "UNKNOWN",
        errorCode: "RECEIPT_INVALID",
      });
    } finally {
      subject.close();
    }
  });

  it("sends one contact SMS through a high-risk signed proposal without leaking content", async () => {
    const subject = fixture({
      executeResponse: (proposal) => ({
        ok: true,
        payload: receiptFor(proposal, {
          resultSummary: { sent: true },
        }),
      }),
    });
    try {
      const result = await sendContactSmsWithApproval({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: { query: "Jen", message: "I will be there at six." },
      });

      assert.deepEqual(result.details, { status: "COMPLETED", sent: true });
      const proposal = subject.calls[1].params.proposal;
      assert.equal(proposal.capability, "android.sms.send_contact");
      assert.equal(proposal.risk, "HIGH");
      assert.deepEqual(proposal.arguments, {
        query: "Jen",
        message: "I will be there at six.",
      });
      assert.equal(JSON.stringify(result).includes("Jen"), false);
      assert.equal(JSON.stringify(result).includes("six"), false);
      assert.equal(subject.ledger.status().terminalReceipts, 1);
    } finally {
      subject.close();
    }
  });

  it("registers the optional SMS tool only when explicitly enabled", () => {
    const subject = fixture({ smsSendEnabled: true });
    const names = [];
    subject.api.registerTool = (tool) => names.push(tool.name);
    try {
      assert.equal(registerPrivateReadTools(subject.api, {
        ledger: () => subject.ledger,
        signingIdentity: () => subject.signingIdentity,
      }), 2);
      assert.deepEqual(names.sort(), [CONTACTS_TOOL_NAME, CONTACT_SMS_TOOL_NAME].sort());
    } finally {
      subject.close();
    }
  });

  it("turns an unconfirmed SMS completion into UNKNOWN", async () => {
    const subject = fixture({
      executeResponse: (proposal) => ({
        ok: true,
        payload: receiptFor(proposal, {
          resultSummary: { sent: false },
        }),
      }),
    });
    try {
      const result = await sendContactSmsWithApproval({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: { query: "Jen", message: "Test" },
      });

      assert.deepEqual(result.details, {
        status: "UNKNOWN",
        errorCode: "RECEIPT_INVALID",
      });
    } finally {
      subject.close();
    }
  });

  it("reads upcoming calendar events privately through a medium-risk proposal", async () => {
    const subject = fixture({
      executeResponse: (proposal) => ({
        ok: true,
        payload: receiptFor(proposal, {
          resultSummary: { eventCount: 2, truncated: false },
        }),
      }),
    });
    try {
      const result = await readCalendarPrivately({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: { afterEpochMs: 1_700_000_000_000, limit: 5 },
      });

      assert.deepEqual(result.details, {
        status: "COMPLETED",
        privateDelivery: "spoken_on_phone",
        eventCount: 2,
        truncated: false,
      });
      const proposal = subject.calls[1].params.proposal;
      assert.equal(proposal.capability, "android.calendar.next");
      assert.equal(proposal.risk, "MEDIUM");
      assert.deepEqual(proposal.arguments, { afterEpochMs: 1_700_000_000_000, limit: 5 });
      assert.equal(JSON.stringify(result).includes("Dentist"), false);
    } finally {
      subject.close();
    }
  });

  it("creates one calendar event through a high-risk proposal without leaking its title", async () => {
    const subject = fixture({
      executeResponse: (proposal) => ({
        ok: true,
        payload: receiptFor(proposal, { resultSummary: { created: true } }),
      }),
    });
    try {
      const startEpochMs = Date.now() + 60_000;
      const result = await createCalendarEventWithApproval({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: {
          title: "Dentist",
          startEpochMs,
          endEpochMs: startEpochMs + 3_600_000,
          allDay: false,
        },
      });

      assert.deepEqual(result.details, { status: "COMPLETED", created: true });
      const proposal = subject.calls[1].params.proposal;
      assert.equal(proposal.capability, "android.calendar.create");
      assert.equal(proposal.risk, "HIGH");
      assert.equal(proposal.arguments.title, "Dentist");
      assert.equal(JSON.stringify(result).includes("Dentist"), false);
    } finally {
      subject.close();
    }
  });

  it("registers calendar tools only behind their explicit flags", () => {
    const subject = fixture({ calendarReadsEnabled: true, calendarWritesEnabled: true });
    const names = [];
    subject.api.registerTool = (tool) => names.push(tool.name);
    try {
      assert.equal(registerPrivateReadTools(subject.api, {
        ledger: () => subject.ledger,
        signingIdentity: () => subject.signingIdentity,
      }), 3);
      assert.deepEqual(names.sort(), [
        CONTACTS_TOOL_NAME,
        CALENDAR_NEXT_TOOL_NAME,
        CALENDAR_CREATE_TOOL_NAME,
      ].sort());
    } finally {
      subject.close();
    }
  });

  it("rejects a calendar receipt that tries to smuggle event details", async () => {
    const subject = fixture({
      executeResponse: (proposal) => ({
        ok: true,
        payload: receiptFor(proposal, {
          resultSummary: { eventCount: 1, truncated: false, title: "Private appointment" },
        }),
      }),
    });
    try {
      const result = await readCalendarPrivately({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: {},
      });

      assert.deepEqual(result.details, {
        status: "UNKNOWN",
        privateDelivery: "not_delivered",
        errorCode: "RECEIPT_INVALID",
      });
      assert.equal(JSON.stringify(result).includes("Private appointment"), false);
    } finally {
      subject.close();
    }
  });

  it("reads Messenger previews through a medium-risk private-delivery proposal", async () => {
    const subject = fixture({
      messengerReadsEnabled: true,
      executeResponse: (proposal) => ({
        ok: true,
        payload: receiptFor(proposal, {
          resultSummary: { notificationCount: 1, truncated: false },
        }),
      }),
    });
    try {
      const result = await readMessengerNotificationsPrivately({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: { sender: "  JEN\u00a0  Thorndale ", limit: 1 },
      });

      assert.deepEqual(result.details, {
        status: "COMPLETED",
        privateDelivery: "spoken_on_phone",
        notificationCount: 1,
        truncated: false,
      });
      const proposal = subject.calls[1].params.proposal;
      assert.equal(proposal.capability, "android.messenger.notifications.read");
      assert.equal(proposal.risk, "MEDIUM");
      assert.deepEqual(proposal.arguments, { sender: "jen thorndale", limit: 1 });
      assert.equal(JSON.stringify(result).includes("See you at seven"), false);
    } finally {
      subject.close();
    }
  });

  it("registers Messenger private read only behind its explicit broker flag", () => {
    const subject = fixture({ messengerReadsEnabled: true });
    const names = [];
    subject.api.registerTool = (tool) => names.push(tool.name);
    try {
      assert.equal(registerPrivateReadTools(subject.api, {
        ledger: () => subject.ledger,
        signingIdentity: () => subject.signingIdentity,
      }), 2);
      assert.deepEqual(names.sort(), [CONTACTS_TOOL_NAME, MESSENGER_NOTIFICATIONS_TOOL_NAME].sort());
    } finally {
      subject.close();
    }
  });

  it("rejects Messenger receipts that smuggle preview text", async () => {
    const subject = fixture({
      executeResponse: (proposal) => ({
        ok: true,
        payload: receiptFor(proposal, {
          resultSummary: {
            notificationCount: 1,
            truncated: false,
            textPreview: "private preview",
          },
        }),
      }),
    });
    try {
      const result = await readMessengerNotificationsPrivately({
        api: subject.api,
        ledger: subject.ledger,
        signingIdentity: subject.signingIdentity,
        nodeId: NODE_ID,
        voiceSessionKey: SESSION,
        request: {},
      });

      assert.deepEqual(result.details, {
        status: "UNKNOWN",
        privateDelivery: "not_delivered",
        errorCode: "RECEIPT_INVALID",
      });
      assert.equal(JSON.stringify(result).includes("private preview"), false);
    } finally {
      subject.close();
    }
  });
});
