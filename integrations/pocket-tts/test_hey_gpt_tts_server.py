from __future__ import annotations

import os
import unittest
from types import SimpleNamespace

from hey_gpt_tts_server import SpeechRequest, runtime, synthesize


class SynthesisLockTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.previous_login = os.environ.get("HEY_GPT_TTS_ALLOWED_LOGIN")
        os.environ["HEY_GPT_TTS_ALLOWED_LOGIN"] = "owner@example.com"
        runtime.model = SimpleNamespace(sample_rate=24_000)
        runtime.voice_state = {}
        if runtime.generation_lock.locked():
            runtime.generation_lock.release()

    def tearDown(self) -> None:
        if runtime.generation_lock.locked():
            runtime.generation_lock.release()
        runtime.model = None
        runtime.voice_state = None
        if self.previous_login is None:
            os.environ.pop("HEY_GPT_TTS_ALLOWED_LOGIN", None)
        else:
            os.environ["HEY_GPT_TTS_ALLOWED_LOGIN"] = self.previous_login

    async def test_abandoned_response_releases_lock_before_first_chunk(self) -> None:
        first = await synthesize(
            SpeechRequest(text="first"),
            tailscale_user_login="owner@example.com",
        )
        self.assertTrue(runtime.generation_lock.locked())
        self.assertIsNotNone(first.background)

        await first.background()

        self.assertFalse(runtime.generation_lock.locked())
        second = await synthesize(
            SpeechRequest(text="second"),
            tailscale_user_login="owner@example.com",
        )
        self.assertTrue(runtime.generation_lock.locked())
        self.assertIsNotNone(second.background)
        await second.background()
        self.assertFalse(runtime.generation_lock.locked())


if __name__ == "__main__":
    unittest.main()
