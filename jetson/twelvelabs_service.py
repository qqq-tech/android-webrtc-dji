"""Twelve Labs analysis orchestration with persistent caching."""

from __future__ import annotations

import json
import logging
import re
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, Optional, Sequence, Tuple

from .twelvelabs_client import (
    AnalysisText,
    DEFAULT_GIST_TYPES,
    DEFAULT_INDEX_NAME,
    DEFAULT_PROMPT,
    DEFAULT_EMBEDDING_OPTIONS,
    TwelveLabsClient,
)

try:  # pragma: no cover - optional dependency shape defined by SDK
    from twelvelabs.errors.not_found_error import NotFoundError
except Exception:  # pragma: no cover - safety for environments lacking SDK
    NotFoundError = None  # type: ignore[assignment]

try:  # pragma: no cover - optional dependency for status callbacks
    from twelvelabs.tasks import TasksRetrieveResponse
except Exception:  # pragma: no cover - safety for environments lacking SDK
    TasksRetrieveResponse = Any  # type: ignore[assignment]


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


_MISSING_EMBEDDING_RE = re.compile(
    r"embedding_option ['\"](?P<option>[^'\"]+)['\"]",
    re.IGNORECASE,
)


def _extract_missing_embedding_option(error: Exception) -> Optional[str]:
    """Return the embedding option that caused a retrieval failure."""

    message: str = ""
    body = getattr(error, "body", None)
    if isinstance(body, dict):  # SDK provides structured error bodies
        code = str(body.get("code") or "").strip()
        message = str(body.get("message") or "")
        if code and code != "embed_no_embeddings_found":
            return None
    else:
        message = str(error)

    match = _MISSING_EMBEDDING_RE.search(message)
    if match:
        candidate = match.group("option").strip()
        if candidate:
            return candidate
    return None


