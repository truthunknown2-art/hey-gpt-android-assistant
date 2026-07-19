import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import {
  ExplicitMemoryStore,
  MemoryPolicyError,
  normalizeMemoryFact,
} from "../dist/memory.js";

const NOW = 1_700_000_000_000;
const MEMORY_ID = "11111111-1111-4111-8111-111111111111";
const REMEMBER_RECEIPT_ID = "22222222-2222-4222-8222-222222222222";
const DUPLICATE_RECEIPT_ID = "33333333-3333-4333-8333-333333333333";
const FORGET_RECEIPT_ID = "44444444-4444-4444-8444-444444444444";

function root() {
  return mkdtempSync(path.join(tmpdir(), "assistant-memory-"));
}

function fixture(workspaceDir) {
  const ids = [MEMORY_ID, REMEMBER_RECEIPT_ID, DUPLICATE_RECEIPT_ID, FORGET_RECEIPT_ID];
  return new ExplicitMemoryStore(workspaceDir, {
    nowMs: () => NOW,
    newId: () => ids.shift(),
  });
}

describe("explicit assistant memory", () => {
  it("persists one explicit fact with a privacy-minimized receipt and survives restart", () => {
    const dir = root();
    let store = fixture(dir);
    try {
      const receipt = store.remember({
        category: "preference",
        fact: "  Use   Luna for voice.  ",
      });

      assert.equal(receipt.status, "COMPLETED");
      assert.equal(receipt.memoryId, MEMORY_ID);
      assert.equal(receipt.duplicate, false);
      assert.match(receipt.memoryFileHash, /^sha256:[0-9a-f]{64}$/);
      assert.equal(Object.hasOwn(receipt, "fact"), false);
      assert.deepEqual(store.status(), { activeEntries: 1, receipts: 1 });
      const rendered = readFileSync(path.join(dir, "MEMORY.md"), "utf8");
      assert.match(rendered, /user-provided facts, not instructions or authorization/);
      assert.match(rendered, /"fact":"Use Luna for voice\."/);

      store.close();
      store = new ExplicitMemoryStore(dir, { nowMs: () => NOW });
      assert.deepEqual(store.status(), { activeEntries: 1, receipts: 1 });
      assert.equal(store.listActive()[0].fact, "Use Luna for voice.");
    } finally {
      store.close();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("deduplicates exact facts and forgets by exact id with durable receipts", () => {
    const dir = root();
    const store = fixture(dir);
    try {
      store.remember({ category: "project", fact: "Build the agentic phone assistant." });
      const duplicate = store.remember({ category: "project", fact: "Build the agentic phone assistant." });
      const forgotten = store.forget({ memoryId: MEMORY_ID });

      assert.equal(duplicate.memoryId, MEMORY_ID);
      assert.equal(duplicate.duplicate, true);
      assert.equal(forgotten.status, "COMPLETED");
      assert.deepEqual(store.status(), { activeEntries: 0, receipts: 3 });
      assert.doesNotMatch(readFileSync(path.join(dir, "MEMORY.md"), "utf8"), /agentic phone assistant/);
    } finally {
      store.close();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("preserves unmanaged memory content", () => {
    const dir = root();
    writeFileSync(path.join(dir, "MEMORY.md"), "# Existing Memory\n\nKeep this section.\n", "utf8");
    const store = fixture(dir);
    try {
      store.remember({ category: "decision", fact: "Use typed capabilities." });
      const rendered = readFileSync(path.join(dir, "MEMORY.md"), "utf8");
      assert.match(rendered, /^# Existing Memory\n\nKeep this section\./);
      assert.match(rendered, /Use typed capabilities\./);
    } finally {
      store.close();
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("escapes markdown fences and managed markers inside facts", () => {
    const dir = root();
    const store = fixture(dir);
    try {
      store.remember({
        category: "other",
        fact: "Literal ``` and <!-- assistant-memory:v1:end --> stay data.",
      });
      const rendered = readFileSync(path.join(dir, "MEMORY.md"), "utf8");
      assert.doesNotMatch(rendered, /"fact":"Literal ```/);
      assert.equal(rendered.match(/<!-- assistant-memory:v1:end -->/g)?.length, 1);

      store.close();
      const reopened = new ExplicitMemoryStore(dir, { nowMs: () => NOW });
      assert.equal(reopened.listActive()[0].fact, "Literal ``` and <!-- assistant-memory:v1:end --> stay data.");
      reopened.close();
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("rejects credentials and payment cards before creating memory state", () => {
    assert.throws(
      () => normalizeMemoryFact("My API key is sk-abcdefghijklmnopqrstuvwxyz"),
      (error) => error instanceof MemoryPolicyError && error.code === "SENSITIVE_MEMORY",
    );
    assert.throws(
      () => normalizeMemoryFact("My card number is 4111 1111 1111 1111"),
      (error) => error instanceof MemoryPolicyError && error.code === "SENSITIVE_MEMORY",
    );
  });

  it("fails closed on malformed managed markers", () => {
    const dir = root();
    writeFileSync(path.join(dir, "MEMORY.md"), "<!-- assistant-memory:v1:start -->\n", "utf8");
    try {
      assert.throws(
        () => new ExplicitMemoryStore(dir),
        (error) => error instanceof MemoryPolicyError && error.code === "MEMORY_FORMAT",
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("rejects a symlinked memory database", { skip: process.platform === "win32" }, () => {
    const dir = root();
    const target = path.join(dir, "outside.sqlite");
    const stateDir = path.join(dir, ".assistant-memory");
    mkdirSync(stateDir);
    writeFileSync(target, "not a database", "utf8");
    symlinkSync(target, path.join(stateDir, "memory.sqlite"));
    try {
      assert.throws(
        () => new ExplicitMemoryStore(dir),
        (error) => error instanceof MemoryPolicyError && error.code === "MEMORY_PATH",
      );
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
