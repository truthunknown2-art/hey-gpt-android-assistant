import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { describe, it } from "node:test";
import {
  ContractError,
  canonicalHash,
  canonicalJson,
  createProposal,
  signProposal,
  verifySignedProposal,
} from "../dist/contract-v1.js";

const NOW = 1_700_000_000_000;
const DEVICE_ID = "paired-device";
const SESSION_KEY = "agent:voice-main:voice-android-device";

function proposal() {
  return createProposal({
    capability: "android.calendar.next",
    arguments: { afterEpochMs: NOW, limit: 2 },
    targetDeviceId: DEVICE_ID,
    voiceSessionKey: SESSION_KEY,
    presenceLeaseId: "lease-1",
    planId: "22222222-2222-4222-8222-222222222222",
    stepId: "33333333-3333-4333-8333-333333333333",
    proposalId: "11111111-1111-4111-8111-111111111111",
    idempotencyKey: "44444444-4444-4444-8444-444444444444",
    nowMs: NOW,
  });
}

describe("assistant contract v1", () => {
  it("matches the Android canonical fixture", () => {
    const value = { z: 2, a: { b: true, a: ["second", "first"] } };
    assert.equal(canonicalJson(value), '{"a":{"a":["second","first"],"b":true},"z":2}');
    assert.equal(
      canonicalHash(value),
      "sha256:132802f7c6db0d43cd899b7e7f1aed4fba3701a0c678fccbf459670a9a6bc27c",
    );
  });

  it("derives risk and hash then verifies an Ed25519 signature", () => {
    const { privateKey, publicKey } = generateKeyPairSync("ed25519");
    const signed = signProposal(proposal(), "gateway-key-1", privateKey);

    const verified = verifySignedProposal(signed, {
      publicKey,
      nowMs: NOW + 1,
      expectedDeviceId: DEVICE_ID,
      expectedVoiceSessionKey: SESSION_KEY,
    });

    assert.equal(verified.risk, "LOW");
    assert.match(verified.argumentsHash, /^sha256:[0-9a-f]{64}$/);
  });

  it("rejects mutation, unknown arguments, wrong bindings, and expiry", () => {
    assert.throws(
      () => createProposal({
        ...proposal(),
        capability: "android.calendar.next",
        arguments: { limit: "2" },
        nowMs: NOW,
      }),
      (error) => error instanceof ContractError && error.code === "ARGUMENT_SCHEMA",
    );

    const { privateKey, publicKey } = generateKeyPairSync("ed25519");
    const signed = signProposal(proposal(), "gateway-key-1", privateKey);
    const mutated = {
      ...signed,
      proposal: { ...signed.proposal, arguments: { afterEpochMs: NOW, limit: 10 } },
    };
    assert.throws(
      () => verifySignedProposal(mutated, { publicKey, nowMs: NOW + 1 }),
      (error) => error instanceof ContractError && error.code === "ARGUMENT_HASH",
    );
    assert.throws(
      () => verifySignedProposal(signed, { publicKey, nowMs: NOW + 1, expectedDeviceId: "wrong" }),
      (error) => error instanceof ContractError && error.code === "TARGET_DEVICE",
    );
    assert.throws(
      () => verifySignedProposal(signed, { publicKey, nowMs: NOW + 31_000 }),
      (error) => error instanceof ContractError && error.code === "PROPOSAL_TIME",
    );
  });
});
