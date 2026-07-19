import {
  createHash,
  createPublicKey,
  randomUUID,
} from "node:crypto";
import {
  closeSync,
  fstatSync,
  lstatSync,
  openSync,
  readSync,
  readdirSync,
  realpathSync,
  statSync,
} from "node:fs";
import path from "node:path";
import { verifySignedProposal } from "./contract-v1.js";

export const WINDOWS_EXECUTE_COMMAND = "assistant.windows.execute.v1";

const NODE_ID_PATTERN = /^[a-f0-9]{64}$/;
const KEY_ID_PATTERN = /^[A-Za-z0-9._-]{1,128}$/;
const ALIAS_PATTERN = /^[a-z][a-z0-9_-]{0,31}$/;
const MAX_SEARCH_LIMIT = 50;
const MAX_SEARCH_ENTRIES = 50_000;
const MAX_SEARCH_DEPTH = 8;
const MAX_READ_BYTES = 64 * 1024;
const MAX_SOURCE_FILE_BYTES = 5 * 1024 * 1024;
const SPKI_PREFIX = Buffer.from("302a300506032b6570032100", "hex");
const DEFAULT_EXTENSIONS = Object.freeze([
  ".css", ".csv", ".html", ".java", ".js", ".json", ".jsx", ".kt",
  ".log", ".md", ".ps1", ".py", ".sh", ".sql", ".ts", ".tsv",
  ".tsx", ".txt", ".xml", ".yaml", ".yml",
]);
const DENIED_DIRECTORIES = new Set([
  ".git", ".gradle", ".idea", ".kotlin", ".svn", "appdata", "build",
  "dist", "node_modules", "packages", "system volume information",
]);
const DENIED_FILE_NAMES = [
  /^\.env(?:\.|$)/i,
  /^\.npmrc$/i,
  /^id_(?:rsa|dsa|ecdsa|ed25519)(?:\.|$)/i,
  /(?:credential|password|secret|token)/i,
  /\.(?:key|kdbx|p12|pfx|pem)$/i,
];
const DEFAULT_FILE_OPS = Object.freeze({ closeSync, fstatSync, openSync, readSync });

class WindowsNodeCommandError extends Error {
  constructor(code) {
    super(code);
    this.name = "WindowsNodeCommandError";
    this.code = code;
  }
}

function fail(code) {
  throw new WindowsNodeCommandError(code);
}

function isRecord(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function exactKeys(value, required, allowed) {
  const keys = Object.keys(value);
  return required.every((key) => keys.includes(key)) && keys.every((key) => allowed.has(key));
}

function publicKeyFromRawBase64Url(value) {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(value)) fail("BROKER_KEY_INVALID");
  const raw = Buffer.from(value, "base64url");
  if (raw.length !== 32 || raw.toString("base64url") !== value) fail("BROKER_KEY_INVALID");
  return createPublicKey({
    key: Buffer.concat([SPKI_PREFIX, raw]),
    format: "der",
    type: "spki",
  });
}

function normalizeExtensions(configured) {
  const source = configured === undefined ? DEFAULT_EXTENSIONS : configured;
  if (!Array.isArray(source) || source.length < 1 || source.length > 100) {
    fail("EXTENSION_POLICY_INVALID");
  }
  const values = source.map((entry) => {
    if (typeof entry !== "string" || !/^\.[a-z0-9]{1,12}$/i.test(entry)) {
      fail("EXTENSION_POLICY_INVALID");
    }
    return entry.toLowerCase();
  });
  return new Set(values);
}

function isInside(root, candidate) {
  const relative = path.relative(root, candidate);
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== "..");
}

function normalizeRoots(configured) {
  if (!isRecord(configured)) fail("ROOT_POLICY_INVALID");
  const entries = Object.entries(configured);
  if (entries.length < 1 || entries.length > 10) fail("ROOT_POLICY_INVALID");
  const roots = new Map();
  for (const [alias, configuredPath] of entries) {
    if (!ALIAS_PATTERN.test(alias) || typeof configuredPath !== "string" || !path.isAbsolute(configuredPath)) {
      fail("ROOT_POLICY_INVALID");
    }
    const resolved = realpathSync.native(configuredPath);
    if (!statSync(resolved).isDirectory()) fail("ROOT_POLICY_INVALID");
    roots.set(alias, resolved);
  }
  return roots;
}

