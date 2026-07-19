import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  playSpotify,
  readMessengerNotifications,
  sanitizeMessengerNotifications,
  selectConfiguredNode,
} from "../dist/index.js";

const CUSTOM_NODE_ID = "a".repeat(64);
const OFFICIAL_NODE_ID = "b".repeat(64);

function fakeApi(commands, payload) {
  const calls = [];
  const api = {
    runtime: {
      nodes: {
        list: async () => ({
          nodes: [{
            nodeId: CUSTOM_NODE_ID,
            displayName: "Phone Assistant Tools",
            connected: true,
            commands,
          }],
        }),
        invoke: async (params) => {
          calls.push(params);
          return { ok: true, payload };
        },
      },
    },
  };
  return { api, calls };
}

describe("voice assistant tools", () => {
  it("binds Spotify playback to the configured node and fixed command", async () => {
    const { api, calls } = fakeApi(["media.play_search"], {
      launched: true,
      playbackConfirmed: false,
    });

    assert.deepEqual(await playSpotify(api, CUSTOM_NODE_ID, {
      query: "Miles Davis Kind of Blue",
      title: "Kind of Blue",
      artist: "Miles Davis",
    }), {
      launched: true,
      playbackConfirmed: false,
      query: "Miles Davis Kind of Blue",
      title: "Kind of Blue",
      artist: "Miles Davis",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].nodeId, CUSTOM_NODE_ID);
    assert.equal(calls[0].command, "media.play_search");
    assert.deepEqual(calls[0].params, {
      query: "Miles Davis Kind of Blue",
      title: "Kind of Blue",
      artist: "Miles Davis",
      packageName: "com.spotify.music",
    });
  });

  it("does not claim playback confirmation from a successful intent launch", async () => {
    const { api } = fakeApi(["media.play_search"], { launched: true });

    assert.deepEqual(await playSpotify(api, CUSTOM_NODE_ID, { query: "Pearl Jam" }), {
      launched: true,
      playbackConfirmed: false,
      query: "Pearl Jam",
    });
  });

  it("rejects a configured node that lacks the media command", () => {
    assert.throws(
      () => selectConfiguredNode(
        [{ nodeId: OFFICIAL_NODE_ID, connected: true, commands: ["notifications.list"] }],
        OFFICIAL_NODE_ID,
        "media.play_search",
      ),
      /does not declare media\.play_search/,
    );
  });

  it("invokes only the fixed Messenger command and strips private control fields", async () => {
    const { api, calls } = fakeApi(["notifications.list_package"], {
      notifications: [{
        sender: "Alex",
        textPreview: "Are you free later?",
        timestamp: 12345,
        key: "private-key",
        action: "reply",
        packageName: "com.facebook.orca",
      }],
    });

    assert.deepEqual(await readMessengerNotifications(api, CUSTOM_NODE_ID), {
      notifications: [{
        sender: "Alex",
        textPreview: "Are you free later?",
        timestamp: 12345,
      }],
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].nodeId, CUSTOM_NODE_ID);
    assert.equal(calls[0].command, "notifications.list_package");
    assert.deepEqual(calls[0].params, {});
  });

  it("limits notification count and text length", () => {
    const notifications = Array.from({ length: 25 }, () => ({
      sender: "s".repeat(600),
      textPreview: "t".repeat(600),
      timestamp: 1,
    }));

    const sanitized = sanitizeMessengerNotifications({ notifications });

    assert.equal(sanitized.length, 20);
    assert.equal(sanitized[0].sender.length, 500);
    assert.equal(sanitized[0].textPreview.length, 500);
  });
});
