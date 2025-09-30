"""Utilities to embed and analyse recordings with the Twelve Labs API."""

from __future__ import annotations

import json
import os
import tempfile
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Optional, Sequence

from .twelvelabs_client import (
    DEFAULT_BASE_URL,
    DEFAULT_GIST_TYPES,
    DEFAULT_INDEX_MODEL_NAME,
    DEFAULT_INDEX_MODEL_OPTIONS,
    DEFAULT_INDEX_NAME,
    DEFAULT_SEARCH_OPTIONS,
    DEFAULT_SUMMARY_TYPES,
    TwelveLabsClient,
    TwelveLabsError,
    extract_index_id,
    extract_video_id,
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


def _normalise_list(values: Optional[Iterable[str]], default: Sequence[str]) -> list[str]:
    if values is None:
        return list(default)
    parsed = [value.strip() for value in values if value and value.strip()]
    return parsed or list(default)


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
        index_name: str = DEFAULT_INDEX_NAME,
        index_model_name: str = DEFAULT_INDEX_MODEL_NAME,
        index_model_options: Optional[Iterable[str]] = None,
        index_addons: Optional[Iterable[str]] = None,
        enable_video_stream: bool = False,
        user_metadata: Optional[str] = None,
        gist_types: Optional[Iterable[str]] = None,
        summary_types: Optional[Iterable[str]] = None,
        summary_prompt: Optional[str] = None,
        summary_temperature: Optional[float] = None,
        summary_max_tokens: Optional[int] = None,
        search_prompt: Optional[str] = None,
        search_options: Optional[Iterable[str]] = None,
        search_group_by: str = "video",
        search_threshold: str = "medium",
        search_operator: str = "or",
        search_page_limit: int = 5,
        search_sort: str = "score",
        include_hls_url: bool = False,
        ignore_hls_errors: bool = False,
    ) -> None:
        self._client = client
        self._model_name = model_name
        self._recordings_dir = Path(recordings_dir)
        self._storage_path = Path(storage_path)
        self._default_prompt = default_prompt
        self._poll_interval = max(1, int(poll_interval))
        self._temperature = temperature
        self._max_tokens = max_tokens
        self._index_name = index_name or DEFAULT_INDEX_NAME
        self._index_model_name = index_model_name or DEFAULT_INDEX_MODEL_NAME
        self._index_model_options = _normalise_list(
            index_model_options, DEFAULT_INDEX_MODEL_OPTIONS
        )
        self._index_addons = [
            addon.strip()
            for addon in index_addons or []
            if addon and addon.strip()
        ]
        self._enable_video_stream = enable_video_stream
        self._user_metadata = user_metadata
        self._gist_types = _normalise_list(gist_types, DEFAULT_GIST_TYPES)
        self._summary_types = _normalise_list(summary_types, DEFAULT_SUMMARY_TYPES)
        self._summary_prompt = summary_prompt
        self._summary_temperature = summary_temperature
        self._summary_max_tokens = summary_max_tokens
        self._search_prompt = (
            search_prompt.strip()
            if isinstance(search_prompt, str) and search_prompt.strip()
            else None
        )
        self._search_options = (
            _normalise_list(search_options, DEFAULT_SEARCH_OPTIONS)
            if self._search_prompt
            else []
        )
        self._search_group_by = search_group_by
        self._search_threshold = search_threshold
        self._search_operator = search_operator
        self._search_page_limit = max(1, int(search_page_limit))
        self._search_sort = search_sort
        self._include_hls_url = include_hls_url
        self._ignore_hls_errors = ignore_hls_errors
        self._lock = threading.Lock()
        self._records: Dict[str, Dict[str, Any]] = {}
        self._load_cache()

    @staticmethod
    def default_client(api_key: str) -> TwelveLabsClient:
        """Return a Twelve Labs client configured with default endpoints."""

        return TwelveLabsClient(
            api_key=api_key,
            base_url=DEFAULT_BASE_URL,
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

        index_response = self._client.ensure_index(
            index_name=self._index_name,
            model_name=self._index_model_name,
            model_options=self._index_model_options,
            addons=self._index_addons,
        )
        index_id = extract_index_id(index_response)
        if not index_id:
            raise AnalysisServiceError("Unable to determine managed index identifier")

        with open(recording_path, "rb") as handle:
            ingest_response = self._client.create_indexing_task(
                index_id=index_id,
                video_file=handle,
                enable_video_stream=self._enable_video_stream,
                user_metadata=self._user_metadata,
            )

        task_id = (
            ingest_response.get("task_id")
            or ingest_response.get("id")
            or ingest_response.get("_id")
        )
        if not isinstance(task_id, str) or not task_id:
            raise AnalysisServiceError(
                "Indexing response did not include a task identifier"
            )

        ingest_status = self._client.wait_for_task(
            task_id=task_id,
            poll_interval=self._poll_interval,
        )

        video_id = (
            extract_video_id(ingest_status)
            or ingest_response.get("video_id")
            or ingest_response.get("videoId")
        )
        if not isinstance(video_id, str) or not video_id:
            raise AnalysisServiceError(
                "Unable to determine Twelve Labs video_id from indexing status"
            )

        gist_response = self._client.fetch_gist(
            video_id=video_id,
            gist_types=self._gist_types,
        )

        summary_payloads: Dict[str, Any] = {}
        for summary_type in self._summary_types:
            summary_payloads[summary_type] = self._client.summarize(
                video_id=video_id,
                summary_type=summary_type,
                prompt=self._summary_prompt,
                temperature=self._summary_temperature,
                max_tokens=self._summary_max_tokens,
            )

        analysis_response = self._client.analyze_video(
            video_id=video_id,
            prompt=prompt_value,
            temperature=temperature_value,
            max_tokens=max_tokens_value,
            stream=False,
        )

        search_results: Optional[list[Dict[str, Any]]] = None
        if self._search_prompt:
            search_results = self._client.search_videos(
                index_id=index_id,
                query_text=self._search_prompt,
                search_options=self._search_options or None,
                group_by=self._search_group_by,
                threshold=self._search_threshold,
                operator=self._search_operator,
                page_limit=self._search_page_limit,
                sort_option=self._search_sort,
            )

        hls_url: Optional[Any] = None
        if self._include_hls_url:
            try:
                hls_url = self._client.get_video_hls_url(
                    index_id=index_id, video_id=video_id
                )
            except TwelveLabsError as exc:
                if not self._ignore_hls_errors:
                    raise
                hls_url = {"error": str(exc)}

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
            "pollInterval": self._poll_interval,
            "videoId": video_id,
            "updatedAt": _utc_iso_now(),
            "index": {
                "name": self._index_name,
                "modelName": self._index_model_name,
                "modelOptions": list(self._index_model_options),
                "addons": self._index_addons or None,
                "response": index_response,
            },
            "task": {
                "id": task_id,
                "response": ingest_response,
                "status": ingest_status,
                "enableVideoStream": self._enable_video_stream,
                "userMetadata": self._user_metadata,
            },
            "video": {
                "id": video_id,
                "hlsUrl": hls_url,
            },
            "gist": {
                "types": list(self._gist_types),
                "response": gist_response,
            },
            "summary": {
                "types": list(self._summary_types),
                "prompt": self._summary_prompt,
                "temperature": self._summary_temperature,
                "maxTokens": self._summary_max_tokens,
                "responses": summary_payloads,
            },
            "analysis": {
                "modelName": self._model_name,
                "prompt": prompt_value,
                "temperature": temperature_value,
                "maxTokens": max_tokens_value,
                "stream": False,
                "response": analysis_response,
            },
        }
        if search_results is not None:
            record["search"] = {
                "prompt": self._search_prompt,
                "options": list(self._search_options),
                "groupBy": self._search_group_by,
                "threshold": self._search_threshold,
                "operator": self._search_operator,
                "pageLimit": self._search_page_limit,
                "sort": self._search_sort,
                "results": search_results,
            }

        with self._lock:
            self._records[key] = record
            self._save_cache()

        return AnalysisResult(record=_clone_json(record), cached=False)


__all__ += [
    "DEFAULT_BASE_URL",
    "DEFAULT_INDEX_NAME",
    "DEFAULT_INDEX_MODEL_NAME",
    "DEFAULT_INDEX_MODEL_OPTIONS",
    "DEFAULT_GIST_TYPES",
    "DEFAULT_SUMMARY_TYPES",
    "DEFAULT_SEARCH_OPTIONS",
    "TwelveLabsError",
]