function deniedName(name) {
  return DENIED_FILE_NAMES.some((pattern) => pattern.test(name));
}

function hasDeniedDirectory(relative) {
  const segments = relative.split(path.sep);
  return segments.slice(0, -1).some((segment) => DENIED_DIRECTORIES.has(segment.toLowerCase()));
}

function assertNoSymlink(root, candidate) {
  const relative = path.relative(root, candidate);
  if (!relative || relative.startsWith("..")) return;
  let current = root;
  for (const segment of relative.split(path.sep)) {
    current = path.join(current, segment);
    if (lstatSync(current).isSymbolicLink()) fail("PATH_SYMLINK_DENIED");
  }
}

function encodeReference(alias, root, candidate) {
  const relative = path.relative(root, candidate).split(path.sep).join("/");
  if (!relative || relative.startsWith("../")) fail("PATH_OUTSIDE_ROOT");
  return `${alias}:${relative}`;
}

function resolveReference(reference, roots, extensions) {
  if (typeof reference !== "string" || reference.length < 3 || reference.length > 1_024) {
    fail("PATH_INVALID");
  }
  const separator = reference.indexOf(":");
  if (separator < 1) fail("PATH_INVALID");
  const alias = reference.slice(0, separator);
  const relative = reference.slice(separator + 1);
  const root = roots.get(alias);
  if (
    !root || !relative || path.isAbsolute(relative) ||
    relative.includes("\\") || relative.includes(":")
  ) {
    fail("PATH_INVALID");
  }
  const candidate = path.resolve(root, ...relative.split("/"));
  if (!isInside(root, candidate)) fail("PATH_OUTSIDE_ROOT");
  if (hasDeniedDirectory(path.relative(root, candidate))) fail("PATH_POLICY_DENIED");
  assertNoSymlink(root, candidate);
  const real = realpathSync.native(candidate);
  if (!isInside(root, real)) fail("PATH_OUTSIDE_ROOT");
  const stat = statSync(real, { bigint: true });
  if (!stat.isFile() || stat.size > BigInt(MAX_SOURCE_FILE_BYTES) || stat.nlink !== 1n) {
    fail("FILE_POLICY_DENIED");
  }
  if (deniedName(path.basename(real)) || !extensions.has(path.extname(real).toLowerCase())) {
    fail("FILE_POLICY_DENIED");
  }
  return { alias, root, real, reference: encodeReference(alias, root, real), stat };
}

function terminalReceipt(proposal, startedAtMs, status, resultSummary, errorCode) {
  return {
    contractVersion: 1,
    receiptId: randomUUID(),
    proposalId: proposal.proposalId,
    planId: proposal.planId,
    stepId: proposal.stepId,
    capability: proposal.capability,
    argumentsHash: proposal.argumentsHash,
    targetDeviceId: proposal.targetDeviceId,
    idempotencyKey: proposal.idempotencyKey,
    status,
    startedAtMs,
    finishedAtMs: Math.max(startedAtMs, Date.now()),
    ...(status === "COMPLETED" ? { resultSummary } : { errorCode }),
  };
}

