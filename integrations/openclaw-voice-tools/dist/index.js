const MEDIA_COMMAND = "media.play_search";
const SPOTIFY_PACKAGE = "com.spotify.music";
const MAX_TEXT_LENGTH = 500;

const NODE_ID_PATTERN = /^[a-f0-9]{64}$/;
const ConfigSchema = {
  type: "object",
  required: ["nodeId"],
  properties: {
    nodeId: { type: "string", pattern: "^[a-f0-9]{64}$" },
  },
  additionalProperties: false,
};

function asRecord(value) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error("Android node returned an invalid response");
  }
  return value;
}

function parsePayload(value) {
  const result = asRecord(value);
  if (result.ok === false) {
    const error = typeof result.error === "object" && result.error !== null
      ? result.error
      : {};
    throw new Error(
      typeof error.message === "string" ? error.message : "Android node command failed",
    );
  }

  const candidate = result.payload ?? result.payloadJSON ?? result;
  if (typeof candidate === "string") {
    return asRecord(JSON.parse(candidate));
  }
  return asRecord(candidate);
}

export function selectConfiguredNode(nodes, nodeId, requiredCommand) {
  const matches = nodes.filter((node) => node.nodeId === nodeId);
  if (matches.length !== 1) {
    throw new Error("Configured Android node is missing or duplicated");
  }
  const node = matches[0];
  if (node.connected === false) {
    throw new Error("Configured Android node is offline");
  }
  if (!node.commands?.includes(requiredCommand)) {
    throw new Error(`Configured Android node does not declare ${requiredCommand}`);
  }
  return node;
}

async function invokeConfiguredNode(api, nodeId, command, params) {
  const { nodes } = await api.runtime.nodes.list({ connected: true });
  const node = selectConfiguredNode(nodes, nodeId, command);
  const result = await api.runtime.nodes.invoke({
    nodeId: node.nodeId,
    command,
    params,
    idempotencyKey: crypto.randomUUID(),
  });
  return parsePayload(result);
}

export async function playSpotify(api, nodeId, request) {
  const { query, title = "", artist = "", spotifyUri = "" } = request;
  const normalizedQuery = query.trim();
  if (!normalizedQuery) throw new Error("A Spotify search query is required");
  const normalizedTitle = title.trim();
  const normalizedArtist = artist.trim();
  const normalizedSpotifyUri = spotifyUri.trim();
  if (normalizedSpotifyUri && !/^spotify:track:[A-Za-z0-9]{22}$/.test(normalizedSpotifyUri)) {
    throw new Error("spotifyUri must identify one Spotify track");
  }
  const payload = await invokeConfiguredNode(api, nodeId, MEDIA_COMMAND, {
    query: normalizedQuery,
    ...(normalizedTitle ? { title: normalizedTitle } : {}),
    ...(normalizedArtist ? { artist: normalizedArtist } : {}),
    ...(normalizedSpotifyUri ? { spotifyUri: normalizedSpotifyUri } : {}),
    packageName: SPOTIFY_PACKAGE,
  });
  if (payload.launched !== true) throw new Error("Spotify did not accept the playback request");
  return {
    launched: true,
    playbackConfirmed: payload.playbackConfirmed === true,
    route: typeof payload.route === "string" ? payload.route : "unknown",
    query: normalizedQuery,
    ...(normalizedTitle ? { title: normalizedTitle } : {}),
    ...(normalizedArtist ? { artist: normalizedArtist } : {}),
    ...(normalizedSpotifyUri ? { spotifyUri: normalizedSpotifyUri } : {}),
    ...(typeof payload.confirmedTitle === "string"
      ? { confirmedTitle: payload.confirmedTitle.slice(0, MAX_TEXT_LENGTH) }
      : {}),
    ...(typeof payload.confirmedArtist === "string"
      ? { confirmedArtist: payload.confirmedArtist.slice(0, MAX_TEXT_LENGTH) }
      : {}),
  };
}

function jsonResult(payload) {
  return {
    content: [{ type: "text", text: JSON.stringify(payload, null, 2) }],
    details: payload,
  };
}

function configuredNodeId(api) {
  const nodeId = api.pluginConfig?.nodeId;
  if (typeof nodeId !== "string" || !NODE_ID_PATTERN.test(nodeId)) {
    throw new Error("voice-assistant-tools requires one valid configured nodeId");
  }
  return nodeId;
}

export default {
  id: "voice-assistant-tools",
  name: "Voice Assistant Tools",
  description: "Narrow Android tools for the ambient voice agent.",
  configSchema: ConfigSchema,
  register(api) {
    const nodeId = configuredNodeId(api);
    api.registerTool({
      name: "android_media_play",
      label: "Play Spotify",
      description: "Play Spotify on the configured Android phone through its locally authorized Spotify control. For an exact track, first use web_search to find its public open.spotify.com/track URL, convert the final 22-character ID to spotify:track:ID, and pass spotifyUri with title and artist. playbackConfirmed is true only when Android reports unpaused state with matching URI or metadata.",
      parameters: {
        type: "object",
        required: ["query"],
        properties: {
          query: { type: "string", minLength: 1, maxLength: 300 },
          title: { type: "string", minLength: 1, maxLength: 300 },
          artist: { type: "string", minLength: 1, maxLength: 300 },
          spotifyUri: { type: "string", pattern: "^spotify:track:[A-Za-z0-9]{22}$" },
        },
        additionalProperties: false,
      },
      execute: async (_toolCallId, request) =>
        jsonResult(await playSpotify(api, nodeId, request)),
    }, { optional: true });
  },
};
