from __future__ import annotations

import time
import threading
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import DeterministicMusicBackend, FailingMusicBackend, GenerationResult, create_app


def test_health_reports_selected_backend_fingerprint(tmp_path: Path) -> None:
    client = TestClient(create_app(DeterministicMusicBackend(tmp_path)))

    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {
        "status": "UP",
        "providerFingerprint": "deterministic-worker:1.0",
    }


def test_music_job_contract_returns_generated_asset_metadata(tmp_path: Path) -> None:
    client = TestClient(create_app(DeterministicMusicBackend(tmp_path)))

    create_response = client.post(
        "/music/jobs",
        json={
            "requestId": "req-001",
            "stationId": "station-night",
            "mode": "BGM",
            "genre": "ambient",
            "mood": ["calm", "night"],
            "durationSec": 5,
            "seed": 42,
        },
    )

    assert create_response.status_code == 200
    payload = create_response.json()
    assert payload["status"] == "QUEUED"

    job_status = wait_for_terminal_status(client, payload["jobId"])

    assert job_status["status"] == "SUCCEEDED"
    assert job_status["durationSec"] == 5
    assert job_status["providerFingerprint"] == "deterministic-worker:1.0"
    assert job_status["promptHash"]
    assert len(job_status["promptHash"]) == 64
    assert job_status["lyricsHash"]
    assert len(job_status["lyricsHash"]) == 64
    assert job_status["message"] == "generated"
    assert job_status["model"] == "deterministic-sine"
    assert job_status["seed"] == "42"
    assert job_status["assetPath"]
    assert Path(job_status["assetPath"]).exists()
    assert Path(job_status["assetPath"]).parent == tmp_path / "assets" / "music"


def test_music_job_accepts_generation_request_without_echoing_prompt_or_lyrics(tmp_path: Path) -> None:
    client = TestClient(create_app(DeterministicMusicBackend(tmp_path)))
    prompt = "clear Japanese vocal, genre=city pop"
    lyrics = "[Verse]\n夜明けの窓辺で\n[Chorus]\nまた走り出す"

    create_response = client.post(
        "/music/jobs",
        json={
            "requestId": "req-generation-001",
            "stationId": "station-night",
            "purpose": "radio",
            "mode": "JAPANESE_SONG",
            "prompt": prompt,
            "lyrics": lyrics,
            "lyricsLanguage": "ja",
            "durationSeconds": 5,
            "bpm": 128,
            "keyScale": "C major",
            "timeSignature": "4",
            "seed": 123,
            "modelProfileId": "ace-ja-fast",
            "outputFormat": "wav",
        },
    )

    assert create_response.status_code == 200
    job_status = wait_for_terminal_status(client, create_response.json()["jobId"])

    assert job_status["status"] == "SUCCEEDED"
    assert job_status["durationSec"] == 5
    assert job_status["promptHash"] != prompt
    assert job_status["lyricsHash"] != lyrics
    assert len(job_status["promptHash"]) == 64
    assert len(job_status["lyricsHash"]) == 64
    assert "prompt" not in job_status
    assert "lyrics" not in job_status
    assert job_status["model"] == "ace-ja-fast"
    assert job_status["seed"] == "123"


def test_music_job_failure_exposes_error_metadata() -> None:
    client = TestClient(create_app(FailingMusicBackend()))
    prompt = "clear Japanese vocal, genre=city pop"
    lyrics = "[Verse]\n夜明けの窓辺で\n[Chorus]\nまた走り出す"

    create_response = client.post(
        "/music/jobs",
        json={
            "requestId": "req-fail-001",
            "stationId": "station-night",
            "purpose": "radio",
            "mode": "JAPANESE_SONG",
            "prompt": prompt,
            "lyrics": lyrics,
            "lyricsLanguage": "ja",
            "durationSeconds": 5,
            "bpm": 128,
            "keyScale": "C major",
            "timeSignature": "4",
            "seed": 7,
            "modelProfileId": "ace-ja-fast",
            "outputFormat": "wav",
        },
    )

    assert create_response.status_code == 200
    job_status = wait_for_terminal_status(client, create_response.json()["jobId"])

    assert job_status["status"] == "FAILED"
    assert job_status["assetPath"] is None
    assert job_status["durationSec"] is None
    assert job_status["providerFingerprint"] == "failing-worker:1.0"
    assert job_status["promptHash"]
    assert job_status["lyricsHash"]
    assert job_status["errorCode"] == "PROVIDER_BAD_RESPONSE"
    assert "backend forced failure" in job_status["message"]
    assert "prompt" not in job_status
    assert "lyrics" not in job_status


def test_running_job_contract_exposes_safe_metadata_without_prompt_or_lyrics(tmp_path: Path) -> None:
    backend = BlockingMusicBackend(tmp_path)
    client = TestClient(create_app(backend))
    prompt = "clear Japanese vocal, genre=city pop"
    lyrics = "[Verse]\n夜明けの窓辺で\n[Chorus]\nまた走り出す"

    create_response = client.post(
        "/music/jobs",
        json={
            "requestId": "req-running-001",
            "stationId": "station-night",
            "purpose": "radio",
            "mode": "JAPANESE_SONG",
            "prompt": prompt,
            "lyrics": lyrics,
            "lyricsLanguage": "ja",
            "durationSeconds": 5,
            "seed": 42,
            "modelProfileId": "ace-ja-fast",
            "outputFormat": "wav",
        },
    )

    assert create_response.status_code == 200
    job_id = create_response.json()["jobId"]
    running_status = wait_for_running_status(client, job_id)

    assert running_status["status"] == "RUNNING"
    assert running_status["providerFingerprint"] == "blocking-worker:1.0"
    assert running_status["assetPath"] is None
    assert running_status["promptHash"] != prompt
    assert running_status["lyricsHash"] != lyrics
    assert len(running_status["promptHash"]) == 64
    assert len(running_status["lyricsHash"]) == 64
    assert "prompt" not in running_status
    assert "lyrics" not in running_status

    backend.release.set()
    terminal_status = wait_for_terminal_status(client, job_id)
    assert terminal_status["status"] == "SUCCEEDED"


def wait_for_terminal_status(client: TestClient, job_id: str) -> dict[str, object]:
    deadline = time.time() + 2
    while time.time() < deadline:
        response = client.get(f"/music/jobs/{job_id}")
        assert response.status_code == 200
        payload = response.json()
        if payload["status"] not in {"QUEUED", "RUNNING"}:
            return payload
        time.sleep(0.01)
    raise AssertionError(f"job {job_id} did not reach terminal state")


def wait_for_running_status(client: TestClient, job_id: str) -> dict[str, object]:
    deadline = time.time() + 2
    while time.time() < deadline:
        response = client.get(f"/music/jobs/{job_id}")
        assert response.status_code == 200
        payload = response.json()
        if payload["status"] == "RUNNING":
            return payload
        time.sleep(0.01)
    raise AssertionError(f"job {job_id} did not reach running state")


class BlockingMusicBackend(DeterministicMusicBackend):
    provider_fingerprint = "blocking-worker:1.0"

    def __init__(self, data_root: Path):
        super().__init__(data_root)
        self.release = threading.Event()

    def generate(self, job_id: str, request) -> GenerationResult:
        if not self.release.wait(timeout=2):
            raise AssertionError("blocking backend was not released")
        return super().generate(job_id, request)
