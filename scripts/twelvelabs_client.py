"""Command line client for embedding and analyzing videos with the Twelve Labs API.

The script performs the following steps:
1. Upload a video (or provide a public URL) to create an embedding task.
2. Poll the embedding task until it is completed and extract the resulting video ID.
3. Trigger an open-ended analysis request with a natural language prompt and print the
   response JSON to stdout.

Usage example::

    python scripts/twelvelabs_client.py \
        --api-key "$TWELVE_LABS_API_KEY" \
        --model-name "Marengo-retrieval-2.7" \
        --video-file /path/to/video.mp4 \
        --prompt "Summarize the main events" \
        --temperature 0.2

The script intentionally keeps the payload shape close to the public
API specification. The default embedding path points at the v1.3 task
creation endpoint documented at
https://docs.twelvelabs.io/v1.3/api-reference/video-embeddings/create-video-embedding-task
and the analysis request follows the v1.3 open-ended analysis API at
https://docs.twelvelabs.io/api-reference/analyze-videos. Both paths can
be overridden with ``--embedding-path`` and ``--analysis-path`` when
Twelve Labs publishes an updated revision.
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
import sys
import tempfile
import time
from dataclasses import dataclass
from typing import Any, Dict, Iterable, Optional, BinaryIO

import requests


DEFAULT_BASE_URL = "https://api.twelvelabs.io"
DEFAULT_EMBEDDING_TASK_PATH = "/v1.3/embed/tasks"
DEFAULT_ANALYSIS_PATH = "/v1.3/analyze"


class TwelveLabsError(RuntimeError):
    """Raised when an API call fails."""


@dataclass
class TwelveLabsClient:
    """Small helper around the Twelve Labs REST API."""

    api_key: str
    base_url: str = DEFAULT_BASE_URL
    timeout: int = 30
    embedding_path: str = DEFAULT_EMBEDDING_TASK_PATH
    analysis_path: str = DEFAULT_ANALYSIS_PATH

    def __post_init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update({
            "accept": "application/json",
            "x-api-key": self.api_key,
        })

    def _post(self, path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        url = path if path.startswith("http") else f"{self.base_url}{path}"
        response = self.session.post(
            url,
            json=payload,
            timeout=self.timeout,
        )
        self._raise_for_status(response)
        return response.json()

    def _get(self, path: str) -> Dict[str, Any]:
        url = path if path.startswith("http") else f"{self.base_url}{path}"
        response = self.session.get(
            url,
            timeout=self.timeout,
        )
        self._raise_for_status(response)
        return response.json()

    @staticmethod
    def _raise_for_status(response: requests.Response) -> None:
        try:
            response.raise_for_status()
        except requests.HTTPError as exc:  # pragma: no cover - defensive branch
            message = exc.response.text if exc.response is not None else str(exc)
            raise TwelveLabsError(message) from exc

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
        gzip_upload: bool = True,
    ) -> Dict[str, Any]:
        """Create a video embedding task using either a local file or a URL."""

        if not video_file and not video_url:
            raise ValueError("Either video_file or video_url must be supplied for embeddings")

        url = (
            self.embedding_path
            if self.embedding_path.startswith("http")
            else f"{self.base_url}{self.embedding_path}"
        )

        form_data: Dict[str, Any] = {"model_name": model_name}
        if video_url:
            form_data["video_url"] = video_url
        if video_start_offset_sec is not None:
            form_data["video_start_offset_sec"] = str(video_start_offset_sec)
        if video_end_offset_sec is not None:
            form_data["video_end_offset_sec"] = str(video_end_offset_sec)
        if video_clip_length is not None:
            form_data["video_clip_length"] = str(video_clip_length)
        if video_embedding_scope:
            scopes = [scope for scope in video_embedding_scope if scope]
            if not scopes:
                raise ValueError("video_embedding_scope must contain at least one value when provided")
            primary, *extra = scopes
            form_data["video_embedding_scope"] = primary
            if extra:
                form_data["_extra_scopes"] = [("video_embedding_scope", scope) for scope in extra]

        files = None
        gzip_handle: Optional[BinaryIO] = None
        if video_file and not video_url:
            filename = video_file_name or getattr(video_file, "name", "video.mp4") or "video.mp4"
            upload_handle: BinaryIO
            upload_headers: Optional[Dict[str, str]] = None
            if gzip_upload:
                gzip_handle = _gzip_file(video_file)
                upload_handle = gzip_handle
                upload_headers = {"Content-Encoding": "gzip"}
            else:
                if hasattr(video_file, "seek"):
                    video_file.seek(0)
                upload_handle = video_file
            file_tuple = (
                os.path.basename(filename),
                upload_handle,
                "application/octet-stream",
            )
            if upload_headers:
                file_tuple = file_tuple + (upload_headers,)
            files = {
                "video_file": file_tuple,
            }

        data_items = [(key, value) for key, value in form_data.items() if key != "_extra_scopes"]
        for scope_entry in form_data.get("_extra_scopes", []):
            data_items.append(scope_entry)

        try:
            response = self.session.post(
                url,
                data=data_items,
                files=files,
                timeout=self.timeout,
            )
            self._raise_for_status(response)
            return response.json()
        finally:
            if gzip_handle is not None:
                gzip_handle.close()

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
        """Trigger an open-ended analysis request for a stored video."""

        payload: Dict[str, Any] = {
            "video_id": video_id,
            "prompt": prompt,
            "stream": stream,
        }
        if temperature is not None:
            payload["temperature"] = temperature
        if response_format is not None:
            payload["response_format"] = response_format
        if max_tokens is not None:
            payload["max_tokens"] = max_tokens

        if not stream:
            return self._post(self.analysis_path, payload)

        url = (
            self.analysis_path
            if self.analysis_path.startswith("http")
            else f"{self.base_url}{self.analysis_path}"
        )
        response = self.session.post(
            url,
            json=payload,
            timeout=self.timeout,
            stream=True,
        )
        try:
            self._raise_for_status(response)
            chunks = []
            for line in response.iter_lines():
                if not line:
                    continue
                try:
                    decoded = line.decode("utf-8")
                except AttributeError:
                    decoded = line
                chunks.append(json.loads(decoded))
            return {"stream": chunks}
        finally:
            response.close()

    def wait_for_task(
        self,
        *,
        status_url: str,
        poll_interval: int = 10,
    ) -> Dict[str, Any]:
        """Poll a task endpoint until it completes or fails."""

        if not status_url:
            raise ValueError("status_url is required to poll task status")

        while True:
            data = self._get(status_url)
            task_info = data.get("task", {}) if isinstance(data, dict) else {}
            status = (
                (data.get("status") if isinstance(data, dict) else None)
                or task_info.get("status")
                or task_info.get("state")
                or (data.get("state") if isinstance(data, dict) else None)
                or task_info.get("task_status")
            )
            if status in {"completed", "succeeded", "ready", "finished"}:
                return data
            if status in {"failed", "error", "cancelled", "canceled"}:
                raise TwelveLabsError(json.dumps(data))
            time.sleep(poll_interval)


def _coerce_scopes(raw: Optional[Iterable[str]]) -> Optional[Iterable[str]]:
    if raw is None:
        return None
    scopes = [scope for scope in raw if scope]
    return scopes or None


def _gzip_file(source: BinaryIO, *, chunk_size: int = 1024 * 1024) -> BinaryIO:
    """Return a temporary file-like object containing gzipped contents of *source*."""

    if hasattr(source, "seek"):
        source.seek(0)
    spool = tempfile.SpooledTemporaryFile(max_size=8 * 1024 * 1024)
    with gzip.GzipFile(fileobj=spool, mode="wb") as gz_stream:
        while True:
            chunk = source.read(chunk_size)
            if not chunk:
                break
            gz_stream.write(chunk)
    spool.seek(0)
    if hasattr(source, "seek"):
        source.seek(0)
    return spool


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


def _extract_video_id(payload: Dict[str, Any]) -> Optional[str]:
    """Try to locate a Twelve Labs video identifier in the payload."""

    if not isinstance(payload, dict):
        return None

    candidates = [
        payload.get("video_id"),
        payload.get("id"),
        payload.get("_id"),
    ]

    for key in ("task", "data", "result", "embedding"):
        nested = payload.get(key)
        if isinstance(nested, dict):
            candidates.extend(
                [
                    nested.get("video_id"),
                    nested.get("id"),
                    nested.get("_id"),
                ]
            )
            videos = nested.get("videos")
            if isinstance(videos, list):
                for item in videos:
                    if isinstance(item, dict):
                        candidates.append(item.get("video_id"))
        elif isinstance(nested, list):
            for item in nested:
                if isinstance(item, dict):
                    candidates.append(item.get("video_id"))

    for candidate in candidates:
        if isinstance(candidate, str) and candidate:
            return candidate
    return None


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
        help="Enable NDJSON streaming responses from the analysis endpoint",
    )
    parser.add_argument("--max-tokens", type=int, help="Maximum number of tokens to generate")
    parser.add_argument(
        "--response-format",
        help="JSON object or path to JSON file describing the desired response_format",
    )
    parser.add_argument(
        "--disable-upload-gzip",
        action="store_true",
        help="Send the raw video bytes instead of gzip-compressing them for uploads",
    )
    parser.add_argument("--poll-interval", type=int, default=10, help="Seconds between task status polls")
    parser.add_argument("--timeout", type=int, default=30, help="HTTP request timeout in seconds")
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
