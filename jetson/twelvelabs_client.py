"""Utilities for interacting with the Twelve Labs API via the official SDK.

This module mirrors the previous command-line flow but now delegates all
network operations to the ``twelvelabs`` Python package provided by
``twelvelabs-io/twelvelabs-python``. The SDK offers higher level helpers for
creating embedding tasks, polling their status and running open-ended video
analysis. The wrapper defined here keeps a similar API surface so the rest of
this project can continue to orchestrate embeddings and analyses without being
aware of the underlying SDK. Compared to the prior implementation that
manually issued HTTP requests, this version closely follows the vendor
examples so behaviour stays aligned with the public SDK contract.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from typing import Any, BinaryIO, Dict, Iterable, Optional
from urllib.parse import urlparse

from twelvelabs import TwelveLabs
from twelvelabs.core import ApiError
from twelvelabs.types.response_format import ResponseFormat

DEFAULT_BASE_URL = "https://api.twelvelabs.io"
DEFAULT_EMBEDDING_TASK_PATH = "/v1.3/embed/tasks"
DEFAULT_ANALYSIS_PATH = "/v1.3/analyze"


class TwelveLabsError(RuntimeError):
    """Raised when an SDK operation fails."""


def _model_to_dict(payload: Any) -> Any:
    """Recursively convert SDK models into plain Python structures."""

    if hasattr(payload, "model_dump"):
        return payload.model_dump(mode="json")
    if isinstance(payload, dict):
        return {key: _model_to_dict(value) for key, value in payload.items()}
    if isinstance(payload, (list, tuple)):
        return [_model_to_dict(item) for item in payload]
    return payload


def _normalise_task_id(identifier: str) -> str:
    """Extract the Twelve Labs task identifier from a status URL or raw id."""

    candidate = (identifier or "").strip()
    if not candidate:
        raise ValueError("status_url is required to poll task status")

    parsed = urlparse(candidate)
    path = parsed.path or candidate
    fragments = [fragment for fragment in path.split("/") if fragment]
    if fragments and fragments[-1] == "status":
        fragments = fragments[:-1]
    if fragments:
        return fragments[-1]
    return candidate


def _prepare_file_tuple(handle: BinaryIO, filename: Optional[str]) -> tuple[str, BinaryIO, str]:
    """Create an SDK compatible file tuple for uploads."""

    if hasattr(handle, "seek"):
        handle.seek(0)
    resolved_name = os.path.basename(filename or getattr(handle, "name", "video.mp4") or "video.mp4")
    return resolved_name, handle, "application/octet-stream"


def _coerce_scopes(raw: Optional[Iterable[str]]) -> Optional[list[str]]:
    if raw is None:
        return None
    scopes = [scope for scope in raw if scope]
    return scopes or None


def _load_response_format(raw: Optional[str]) -> Optional[Dict[str, Any]]:
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


def _as_response_format(payload: Optional[Dict[str, Any]]) -> Optional[ResponseFormat]:
    if payload is None:
        return None
    try:
        return ResponseFormat.model_validate(payload)
    except Exception as exc:  # pragma: no cover - invalid schemas should be surfaced to the caller
        raise TwelveLabsError(f"Invalid response_format payload: {exc}") from exc


def _extract_video_id(payload: Any) -> Optional[str]:
    """Locate the Twelve Labs ``video_id`` within nested dictionaries."""

    data = _model_to_dict(payload)
    if not isinstance(data, dict):
        return None

    stack = [data]
    while stack:
        current = stack.pop()
        if not isinstance(current, dict):
            continue
        for key, value in current.items():
            if key in {"video_id", "videoId"} and isinstance(value, str) and value:
                return value
            if isinstance(value, dict):
                stack.append(value)
            elif isinstance(value, list):
                stack.extend(item for item in value if isinstance(item, (dict, list)))
    return None


@dataclass
class TwelveLabsClient:
    """Thin wrapper around :class:`twelvelabs.TwelveLabs`."""

    api_key: str
    base_url: Optional[str] = DEFAULT_BASE_URL
    timeout: Optional[int] = 60
    embedding_path: str = DEFAULT_EMBEDDING_TASK_PATH
    analysis_path: str = DEFAULT_ANALYSIS_PATH

    def __post_init__(self) -> None:
        client_kwargs: Dict[str, Any] = {"api_key": self.api_key}
        if self.base_url:
            client_kwargs["base_url"] = self.base_url
        if self.timeout:
            client_kwargs["timeout"] = self.timeout
        try:
            self._client = TwelveLabs(**client_kwargs)
        except ApiError as exc:  # pragma: no cover - configuration errors are propagated
            raise TwelveLabsError(str(exc)) from exc

    def create_embedding(
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
        gzip_upload: bool = True,  # retained for backwards compatibility
    ) -> Dict[str, Any]:
        """Create an embedding task for a local file or a remote URL."""

        if not video_file and not video_url:
            raise ValueError("Either video_file or video_url must be supplied for embeddings")

        kwargs: Dict[str, Any] = {"model_name": model_name}
        if video_url:
            kwargs["video_url"] = video_url
        elif video_file:
            kwargs["video_file"] = _prepare_file_tuple(video_file, video_file_name)
        if video_start_offset_sec is not None:
            kwargs["video_start_offset_sec"] = video_start_offset_sec
        if video_end_offset_sec is not None:
            kwargs["video_end_offset_sec"] = video_end_offset_sec
        if video_clip_length is not None:
            kwargs["video_clip_length"] = video_clip_length
        scopes = _coerce_scopes(video_embedding_scope)
        if scopes is not None:
            kwargs["video_embedding_scope"] = scopes

        try:
            task = self._client.embed.tasks.create(**kwargs)
        except ApiError as exc:
            raise TwelveLabsError(str(exc)) from exc

        payload = _model_to_dict(task)
        if isinstance(payload, dict):
            task_id = payload.get("id")
            if isinstance(task_id, str):
                payload.setdefault("task_id", task_id)
                payload.setdefault("_id", task_id)
                status_path = f"{self.embedding_path.rstrip('/')}/{task_id}/status"
                payload.setdefault("status_url", status_path)
                payload.setdefault("href", status_path)
        return payload

    def wait_for_task(
        self,
        *,
        status_url: str,
        poll_interval: int = 10,
    ) -> Dict[str, Any]:
        """Poll a Twelve Labs task until completion using the SDK helper."""

        task_id = _normalise_task_id(status_url)
        if poll_interval <= 0:
            raise ValueError("poll_interval must be positive")
        try:
            status = self._client.embed.tasks.wait_for_done(
                task_id=task_id,
                sleep_interval=float(poll_interval),
            )
        except ApiError as exc:
            raise TwelveLabsError(str(exc)) from exc
        payload = _model_to_dict(status)
        if isinstance(payload, dict):
            payload.setdefault("task_id", task_id)
            payload.setdefault("id", task_id)
        return payload

    def request_analysis(
        self,
        *,
        video_id: str,
        prompt: str,
        temperature: Optional[float] = None,
        stream: bool = False,
        response_format: Optional[Dict[str, Any]] = None,
        max_tokens: Optional[int] = None,
    ) -> Dict[str, Any]:
        """Execute an open-ended analysis request via the SDK."""

        response_format_obj = _as_response_format(response_format)
        try:
            if stream:
                chunks = [
                    _model_to_dict(chunk)
                    for chunk in self._client.analyze_stream(
                        video_id=video_id,
                        prompt=prompt,
                        temperature=temperature,
                        response_format=response_format_obj,
                        max_tokens=max_tokens,
                    )
                ]
                return {"stream": chunks}
            response = self._client.analyze(
                video_id=video_id,
                prompt=prompt,
                temperature=temperature,
                response_format=response_format_obj,
                max_tokens=max_tokens,
            )
        except ApiError as exc:
            raise TwelveLabsError(str(exc)) from exc
        return _model_to_dict(response)


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

    print("[1/3] Creating embeddings task...", file=sys.stderr)
    embedding_kwargs = {
        "model_name": args.model_name,
        "video_url": args.video_url,
        "video_start_offset_sec": args.video_start_offset,
        "video_end_offset_sec": args.video_end_offset,
        "video_clip_length": args.video_clip_length,
        "video_embedding_scope": _coerce_scopes(args.video_embedding_scope),
        "gzip_upload": not args.disable_upload_gzip,
    }

    video_file_handle: Optional[BinaryIO] = None
    if args.video_file and not args.video_url:
        video_file_handle = open(args.video_file, "rb")
        embedding_kwargs.update(
            {
                "video_file": video_file_handle,
                "video_file_name": args.video_file,
            }
        )

    try:
        embedding_response = client.create_embedding(**embedding_kwargs)
    finally:
        if video_file_handle is not None:
            video_file_handle.close()

    embedding_task_id = (
        embedding_response.get("task_id")
        or embedding_response.get("id")
        or embedding_response.get("_id")
    )
    if not embedding_task_id:
        raise SystemExit("Embedding response does not contain a task identifier.")

    embedding_status_path = embedding_response.get("status_url") or embedding_response.get("href")
    if not isinstance(embedding_status_path, str) or not embedding_status_path:
        embedding_status_path = f"{client.embedding_path.rstrip('/')}/{embedding_task_id}/status"

    print("[2/3] Waiting for embeddings to complete...", file=sys.stderr)
    embedding_status = client.wait_for_task(
        status_url=embedding_status_path,
        poll_interval=args.poll_interval,
    )

    analysis_video_id = args.analysis_video_id or _extract_video_id(embedding_status)
    if not analysis_video_id:
        raise SystemExit(
            "Could not determine the Twelve Labs video_id from the embedding status. "
            "Pass --analysis-video-id explicitly to continue."
        )

    try:
        response_format = _load_response_format(args.response_format)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        raise SystemExit(f"Failed to parse --response-format: {exc}") from exc

    print("[3/3] Requesting analysis...", file=sys.stderr)
    analysis_result = client.request_analysis(
        video_id=analysis_video_id,
        prompt=args.prompt,
        temperature=args.temperature,
        stream=args.analysis_stream,
        response_format=response_format,
        max_tokens=args.max_tokens,
    )

    print(json.dumps(
        {
            "embedding": embedding_status,
            "analysis": analysis_result,
        },
        indent=2,
        ensure_ascii=False,
    ))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Embed uploaded videos and analyze them with Twelve Labs")
    parser.add_argument("--api-key", required=True, help="Twelve Labs API key")
    parser.add_argument("--model-name", required=True, help="Embedding model to use (e.g. Marengo-retrieval-2.7)")
    parser.add_argument("--video-file", help="Path to a local video file for embedding")
    parser.add_argument("--video-url", help="Public URL pointing directly to the video file")
    parser.add_argument("--video-start-offset", type=float, help="Start offset in seconds from the beginning of the video")
    parser.add_argument("--video-end-offset", type=float, help="End offset in seconds from the beginning of the video")
    parser.add_argument("--video-clip-length", type=float, help="Clip length in seconds for segment embeddings")
    parser.add_argument(
        "--video-embedding-scope",
        action="append",
        help="Embedding scope values (clip, video). Repeat the flag to request multiple scopes",
    )
    parser.add_argument("--prompt", required=True, help="Natural language prompt for the analysis")
    parser.add_argument(
        "--analysis-video-id",
        help="Override the video_id used for analysis if it cannot be derived automatically",
    )
    parser.add_argument("--temperature", type=float, help="Sampling temperature for text generation (0-1)")
    parser.add_argument(
        "--analysis-stream",
        action="store_true",
        help="Enable streaming responses from the analysis endpoint",
    )
    parser.add_argument("--max-tokens", type=int, help="Maximum number of tokens to generate")
    parser.add_argument(
        "--response-format",
        help="JSON object or path to JSON file describing the desired response_format",
    )
    parser.add_argument(
        "--disable-upload-gzip",
        action="store_true",
        help="Retained for backwards compatibility; uploads are handled by the SDK",
    )
    parser.add_argument("--poll-interval", type=int, default=10, help="Seconds between task status polls")
    parser.add_argument("--timeout", type=int, default=60, help="HTTP request timeout in seconds")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="Override the Twelve Labs API base URL")
    parser.add_argument(
        "--embedding-path",
        default=DEFAULT_EMBEDDING_TASK_PATH,
        help="API path used to create and poll embedding tasks (default: %(default)s)",
    )
    parser.add_argument(
        "--analysis-path",
        default=DEFAULT_ANALYSIS_PATH,
        help="API path used to create and poll analysis tasks (default: %(default)s)",
    )
    return parser


if __name__ == "__main__":
    run_pipeline(build_parser().parse_args())
