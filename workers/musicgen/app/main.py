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
from typing import Literal, Protocol

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field, model_validator


SAMPLE_RATE = 16_000
DEFAULT_DATA_ROOT = Path(os.environ.get("SEEDSHIFT_MUSICGEN_DATA_ROOT", "./data")).resolve()


class MusicJobCreateRequest(BaseModel):
    requestId: str = Field(min_length=1)
    stationId: str = Field(min_length=1)
    purpose: str = "radio"
    mode: str = Field(default="JAPANESE_SONG", min_length=1)
    prompt: str | None = None
    lyrics: str = ""
    lyricsLanguage: str = "ja"
    durationSeconds: int | None = Field(default=None, ge=5, le=600)
    bpm: int | None = None
    keyScale: str = ""
    timeSignature: str = "4"
    seed: int | None = None
    modelProfileId: str | None = None
    outputFormat: str = "wav"
    genre: str | None = None
    mood: list[str] = Field(default_factory=list)
    durationSec: int | None = Field(default=None, ge=5, le=120)

    @model_validator(mode="after")
    def normalize_compatible_payload(self) -> "MusicJobCreateRequest":
        if self.durationSeconds is None:
            self.durationSeconds = self.durationSec if self.durationSec is not None else 30
        if self.durationSec is None:
            self.durationSec = self.durationSeconds
        if self.prompt is None or not self.prompt.strip():
            genre = self.genre or "ambient"
            mood = ",".join(self.mood) if self.mood else "radio"
            self.prompt = f"Japanese original radio music, genre={genre}, mood={mood}"
        if self.genre is None or not self.genre.strip():
            self.genre = extract_genre(self.prompt)
        if not self.outputFormat:
            self.outputFormat = "wav"
        return self


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
    lyricsHash: str | None = None
    errorCode: str | None = None
    message: str | None = None
    model: str | None = None
    lmModel: str | None = None
    seed: str | None = None


@dataclass
class WorkerJob:
    job_id: str
    request: MusicJobCreateRequest
    status: str = "QUEUED"
    asset_path: str | None = None
    duration_sec: int | None = None
    provider_fingerprint: str | None = None
    prompt_hash: str | None = None
    lyrics_hash: str | None = None
    error_code: str | None = None
    message: str | None = None
    model: str | None = None
    lm_model: str | None = None
    seed: str | None = None
    lock: threading.Lock = field(default_factory=threading.Lock)


@dataclass(frozen=True)
class GenerationResult:
    asset_path: Path
    duration_sec: int
    provider_fingerprint: str
    message: str = "generated"
    model: str | None = None
    lm_model: str | None = None
    seed: str | None = None


class MusicBackendError(RuntimeError):
    def __init__(self, error_code: str, message: str):
        super().__init__(message)
        self.error_code = error_code


class MusicBackend(Protocol):
    provider_fingerprint: str

    def generate(self, job_id: str, request: MusicJobCreateRequest) -> GenerationResult:
        ...


class DeterministicMusicBackend:
    provider_fingerprint = "deterministic-worker:1.0"

    def __init__(self, data_root: Path | None = None):
        root = (data_root or DEFAULT_DATA_ROOT).resolve()
        self.music_root = root / "assets" / "music"

    def generate(self, job_id: str, request: MusicJobCreateRequest) -> GenerationResult:
        seed = request.seed if request.seed is not None else 0
        asset_path = synthesize_wav(
            self.music_root,
            job_id,
            request.durationSeconds or 30,
            seed,
            request.genre or "ambient",
            request.mood,
        )
        return GenerationResult(
            asset_path=asset_path,
            duration_sec=request.durationSeconds or 30,
            provider_fingerprint=self.provider_fingerprint,
            model=request.modelProfileId or "deterministic-sine",
            seed=str(seed),
        )


class FailingMusicBackend:
    provider_fingerprint = "failing-worker:1.0"

    def generate(self, job_id: str, request: MusicJobCreateRequest) -> GenerationResult:
        raise MusicBackendError("PROVIDER_BAD_RESPONSE", f"backend forced failure for {request.requestId}")