function searchFiles(proposal, roots, extensions) {
  const query = proposal.arguments.query.trim().toLowerCase();
  const limit = proposal.arguments.limit ?? 20;
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > MAX_SEARCH_LIMIT) fail("ARGUMENT_SCHEMA");
  const matches = [];
  let visited = 0;
  let truncated = false;
  const queue = [...roots.entries()].map(([alias, root]) => ({ alias, root, directory: root, depth: 0 }));
  while (queue.length > 0 && matches.length < limit) {
    const current = queue.shift();
    let entries;
    try {
      entries = readdirSync(current.directory, { withFileTypes: true });
    } catch {
      continue;
    }
    entries.sort((left, right) => left.name.localeCompare(right.name, "en", { sensitivity: "base" }));
    for (const entry of entries) {
      visited += 1;
      if (visited > MAX_SEARCH_ENTRIES) {
        truncated = true;
        queue.length = 0;
        break;
      }
      if (entry.name.startsWith(".") || entry.isSymbolicLink()) continue;
      const candidate = path.join(current.directory, entry.name);
      if (entry.isDirectory()) {
        if (current.depth < MAX_SEARCH_DEPTH && !DENIED_DIRECTORIES.has(entry.name.toLowerCase())) {
          queue.push({ ...current, directory: candidate, depth: current.depth + 1 });
        }
        continue;
      }
      if (!entry.isFile() || deniedName(entry.name) || !extensions.has(path.extname(entry.name).toLowerCase())) {
        continue;
      }
      const relative = path.relative(current.root, candidate).split(path.sep).join("/");
      if (query !== "*" && !entry.name.toLowerCase().includes(query) && !relative.toLowerCase().includes(query)) {
        continue;
      }
      try {
        assertNoSymlink(current.root, candidate);
        const real = realpathSync.native(candidate);
        if (!isInside(current.root, real)) continue;
        const stat = statSync(real, { bigint: true });
        if (!stat.isFile() || stat.size > BigInt(MAX_SOURCE_FILE_BYTES) || stat.nlink !== 1n) continue;
        matches.push({
          path: encodeReference(current.alias, current.root, real),
          bytes: Number(stat.size),
          modifiedAtMs: Math.max(0, Number(stat.mtimeMs)),
        });
      } catch {
        continue;
      }
      if (matches.length >= limit) {
        truncated = queue.length > 0 || entries.at(-1) !== entry;
        break;
      }
    }
  }
  return { matches, truncated };
}

function decodeUtf8(buffer) {
  for (let trim = 0; trim <= 3 && trim <= buffer.length; trim += 1) {
    try {
      const candidate = buffer.subarray(0, buffer.length - trim);
      const text = new TextDecoder("utf-8", { fatal: true }).decode(candidate);
      if (text.includes("\u0000")) fail("FILE_ENCODING_DENIED");
      const hasBom = candidate.subarray(0, 3).equals(Buffer.from([0xef, 0xbb, 0xbf]));
      return {
        text: text.replace(/^\uFEFF/, ""),
        bytes: hasBom ? candidate.subarray(3) : candidate,
        consumedBytes: candidate.length,
      };
    } catch (error) {
      if (error instanceof WindowsNodeCommandError) throw error;
    }
  }
  fail("FILE_ENCODING_DENIED");
}

function sameFileIdentity(left, right) {
  return left.dev === right.dev && left.ino === right.ino;
}

function readFile(proposal, roots, extensions, fileOps) {
  const maxBytes = proposal.arguments.maxBytes ?? 16 * 1_024;
  if (!Number.isSafeInteger(maxBytes) || maxBytes < 1 || maxBytes > MAX_READ_BYTES) {
    fail("ARGUMENT_SCHEMA");
  }
  const resolved = resolveReference(proposal.arguments.path, roots, extensions);
  const fd = fileOps.openSync(resolved.real, "r");
  let before;
  let after;
  let bytesRead;
  const buffer = Buffer.alloc(maxBytes + 4);
  try {
    before = fileOps.fstatSync(fd, { bigint: true });
    bytesRead = fileOps.readSync(fd, buffer, 0, buffer.length, 0);
    after = fileOps.fstatSync(fd, { bigint: true });
  } finally {
    fileOps.closeSync(fd);
  }
  if (
    !sameFileIdentity(resolved.stat, before) ||
    !sameFileIdentity(before, after) ||
    before.nlink !== 1n ||
    after.nlink !== 1n ||
    before.size !== after.size ||
    before.mtimeMs !== after.mtimeMs
  ) {
    fail("FILE_CHANGED");
  }
  const available = buffer.subarray(0, Math.min(bytesRead, maxBytes));
  const decoded = decodeUtf8(available);
  return {
    path: resolved.reference,
    content: decoded.text,
    bytes: decoded.bytes.length,
    truncated: before.size > BigInt(decoded.consumedBytes),
    sha256: `sha256:${createHash("sha256").update(decoded.bytes).digest("hex")}`,
  };
}

