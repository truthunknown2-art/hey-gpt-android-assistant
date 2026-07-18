from __future__ import annotations

import logging
import os
import threading
import time
from collections.abc import Iterator
from contextlib import asynccontextmanager

import numpy as np
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pocket_tts import TTSModel
from pydantic import BaseModel, Field


LOGGER = logging.getLogger("hey-gpt-tts")
MAX_TEXT_LENGTH = 4_000


class SpeechRequest(BaseModel):
    text: str = Field(min_length=1, max_length=MAX_TEXT_LENGTH)


class Runtime:
    def __init__(self) -> None:
        self.model: TTSModel | None = None
        self.voice_state: dict | None = None
        self.generation_lock = threading.Lock()


runtime = Runtime()


def _required_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} must be configured")
    return value


@asynccontextmanager
async def lifespan(_: FastAPI):
    voice = os.environ.get("HEY_GPT_TTS_VOICE", "alba").strip() or "alba"
    LOGGER.info("Loading Pocket TTS model and voice=%s", voice)
    runtime.model = TTSModel.load_model()
    runtime.voice_state = runtime.model.get_state_for_audio_prompt(voice)
    LOGGER.info("Pocket TTS ready sample_rate=%s", runtime.model.sample_rate)
    yield
    runtime.model = None
    runtime.voice_state = None


app = FastAPI(title="Hey GPT Pocket TTS", lifespan=lifespan)


def _authorize(tailscale_user_login: str | None) -> None:
    expected = _required_env("HEY_GPT_TTS_ALLOWED_LOGIN")
    if tailscale_user_login is None or tailscale_user_login.casefold() != expected.casefold():
        raise HTTPException(status_code=403, detail="Tailscale identity is not authorized")


@app.get("/health")
async def health(tailscale_user_login: str | None = Header(default=None)) -> dict[str, str]:
    _authorize(tailscale_user_login)
    if runtime.model is None or runtime.voice_state is None:
        raise HTTPException(status_code=503, detail="TTS model is not ready")
    return {"status": "ready"}


def _pcm_stream(text: str) -> Iterator[bytes]:
    model = runtime.model
    voice_state = runtime.voice_state
    if model is None or voice_state is None:
        raise RuntimeError("TTS model is not ready")

    started = time.monotonic()
    chunks = 0
    try:
        for audio in model.generate_audio_stream(voice_state, text):
            pcm = audio.detach().cpu().clamp(-1.0, 1.0).mul(32767.0)
            # Pocket TTS returns float PCM; little-endian signed 16-bit keeps
            # the Android streaming path dependency-free and low latency.
            samples = np.asarray(pcm.numpy(), dtype="<i2")
            chunks += 1
            yield samples.tobytes()
    finally:
        LOGGER.info("Synthesis stream closed chunks=%d elapsed=%.3fs", chunks, time.monotonic() - started)


@app.post("/v1/tts")
async def synthesize(
    request: SpeechRequest,
    tailscale_user_login: str | None = Header(default=None),
) -> StreamingResponse:
    _authorize(tailscale_user_login)
    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text cannot be blank")
    if not runtime.generation_lock.acquire(blocking=False):
        raise HTTPException(status_code=429, detail="TTS is already speaking")

    def stream_and_release() -> Iterator[bytes]:
        try:
            yield from _pcm_stream(text)
        finally:
            runtime.generation_lock.release()

    sample_rate = runtime.model.sample_rate if runtime.model is not None else 24_000
    return StreamingResponse(
        stream_and_release(),
        media_type=f"audio/L16;rate={sample_rate};channels=1",
        headers={
            "Cache-Control": "no-store",
            "X-Audio-Sample-Rate": str(sample_rate),
            "X-Audio-Channels": "1",
            "X-Audio-Encoding": "pcm_s16le",
        },
    )
