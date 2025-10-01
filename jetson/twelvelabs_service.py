"""Twelve Labs analysis orchestration with persistent caching."""

from __future__ import annotations

import json
import threading
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Optional

from .twelvelabs_client import (
    AnalysisText,
    DEFAULT_GIST_TYPES,
    DEFAULT_INDEX_NAME,
    DEFAULT_PROMPT,
    DEFAULT_EMBEDDING_OPTIONS,
    TwelveLabsClient,
)


LOGGER = logging.getLogger(__name__)


@dataclass(slots=True)
class AnalysisResult:
    record: Dict[str, Any]
    cached: bool


@dataclass(slots=True)
class EmbeddingResult:
    record: Dict[str, Any]
    cached: bool


class AnalysisServiceError(RuntimeError):
    """Raised when the Twelve Labs workflow fails."""


class RecordingNotFoundError(AnalysisServiceError):
    """Raised when a requested recording cannot be located on disk."""


def _clone(data: Dict[str, Any]) -> Dict[str, Any]:
    return json.loads(json.dumps(data))


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _file_signature(path: Path) -> Dict[str, Any]:
    stat = path.stat()
    modified = datetime.fromtimestamp(stat.st_mtime, tz=timezone.utc)
    return {
        "size": stat.st_size,
        "modified": modified.isoformat().replace("+00:00", "Z"),
    }


def _extract_hls_url(payload: Optional[Dict[str, Any]]) -> Optional[str]:
    if not isinstance(payload, dict):
        return None
    hls = payload.get("hls")
    if isinstance(hls, dict):
        for key in ("video_url", "videoUrl", "url"):
            value = hls.get(key)
            if isinstance(value, str) and value:
                return value
    return None