def create_backend(backend_name: str | None = None) -> MusicBackend:
    selected = (backend_name or os.environ.get("SEEDSHIFT_MUSICGEN_BACKEND", "deterministic")).strip().lower()
    if selected == "deterministic":
        return DeterministicMusicBackend()
    if selected == "failing":
        return FailingMusicBackend()
    raise ValueError(f"Unsupported MusicGen backend: {selected}")


def create_app(backend: MusicBackend | None = None) -> FastAPI:
    selected_backend = backend or create_backend()
    application = FastAPI(title="SeedShiftRadio MusicGen Worker")
    application.state.jobs = {}
    application.state.backend = selected_backend

    @application.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP", "providerFingerprint": selected_backend.provider_fingerprint}

    @application.post("/music/jobs", response_model=MusicJobCreateResponse)
    def create_job(request: MusicJobCreateRequest) -> MusicJobCreateResponse:
        job_id = f"worker-job-{uuid.uuid4().hex[:12]}"
        job = WorkerJob(
            job_id=job_id,
            request=request,
            prompt_hash=build_prompt_hash(request),
            lyrics_hash=build_lyrics_hash(request),
        )
        application.state.jobs[job_id] = job
        thread = threading.Thread(target=run_generation, args=(application, job_id), daemon=True)
        thread.start()
        return MusicJobCreateResponse(jobId=job_id, status="QUEUED")

    @application.get("/music/jobs/{job_id}", response_model=MusicJobStatusResponse)
    def get_job(job_id: str) -> MusicJobStatusResponse:
        job = application.state.jobs.get(job_id)
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
                lyricsHash=job.lyrics_hash,
                errorCode=job.error_code,
                message=job.message,
                model=job.model,
                lmModel=job.lm_model,
                seed=job.seed,
            )

    return application


def run_generation(application: FastAPI, job_id: str) -> None:
    jobs: dict[str, WorkerJob] = application.state.jobs
    backend: MusicBackend = application.state.backend
    job = jobs[job_id]
    with job.lock:
        job.status = "RUNNING"
    try:
        result = backend.generate(job_id, job.request)
        with job.lock:
            job.status = "SUCCEEDED"
            job.asset_path = str(result.asset_path)
            job.duration_sec = result.duration_sec
            job.provider_fingerprint = result.provider_fingerprint
            job.message = result.message
            job.model = result.model
            job.lm_model = result.lm_model
            job.seed = result.seed
    except MusicBackendError as exc:
        with job.lock:
            job.status = "FAILED"
            job.error_code = exc.error_code
            job.provider_fingerprint = backend.provider_fingerprint
            job.message = str(exc)
    except Exception as exc:  # pragma: no cover
        with job.lock:
            job.status = "FAILED"
            job.error_code = "PROVIDER_BAD_RESPONSE"
            job.provider_fingerprint = backend.provider_fingerprint
            job.message = str(exc)


def synthesize_wav(music_root: Path, job_id: str, duration_sec: int, seed: int, genre: str, mood: list[str]) -> Path:
    music_root.mkdir(parents=True, exist_ok=True)
    path = music_root / f"{job_id}.wav"
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
    return sha256(request.prompt or "")


def build_lyrics_hash(request: MusicJobCreateRequest) -> str:
    return sha256(request.lyrics or "")


def sha256(value: str) -> str:
    digest = hashlib.sha256()
    digest.update(value.encode("utf-8"))
    return digest.hexdigest()


def extract_genre(prompt: str) -> str:
    lowered = prompt.lower()
    marker = "genre="
    if marker not in lowered:
        return "ambient"
    start = lowered.index(marker) + len(marker)
    end_candidates = [index for index in [lowered.find(",", start), lowered.find(";", start)] if index >= 0]
    end = min(end_candidates) if end_candidates else len(lowered)
    genre = lowered[start:end].strip()
    return genre or "ambient"


app = create_app()
