import {
  createHash,
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  randomUUID,
  timingSafeEqual,
} from "node:crypto";
import {
  chmodSync,
  closeSync,
  existsSync,
  fsyncSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import path from "node:path";
import { signProposal } from "./contract-v1.js";

const CONTRACT_VERSION = 1;
const ALGORITHM = "Ed25519";
const FILE_NAME = "signing-identity-v1.json";
const MAX_FILE_BYTES = 16 * 1024;
const KEY_ID_PATTERN = /^assistant-v1-[0-9a-f]{24}$/;
const HASH_PATTERN = /^sha256:[0-9a-f]{64}$/;
const SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");

export class SigningIdentityError extends Error {
  constructor(code, message) {
    super(message);
    this.name = "SigningIdentityError";
    this.code = code;
  }
}

function fail(code, message) {
  throw new SigningIdentityError(code, message);
}

function canonicalBase64Url(value, field) {
  if (typeof value !== "string" || !value) fail("SIGNING_KEY_FORMAT", `${field} is invalid`);
  const decoded = Buffer.from(value, "base64url");
  if (!decoded.length || decoded.toString("base64url") !== value) {
    fail("SIGNING_KEY_FORMAT", `${field} is invalid`);
  }
  return decoded;
}

function rawPublicKey(publicKey) {
  const spki = Buffer.from(publicKey.export({ format: "der", type: "spki" }));
  if (spki.length !== SPKI_PREFIX.length + 32 || !spki.subarray(0, SPKI_PREFIX.length).equals(SPKI_PREFIX)) {
    fail("SIGNING_KEY_FORMAT", "Ed25519 public key encoding is invalid");
  }
  return spki.subarray(SPKI_PREFIX.length);
}

function descriptor(raw) {
  const hex = createHash("sha256").update(raw).digest("hex");
  return Object.freeze({
    contractVersion: CONTRACT_VERSION,
    algorithm: ALGORITHM,
    keyId: `assistant-v1-${hex.slice(0, 24)}`,
    publicKeyBase64Url: raw.toString("base64url"),
    fingerprintSha256: `sha256:${hex}`,
  });
}

function ensureStateDirectory(stateDir) {
  if (typeof stateDir !== "string" || !stateDir.trim()) fail("SIGNING_KEY_PATH", "state directory is required");
  mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  const stat = lstatSync(stateDir);
  if (stat.isSymbolicLink() || !stat.isDirectory()) {
    fail("SIGNING_KEY_PATH", "signing state path must be a real directory");
  }
  chmodSync(stateDir, 0o700);
}

function ensureRegularFileOrMissing(filePath) {
  if (!existsSync(filePath)) return;
  const stat = lstatSync(filePath);
  if (stat.isSymbolicLink() || !stat.isFile()) {
    fail("SIGNING_KEY_PATH", "signing identity must be a regular file");
  }
  if (stat.size < 1 || stat.size > MAX_FILE_BYTES) {
    fail("SIGNING_KEY_FORMAT", "signing identity file size is invalid");
  }
}

function atomicWrite(filePath, value) {
  ensureRegularFileOrMissing(filePath);
  const temporary = path.join(path.dirname(filePath), `.${FILE_NAME}.${process.pid}.${randomUUID()}.tmp`);
  let fd;
  try {
    fd = openSync(temporary, "wx", 0o600);
    writeFileSync(fd, value, { encoding: "utf8" });
    fsyncSync(fd);
    closeSync(fd);
    fd = undefined;
    renameSync(temporary, filePath);
    chmodSync(filePath, 0o600);
  } finally {
    if (fd !== undefined) closeSync(fd);
    rmSync(temporary, { force: true });
  }
}

function createIdentity(filePath, nowMs) {
  const now = nowMs();
  if (!Number.isSafeInteger(now) || now < 0) fail("CLOCK", "signing identity clock is invalid");
  const { privateKey, publicKey } = generateKeyPairSync("ed25519");
  const raw = rawPublicKey(publicKey);
  const publicDescriptor = descriptor(raw);
  const privateKeyPkcs8Base64Url = Buffer.from(
    privateKey.export({ format: "der", type: "pkcs8" }),
  ).toString("base64url");
  atomicWrite(filePath, `${JSON.stringify({
    ...publicDescriptor,
    privateKeyPkcs8Base64Url,
    createdAtMs: now,
  })}\n`);
  return { privateKey, publicDescriptor };
}

function loadIdentity(filePath) {
  ensureRegularFileOrMissing(filePath);
  const parsed = (() => {
    try {
      return JSON.parse(readFileSync(filePath, "utf8"));
    } catch {
      fail("SIGNING_KEY_FORMAT", "signing identity JSON is invalid");
    }
  })();
  const exactKeys = [
    "contractVersion",
    "algorithm",
    "keyId",
    "publicKeyBase64Url",
    "fingerprintSha256",
    "privateKeyPkcs8Base64Url",
    "createdAtMs",
  ];
  if (
    !parsed || Array.isArray(parsed) || typeof parsed !== "object" ||
    Object.keys(parsed).sort().join("\0") !== exactKeys.sort().join("\0") ||
    parsed.contractVersion !== CONTRACT_VERSION || parsed.algorithm !== ALGORITHM ||
    !KEY_ID_PATTERN.test(parsed.keyId) || !HASH_PATTERN.test(parsed.fingerprintSha256) ||
    !Number.isSafeInteger(parsed.createdAtMs) || parsed.createdAtMs < 0
  ) {
    fail("SIGNING_KEY_FORMAT", "signing identity fields are invalid");
  }
  const storedRaw = canonicalBase64Url(parsed.publicKeyBase64Url, "public key");
  if (storedRaw.length !== 32) fail("SIGNING_KEY_FORMAT", "public key length is invalid");
  const privateDer = canonicalBase64Url(parsed.privateKeyPkcs8Base64Url, "private key");
  const privateKey = (() => {
    try {
      return createPrivateKey({ key: privateDer, format: "der", type: "pkcs8" });
    } catch {
      fail("SIGNING_KEY_FORMAT", "private key encoding is invalid");
    }
  })();
  if (privateKey.asymmetricKeyType !== "ed25519") fail("SIGNING_KEY_FORMAT", "private key type is invalid");
  const derivedRaw = rawPublicKey(createPublicKey(privateKey));
  if (!timingSafeEqual(storedRaw, derivedRaw)) fail("SIGNING_KEY_MISMATCH", "stored key pair does not match");
  const expected = descriptor(derivedRaw);
  if (
    parsed.keyId !== expected.keyId ||
    parsed.fingerprintSha256 !== expected.fingerprintSha256
  ) {
    fail("SIGNING_KEY_MISMATCH", "stored signing identity metadata does not match");
  }
  chmodSync(filePath, 0o600);
  return { privateKey, publicDescriptor: expected };
}

export class BrokerSigningIdentityV1 {
  constructor(stateDir, { nowMs = Date.now } = {}) {
    ensureStateDirectory(stateDir);
    this.filePath = path.join(stateDir, FILE_NAME);
    const loaded = existsSync(this.filePath)
      ? loadIdentity(this.filePath)
      : createIdentity(this.filePath, nowMs);
    this.privateKey = loaded.privateKey;
    this.descriptor = loaded.publicDescriptor;
  }

  publicDescriptor() {
    return { ...this.descriptor };
  }

  sign(proposal) {
    return signProposal(proposal, this.descriptor.keyId, this.privateKey);
  }
}
