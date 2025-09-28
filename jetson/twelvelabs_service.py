"""Utilities to embed and analyse recordings with the Twelve Labs API."""

from __future__ import annotations

import json
import os
import tempfile
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Optional

from scripts.twelvelabs_client import (
    DEFAULT_ANALYSIS_PATH,
    DEFAULT_BASE_URL,
    DEFAULT_EMBEDDING_TASK_PATH,
    TwelveLabsClient,
    TwelveLabsError,
    _extract_video_id,
)

__all__ = [
    "AnalysisServiceError",
    "RecordingNotFoundError",
    "TwelveLabsAnalysisService",
]


class AnalysisServiceError(RuntimeError):
    """Base class for analysis orchestration errors."""


class RecordingNotFoundError(AnalysisServiceError):
    """Raised when the requested recording could not be located on disk."""


def _clone_json(data: Any) -> Any:
    """Return a deep copy of ``data`` by round-tripping through JSON."""

    return json.loads(json.dumps(data))


def _utc_iso_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _serialise_signature(stat_result) -> Dict[str, Any]:
    modified = datetime.fromtimestamp(stat_result.st_mtime, tz=timezone.utc)
    return {
        "size": stat_result.st_size,
        "modified": modified.isoformat().replace("+00:00", "Z"),
    }


def _ensure_directory(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)


@dataclass
class AnalysisResult:
    record: Dict[str, Any]
    cached: bool


