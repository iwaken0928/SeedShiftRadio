from __future__ import annotations

import time
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import DeterministicMusicBackend, FailingMusicBackend, create_app


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
    assert job_status["message"] == "generated"
    assert job_status["assetPath"]
    assert Path(job_status["assetPath"]).exists()
    assert Path(job_status["assetPath"]).parent == tmp_path / "assets" / "music"


def test_music_job_failure_exposes_error_metadata() -> None:
    client = TestClient(create_app(FailingMusicBackend()))

    create_response = client.post(
        "/music/jobs",
        json={
            "requestId": "req-fail-001",
            "stationId": "station-night",
            "mode": "BGM",
            "genre": "ambient",
            "mood": ["calm"],
            "durationSec": 5,
            "seed": 7,
        },
    )

    assert create_response.status_code == 200
    job_status = wait_for_terminal_status(client, create_response.json()["jobId"])

    assert job_status["status"] == "FAILED"
    assert job_status["assetPath"] is None
    assert job_status["durationSec"] is None
    assert job_status["providerFingerprint"] == "failing-worker:1.0"
    assert job_status["promptHash"]
    assert job_status["errorCode"] == "PROVIDER_BAD_RESPONSE"
    assert "backend forced failure" in job_status["message"]


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
