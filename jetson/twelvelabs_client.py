"""Lightweight Twelve Labs client helpers tailored for the Jetson tooling.

This module intentionally mirrors the public Twelve Labs notebook workflow that
covers index provisioning, video ingestion, gist retrieval, and streaming
analysis.  The helpers below keep the API surface small while providing a few
quality-of-life improvements for the WebRTC project:

* Automatically ensure the managed ``test-webrtc`` index exists with the
  ``marengo2.7`` and ``pegasus1.2`` models enabled for both the visual and
  audio modalities.
* Cache resolved index identifiers so subsequent uploads reuse the same index
  without hitting the API again.
* Collect streamed analysis events into paragraphs so that callers can persist
  or render the generated text easily.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

import httpx
from twelvelabs import TwelveLabs
from twelvelabs.indexes import IndexesCreateRequestModelsItem
from twelvelabs.tasks import TasksRetrieveResponse

DEFAULT_INDEX_NAME = "test-webrtc"
DEFAULT_PROMPT = "이 영상을 분석해줘."
DEFAULT_MODALITIES: Sequence[str] = ("visual", "audio")
DEFAULT_GIST_TYPES: Sequence[str] = ("title", "topic", "hashtag")
DEFAULT_EMBEDDING_OPTIONS: Sequence[str] = ("visual-text", "audio")
DEFAULT_MODELS: Sequence[Tuple[str, Sequence[str]]] = (
    ("marengo2.7", DEFAULT_MODALITIES),
    ("pegasus1.2", DEFAULT_MODALITIES),
)
DEFAULT_ADDONS: Sequence[str] = ("thumbnail",)


def _serialise(payload: Any) -> Any:
    """Convert SDK models into JSON-serialisable dictionaries."""

    if payload is None:
        return None
    if hasattr(payload, "model_dump"):
        return payload.model_dump(mode="json")  # type: ignore[no-untyped-call]
    if hasattr(payload, "dict"):
        return payload.dict()
    if isinstance(payload, dict):
        return {key: _serialise(value) for key, value in payload.items()}
    if isinstance(payload, (list, tuple)):
        return [_serialise(item) for item in payload]
    return payload


def _normalise_text_chunks(chunks: Iterable[str]) -> List[str]:
    normalised: List[str] = []
    for chunk in chunks:
        if not isinstance(chunk, str):
            continue
        candidate = chunk.strip()
        if candidate:
            normalised.append(candidate)
    return normalised


@dataclass(slots=True)
class AnalysisText:
    """Container that stores the streamed analysis output."""

    text: str
    chunks: List[str] = field(default_factory=list)


class TwelveLabsClient:
    """Thin wrapper around :class:`twelvelabs.TwelveLabs`."""

    def __init__(
        self,
        *,
        api_key: str,
        base_url: Optional[str] = None,
        timeout: Optional[float] = None,
        verify_ssl: bool = False,
    ) -> None:
        if not api_key:
            raise ValueError("A Twelve Labs API key is required")
        kwargs: Dict[str, Any] = {"api_key": api_key}
        if base_url:
            kwargs["base_url"] = base_url
        if verify_ssl:
            if timeout is not None:
                kwargs["timeout"] = timeout
        else:
            client_kwargs: Dict[str, Any] = {"verify": False}
            if timeout is not None:
                client_kwargs["timeout"] = timeout
            kwargs["httpx_client"] = httpx.Client(**client_kwargs)
        self._sdk = TwelveLabs(**kwargs)
        self._index_cache: Dict[str, Dict[str, Any]] = {}

    # ---------------------------------------------------------------------
    # Index helpers
    # ---------------------------------------------------------------------
    def ensure_index(
        self,
        index_name: str = DEFAULT_INDEX_NAME,
        *,
        models: Sequence[Tuple[str, Iterable[str]]] = DEFAULT_MODELS,
        addons: Sequence[str] = DEFAULT_ADDONS,
    ) -> Dict[str, Any]:
        """Return metadata for ``index_name``, creating it when necessary."""

        if not index_name:
            raise ValueError("index_name must be provided")

        cached = self._index_cache.get(index_name)
        if cached:
            return cached

        # List existing indexes and reuse one that matches ``index_name``.
        for entry in self._sdk.indexes.list():
            payload = _serialise(entry)
            name = payload.get("index_name") or payload.get("name")
            if name == index_name:
                self._index_cache[index_name] = payload
                return payload

        model_payloads = [
            IndexesCreateRequestModelsItem(
                model_name=model_name,
                model_options=list(modalities),
            )
            for model_name, modalities in models
        ]
        created = self._sdk.indexes.create(
            index_name=index_name,
            models=model_payloads,
            addons=list(addons) if addons else None,
        )
        payload = _serialise(created)
        index_id = payload.get("id") or payload.get("index_id")
        if not isinstance(index_id, str) or not index_id:
            raise RuntimeError("Twelve Labs did not return an index identifier")
        self._index_cache[index_name] = payload
        return payload

    def get_index_id(self, index_name: str = DEFAULT_INDEX_NAME) -> Optional[str]:
        """Return the cached identifier for ``index_name`` if available."""

        payload = self._index_cache.get(index_name)
        if not payload:
            return None
        index_id = payload.get("id") or payload.get("index_id")
        return index_id if isinstance(index_id, str) and index_id else None

    # ---------------------------------------------------------------------
    # Upload helpers
    # ---------------------------------------------------------------------
    def create_indexing_task(
        self,
        *,
        index_id: str,
        video_file,
    ) -> Dict[str, Any]:
        if not index_id:
            raise ValueError("index_id must be provided")
        task = self._sdk.tasks.create(index_id=index_id, video_file=video_file)
        return _serialise(task)

    def wait_for_task(
        self,
        *,
        task_id: str,
        poll_interval: float = 5.0,
    ) -> Dict[str, Any]:
        if not task_id:
            raise ValueError("task_id must be provided")

        def _callback(task: TasksRetrieveResponse) -> None:
            # The SDK invokes this callback with live status updates.  The
            # caller does not need them, so we simply ignore the payload while
            # still complying with the API.
            _ = task

        status = self._sdk.tasks.wait_for_done(
            task_id=task_id,
            sleep_interval=poll_interval,
            callback=_callback,
        )
        return _serialise(status)

    # ---------------------------------------------------------------------
    # Retrieval helpers
    # ---------------------------------------------------------------------
    def retrieve_video_metadata(
        self,
        *,
        index_id: str,
        video_id: str,
        include_embeddings: Optional[Iterable[str]] = None,
        include_transcription: Optional[bool] = None,
    ) -> Dict[str, Any]:
        """Return metadata for ``video_id`` within ``index_id``."""

        if not index_id:
            raise ValueError("index_id must be provided")
        if not video_id:
            raise ValueError("video_id must be provided")

        embedding_option: Optional[List[str]] = None
        if include_embeddings:
            if isinstance(include_embeddings, (str, bytes)):
                candidate = str(include_embeddings).strip()
                embedding_option = [candidate] if candidate else None
            else:
                collected: List[str] = []
                for item in include_embeddings:
                    candidate = str(item).strip()
                    if candidate and candidate not in collected:
                        collected.append(candidate)
                if collected:
                    embedding_option = collected

        transcription_value: Optional[bool]
        if include_transcription is None:
            transcription_value = None
        else:
            transcription_value = bool(include_transcription)

        response = self._sdk.indexes.videos.retrieve(
            index_id=index_id,
            video_id=video_id,
            embedding_option=embedding_option,
            transcription=transcription_value,
        )
        return _serialise(response)

    def fetch_gist(
        self,
        *,
        video_id: str,
        gist_types: Sequence[str] = DEFAULT_GIST_TYPES,
    ) -> Dict[str, Any]:
        if not video_id:
            raise ValueError("video_id must be provided")
        response = self._sdk.gist(video_id=video_id, types=list(gist_types))
        return _serialise(response)

    def collect_analysis(
        self,
        *,
        video_id: str,
        prompt: str = DEFAULT_PROMPT,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
    ) -> AnalysisText:
        if not video_id:
            raise ValueError("video_id must be provided")
        prompt_value = prompt.strip() if isinstance(prompt, str) else ""
        if not prompt_value:
            prompt_value = DEFAULT_PROMPT

        stream = self._sdk.analyze_stream(
            video_id=video_id,
            prompt=prompt_value,
            temperature=temperature,
            max_tokens=max_tokens,
        )

        chunks: List[str] = []
        for event in stream:
            event_type = getattr(event, "event_type", None) or getattr(event, "type", None)
            if event_type != "text_generation":
                continue
            text = getattr(event, "text", None) or getattr(event, "data", None)
            if not isinstance(text, str):
                continue
            cleaned = text.strip()
            if cleaned:
                chunks.append(cleaned)

        normalised = _normalise_text_chunks(chunks)
        combined = "\n\n".join(normalised)
        return AnalysisText(text=combined, chunks=normalised)


__all__ = [
    "AnalysisText",
    "DEFAULT_EMBEDDING_OPTIONS",
    "DEFAULT_ADDONS",
    "DEFAULT_GIST_TYPES",
    "DEFAULT_INDEX_NAME",
    "DEFAULT_MODALITIES",
    "DEFAULT_MODELS",
    "DEFAULT_PROMPT",
    "TwelveLabsClient",
]
