"""High-level helpers built on top of the official Twelve Labs SDK.

The upstream project exposes a ``TwelveLabs`` client class under ``src/twelvelabs``
and provides concrete end-to-end samples under ``examples``.  This module wraps
those primitives so the rest of the Jetson tooling can follow the same flow as
the official ``video`` and ``embedding`` examples while keeping backwards
compatibility with the previous script interface.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from typing import Any, BinaryIO, Dict, Iterable, Optional

from twelvelabs import TwelveLabs
try:  # pragma: no cover - import path differs between SDK versions
    from twelvelabs.core.api_error import ApiError
except ImportError:  # pragma: no cover - fallback for older releases
    from twelvelabs.errors import ApiError  # type: ignore[no-redef]
from twelvelabs.types.response_format import ResponseFormat
from twelvelabs.indexes import IndexesCreateRequestModelsItem

try:  # pragma: no cover - optional dependency is provided by the SDK
    from httpx import HTTPError
except ImportError:  # pragma: no cover - httpx shipped alongside SDK
    HTTPError = Exception  # type: ignore[assignment]

DEFAULT_BASE_URL = "https://api.twelvelabs.io"
DEFAULT_EMBEDDING_TASK_PATH = "/v1.3/embed/tasks"
DEFAULT_ANALYSIS_PATH = "/v1.3/analyze"
DEFAULT_INDEX_NAME = "test-webrtc"
DEFAULT_INDEX_MODEL_NAME = "marengo2.7"
DEFAULT_INDEX_MODEL_OPTIONS: tuple[str, ...] = ("visual", "audio")
DEFAULT_EMBEDDING_OPTIONS: tuple[str, ...] = ("visual-text",)


class TwelveLabsError(RuntimeError):
    """Raised when an SDK interaction fails."""


def _serialise_sdk_payload(payload: Any) -> Any:
    """Convert SDK models into plain Python containers."""

    if hasattr(payload, "model_dump_json"):
        return json.loads(payload.model_dump_json())
    if hasattr(payload, "model_dump"):
        return payload.model_dump(mode="json")
    if isinstance(payload, dict):
        return {key: _serialise_sdk_payload(value) for key, value in payload.items()}
    if isinstance(payload, list):
        return [_serialise_sdk_payload(item) for item in payload]
    return payload


def _prepare_upload(handle: BinaryIO, file_name: Optional[str]) -> tuple[str, BinaryIO, str]:
    """Return a ``core.File`` compatible tuple for SDK uploads."""

    if hasattr(handle, "seek"):
        handle.seek(0)
    resolved_name = file_name or getattr(handle, "name", "") or "video.mp4"
    return os.path.basename(resolved_name), handle, "application/octet-stream"


def _parse_scopes(scopes: Optional[Iterable[str]]) -> Optional[list[str]]:
    if scopes is None:
        return None
    parsed = [scope.strip() for scope in scopes if scope and scope.strip()]
    return parsed or None


def _build_response_format(spec: Optional[Dict[str, Any]]) -> Optional[ResponseFormat]:
    if spec is None:
        return None
    try:
        return ResponseFormat.model_validate(spec)
    except Exception as exc:
        raise TwelveLabsError(f"Invalid response_format payload: {exc}") from exc


def extract_index_id(payload: Any) -> Optional[str]:
    """Locate the Twelve Labs ``index_id`` within ``payload``."""

    data = _serialise_sdk_payload(payload)
    if isinstance(data, dict):
        candidate = (
            data.get("index_id")
            or data.get("id")
            or data.get("_id")
        )
        if isinstance(candidate, str) and candidate:
            return candidate
    return None


def extract_video_id(payload: Any) -> Optional[str]:
    """Traverse ``payload`` to locate a Twelve Labs ``video_id`` field."""

    data = _serialise_sdk_payload(payload)
    if not isinstance(data, dict):
        return None

    stack: list[Any] = [data]
    while stack:
        current = stack.pop()
        if not isinstance(current, dict):
            continue
        candidate = current.get("video_id") or current.get("videoId")
        if isinstance(candidate, str) and candidate:
            return candidate
        for value in current.values():
            if isinstance(value, dict):
                stack.append(value)
            elif isinstance(value, list):
                stack.extend(item for item in value if isinstance(item, (dict, list)))
    return None


@dataclass
class TwelveLabsClient:
    api_key: str
    base_url: Optional[str] = DEFAULT_BASE_URL
    timeout: Optional[int] = 60
    embedding_path: Optional[str] = DEFAULT_EMBEDDING_TASK_PATH
    analysis_path: Optional[str] = DEFAULT_ANALYSIS_PATH

    def __post_init__(self) -> None:
        client_kwargs: Dict[str, Any] = {"api_key": self.api_key}
        if self.base_url:
            client_kwargs["base_url"] = self.base_url
        if self.timeout is not None:
            client_kwargs["timeout"] = self.timeout
        try:
            self._sdk = TwelveLabs(**client_kwargs)
        except (ApiError, HTTPError) as exc:  # pragma: no cover - configuration errors propagate to caller
            raise TwelveLabsError(str(exc)) from exc

    def ensure_index(
        self,
        *,
        index_name: str = DEFAULT_INDEX_NAME,
        model_name: str = DEFAULT_INDEX_MODEL_NAME,
        model_options: Optional[Iterable[str]] = None,
        addons: Optional[Iterable[str]] = None,
    ) -> Dict[str, Any]:
        if not index_name:
            raise ValueError("index_name is required")
        parsed_options = _parse_scopes(model_options)
        if not parsed_options:
            parsed_options = list(DEFAULT_INDEX_MODEL_OPTIONS)
        parsed_addons = _parse_scopes(addons)

        try:
            existing = self._sdk.indexes.list(index_name=index_name)
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(str(exc)) from exc

        for index in existing:
            payload = _serialise_sdk_payload(index)
            if isinstance(payload, dict):
                payload_name = (
                    payload.get("index_name")
                    or payload.get("indexName")
                    or payload.get("name")
                )
                if payload_name == index_name:
                    index_id = extract_index_id(payload)
                    if index_id:
                        payload.setdefault("index_id", index_id)
                    payload.setdefault("index_name", payload_name)
                    return payload

        model = IndexesCreateRequestModelsItem(
            model_name=model_name,
            model_options=parsed_options,
        )
        try:
            created = self._sdk.indexes.create(
                index_name=index_name,
                models=[model],
                addons=parsed_addons,
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(str(exc)) from exc

        payload = _serialise_sdk_payload(created)
        if isinstance(payload, dict):
            index_id = extract_index_id(payload)
            if index_id:
                payload.setdefault("index_id", index_id)
            payload.setdefault("index_name", index_name)
        return payload

    def create_video_indexing_task(
        self,
        *,
        index_id: str,
        video_file: Optional[BinaryIO] = None,
        video_file_name: Optional[str] = None,
        video_url: Optional[str] = None,
        enable_video_stream: Optional[bool] = None,
    ) -> Dict[str, Any]:
        if not index_id:
            raise ValueError("index_id is required for indexing tasks")
        if not video_file and not video_url:
            raise ValueError("Provide either video_file or video_url for indexing")

        request: Dict[str, Any] = {"index_id": index_id}
        if video_url:
            request["video_url"] = video_url
        if video_file:
            request["video_file"] = _prepare_upload(video_file, video_file_name)
        if enable_video_stream is not None:
            request["enable_video_stream"] = bool(enable_video_stream)

        try:
            response = self._sdk.tasks.create(**request)
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(str(exc)) from exc

        payload = _serialise_sdk_payload(response)
        if isinstance(payload, dict):
            task_id = (
                getattr(response, "id", None)
                or payload.get("id")
                or payload.get("task_id")
                or payload.get("_id")
            )
            if isinstance(task_id, str) and task_id:
                payload.setdefault("task_id", task_id)
            payload.setdefault("index_id", index_id)
        return payload

    def wait_for_indexing_task(
        self,
        *,
        task_id: str,
        poll_interval: float = 5.0,
    ) -> Dict[str, Any]:
        if not task_id:
            raise ValueError("task_id is required to poll indexing task status")
        if poll_interval <= 0:
            raise ValueError("poll_interval must be positive")

        try:
            status = self._sdk.tasks.wait_for_done(
                task_id=task_id,
                sleep_interval=float(poll_interval),
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(str(exc)) from exc

        payload = _serialise_sdk_payload(status)
        if isinstance(payload, dict):
            payload.setdefault("task_id", task_id)
        return payload

    def retrieve_index_video(
        self,
        *,
        index_id: str,
        video_id: str,
        embedding_options: Optional[Iterable[str]] = None,
        transcription: Optional[bool] = None,
    ) -> Dict[str, Any]:
        if not index_id:
            raise ValueError("index_id is required to retrieve videos")
        if not video_id:
            raise ValueError("video_id is required to retrieve videos")

        parsed_options = _parse_scopes(embedding_options)
        if parsed_options is None:
            parsed_options = list(DEFAULT_EMBEDDING_OPTIONS)

        try:
            response = self._sdk.indexes.videos.retrieve(
                index_id=index_id,
                video_id=video_id,
                embedding_option=parsed_options,
                transcription=transcription,
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(str(exc)) from exc

        payload = _serialise_sdk_payload(response)
        if isinstance(payload, dict):
            payload.setdefault("video_id", video_id)
            payload.setdefault("index_id", index_id)
            payload.setdefault("embedding_options", parsed_options)
        return payload

    def create_video_embedding_task(
        self,
        *,
        model_name: str,
        video_file: Optional[BinaryIO] = None,
        video_file_name: Optional[str] = None,
        video_url: Optional[str] = None,
        video_start_offset_sec: Optional[float] = None,
        video_end_offset_sec: Optional[float] = None,
        video_clip_length: Optional[float] = None,
        video_embedding_scope: Optional[Iterable[str]] = None,
    ) -> Dict[str, Any]:
        if not video_file and not video_url:
            raise ValueError("Provide either video_file or video_url for embeddings")

        request: Dict[str, Any] = {"model_name": model_name}
        if video_url:
            request["video_url"] = video_url
        if video_file:
            request["video_file"] = _prepare_upload(video_file, video_file_name)
        if video_start_offset_sec is not None:
            request["video_start_offset_sec"] = video_start_offset_sec
        if video_end_offset_sec is not None:
            request["video_end_offset_sec"] = video_end_offset_sec
        if video_clip_length is not None:
            request["video_clip_length"] = video_clip_length
        scopes = _parse_scopes(video_embedding_scope)
        if scopes is not None:
            request["video_embedding_scope"] = scopes

        try:
            response = self._sdk.embed.tasks.create(**request)
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(str(exc)) from exc

        payload = _serialise_sdk_payload(response)
        if isinstance(payload, dict):
            task_id = (
                getattr(response, "id", None)
                or payload.get("id")
                or payload.get("task_id")
                or payload.get("_id")
            )
            if isinstance(task_id, str) and task_id:
                payload.setdefault("task_id", task_id)
                if self.embedding_path:
                    payload.setdefault(
                        "status_url",
                        f"{self.embedding_path.rstrip('/')}/{task_id}/status",
                    )
        return payload

    def wait_for_embedding_task(
        self,
        *,
        task_id: str,
        poll_interval: float = 5.0,
    ) -> Dict[str, Any]:
        if not task_id:
            raise ValueError("task_id is required to poll task status")
        if poll_interval <= 0:
            raise ValueError("poll_interval must be positive")

        try:
            status = self._sdk.embed.tasks.wait_for_done(
                task_id=task_id,
                sleep_interval=float(poll_interval),
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(str(exc)) from exc

        payload = _serialise_sdk_payload(status)
        if isinstance(payload, dict):
            payload.setdefault("task_id", task_id)
        return payload

    def analyze_video(
        self,
        *,
        video_id: str,
        prompt: str,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
        stream: bool = False,
        response_format: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        if not video_id:
            raise ValueError("video_id is required for analysis requests")

        response_format_obj = _build_response_format(response_format)
        try:
            if stream:
                chunks = [
                    _serialise_sdk_payload(chunk)
                    for chunk in self._sdk.analyze_stream(
                        video_id=video_id,
                        prompt=prompt,
                        temperature=temperature,
                        max_tokens=max_tokens,
                        response_format=response_format_obj,
                    )
                ]
                return {"stream": chunks}
            response = self._sdk.analyze(
                video_id=video_id,
                prompt=prompt,
                temperature=temperature,
                max_tokens=max_tokens,
                response_format=response_format_obj,
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(str(exc)) from exc

        return _serialise_sdk_payload(response)


def _load_response_format_arg(raw: Optional[str]) -> Optional[Dict[str, Any]]:
    if raw is None:
        return None
    candidate = raw.strip()
    if not candidate:
        return None
    if os.path.exists(candidate):
        with open(candidate, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    else:
        data = json.loads(candidate)
    if not isinstance(data, dict):
        raise ValueError("response_format must be a JSON object")
    return data


def run_pipeline(args: argparse.Namespace) -> None:
    client = TwelveLabsClient(
        api_key=args.api_key,
        base_url=args.base_url,
        timeout=args.timeout,
        embedding_path=args.embedding_path,
        analysis_path=args.analysis_path,
    )

    if not args.video_file and not args.video_url:
        raise SystemExit("Provide either --video-file or --video-url to create embeddings")

    index_model_name = args.index_model_name or DEFAULT_INDEX_MODEL_NAME
    if args.index_model_name is None:
        model_hint = (args.model_name or "").lower()
        if model_hint.startswith("pegasus"):
            index_model_name = "pegasus1.2"
    index_model_options = _parse_scopes(args.index_model_options)
    index_addons = _parse_scopes(args.index_addons)

    index_response = client.ensure_index(
        index_name=args.index_name,
        model_name=index_model_name,
        model_options=index_model_options,
        addons=index_addons,
    )
    index_id = extract_index_id(index_response)
    if not isinstance(index_id, str) or not index_id:
        raise SystemExit("Failed to determine index identifier from Twelve Labs response")

    indexing_kwargs: Dict[str, Any] = {
        "index_id": index_id,
        "video_url": args.video_url,
        "enable_video_stream": args.enable_video_stream,
    }

    video_handle: Optional[BinaryIO] = None
    if args.video_file and not args.video_url:
        video_handle = open(args.video_file, "rb")
        indexing_kwargs.update(
            {
                "video_file": video_handle,
                "video_file_name": args.video_file,
            }
        )

    try:
        indexing_response = client.create_video_indexing_task(**indexing_kwargs)
    finally:
        if video_handle is not None:
            video_handle.close()

    task_id = (
        indexing_response.get("task_id")
        or indexing_response.get("id")
        or indexing_response.get("_id")
    )
    if not isinstance(task_id, str) or not task_id:
        raise SystemExit("Indexing response did not return a task identifier")

    indexing_status = client.wait_for_indexing_task(
        task_id=task_id,
        poll_interval=args.poll_interval,
    )

    video_id = args.analysis_video_id or extract_video_id(indexing_status)
    if not video_id:
        raise SystemExit(
            "Could not determine Twelve Labs video_id from embedding status. "
            "Pass --analysis-video-id to override."
        )

    embedding_options = _parse_scopes(args.embedding_options)

    embedding_response = client.retrieve_index_video(
        index_id=index_id,
        video_id=video_id,
        embedding_options=embedding_options,
        transcription=args.retrieve_transcription,
    )

    try:
        response_format = _load_response_format_arg(args.response_format)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        raise SystemExit(f"Failed to parse --response-format: {exc}") from exc

    analysis_response = client.analyze_video(
        video_id=video_id,
        prompt=args.prompt,
        temperature=args.temperature,
        max_tokens=args.max_tokens,
        stream=args.analysis_stream,
        response_format=response_format,
    )

    print(
        json.dumps(
            {
                "index": index_response,
                "ingest": {
                    "task": indexing_response,
                    "status": indexing_status,
                },
                "embedding": embedding_response,
                "analysis": analysis_response,
            },
            indent=2,
            ensure_ascii=False,
        )
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Create Twelve Labs embeddings for a video and run an analysis prompt",
    )
    parser.add_argument("--api-key", required=True, help="Twelve Labs API key")
    parser.add_argument("--model-name", required=True, help="Embedding model (e.g. Marengo-retrieval-2.7)")
    parser.add_argument("--video-file", help="Path to a local video file")
    parser.add_argument("--video-url", help="Public URL pointing to a video file")
    parser.add_argument("--video-start-offset", type=float, dest="video_start_offset", help="Start offset in seconds")
    parser.add_argument("--video-end-offset", type=float, dest="video_end_offset", help="End offset in seconds")
    parser.add_argument("--video-clip-length", type=float, dest="video_clip_length", help="Clip length in seconds")
    parser.add_argument(
        "--embedding-option",
        "--video-embedding-scope",
        action="append",
        dest="embedding_options",
        default=None,
        help="Repeat to request specific embedding options (visual-text, audio)",
    )
    parser.add_argument("--poll-interval", type=float, default=5.0, help="Seconds between task status polls")
    parser.add_argument("--prompt", required=True, help="Analysis prompt to send to Twelve Labs")
    parser.add_argument("--temperature", type=float, help="Sampling temperature for analysis")
    parser.add_argument("--max-tokens", type=int, dest="max_tokens", help="Maximum tokens to generate")
    parser.add_argument("--analysis-video-id", help="Override the video_id returned by the embedding task")
    parser.add_argument("--analysis-stream", action="store_true", help="Use the streaming analysis endpoint")
    parser.add_argument(
        "--response-format",
        help="Path or JSON string describing the structured response format",
    )
    parser.add_argument("--index-name", default=DEFAULT_INDEX_NAME, help="Name of the Twelve Labs managed index")
    parser.add_argument(
        "--index-model-name",
        default=None,
        help="Video understanding model to enable for the managed index (defaults to Marengo if omitted)",
    )
    parser.add_argument(
        "--index-model-option",
        "--index-model-options",
        action="append",
        dest="index_model_options",
        default=None,
        help="Repeat to enable model options (visual, audio) for the managed index",
    )
    parser.add_argument(
        "--index-addon",
        action="append",
        dest="index_addons",
        default=None,
        help="Repeat to enable optional index add-ons (e.g. thumbnail)",
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="Override the Twelve Labs API base URL")
    parser.add_argument("--embedding-path", default=DEFAULT_EMBEDDING_TASK_PATH, help="Stored for compatibility")
    parser.add_argument("--analysis-path", default=DEFAULT_ANALYSIS_PATH, help="Stored for compatibility")
    parser.add_argument(
        "--enable-video-stream",
        action="store_true",
        help="Store uploaded videos for streaming playback",
    )
    parser.add_argument(
        "--retrieve-transcription",
        action="store_true",
        help="Request transcriptions when retrieving indexed video metadata",
    )
    parser.add_argument("--timeout", type=float, default=60, help="HTTP timeout in seconds")
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        run_pipeline(args)
    except TwelveLabsError as exc:
        print(f"Twelve Labs API error: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":  # pragma: no cover - manual invocation entry point
    raise SystemExit(main())


__all__ = [
    "DEFAULT_ANALYSIS_PATH",
    "DEFAULT_BASE_URL",
    "DEFAULT_EMBEDDING_TASK_PATH",
    "DEFAULT_EMBEDDING_OPTIONS",
    "DEFAULT_INDEX_MODEL_NAME",
    "DEFAULT_INDEX_MODEL_OPTIONS",
    "DEFAULT_INDEX_NAME",
    "TwelveLabsClient",
    "TwelveLabsError",
    "build_parser",
    "extract_index_id",
    "extract_video_id",
    "main",
    "run_pipeline",
]
