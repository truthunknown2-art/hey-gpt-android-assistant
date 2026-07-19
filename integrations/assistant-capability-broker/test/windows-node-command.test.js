import assert from "node:assert/strict";
import {
  mkdtempSync,
  linkSync,
  mkdirSync,
  openSync as nativeOpenSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { createProposal } from "../dist/contract-v1.js";
import { BrokerSigningIdentityV1 } from "../dist/signing-identity-v1.js";
import {
  WINDOWS_EXECUTE_COMMAND,
  WindowsFilesNodeExecutorV1,
  registerWindowsNodeHostCommand,
} from "../dist/windows-node-command.js";

const WINDOWS_NODE_ID = "b".repeat(64);
const VOICE_SESSION = `agent:voice-main:voice-android-${"a".repeat(32)}`;
const PRESENCE_LEASE = "66666666-6666-4666-8666-666666666666";

function fixture({ fileOps = {} } = {}) {
  const directory = mkdtempSync(path.join(tmpdir(), "assistant-windows-node-"));
  const root = path.join(directory, "Documents");
  mkdirSync(path.join(root, "Projects"), { recursive: true });
  mkdirSync(path.join(root, "node_modules"), { recursive: true });
  mkdirSync(path.join(root, "AppData", "Local"), { recursive: true });
  writeFileSync(path.join(root, "Projects", "Meeting Notes.md"), "Agenda\nShip the signed helper.\n", "utf8");
  writeFileSync(path.join(root, "Projects", "data.bin"), Buffer.from([0, 1, 2, 3]));
  writeFileSync(path.join(root, "credentials.txt"), "never expose", "utf8");
  writeFileSync(path.join(root, "node_modules", "ignored.md"), "ignored", "utf8");
  writeFileSync(path.join(root, "AppData", "Local", "guessed.json"), "never expose", "utf8");
  const signing = new BrokerSigningIdentityV1(path.join(directory, "broker"));
  const descriptor = signing.publicDescriptor();
  const executor = new WindowsFilesNodeExecutorV1({
    expectedNodeId: WINDOWS_NODE_ID,
    expectedVoiceSessionKey: VOICE_SESSION,
    brokerKeyId: descriptor.keyId,
    brokerPublicKeyBase64Url: descriptor.publicKeyBase64Url,
    roots: { documents: root },
    fileOps,
  });
  const signed = (capability, args, overrides = {}) => signing.sign(createProposal({
    capability,
    arguments: args,
    targetDeviceId: WINDOWS_NODE_ID,
    voiceSessionKey: VOICE_SESSION,
    presenceLeaseId: PRESENCE_LEASE,
    lifetimeMs: 60_000,
    ...overrides,
  }));
  return {
    directory,
    root,
    signing,
    descriptor,
    executor,
    signed,
    close: () => rmSync(directory, { recursive: true, force: true }),
  };
}

describe("signed Windows files node command", () => {
  it("searches only allowlisted text files and returns opaque root references", () => {
    const subject = fixture();
    try {
      const receipt = subject.executor.handle(JSON.stringify(subject.signed(
        "windows.files.search",
        { query: "meeting", limit: 10 },
      )));
      assert.equal(receipt.status, "COMPLETED");
      assert.deepEqual(receipt.resultSummary, {
        matches: [{
          path: "documents:Projects/Meeting Notes.md",
          bytes: 31,
          modifiedAtMs: receipt.resultSummary.matches[0].modifiedAtMs,
        }],
        truncated: false,
      });
      assert.equal(JSON.stringify(receipt).includes(subject.root), false);
      assert.equal(JSON.stringify(receipt).includes("credentials"), false);
      assert.equal(JSON.stringify(receipt).includes("node_modules"), false);
    } finally {
      subject.close();
    }
  });

  it("reads bounded UTF-8 content and replays the same receipt idempotently", () => {
    const subject = fixture();
    try {
      const proposal = subject.signed("windows.files.read", {
        path: "documents:Projects/Meeting Notes.md",
        maxBytes: 12,
      });
      const first = subject.executor.handle(JSON.stringify(proposal));
      const second = subject.executor.handle(JSON.stringify(proposal));
      assert.equal(first.status, "COMPLETED");
      assert.equal(first.resultSummary.path, "documents:Projects/Meeting Notes.md");
      assert.equal(first.resultSummary.content, "Agenda\nShip ");
      assert.equal(first.resultSummary.bytes, 12);
      assert.equal(first.resultSummary.truncated, true);
      assert.match(first.resultSummary.sha256, /^sha256:[0-9a-f]{64}$/);
      assert.deepEqual(second, first);
    } finally {
      subject.close();
    }
  });

  it("normalizes a UTF-8 BOM without corrupting byte counts or hashes", () => {
    const subject = fixture();
    try {
      writeFileSync(
        path.join(subject.root, "Projects", "bom.txt"),
        Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from("hello", "utf8")]),
      );
      const receipt = subject.executor.handle(JSON.stringify(subject.signed(
        "windows.files.read",
        { path: "documents:Projects/bom.txt", maxBytes: 64 },
      )));

      assert.equal(receipt.status, "COMPLETED");
      assert.equal(receipt.resultSummary.content, "hello");
      assert.equal(receipt.resultSummary.bytes, 5);
      assert.equal(receipt.resultSummary.truncated, false);
      assert.equal(
        receipt.resultSummary.sha256,
        "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
      );
    } finally {
      subject.close();
    }
  });

  it("rejects a file replaced between path validation and descriptor open", () => {
    let replaced = false;
    const subject = fixture({
      fileOps: {
        openSync(filePath, flags) {
          if (!replaced && path.basename(filePath) === "Meeting Notes.md") {
            renameSync(filePath, `${filePath}.validated`);
            writeFileSync(filePath, Buffer.alloc(31, 0x78));
            replaced = true;
          }
          return nativeOpenSync(filePath, flags);
        },
      },
    });
    try {
      const receipt = subject.executor.handle(JSON.stringify(subject.signed(
        "windows.files.read",
        { path: "documents:Projects/Meeting Notes.md", maxBytes: 64 },
      )));

      assert.equal(replaced, true);
      assert.equal(receipt.status, "FAILED");
      assert.equal(receipt.errorCode, "FILE_CHANGED");
      assert.equal("resultSummary" in receipt, false);
    } finally {
      subject.close();
    }
  });

  it("rejects hard links to files outside the selected root", () => {
    const subject = fixture();
    try {
      const outside = path.join(subject.directory, "outside-private.txt");
      const linked = path.join(subject.root, "Projects", "innocent.txt");
      writeFileSync(outside, "outside private content", "utf8");
      linkSync(outside, linked);

      const search = subject.executor.handle(JSON.stringify(subject.signed(
        "windows.files.search",
        { query: "innocent", limit: 10 },
      )));
      assert.equal(search.status, "COMPLETED");
      assert.deepEqual(search.resultSummary.matches, []);

      const read = subject.executor.handle(JSON.stringify(subject.signed(
        "windows.files.read",
        { path: "documents:Projects/innocent.txt", maxBytes: 100 },
      )));
      assert.equal(read.status, "FAILED");
      assert.equal(read.errorCode, "FILE_POLICY_DENIED");
      assert.equal(JSON.stringify(read).includes("outside private content"), false);
    } finally {
      subject.close();
    }
  });

  it("rejects alternate data stream references before signing", () => {
    const subject = fixture();
    try {
      assert.throws(
        () => subject.signed("windows.files.read", {
          path: "documents:Projects/Meeting Notes.md:secret",
          maxBytes: 100,
        }),
        /arguments do not match/i,
      );
    } finally {
      subject.close();
    }
  });

  it("fails closed for traversal, denied names, binary files, and oversized requests", () => {
    const subject = fixture();
    try {
      const cases = [
        ["documents:credentials.txt", 100],
        ["documents:Projects/data.bin", 100],
        ["documents:AppData/Local/guessed.json", 100],
      ];
      for (const [reference, maxBytes] of cases) {
        const receipt = subject.executor.handle(JSON.stringify(subject.signed(
          "windows.files.read",
          { path: reference, maxBytes },
        )));
        assert.equal(receipt.status, "FAILED");
        assert.match(receipt.errorCode, /^(PATH_|FILE_|ARGUMENT_)/);
        assert.equal("resultSummary" in receipt, false);
      }
      assert.throws(
        () => subject.signed("windows.files.read", {
          path: "documents:../outside.txt",
          maxBytes: 100,
        }),
        /arguments do not match/i,
      );
      assert.throws(
        () => subject.signed("windows.files.read", {
          path: "documents:Projects/Meeting Notes.md",
          maxBytes: 65_537,
        }),
        /arguments do not match/i,
      );
    } finally {
      subject.close();
    }
  });

  it("rejects mutation, the wrong node, voice session, and broker key before execution", () => {
    const subject = fixture();
    try {
      const valid = subject.signed("windows.files.search", { query: "meeting" });
      const mutated = structuredClone(valid);
      mutated.proposal.arguments.query = "credentials";
      assert.throws(() => subject.executor.handle(JSON.stringify(mutated)), /argument hash|signature/i);

      const wrongNode = subject.signed("windows.files.search", { query: "meeting" }, {
        targetDeviceId: "c".repeat(64),
      });
      assert.throws(() => subject.executor.handle(JSON.stringify(wrongNode)), /wrong target device/i);

      const wrongSession = subject.signed("windows.files.search", { query: "meeting" }, {
        voiceSessionKey: "agent:other:voice-android-deadbeef",
      });
      assert.throws(() => subject.executor.handle(JSON.stringify(wrongSession)), /wrong voice session/i);

      const wrongKeyId = { ...valid, signatureKeyId: "other-key" };
      assert.throws(() => subject.executor.handle(JSON.stringify(wrongKeyId)), /BROKER_KEY_INVALID/);
    } finally {
      subject.close();
    }
  });

  it("registers exactly one fixed node-host command only when enabled", () => {
    const subject = fixture();
    const commands = [];
    try {
      const api = {
        pluginConfig: {
          windowsNodeEnabled: true,
          windowsNodeId: WINDOWS_NODE_ID,
          windowsVoiceSessionKey: VOICE_SESSION,
          windowsBrokerKeyId: subject.descriptor.keyId,
          windowsBrokerPublicKeyBase64Url: subject.descriptor.publicKeyBase64Url,
          windowsReadRoots: { documents: subject.root },
        },
        registerNodeHostCommand: (command) => commands.push(command),
      };
      assert.equal(registerWindowsNodeHostCommand(api), 1);
      assert.equal(commands.length, 1);
      assert.equal(commands[0].command, WINDOWS_EXECUTE_COMMAND);
      assert.equal(commands[0].dangerous, false);
      assert.equal(registerWindowsNodeHostCommand({ ...api, pluginConfig: {} }), 0);
    } finally {
      subject.close();
    }
  });
});
