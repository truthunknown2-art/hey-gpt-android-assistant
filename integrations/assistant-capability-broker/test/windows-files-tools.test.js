import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { BrokerLedger } from "../dist/ledger.js";
import { BrokerSigningIdentityV1 } from "../dist/signing-identity-v1.js";
import {
  WINDOWS_READ_TOOL_NAME,
  WINDOWS_SEARCH_TOOL_NAME,
  readWindowsFile,
  registerWindowsFileTools,
  searchWindowsFiles,
} from "../dist/windows-files-tools.js";
import { WINDOWS_EXECUTE_COMMAND } from "../dist/windows-node-command.js";
import { EXECUTE_COMMAND, PRESENCE_COMMAND } from "../dist/private-read-tools.js";

const ANDROID_NODE_ID = "a".repeat(64);
const WINDOWS_NODE_ID = "b".repeat(64);
const SESSION = `agent:voice-main:voice-android-${ANDROID_NODE_ID.slice(0, 32)}`;
const PRESENCE_LEASE = "66666666-6666-4666-8666-666666666666";
const RECEIPT_ID = "55555555-5555-4555-8555-555555555555";

function receiptFor(proposal, resultSummary, overrides = {}) {
  return {
    contractVersion: 1,
    receiptId: RECEIPT_ID,
    proposalId: proposal.proposalId,
    planId: proposal.planId,
    stepId: proposal.stepId,
    capability: proposal.capability,
    argumentsHash: proposal.argumentsHash,
    targetDeviceId: proposal.targetDeviceId,
    idempotencyKey: proposal.idempotencyKey,
    status: "COMPLETED",
    startedAtMs: proposal.issuedAtMs,
    finishedAtMs: proposal.issuedAtMs + 1,
    resultSummary,
    ...overrides,
  };
}

function authorizationFor(proposal, authorized = true, errorCode = undefined) {
  return {
    contractVersion: 1,
    proposalId: proposal.proposalId,
    capability: "windows.files.read",
    argumentsHash: proposal.argumentsHash,
    targetDeviceId: proposal.targetDeviceId,
    authorized,
    ...(!authorized ? { errorCode } : {}),
  };
}

function fixture({ authorize = true, windowsResponse } = {}) {
  const directory = mkdtempSync(path.join(tmpdir(), "assistant-windows-tools-"));
  const signingIdentity = new BrokerSigningIdentityV1(path.join(directory, "signing"));
  const ledger = new BrokerLedger(path.join(directory, "ledger"));
  const calls = [];
  const api = {
    runtime: {
      nodes: {
        list: async () => ({
          nodes: [{
            nodeId: ANDROID_NODE_ID,
            connected: true,
            commands: [PRESENCE_COMMAND, EXECUTE_COMMAND],
          }, {
            nodeId: WINDOWS_NODE_ID,
            connected: true,
            commands: [WINDOWS_EXECUTE_COMMAND],
          }],
        }),
        invoke: async (input) => {
          calls.push(input);
          if (input.command === PRESENCE_COMMAND) {
            return { payload: { contractVersion: 1, presenceLeaseId: PRESENCE_LEASE } };
          }
          if (input.command === EXECUTE_COMMAND) {
            return {
              payloadJSON: JSON.stringify(authorizationFor(
                input.params.proposal,
                authorize,
                authorize ? undefined : "PRIVATE_READ_APPROVAL_DENIED",
              )),
            };
          }
          if (input.command === WINDOWS_EXECUTE_COMMAND) {
            const proposal = input.params.proposal;
            const defaultSummary = proposal.capability === "windows.files.search"
              ? {
                  matches: [{
                    path: "documents:Projects/Meeting Notes.md",
                    bytes: 31,
                    modifiedAtMs: 1_700_000_000_000,
                  }],
                  truncated: false,
                }
              : {
                  path: proposal.arguments.path,
                  content: "Agenda\nShip the signed helper.\n",
                  bytes: 31,
                  truncated: false,
                  sha256: "sha256:ce3889b02851ee7c44093568bf69ad146bf8ff8ae0aa96c96d109f7dbf3e0524",
                };
            return {
              payloadJSON: JSON.stringify(
                windowsResponse?.(proposal) ?? receiptFor(proposal, defaultSummary),
              ),
            };
          }
          throw new Error(`unexpected command ${input.command}`);
        },
      },
    },
  };
  return {
    directory,
    api,
    ledger,
    signingIdentity,
    calls,
    close() {
      ledger.close();
      rmSync(directory, { recursive: true, force: true });
    },
  };
}

function options(subject, request) {
  return {
    api: subject.api,
    ledger: subject.ledger,
    signingIdentity: subject.signingIdentity,
    androidNodeId: ANDROID_NODE_ID,
    windowsNodeId: WINDOWS_NODE_ID,
    voiceSessionKey: SESSION,
    request,
  };
}

