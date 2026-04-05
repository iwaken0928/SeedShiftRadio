from __future__ import annotations

import hashlib
import math
import os
import random
import struct
import threading
import uuid
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field


PROVIDER_FINGERPRINT = "deterministic-worker:1.0"
SAMPLE_RATE = 16_000
DATA_ROOT = Path(os.environ.get("SEEDSHIFT_MUSICGEN_DATA_ROOT", "./data")).resolve()
MUSIC_ROOT = DATA_ROOT / "assets" / "music"


class MusicJobCreateRequest(BaseModel):
    requestId: str = Field(min_length=1)
    stationId: str = Field(min_length=1)
    mode: str = Field(min_length=1)
    genre: str = Field(min_length=1)
    mood: list[str] = Field(default_factory=list)
    durationSec: int = Field(ge=5, le=120)
    seed: int | None = None


class MusicJobCreateResponse(BaseModel):
    jobId: str
    status: Literal["QUEUED"]


class MusicJobStatusResponse(BaseModel):
    jobId: str
    status: Literal["QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"]
    assetPath: str | None = None
    durationSec: int | None = None
    providerFingerprint: str | None = None
    promptHash: str | None = None
    errorCode: str | None = None
    message: str | None = None


@dataclass
class WorkerJob:
    job_id: str
    request: MusicJobCreateRequest
    status: str = "QUEUED"
    asset_path: str | None = None
    duration_sec: int | None = None
    provider_fingerprint: str | None = None
    prompt_hash: str | None = None
    error_code: str | None = None
    message: str | None = None
    lock: threading.Lock = field(default_factory=threading.Lock)


app = FastAPI(title="SeedShiftRadio MusicGen Worker")
jobs: dict[str, WorkerJob] = {}


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "providerFingerprint": PROVIDER_FINGERPRINT}


@app.post("/music/jobs", response_model=MusicJobCreateResponse)
def create_job(request: MusicJobCreateRequest) -> MusicJobCreateResponse:
    job_id = f"worker-job-{uuid.uuid4().hex[:12]}"
    job = WorkerJob(
        job_id=job_id,
        request=request,
        prompt_hash=build_prompt_hash(request),
    )
    jobs[job_id] = job
    thread = threading.Thread(target=run_generation, args=(job_id,), daemon=True)
    thread.start()
    return MusicJobCreateResponse(jobId=job_id, status="QUEUED")


@app.get("/music/jobs/{job_id}", response_model=MusicJobStatusResponse)
def get_job(job_id: str) -> MusicJobStatusResponse:
    job = jobs.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="job not found")
    with job.lock:
        return MusicJobStatusResponse(
            jobId=job.job_id,
            status=job.status,
            assetPath=job.asset_path,
            durationSec=job.duration_sec,
            providerFingerprint=job.provider_fingerprint,
            promptHash=job.prompt_hash,
            errorCode=job.error_code,
            message=job.message,
        )


def run_generation(job_id: str) -> None:
    job = jobs[job_id]
    with job.lock:
        job.status = "RUNNING"
    try:
        seed = job.request.seed if job.request.seed is not None else 0
        asset_path = synthesize_wav(job_id, job.request.durationSec, seed, job.request.genre, job.request.mood)
        with job.lock:
            job.status = "SUCCEEDED"
            job.asset_path = str(asset_path)
            job.duration_sec = job.request.durationSec
            job.provider_fingerprint = PROVIDER_FINGERPRINT
            job.message = "generated"
    except Exception as exc:  # pragma: no cover
        with job.lock:
            job.status = "FAILED"
            job.error_code = "PROVIDER_BAD_RESPONSE"
            job.message = str(exc)


def synthesize_wav(job_id: str, duration_sec: int, seed: int, genre: str, mood: list[str]) -> Path:
    MUSIC_ROOT.mkdir(parents=True, exist_ok=True)
    path = MUSIC_ROOT / f"{job_id}.wav"
    base_frequency = 180 + (seed % 220)
    accent = 1 + (len(mood) % 3)
    genre_bias = (sum(ord(char) for char in genre) % 90) / 10.0
    rng = random.Random(seed)
    total_frames = duration_sec * SAMPLE_RATE

    with wave.open(str(path), "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(SAMPLE_RATE)
        for frame in range(total_frames):
            time_sec = frame / SAMPLE_RATE
            envelope = min(1.0, frame / (SAMPLE_RATE * 0.15))
            tail = min(1.0, (total_frames - frame) / (SAMPLE_RATE * 0.2))
            carrier = math.sin(2.0 * math.pi * (base_frequency + genre_bias) * time_sec)
            harmony = math.sin(2.0 * math.pi * (base_frequency * accent / 2.0) * time_sec + 0.35)
            flutter = math.sin(2.0 * math.pi * (0.8 + rng.random() * 0.2) * time_sec)
            sample = (carrier * 0.6 + harmony * 0.3 + flutter * 0.1) * envelope * tail
            wav_file.writeframesraw(struct.pack("<h", int(sample * 12_000)))
    return path


def build_prompt_hash(request: MusicJobCreateRequest) -> str:
    digest = hashlib.sha256()
    digest.update(request.model_dump_json().encode("utf-8"))
    return digest.hexdigest()