class TwelveLabsAnalysisService:
    """Upload recordings to Twelve Labs and cache the generated responses."""

    def __init__(
        self,
        *,
        client: TwelveLabsClient,
        recordings_dir: Path,
        storage_path: Path,
        default_prompt: str = DEFAULT_PROMPT,
        poll_interval: int = 5,
        gist_types: Optional[list[str]] = None,
        index_name: str = DEFAULT_INDEX_NAME,
    ) -> None:
        self._client = client
        self._recordings_dir = Path(recordings_dir)
        self._storage_path = Path(storage_path)
        prompt_seed = default_prompt.strip() if isinstance(default_prompt, str) else ""
        self._default_prompt = prompt_seed or DEFAULT_PROMPT
        self._poll_interval = max(1, int(poll_interval))
        self._gist_types = gist_types or list(DEFAULT_GIST_TYPES)
        self._index_name = index_name or DEFAULT_INDEX_NAME
        self._index_id: Optional[str] = None
        self._index_payload: Dict[str, Any] = {}
        self._records: Dict[str, Dict[str, Any]] = {}
        self._lock = threading.Lock()

        self._load_cache()
        self._ensure_index()

    # ------------------------------------------------------------------
    # Cache helpers
    # ------------------------------------------------------------------
    def _load_cache(self) -> None:
        if not self._storage_path.exists():
            return
        try:
            with open(self._storage_path, "r", encoding="utf-8") as handle:
                payload = json.load(handle)
        except (OSError, json.JSONDecodeError):
            return

        index_block = payload.get("index") if isinstance(payload, dict) else None
        if isinstance(index_block, dict):
            if index_block.get("name") == self._index_name:
                self._index_payload = index_block.get("payload") or {}
                index_id = index_block.get("id")
                if isinstance(index_id, str) and index_id:
                    self._index_id = index_id

        records_block = payload.get("records") if isinstance(payload, dict) else None
        if isinstance(records_block, dict):
            self._records = records_block

    def _save_cache(self) -> None:
        self._storage_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "index": {
                "name": self._index_name,
                "id": self._index_id,
                "payload": self._index_payload,
            },
            "records": self._records,
        }
        tmp_path = self._storage_path.with_suffix(".tmp")
        with open(tmp_path, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
        tmp_path.replace(self._storage_path)

    def _build_key(self, stream_id: str, file_name: str) -> str:
        return f"{stream_id.strip()}/{file_name.strip()}"

    def _upload_recording(
        self,
        *,
        key: str,
        stream_id: str,
        file_name: str,
        recording_path: Path,
        signature: Dict[str, Any],
    ) -> Dict[str, Any]:
        """Upload a recording to Twelve Labs and persist the cached metadata."""

        index_id = self._ensure_index()
        with open(recording_path, "rb") as handle:
            task_payload = self._client.create_indexing_task(
                index_id=index_id,
                video_file=handle,
            )

        task_id = task_payload.get("id") or task_payload.get("task_id")
        if not isinstance(task_id, str) or not task_id:
            raise AnalysisServiceError("Twelve Labs response did not include a task identifier")

        status_payload = self._client.wait_for_task(
            task_id=task_id,
            poll_interval=self._poll_interval,
        )
        video_id = (
            status_payload.get("video_id")
            or status_payload.get("videoId")
            or task_payload.get("video_id")
            or task_payload.get("videoId")
        )
        if not isinstance(video_id, str) or not video_id:
            raise AnalysisServiceError("Unable to determine the Twelve Labs video identifier")

        timestamp = _utc_now()
        record = {
            "streamId": stream_id,
            "fileName": file_name,
            "videoId": video_id,
            "createdAt": timestamp,
            "updatedAt": timestamp,
            "source": {
                "path": str(recording_path),
                "signature": signature,
            },
            "index": {
                "id": index_id,
                "name": self._index_name,
            },
            "task": {
                "id": task_id,
                "status": status_payload,
                "response": task_payload,
            },
        }

        with self._lock:
            self._records[key] = record
            self._save_cache()
            return _clone(record)

    # ------------------------------------------------------------------
    # Index helpers
    # ------------------------------------------------------------------
    def _ensure_index(self) -> str:
        if self._index_id:
            return self._index_id
        payload = self._client.ensure_index(self._index_name)
        index_id = self._client.get_index_id(self._index_name)
        if not index_id:
            raise AnalysisServiceError("Failed to resolve Twelve Labs index identifier")
        self._index_payload = payload
        self._index_id = index_id
        with self._lock:
            self._save_cache()
        return index_id

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------
    def list_cached_records(self) -> list[Dict[str, Any]]:
        with self._lock:
            records = [_clone(record) for record in self._records.values()]
        records.sort(key=lambda item: item.get("updatedAt", ""), reverse=True)
        return records

    def get_cached_record(self, stream_id: str, file_name: str) -> Optional[Dict[str, Any]]:
        key = self._build_key(stream_id, file_name)
        with self._lock:
            record = self._records.get(key)
            return _clone(record) if record else None

    def ensure_analysis(
        self,
        *,
        stream_id: str,
        file_name: str,
        prompt: Optional[str] = None,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
    ) -> AnalysisResult:
        if not stream_id or not file_name:
            raise AnalysisServiceError("Both stream_id and file_name are required")

        recording_path = (self._recordings_dir / stream_id / file_name).resolve()
        if not recording_path.exists():
            raise RecordingNotFoundError(f"Recording not found: {recording_path}")

        signature = _file_signature(recording_path)
        key = self._build_key(stream_id, file_name)
        with self._lock:
            existing = self._records.get(key)
            if existing and existing.get("source", {}).get("signature") == signature:
                return AnalysisResult(record=_clone(existing), cached=True)

        index_id = self._ensure_index()
        with open(recording_path, "rb") as handle:
            task_payload = self._client.create_indexing_task(
                index_id=index_id,
                video_file=handle,
            )

        task_id = task_payload.get("id") or task_payload.get("task_id")
        if not isinstance(task_id, str) or not task_id:
            raise AnalysisServiceError("Twelve Labs response did not include a task identifier")

        status_payload = self._client.wait_for_task(
            task_id=task_id,
            poll_interval=self._poll_interval,
        )
        video_id = (
            status_payload.get("video_id")
            or status_payload.get("videoId")
            or task_payload.get("video_id")
            or task_payload.get("videoId")
        )
        if not isinstance(video_id, str) or not video_id:
            raise AnalysisServiceError("Unable to determine the Twelve Labs video identifier")

        gist_payload = self._client.fetch_gist(video_id=video_id, gist_types=self._gist_types)

        prompt_candidate = prompt if isinstance(prompt, str) else None
        prompt_value = prompt_candidate.strip() if prompt_candidate else ""
        if not prompt_value:
            prompt_value = self._default_prompt

        analysis_text: AnalysisText = self._client.collect_analysis(
            video_id=video_id,
            prompt=prompt_value,
            temperature=temperature,
            max_tokens=max_tokens,
        )

        video_payload: Optional[Dict[str, Any]] = None
        try:
            video_payload = self._client.retrieve_video_metadata(
                index_id=index_id,
                video_id=video_id,
            )
        except Exception:  # pragma: no cover - diagnostics only
            LOGGER.exception("Failed to retrieve video metadata for Twelve Labs recording")

        timestamp = _utc_now()
        created_at = existing.get("createdAt") if existing else timestamp  # type: ignore[union-attr]
        previous_video = existing.get("video") if isinstance(existing, dict) else None  # type: ignore[union-attr]
        previous_embeddings = existing.get("embeddings") if isinstance(existing, dict) else None  # type: ignore[union-attr]
        video_block: Dict[str, Any] = {}
        if isinstance(previous_video, dict):
            video_block.update(previous_video)
        if video_payload:
            video_block["metadata"] = video_payload
            hls_url = _extract_hls_url(video_payload)
            if hls_url:
                video_block["hlsUrl"] = hls_url
            video_block["syncedAt"] = timestamp
        elif video_block:
            # Ensure legacy records expose the derived URL if missing.
            if "hlsUrl" not in video_block:
                derived = _extract_hls_url(video_block.get("metadata"))
                if derived:
                    video_block["hlsUrl"] = derived

        record = {
            "streamId": stream_id,
            "fileName": file_name,
            "videoId": video_id,
            "prompt": prompt_value,
            "temperature": temperature,
            "maxTokens": max_tokens,
            "createdAt": created_at or timestamp,
            "updatedAt": timestamp,
            "source": {
                "path": str(recording_path),
                "signature": signature,
            },
            "index": {
                "id": index_id,
                "name": self._index_name,
            },
            "task": {
                "id": task_id,
                "status": status_payload,
                "response": task_payload,
            },
            "gist": {
                "types": list(self._gist_types),
                "response": gist_payload,
            },
            "analysis": {
                "prompt": prompt_value,
                "text": analysis_text.text,
                "chunks": analysis_text.chunks,
            },
        }

        if video_block:
            record["video"] = video_block
        if isinstance(previous_embeddings, dict):
            record["embeddings"] = previous_embeddings

        with self._lock:
            self._records[key] = record
            self._save_cache()

        return AnalysisResult(record=_clone(record), cached=False)

    def ensure_embeddings(
        self,
        *,
        stream_id: str,
        file_name: str,
        embedding_options: Optional[Iterable[str]] = None,
        include_transcription: bool = False,
    ) -> EmbeddingResult:
        if not stream_id or not file_name:
            raise AnalysisServiceError("Both stream_id and file_name are required")

        recording_path = (self._recordings_dir / stream_id / file_name).resolve()
        if not recording_path.exists():
            raise RecordingNotFoundError(f"Recording not found: {recording_path}")

        key = self._build_key(stream_id, file_name)
        signature = _file_signature(recording_path)
        with self._lock:
            existing = self._records.get(key)
            if existing:
                cached_signature = (
                    existing.get("source", {}).get("signature")
                    if isinstance(existing.get("source"), dict)
                    else None
                )
                if cached_signature != signature:
                    existing = None

        if not existing:
            existing = self._upload_recording(
                key=key,
                stream_id=stream_id,
                file_name=file_name,
                recording_path=recording_path,
                signature=signature,
            )

        cached_embeddings = existing.get("embeddings")
        index_info = existing.get("index", {})
        video_id = existing.get("videoId")
        index_id = index_info.get("id")

        if not isinstance(video_id, str) or not video_id:
            raise AnalysisServiceError("Recording is missing the Twelve Labs video identifier")
        if not isinstance(index_id, str) or not index_id:
            index_id = self._ensure_index()

        if embedding_options is None:
            options = list(DEFAULT_EMBEDDING_OPTIONS)
        else:
            options = []
            for item in embedding_options:
                candidate = str(item).strip()
                if candidate and candidate not in options:
                    options.append(candidate)
            if not options:
                options = list(DEFAULT_EMBEDDING_OPTIONS)

        if (
            isinstance(cached_embeddings, dict)
            and isinstance(cached_embeddings.get("options"), list)
            and sorted(str(opt) for opt in cached_embeddings["options"]) == sorted(options)
        ):
            return EmbeddingResult(record=_clone(existing), cached=True)

        try:
            metadata = self._client.retrieve_video_metadata(
                index_id=index_id,
                video_id=video_id,
                include_embeddings=options,
                include_transcription=include_transcription,
            )
        except Exception as exc:  # pragma: no cover - diagnostics only
            LOGGER.exception("Failed to retrieve Twelve Labs video embeddings")
            raise AnalysisServiceError(str(exc)) from exc

        timestamp = _utc_now()
        embedding_payload = metadata.get("embedding") if isinstance(metadata, dict) else None
        transcription_payload = metadata.get("transcription") if isinstance(metadata, dict) else None
        hls_url = _extract_hls_url(metadata if isinstance(metadata, dict) else None)

        with self._lock:
            record = self._records.get(key)
            if not record:
                raise AnalysisServiceError(
                    "Recording cache was removed while retrieving embeddings"
                )
            video_block = {}
            if isinstance(record.get("video"), dict):
                video_block.update(record["video"])
            video_block["metadata"] = metadata
            if hls_url:
                video_block["hlsUrl"] = hls_url
            video_block["syncedAt"] = timestamp
            record["video"] = video_block
            record["embeddings"] = {
                "options": options,
                "response": embedding_payload,
                "retrievedAt": timestamp,
            }
            if transcription_payload is not None:
                record["embeddings"]["transcription"] = transcription_payload
            self._save_cache()
            updated = _clone(record)

        return EmbeddingResult(record=updated, cached=False)


__all__ = [
    "AnalysisResult",
    "AnalysisServiceError",
    "EmbeddingResult",
    "RecordingNotFoundError",
    "TwelveLabsAnalysisService",
]