export class WindowsFilesNodeExecutorV1 {
  constructor({
    expectedNodeId,
    expectedVoiceSessionKey,
    brokerKeyId,
    brokerPublicKeyBase64Url,
    roots,
    extensions,
    nowMs = Date.now,
    fileOps = {},
  }) {
    if (!NODE_ID_PATTERN.test(expectedNodeId ?? "")) fail("NODE_ID_INVALID");
    if (typeof expectedVoiceSessionKey !== "string" || !expectedVoiceSessionKey.trim()) {
      fail("VOICE_SESSION_INVALID");
    }
    if (!KEY_ID_PATTERN.test(brokerKeyId ?? "")) fail("BROKER_KEY_INVALID");
    this.expectedNodeId = expectedNodeId;
    this.expectedVoiceSessionKey = expectedVoiceSessionKey;
    this.brokerKeyId = brokerKeyId;
    this.publicKey = publicKeyFromRawBase64Url(brokerPublicKeyBase64Url);
    this.roots = normalizeRoots(roots);
    this.extensions = normalizeExtensions(extensions);
    this.nowMs = nowMs;
    this.fileOps = { ...DEFAULT_FILE_OPS, ...fileOps };
    this.receipts = new Map();
  }

  handle(paramsJSON) {
    let signed;
    try {
      signed = JSON.parse(paramsJSON ?? "");
    } catch {
      fail("PROPOSAL_INVALID");
    }
    if (signed?.signatureKeyId !== this.brokerKeyId) fail("BROKER_KEY_INVALID");
    const proposal = verifySignedProposal(signed, {
      publicKey: this.publicKey,
      nowMs: this.nowMs(),
      expectedDeviceId: this.expectedNodeId,
      expectedVoiceSessionKey: this.expectedVoiceSessionKey,
    });
    const existing = this.receipts.get(proposal.idempotencyKey);
    if (existing) return existing;
    const startedAtMs = this.nowMs();
    let receipt;
    try {
      const resultSummary = proposal.capability === "windows.files.search"
        ? searchFiles(proposal, this.roots, this.extensions)
        : proposal.capability === "windows.files.read"
          ? readFile(proposal, this.roots, this.extensions, this.fileOps)
          : fail("CAPABILITY_NOT_IMPLEMENTED");
      receipt = terminalReceipt(proposal, startedAtMs, "COMPLETED", resultSummary);
    } catch (error) {
      const code = error instanceof WindowsNodeCommandError ? error.code : "WINDOWS_FILES_FAILED";
      receipt = terminalReceipt(proposal, startedAtMs, "FAILED", undefined, code);
    }
    this.receipts.set(proposal.idempotencyKey, receipt);
    if (this.receipts.size > 256) this.receipts.delete(this.receipts.keys().next().value);
    return receipt;
  }
}

export function registerWindowsNodeHostCommand(api) {
  if (api.pluginConfig?.windowsNodeEnabled !== true) return 0;
  const executor = new WindowsFilesNodeExecutorV1({
    expectedNodeId: api.pluginConfig.windowsNodeId,
    expectedVoiceSessionKey: api.pluginConfig.windowsVoiceSessionKey,
    brokerKeyId: api.pluginConfig.windowsBrokerKeyId,
    brokerPublicKeyBase64Url: api.pluginConfig.windowsBrokerPublicKeyBase64Url,
    roots: api.pluginConfig.windowsReadRoots,
    extensions: api.pluginConfig.windowsReadExtensions,
  });
  api.registerNodeHostCommand({
    command: WINDOWS_EXECUTE_COMMAND,
    cap: "assistantWindowsFiles",
    dangerous: false,
    handle: async (paramsJSON) => JSON.stringify(executor.handle(paramsJSON)),
  });
  return 1;
}
