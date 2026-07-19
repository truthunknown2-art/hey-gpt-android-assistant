import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  playSpotify,
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
      playbackConfirmed: true,
      route: "media_session_uri",
      confirmedTitle: "Kind of Blue",
      confirmedArtist: "Miles Davis",
    });

    assert.deepEqual(await playSpotify(api, CUSTOM_NODE_ID, {
      query: "Miles Davis Kind of Blue",
      title: "Kind of Blue",
      artist: "Miles Davis",
      spotifyUri: "spotify:track:0Q5VnK2DYzRyfqQRJuUtvi",
    }), {
      launched: true,
      playbackConfirmed: true,
      route: "media_session_uri",
      query: "Miles Davis Kind of Blue",
      title: "Kind of Blue",
      artist: "Miles Davis",
      spotifyUri: "spotify:track:0Q5VnK2DYzRyfqQRJuUtvi",
      confirmedTitle: "Kind of Blue",
      confirmedArtist: "Miles Davis",
    });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].nodeId, CUSTOM_NODE_ID);
    assert.equal(calls[0].command, "media.play_search");
    assert.deepEqual(calls[0].params, {
      query: "Miles Davis Kind of Blue",
      title: "Kind of Blue",
      artist: "Miles Davis",
      spotifyUri: "spotify:track:0Q5VnK2DYzRyfqQRJuUtvi",
      packageName: "com.spotify.music",
    });
  });

  it("does not claim playback confirmation from a successful intent launch", async () => {
    const { api } = fakeApi(["media.play_search"], { launched: true });

    assert.deepEqual(await playSpotify(api, CUSTOM_NODE_ID, { query: "Pearl Jam" }), {
      launched: true,
      playbackConfirmed: false,
      route: "unknown",
      query: "Pearl Jam",
    });
  });

  it("rejects arbitrary Spotify resources before invoking the phone", async () => {
    const { api, calls } = fakeApi(["media.play_search"], { launched: true });

    await assert.rejects(
      playSpotify(api, CUSTOM_NODE_ID, {
        query: "Pearl Jam",
        spotifyUri: "spotify:playlist:37i9dQZF1DX0",
      }),
      /must identify one Spotify track/,
    );
    assert.equal(calls.length, 0);
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
});