def _retrieve_embeddings_with_fallback(
    *,
    client: TwelveLabsClient,
    index_id: str,
    video_id: str,
    options: Sequence[str],
    include_transcription: bool,
) -> Tuple[Dict[str, Any], Sequence[str], Sequence[str]]:
    """Fetch embeddings while tolerating missing modalities.

    Returns a tuple containing the metadata payload, the successfully
    retrieved embedding options, and the options that failed.
    """

    remaining: list[str] = []
    for opt in options:
        cleaned = str(opt).strip()
        if cleaned:
            remaining.append(cleaned)
    failed: list[str] = []

    while True:
        include_embeddings: Optional[Sequence[str]] = remaining if remaining else None
        try:
            metadata = client.retrieve_video_metadata(
                index_id=index_id,
                video_id=video_id,
                include_embeddings=include_embeddings,
                include_transcription=include_transcription,
            )
            return metadata, tuple(remaining), tuple(failed)
        except Exception as exc:  # pragma: no cover - diagnostics only
            if (
                NotFoundError is not None
                and isinstance(exc, NotFoundError)
                and remaining
            ):
                missing = _extract_missing_embedding_option(exc)
                if missing and missing in remaining:
                    LOGGER.warning(
                        "Embedding option '%s' unavailable for video %s; retrying without it",
                        missing,
                        video_id,
                    )
                    remaining = [opt for opt in remaining if opt != missing]
                    failed.append(missing)
                    continue
            raise


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
    def _set_embedding_status(
        self,
        key: str,
        *,
        state: str,
        message: Optional[str] = None,
        options: Optional[Iterable[str]] = None,
        include_transcription: Optional[bool] = None,
        timestamp: Optional[str] = None,
    ) -> None:
        """Persist the latest embedding workflow state for a cached record."""

        state_value = str(state or "").strip().lower()
        if not state_value:
            return

        cleaned_options: list[str] = []
        if options is not None:
            for option in options:
                candidate = str(option).strip()
                if candidate and candidate not in cleaned_options:
                    cleaned_options.append(candidate)

        status_payload: Dict[str, Any] = {
            "state": state_value,
            "updatedAt": timestamp or _utc_now(),
        }
        if message:
            status_payload["message"] = message
        if cleaned_options:
            status_payload["options"] = cleaned_options
        if include_transcription is not None:
            status_payload["includeTranscription"] = bool(include_transcription)

        with self._lock:
            record = self._records.get(key)
            if not record:
                return
            record["embeddingStatus"] = status_payload
            self._save_cache()

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

        def _on_task_update(task: TasksRetrieveResponse) -> None:
            status_value = getattr(task, "status", None) or getattr(task, "task_status", None)
            if status_value:
                LOGGER.info("  Status=%s", status_value)

        status_payload = self._client.wait_for_task(
            task_id=task_id,
            poll_interval=self._poll_interval,
            callback=_on_task_update,
        )
        status_value = (
            status_payload.get("status")
            or status_payload.get("task_status")
            or status_payload.get("state")
        )
        if status_value != "ready":
            raise AnalysisServiceError(f"Indexing failed with status {status_value}")
        video_id = (
            status_payload.get("video_id")
            or status_payload.get("videoId")
            or task_payload.get("video_id")
            or task_payload.get("videoId")
        )
        if not isinstance(video_id, str) or not video_id:
            raise AnalysisServiceError("Unable to determine the Twelve Labs video identifier")

        LOGGER.info("Upload complete. The unique identifier of your video is %s.", video_id)

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
        # if self._index_id:
        #     return self._index_id
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
            stored = self._records.get(key)
            if stored is None:
                raise AnalysisServiceError(
                    "No stored Twelve Labs upload for this recording. Run embeddings first."
                )

            source_block = stored.get("source") if isinstance(stored, dict) else None
            cached_signature = (
                source_block.get("signature")
                if isinstance(source_block, dict)
                else None
            )
            if cached_signature and cached_signature != signature:
                raise AnalysisServiceError(
                    "Recording has changed since embeddings were generated. Run embeddings again."
                )

            analysis_block = stored.get("analysis") if isinstance(stored, dict) else None
            if isinstance(analysis_block, dict):
                text_value = analysis_block.get("text")
                if isinstance(text_value, str) and text_value.strip():
                    return AnalysisResult(record=_clone(stored), cached=True)

            existing = _clone(stored)

        video_id = existing.get("videoId") if isinstance(existing, dict) else None
        if not isinstance(video_id, str) or not video_id.strip():
            raise AnalysisServiceError(
                "Stored Twelve Labs upload is missing the video identifier. Run embeddings again."
            )

        index_info = existing.get("index") if isinstance(existing, dict) else None
        index_id = (
            index_info.get("id")
            if isinstance(index_info, dict)
            else None
        )
        if not isinstance(index_id, str) or not index_id.strip():
            index_id = self._ensure_index()

        prompt_candidate = prompt if isinstance(prompt, str) else None
        prompt_value = prompt_candidate.strip() if prompt_candidate else ""
        if not prompt_value:
            prompt_value = self._default_prompt

        gist_payload = self._client.gist(
            video_id=video_id,
            types=self._gist_types,
        )

        if isinstance(gist_payload, dict):
            title = gist_payload.get("title")
            topics = gist_payload.get("topics") or gist_payload.get("topic")
            hashtags = gist_payload.get("hashtags") or gist_payload.get("hashtag")
            LOGGER.info("Title=%s\nTopics=%s\nHashtags=%s", title, topics, hashtags)

        stream = self._client.analyze_stream(
            video_id=video_id,
            prompt=prompt_value,
            temperature=temperature,
            max_tokens=max_tokens,
        )

        chunk_texts: list[str] = []
        for event in stream:
            event_type = getattr(event, "event_type", None) or getattr(event, "type", None)
            if event_type != "text_generation":
                continue
            raw_text = getattr(event, "text", None) or getattr(event, "data", None)
            if not isinstance(raw_text, str):
                continue
            cleaned = self._client.clean_generated_text(raw_text)
            if not cleaned:
                continue
            LOGGER.info("%s", cleaned)
            chunk_texts.append(cleaned.strip())

        analysis_chunks = [chunk for chunk in chunk_texts if chunk]
        combined_text = " ".join(analysis_chunks)
        analysis_text = AnalysisText(text=combined_text, chunks=analysis_chunks)

        timestamp = _utc_now()
        created_at = existing.get("createdAt") if isinstance(existing, dict) else None
        previous_embeddings = existing.get("embeddings") if isinstance(existing, dict) else None

        with self._lock:
            record = self._records.get(key)
            if record is None:
                raise AnalysisServiceError(
                    "Recording cache was removed while running analysis"
                )
            record["streamId"] = stream_id
            record["fileName"] = file_name
            record["videoId"] = video_id
            record["prompt"] = prompt_value
            record["temperature"] = temperature
            record["maxTokens"] = max_tokens
            record["updatedAt"] = timestamp
            if not record.get("createdAt") and created_at:
                record["createdAt"] = created_at

            source_block = record.get("source")
            if isinstance(source_block, dict):
                source_block["path"] = str(recording_path)
                source_block["signature"] = signature
            else:
                record["source"] = {
                    "path": str(recording_path),
                    "signature": signature,
                }

            record["index"] = {
                "id": index_id,
                "name": self._index_name,
            }

            record["gist"] = {
                "types": list(self._gist_types),
                "response": gist_payload,
            }

            record["analysis"] = {
                "prompt": prompt_value,
                "text": analysis_text.text,
                "chunks": list(analysis_text.chunks),
            }

            if isinstance(previous_embeddings, dict):
                record["embeddings"] = previous_embeddings

            self._save_cache()
            updated = _clone(record)

        return AnalysisResult(record=updated, cached=False)

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

        if isinstance(cached_embeddings, dict) and isinstance(
            cached_embeddings.get("options"), list
        ):
            cached_set = {
                opt.strip()
                for opt in cached_embeddings["options"]
                if isinstance(opt, str) and opt.strip()
            }
            requested_set = {
                opt.strip()
                for opt in options
                if isinstance(opt, str) and opt.strip()
            }
            missing_candidates = cached_embeddings.get("missingOptions")
            if isinstance(missing_candidates, list):
                missing_set = {
                    opt.strip()
                    for opt in missing_candidates
                    if isinstance(opt, str) and opt.strip()
                }
            else:
                missing_set = set()
            if cached_set == requested_set or cached_set | missing_set == requested_set:
                self._set_embedding_status(
                    key,
                    state="ready",
                    message="Stored Twelve Labs embeddings are available.",
                    options=cached_embeddings.get("options"),
                    include_transcription=bool(include_transcription),
                )
                return EmbeddingResult(record=_clone(existing), cached=True)

        pending_message = "Retrieving Twelve Labs embeddings…"
        self._set_embedding_status(
            key,
            state="pending",
            message=pending_message,
            options=options,
            include_transcription=bool(include_transcription),
        )
        current_options: list[str] = list(options)
        try:
            metadata, retrieved_options, failed_options = _retrieve_embeddings_with_fallback(
                client=self._client,
                index_id=index_id,
                video_id=video_id,
                options=options,
                include_transcription=bool(include_transcription),
            )
        except Exception as exc:  # pragma: no cover - diagnostics only
            LOGGER.exception("Failed to retrieve Twelve Labs video embeddings")
            self._set_embedding_status(
                key,
                state="error",
                message=str(exc) or "Failed to retrieve Twelve Labs embeddings.",
                options=current_options,
                include_transcription=bool(include_transcription),
            )
            raise AnalysisServiceError(str(exc)) from exc

        current_options = [str(item).strip() for item in retrieved_options if str(item).strip()]
        timestamp = _utc_now()
        embedding_payload = metadata.get("embedding") if isinstance(metadata, dict) else None
        transcription_payload = metadata.get("transcription") if isinstance(metadata, dict) else None
        hls_url = _extract_hls_url(metadata if isinstance(metadata, dict) else None)

        error: Optional[Exception] = None
        updated: Optional[Dict[str, Any]] = None
        with self._lock:
            try:
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
                    "options": list(retrieved_options),
                    "response": embedding_payload,
                    "retrievedAt": timestamp,
                }
                if failed_options:
                    record["embeddings"]["missingOptions"] = list(failed_options)
                if transcription_payload is not None:
                    record["embeddings"]["transcription"] = transcription_payload
                status_block: Dict[str, Any] = {
                    "state": "ready",
                    "updatedAt": timestamp,
                    "message": "Twelve Labs embeddings retrieved.",
                    "options": list(retrieved_options),
                    "includeTranscription": bool(include_transcription),
                }
                if failed_options:
                    status_block["missingOptions"] = list(failed_options)
                record["embeddingStatus"] = status_block
                self._save_cache()
                updated = _clone(record)
            except Exception as exc:
                error = exc

        if error is not None:
            self._set_embedding_status(
                key,
                state="error",
                message=str(error) or "Failed to store Twelve Labs embeddings.",
                options=current_options or options,
                include_transcription=bool(include_transcription),
                timestamp=timestamp,
            )
            if isinstance(error, AnalysisServiceError):
                raise error
            raise AnalysisServiceError(str(error)) from error

        assert updated is not None
        return EmbeddingResult(record=updated, cached=False)


__all__ = [
    "AnalysisResult",
    "AnalysisServiceError",
    "EmbeddingResult",
    "RecordingNotFoundError",
    "TwelveLabsAnalysisService",
]
