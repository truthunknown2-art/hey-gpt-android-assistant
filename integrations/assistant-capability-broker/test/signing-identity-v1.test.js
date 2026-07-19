import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, it } from "node:test";
import { createPublicKey } from "node:crypto";
import { createProposal, verifySignedProposal } from "../dist/contract-v1.js";
import { BrokerSigningIdentityV1, SigningIdentityError } from "../dist/signing-identity-v1.js";

const NOW = 1_700_000_000_000;

function root() {
  return mkdtempSync(path.join(tmpdir(), "assistant-signing-"));
}

function proposal() {
  return createProposal({
    capability: "android.device.status",
    arguments: {},
    targetDeviceId: "paired-device",
    voiceSessionKey: "agent:voice-main:voice-android-device",
    presenceLeaseId: "lease-1",
    planId: "22222222-2222-4222-8222-222222222222",
    stepId: "33333333-3333-4333-8333-333333333333",
    proposalId: "11111111-1111-4111-8111-111111111111",
    idempotencyKey: "44444444-4444-4444-8444-444444444444",
    nowMs: NOW,
  });
}

function publicKeyFromRaw(rawBase64Url) {
  const prefix = Buffer.from("302a300506032b6570032100", "hex");
  return createPublicKey({
    key: Buffer.concat([prefix, Buffer.from(rawBase64Url, "base64url")]),
    format: "der",
    type: "spki",
  });
}

describe("broker signing identity v1", () => {
  it("persists one key across restart and signs verifiable proposals", () => {
    const dir = root();
    try {
      const first = new BrokerSigningIdentityV1(dir, { nowMs: () => NOW });
      const descriptor = first.publicDescriptor();
      const signed = first.sign(proposal());
      verifySignedProposal(signed, {
        publicKey: publicKeyFromRaw(descriptor.publicKeyBase64Url),
        nowMs: NOW + 1,
      });

      const second = new BrokerSigningIdentityV1(dir, { nowMs: () => NOW + 1 });
      assert.deepEqual(second.publicDescriptor(), descriptor);
      assert.equal(signed.signatureKeyId, descriptor.keyId);
      assert.match(descriptor.fingerprintSha256, /^sha256:[0-9a-f]{64}$/);
      assert.equal(Buffer.from(descriptor.publicKeyBase64Url, "base64url").length, 32);
      if (process.platform !== "win32") {
        assert.equal(statSync(path.join(dir, "signing-identity-v1.json")).mode & 0o777, 0o600);
      }
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it("rejects malformed persisted identity instead of rotating silently", () => {
    const dir = root();
    try {
      writeFileSync(path.join(dir, "signing-identity-v1.json"), "{}\n", "utf8");
      assert.throws(
        () => new BrokerSigningIdentityV1(dir),
        (error) => error instanceof SigningIdentityError && error.code === "SIGNING_KEY_FORMAT",
      );
      assert.equal(readFileSync(path.join(dir, "signing-identity-v1.json"), "utf8"), "{}\n");
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  });
});