describe("signed Windows file tools", () => {
  it("searches metadata through the fixed Windows command under live phone presence", async () => {
    const subject = fixture();
    try {
      const result = await searchWindowsFiles(options(subject, { query: "meeting", limit: 5 }));

      assert.equal(result.details.status, "COMPLETED");
      assert.equal(result.details.matches[0].path, "documents:Projects/Meeting Notes.md");
      assert.deepEqual(subject.calls.map((call) => call.command), [
        PRESENCE_COMMAND,
        WINDOWS_EXECUTE_COMMAND,
      ]);
      const signed = subject.calls[1].params;
      assert.equal(signed.proposal.targetDeviceId, WINDOWS_NODE_ID);
      assert.equal(signed.proposal.voiceSessionKey, SESSION);
      assert.equal(signed.proposal.presenceLeaseId, PRESENCE_LEASE);
      assert.equal(signed.proposal.risk, "LOW");
      const stored = subject.ledger.getReceiptByProposal(signed.proposal.proposalId);
      assert.equal(stored.result_summary_json, '{"matchCount":1,"truncated":false}');
      assert.equal(stored.result_summary_json.includes("Meeting"), false);
    } finally {
      subject.close();
    }
  });

  it("obtains exact phone authorization before returning bounded Windows content", async () => {
    const subject = fixture();
    try {
      const result = await readWindowsFile(options(subject, {
        path: "documents:Projects/Meeting Notes.md",
        maxBytes: 16_384,
      }));

      assert.equal(result.details.status, "COMPLETED");
      assert.equal(result.details.content, "Agenda\nShip the signed helper.\n");
      assert.deepEqual(subject.calls.map((call) => call.command), [
        PRESENCE_COMMAND,
        EXECUTE_COMMAND,
        WINDOWS_EXECUTE_COMMAND,
      ]);
      assert.deepEqual(subject.calls[1].params, subject.calls[2].params);
      const proposal = subject.calls[2].params.proposal;
      assert.equal(proposal.risk, "MEDIUM");
      const stored = subject.ledger.getReceiptByProposal(proposal.proposalId);
      assert.equal(
        stored.result_summary_json,
        '{"bytes":31,"sha256":"sha256:ce3889b02851ee7c44093568bf69ad146bf8ff8ae0aa96c96d109f7dbf3e0524","truncated":false}',
      );
      assert.equal(stored.result_summary_json.includes("Agenda"), false);
    } finally {
      subject.close();
    }
  });

  it("does not invoke Windows when the phone denies the private-read grant", async () => {
    const subject = fixture({ authorize: false });
    try {
      const result = await readWindowsFile(options(subject, {
        path: "documents:private.txt",
      }));

      assert.deepEqual(result.details, {
        status: "DENIED",
        errorCode: "PRIVATE_READ_APPROVAL_DENIED",
      });
      assert.deepEqual(subject.calls.map((call) => call.command), [
        PRESENCE_COMMAND,
        EXECUTE_COMMAND,
      ]);
      assert.equal(subject.ledger.status().pendingProposals, 0);
    } finally {
      subject.close();
    }
  });

  it("turns a privacy-smuggling Windows receipt into terminal UNKNOWN", async () => {
    const subject = fixture({
      windowsResponse: (proposal) => receiptFor(proposal, {
        path: proposal.arguments.path,
        content: "private",
        bytes: 7,
        truncated: false,
        sha256: "sha256:715dc8493c36579a2a8ca5c96b3a6b06497c4907f43d706e5c34809e24f97822",
        absolutePath: "C:\\Users\\kremo\\private.txt",
      }),
    });
    try {
      const result = await readWindowsFile(options(subject, { path: "documents:private.txt" }));

      assert.deepEqual(result.details, { status: "UNKNOWN", errorCode: "RECEIPT_INVALID" });
      const proposal = subject.calls.at(-1).params.proposal;
      assert.equal(subject.ledger.getReceiptByProposal(proposal.proposalId).error_code, "RECEIPT_INVALID");
    } finally {
      subject.close();
    }
  });

  it("registers only the two optional Windows tools when explicitly enabled", () => {
    const tools = [];
    const api = {
      pluginConfig: {
        windowsFileSearchEnabled: true,
        windowsFileReadEnabled: true,
        androidNodeId: ANDROID_NODE_ID,
        windowsNodeId: WINDOWS_NODE_ID,
      },
      registerTool: (tool) => tools.push(tool),
    };

    assert.equal(registerWindowsFileTools(api, {}), 2);
    assert.deepEqual(tools.map((tool) => tool.name), [
      WINDOWS_SEARCH_TOOL_NAME,
      WINDOWS_READ_TOOL_NAME,
    ]);
    assert.equal(registerWindowsFileTools({ ...api, pluginConfig: {} }, {}), 0);
  });
});