class TwelveLabsAnalysisService:
    """Create embeddings and cached analysis results for stored recordings."""

    def __init__(
        self,
        *,
        client: TwelveLabsClient,
        model_name: str,
        recordings_dir: Path,
        storage_path: Path,
        default_prompt: str,
        poll_interval: int = 10,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
        video_embedding_scope: Optional[Iterable[str]] = None,
        gzip_upload: bool = True,
    ) -> None:
        self._client = client
        self._model_name = model_name
        self._recordings_dir = Path(recordings_dir)
        self._storage_path = Path(storage_path)
        self._default_prompt = default_prompt
        self._poll_interval = max(1, int(poll_interval))
        self._temperature = temperature
        self._max_tokens = max_tokens
        self._scopes = [scope for scope in video_embedding_scope or [] if scope]
        self._gzip_upload = gzip_upload
        self._lock = threading.Lock()
        self._records: Dict[str, Dict[str, Any]] = {}
        self._load_cache()

    @staticmethod
    def default_client(api_key: str) -> TwelveLabsClient:
        """Return a Twelve Labs client configured with default endpoints."""

        return TwelveLabsClient(
            api_key=api_key,
            base_url=DEFAULT_BASE_URL,
            embedding_path=DEFAULT_EMBEDDING_TASK_PATH,
            analysis_path=DEFAULT_ANALYSIS_PATH,
        )

    def _build_key(self, stream_id: str, file_name: str) -> str:
        return f"{stream_id}/{file_name}"

    def _load_cache(self) -> None:
        if not self._storage_path.exists():
            return
        try:
            payload = json.loads(self._storage_path.read_text(encoding="utf-8"))
        except Exception:
            return
        records = payload.get("records") if isinstance(payload, dict) else None
        if isinstance(records, dict):
            self._records = {
                key: value
                for key, value in records.items()
                if isinstance(key, str) and isinstance(value, dict)
            }

    def _save_cache(self) -> None:
        _ensure_directory(self._storage_path)
        snapshot = {"records": self._records}
        temp_dir = self._storage_path.parent
        with tempfile.NamedTemporaryFile(
            "w",
            encoding="utf-8",
            dir=str(temp_dir),
            delete=False,
        ) as handle:
            json.dump(snapshot, handle, indent=2, ensure_ascii=False)
            handle.flush()
            os.fsync(handle.fileno())
            temp_path = Path(handle.name)
        temp_path.replace(self._storage_path)

    def _resolve_recording_path(self, stream_id: str, file_name: str) -> Path:
        if not stream_id:
            raise RecordingNotFoundError("streamId is required")
        if not file_name:
            raise RecordingNotFoundError("fileName is required")
        candidate = (self._recordings_dir / stream_id / file_name).resolve()
        try:
            candidate.relative_to(self._recordings_dir)
        except ValueError as exc:  # pragma: no cover - safety check
            raise RecordingNotFoundError("Recording path escapes recordings directory") from exc
        if not candidate.exists() or not candidate.is_file():
            raise RecordingNotFoundError(
                f"Recording {stream_id}/{file_name} was not found at {candidate}"
            )
        return candidate

    def _signature_matches(self, existing: Dict[str, Any], signature: Dict[str, Any]) -> bool:
        source = existing.get("source") if isinstance(existing, dict) else {}
        stored = source.get("signature") if isinstance(source, dict) else None
        return stored == signature

    def get_cached_record(self, stream_id: str, file_name: str) -> Optional[Dict[str, Any]]:
        key = self._build_key(stream_id, file_name)
        with self._lock:
            record = self._records.get(key)
            if record is None:
                return None
            return _clone_json(record)

    def list_cached_records(self) -> list[Dict[str, Any]]:
        with self._lock:
            records = [_clone_json(record) for record in self._records.values()]
        records.sort(
            key=lambda item: item.get("updatedAt") or item.get("source", {}).get("modified") or "",
            reverse=True,
        )
        return records

    def ensure_analysis(
        self,
        *,
        stream_id: str,
        file_name: str,
        prompt: Optional[str] = None,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
    ) -> AnalysisResult:
        recording_path = self._resolve_recording_path(stream_id, file_name)
        stat_result = recording_path.stat()
        signature = _serialise_signature(stat_result)
        key = self._build_key(stream_id, file_name)

        with self._lock:
            existing = self._records.get(key)
            if existing and existing.get("analysis") and self._signature_matches(existing, signature):
                return AnalysisResult(record=_clone_json(existing), cached=True)

        prompt_value = (prompt or self._default_prompt).strip()
        temperature_value = self._temperature if temperature is None else temperature
        max_tokens_value = self._max_tokens if max_tokens is None else max_tokens

        embedding_kwargs: Dict[str, Any] = {
            "model_name": self._model_name,
            "video_embedding_scope": self._scopes or None,
            "gzip_upload": self._gzip_upload,
        }

        with open(recording_path, "rb") as handle:
            embedding_response = self._client.create_embedding(
                video_file=handle,
                video_file_name=file_name,
                **embedding_kwargs,
            )

        task_id = (
            embedding_response.get("task_id")
            or embedding_response.get("id")
            or embedding_response.get("_id")
        )
        if not task_id:
            raise AnalysisServiceError(
                "Embedding response did not include a task identifier"
            )
        status_url = embedding_response.get("status_url") or embedding_response.get("href")
        if not isinstance(status_url, str) or not status_url:
            status_url = f"{self._client.embedding_path.rstrip('/')}/{task_id}/status"

        embedding_status = self._client.wait_for_task(
            status_url=status_url,
            poll_interval=self._poll_interval,
        )

        video_id = _extract_video_id(embedding_status)
        if not video_id:
            raise AnalysisServiceError("Unable to determine Twelve Labs video_id from embedding status")

        analysis_payload: Dict[str, Any] = {
            "video_id": video_id,
            "prompt": prompt_value,
            "stream": False,
        }
        if temperature_value is not None:
            analysis_payload["temperature"] = temperature_value
        if max_tokens_value is not None:
            analysis_payload["max_tokens"] = max_tokens_value

        analysis_response = self._client.request_analysis(
            video_id=video_id,
            prompt=prompt_value,
            temperature=temperature_value,
            stream=False,
            max_tokens=max_tokens_value,
        )

        record = {
            "streamId": stream_id,
            "fileName": file_name,
            "source": {
                "path": str(recording_path),
                "signature": signature,
            },
            "prompt": prompt_value,
            "temperature": temperature_value,
            "maxTokens": max_tokens_value,
            "videoEmbeddingScope": list(self._scopes) if self._scopes else None,
            "pollInterval": self._poll_interval,
            "videoId": video_id,
            "updatedAt": _utc_iso_now(),
            "embedding": {
                "taskId": task_id,
                "statusUrl": status_url,
                "response": embedding_response,
                "status": embedding_status,
                "request": {
                    "modelName": self._model_name,
                    "gzipUpload": self._gzip_upload,
                },
            },
            "analysis": {
                "request": analysis_payload,
                "response": analysis_response,
            },
        }

        with self._lock:
            self._records[key] = record
            self._save_cache()

        return AnalysisResult(record=_clone_json(record), cached=False)


__all__ += [
    "DEFAULT_BASE_URL",
    "DEFAULT_EMBEDDING_TASK_PATH",
    "DEFAULT_ANALYSIS_PATH",
    "TwelveLabsError",
]
