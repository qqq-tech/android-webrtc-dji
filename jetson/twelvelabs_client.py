"""High-level helpers built on top of the official Twelve Labs SDK.

This module now mirrors the workflows demonstrated in the public notebooks
`Olympics_Video_Content_Search.ipynb` and `TwelveLabs_Quickstart_Analyze.ipynb`.
Those guides illustrate how to provision an index, upload content, monitor
tasks, and run gist/summary/analysis requests directly from the SDK. The helper
below keeps the same ergonomics for the Jetson tooling while implementing the
updated flows showcased by Twelve Labs.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from typing import Any, BinaryIO, Dict, Iterable, List, Optional, Sequence

try:  # pragma: no cover - optional dependency for HLS metadata retrieval
    import requests
except Exception:  # pragma: no cover - fallback when requests is absent
    requests = None  # type: ignore[assignment]

from twelvelabs import TwelveLabs

try:  # pragma: no cover - import path differs between SDK versions
    from twelvelabs.core.api_error import ApiError
except ImportError:  # pragma: no cover - fallback for older releases
    from twelvelabs.errors import ApiError  # type: ignore[no-redef]

try:  # pragma: no cover - optional dependency is provided by the SDK
    from httpx import Client as HTTPXClient, HTTPError
except ImportError:  # pragma: no cover - httpx shipped alongside SDK
    HTTPXClient = None  # type: ignore[assignment]
    HTTPError = Exception  # type: ignore[assignment]

try:  # pragma: no cover - installed alongside newer SDK releases
    from twelvelabs.indexes import IndexesCreateRequestModelsItem
except ImportError:  # pragma: no cover - backwards compatibility shim
    IndexesCreateRequestModelsItem = None  # type: ignore[assignment]

DEFAULT_BASE_URL = "https://api.twelvelabs.io/v1.3"
DEFAULT_INDEX_NAME = "olympics-demo"
DEFAULT_INDEX_MODEL_NAME = "marengo2.7"
DEFAULT_INDEX_MODEL_OPTIONS: Sequence[str] = ("visual", "audio")
DEFAULT_GIST_TYPES: Sequence[str] = ("title", "topic", "hashtag")
DEFAULT_SUMMARY_TYPES: Sequence[str] = ("summary", "chapter", "highlight")
DEFAULT_SEARCH_OPTIONS: Sequence[str] = ("visual", "audio")


class TwelveLabsError(RuntimeError):
    """Raised when an SDK interaction fails."""


def _serialise(payload: Any) -> Any:
    """Best-effort conversion of SDK models into JSON serialisable objects."""

    if hasattr(payload, "model_dump_json"):
        return json.loads(payload.model_dump_json())
    if hasattr(payload, "model_dump"):
        return payload.model_dump(mode="json")
    if isinstance(payload, dict):
        return {key: _serialise(value) for key, value in payload.items()}
    if isinstance(payload, list):
        return [_serialise(item) for item in payload]
    return payload


def extract_index_id(payload: Any) -> Optional[str]:
    """Locate the Twelve Labs ``index_id`` within ``payload``."""

    data = _serialise(payload)
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

    data = _serialise(payload)
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
                stack.extend(
                    item for item in value if isinstance(item, (dict, list))
                )
    return None


def _parse_scope(scopes: Optional[Iterable[str]], *, default: Sequence[str]) -> List[str]:
    if scopes is None:
        return list(default)
    parsed = [scope.strip() for scope in scopes if scope and scope.strip()]
    return parsed or list(default)


def _load_response_format_arg(raw: Optional[str]) -> Optional[Dict[str, Any]]:
    if raw is None:
        return None
    if os.path.exists(raw):
        with open(raw, "r", encoding="utf-8") as handle:
            return json.load(handle)
    return json.loads(raw)


@dataclass
class TwelveLabsClient:
    api_key: str
    base_url: Optional[str] = DEFAULT_BASE_URL
    timeout: Optional[float] = 60.0
    verify_tls: bool = False
    _http_client: Optional[Any] = field(init=False, default=None, repr=False)

    def __post_init__(self) -> None:
        client_kwargs: Dict[str, Any] = {"api_key": self.api_key}
        if self.base_url:
            client_kwargs["base_url"] = self.base_url
        if self.timeout is not None:
            client_kwargs["timeout"] = self.timeout
        if HTTPXClient is not None:
            try:
                timeout = self.timeout if self.timeout is not None else None
                self._http_client = HTTPXClient(verify=self.verify_tls, timeout=timeout)
            except Exception as exc:  # pragma: no cover - httpx configuration errors propagate to caller
                raise TwelveLabsError(
                    f"Failed to configure Twelve Labs HTTP client: {exc}"
                ) from exc
            client_kwargs["httpx_client"] = self._http_client
        try:
            self._sdk = TwelveLabs(**client_kwargs)
        except (ApiError, HTTPError) as exc:  # pragma: no cover - configuration errors propagate to caller
            if self._http_client is not None:
                try:
                    self._http_client.close()
                except Exception:
                    pass
            raise TwelveLabsError(
                f"Failed to initialise Twelve Labs SDK client: {exc}"
            ) from exc

        # Normalise attribute names across SDK releases (client.index vs client.indexes)
        self._indexes_api = getattr(self._sdk, "index", None) or getattr(
            self._sdk, "indexes"
        )
        self._tasks_api = getattr(self._sdk, "task", None) or getattr(
            self._sdk, "tasks"
        )

    # ------------------------------------------------------------------
    # Index helpers inspired by the Olympics content search notebook
    # ------------------------------------------------------------------
    def ensure_index(
        self,
        *,
        index_name: str,
        model_name: str = DEFAULT_INDEX_MODEL_NAME,
        model_options: Optional[Iterable[str]] = None,
        addons: Optional[Iterable[str]] = None,
    ) -> Dict[str, Any]:
        if not index_name:
            raise ValueError("index_name is required")

        try:
            pager = self._indexes_api.list(index_name=index_name)
            for item in pager:
                payload = _serialise(item)
                payload_name = payload.get("index_name") or payload.get("name")
                if payload_name == index_name:
                    return payload
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(
                f"Failed to list Twelve Labs indexes: {exc}"
            ) from exc

        if IndexesCreateRequestModelsItem is not None:
            model = IndexesCreateRequestModelsItem(
                model_name=model_name,
                model_options=_parse_scope(model_options, default=DEFAULT_INDEX_MODEL_OPTIONS),
            )
        else:  # pragma: no cover - fallback for legacy SDKs lacking pydantic models
            model = {
                "model_name": model_name,
                "model_options": _parse_scope(
                    model_options, default=DEFAULT_INDEX_MODEL_OPTIONS
                ),
            }

        try:
            created = self._indexes_api.create(
                index_name=index_name,
                models=[model],
                addons=list(addons) if addons else None,
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(
                f"Failed to create Twelve Labs index '{index_name}': {exc}"
            ) from exc
        return _serialise(created)

    # ------------------------------------------------------------------
    # Task helpers inspired by the public ingestion notebook
    # ------------------------------------------------------------------
    def create_indexing_task(
        self,
        *,
        index_id: str,
        video_file: Optional[BinaryIO] = None,
        video_url: Optional[str] = None,
        enable_video_stream: Optional[bool] = None,
        user_metadata: Optional[str] = None,
    ) -> Dict[str, Any]:
        if not index_id:
            raise ValueError("index_id is required")
        if not video_file and not video_url:
            raise ValueError("Provide either video_file or video_url")

        kwargs: Dict[str, Any] = {"index_id": index_id}
        if video_file is not None:
            kwargs["video_file"] = video_file
        if video_url is not None:
            kwargs["video_url"] = video_url
        if enable_video_stream is not None:
            kwargs["enable_video_stream"] = enable_video_stream
        if user_metadata is not None:
            kwargs["user_metadata"] = user_metadata

        try:
            task = self._tasks_api.create(**kwargs)
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(
                f"Failed to create indexing task for index '{index_id}': {exc}"
            ) from exc
        return _serialise(task)

    def wait_for_task(
        self,
        *,
        task_id: str,
        poll_interval: float = 5.0,
    ) -> Dict[str, Any]:
        if not task_id:
            raise ValueError("task_id is required")
        if poll_interval <= 0:
            raise ValueError("poll_interval must be positive")

        try:
            status = self._tasks_api.wait_for_done(
                task_id=task_id, sleep_interval=float(poll_interval)
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(
                f"Failed to poll indexing task '{task_id}': {exc}"
            ) from exc
        return _serialise(status)

    # ------------------------------------------------------------------
    # High-level operations inspired by the quickstart analysis notebook
    # ------------------------------------------------------------------
    def fetch_gist(
        self,
        *,
        video_id: str,
        gist_types: Sequence[str] = DEFAULT_GIST_TYPES,
    ) -> Dict[str, Any]:
        if not video_id:
            raise ValueError("video_id is required")
        try:
            gist = self._sdk.gist(video_id=video_id, types=list(gist_types))
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(
                f"Failed to retrieve gist for video '{video_id}': {exc}"
            ) from exc
        return _serialise(gist)

    def summarize(
        self,
        *,
        video_id: str,
        summary_type: str,
        prompt: Optional[str] = None,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
    ) -> Dict[str, Any]:
        if not video_id:
            raise ValueError("video_id is required")
        try:
            summary = self._sdk.summarize(
                video_id=video_id,
                type=summary_type,
                prompt=prompt,
                temperature=temperature,
                max_tokens=max_tokens,
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(
                f"Failed to summarise video '{video_id}' ({summary_type}): {exc}"
            ) from exc
        return _serialise(summary)

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
            raise ValueError("video_id is required")
        if not prompt:
            raise ValueError("prompt is required for analysis")

        try:
            if stream:
                return {
                    "stream": [
                        _serialise(chunk)
                        for chunk in self._sdk.analyze_stream(
                            video_id=video_id,
                            prompt=prompt,
                            temperature=temperature,
                            max_tokens=max_tokens,
                            response_format=response_format,
                        )
                    ]
                }
            analysis = self._sdk.analyze(
                video_id=video_id,
                prompt=prompt,
                temperature=temperature,
                max_tokens=max_tokens,
                response_format=response_format,
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(
                f"Failed to analyse video '{video_id}': {exc}"
            ) from exc
        return _serialise(analysis)

    def search_videos(
        self,
        *,
        index_id: str,
        query_text: str,
        search_options: Optional[Iterable[str]] = None,
        group_by: str = "video",
        threshold: str = "medium",
        operator: str = "or",
        page_limit: int = 5,
        sort_option: str = "score",
    ) -> List[Dict[str, Any]]:
        if not index_id:
            raise ValueError("index_id is required for search")
        if not query_text:
            raise ValueError("query_text is required for search")
        options = _parse_scope(
            search_options, default=DEFAULT_SEARCH_OPTIONS
        )
        try:
            pager = self._sdk.search.query(
                index_id=index_id,
                search_options=options,
                query_text=query_text,
                group_by=group_by,
                threshold=threshold,
                operator=operator,
                page_limit=page_limit,
                sort_option=sort_option,
            )
        except (ApiError, HTTPError) as exc:
            raise TwelveLabsError(
                f"Failed to search index '{index_id}': {exc}"
            ) from exc
        return [_serialise(item) for item in pager]

    def get_video_hls_url(self, *, index_id: str, video_id: str) -> Optional[str]:
        if not self.base_url:
            return None
        if requests is None:
            raise TwelveLabsError(
                "The 'requests' package is required to fetch HLS metadata. Install it or disable --include-hls-url."
            )
        endpoint = f"{self.base_url.rstrip('/')}/indexes/{index_id}/videos/{video_id}"
        headers = {"x-api-key": self.api_key, "Content-Type": "application/json"}
        try:
            response = requests.get(
                endpoint,
                headers=headers,
                timeout=self.timeout,
                verify=self.verify_tls,
            )
            response.raise_for_status()
        except requests.RequestException as exc:
            raise TwelveLabsError(
                f"Failed to retrieve video metadata from '{endpoint}': {exc}"
            ) from exc
        payload = response.json()
        hls = payload.get("hls") if isinstance(payload, dict) else None
        if isinstance(hls, dict):
            url = hls.get("video_url")
            if isinstance(url, str) and url:
                return url
        return None


def run_pipeline(args: argparse.Namespace) -> None:
    client = TwelveLabsClient(
        api_key=args.api_key,
        base_url=args.base_url,
        timeout=args.timeout,
        verify_tls=args.verify_tls,
    )

    if not args.video_file and not args.video_url:
        raise SystemExit("Provide either --video-file or --video-url to upload a video")

    model_options = _parse_scope(
        args.index_model_options, default=DEFAULT_INDEX_MODEL_OPTIONS
    )
    index_addons = (
        [addon.strip() for addon in args.index_addons if addon and addon.strip()]
        if args.index_addons
        else None
    )

    index_response = client.ensure_index(
        index_name=args.index_name,
        model_name=args.model_name,
        model_options=model_options,
        addons=index_addons,
    )
    index_id = index_response.get("index_id") or index_response.get("id")
    if not isinstance(index_id, str) or not index_id:
        raise SystemExit("Failed to determine index identifier from Twelve Labs response")

    video_handle: Optional[BinaryIO] = None
    task_payload: Dict[str, Any]
    try:
        if args.video_file and not args.video_url:
            video_handle = open(args.video_file, "rb")
            task_payload = client.create_indexing_task(
                index_id=index_id,
                video_file=video_handle,
                enable_video_stream=args.enable_video_stream,
                user_metadata=args.user_metadata,
            )
        else:
            task_payload = client.create_indexing_task(
                index_id=index_id,
                video_url=args.video_url,
                enable_video_stream=args.enable_video_stream,
                user_metadata=args.user_metadata,
            )
    finally:
        if video_handle is not None:
            video_handle.close()

    task_id = (
        task_payload.get("id")
        or task_payload.get("task_id")
        or task_payload.get("_id")
    )
    if not isinstance(task_id, str) or not task_id:
        raise SystemExit("Indexing response did not return a task identifier")

    task_status = client.wait_for_task(
        task_id=task_id, poll_interval=args.poll_interval
    )
    video_id = (
        args.analysis_video_id
        or task_status.get("video_id")
        or task_payload.get("video_id")
    )
    if not isinstance(video_id, str) or not video_id:
        raise SystemExit(
            "Could not determine Twelve Labs video_id from the indexing task. "
            "Pass --analysis-video-id to override."
        )

    gist_types = _parse_scope(args.gist_types, default=DEFAULT_GIST_TYPES)
    gist_response = client.fetch_gist(video_id=video_id, gist_types=gist_types)

    summary_payloads: Dict[str, Any] = {}
    summary_types = _parse_scope(
        args.summary_types, default=DEFAULT_SUMMARY_TYPES
    )
    for summary_type in summary_types:
        summary_payloads[summary_type] = client.summarize(
            video_id=video_id,
            summary_type=summary_type,
            prompt=args.summary_prompt,
            temperature=args.summary_temperature,
            max_tokens=args.summary_max_tokens,
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

    search_results: Optional[List[Dict[str, Any]]] = None
    if args.search_prompt:
        search_results = client.search_videos(
            index_id=index_id,
            query_text=args.search_prompt,
            search_options=args.search_options,
            group_by=args.search_group_by,
            threshold=args.search_threshold,
            operator=args.search_operator,
            page_limit=args.search_page_limit,
            sort_option=args.search_sort,
        )

    hls_url = None
    if args.include_hls_url:
        try:
            hls_url = client.get_video_hls_url(index_id=index_id, video_id=video_id)
        except TwelveLabsError as exc:
            if not args.ignore_hls_errors:
                raise
            hls_url = {"error": str(exc)}

    output: Dict[str, Any] = {
        "index": index_response,
        "task": {
            "request": task_payload,
            "status": task_status,
        },
        "video": {
            "video_id": video_id,
            "hls_url": hls_url,
        },
        "gist": gist_response,
        "summary": summary_payloads,
        "analysis": analysis_response,
    }
    if search_results is not None:
        output["search"] = search_results

    print(json.dumps(output, indent=2, ensure_ascii=False))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Upload a video to Twelve Labs, wait for indexing, and run gist/summary/analysis workflows",
    )
    parser.add_argument("--api-key", required=True, help="Twelve Labs API key")
    parser.add_argument(
        "--index-name",
        default=DEFAULT_INDEX_NAME,
        help="Name of the Twelve Labs managed index (created if missing)",
    )
    parser.add_argument(
        "--model-name",
        default=DEFAULT_INDEX_MODEL_NAME,
        help="Video understanding model to enable for the index",
    )
    parser.add_argument(
        "--index-model-option",
        "--index-model-options",
        action="append",
        dest="index_model_options",
        default=None,
        help="Repeat to include specific model options (visual, audio)",
    )
    parser.add_argument(
        "--index-addon",
        action="append",
        dest="index_addons",
        default=None,
        help="Repeat to enable optional index add-ons (e.g. thumbnail)",
    )
    parser.add_argument("--video-file", help="Path to a local video file")
    parser.add_argument("--video-url", help="Public URL pointing to a video file")
    parser.add_argument(
        "--enable-video-stream",
        action="store_true",
        help="Store uploaded videos for streaming playback",
    )
    parser.add_argument(
        "--user-metadata",
        help="Optional JSON string to attach as user metadata during ingestion",
    )
    parser.add_argument(
        "--poll-interval",
        type=float,
        default=5.0,
        help="Seconds between task status polls",
    )
    parser.add_argument(
        "--prompt",
        required=True,
        help="Analysis prompt to send to the Twelve Labs analyze endpoint",
    )
    parser.add_argument("--temperature", type=float, help="Sampling temperature for analysis")
    parser.add_argument("--max-tokens", type=int, dest="max_tokens", help="Maximum tokens to generate")
    parser.add_argument(
        "--analysis-video-id",
        help="Override the video_id returned by the indexing task",
    )
    parser.add_argument(
        "--analysis-stream",
        action="store_true",
        help="Use the streaming analysis endpoint",
    )
    parser.add_argument(
        "--response-format",
        help="Path or JSON string describing the structured response format",
    )
    parser.add_argument(
        "--gist-type",
        action="append",
        dest="gist_types",
        default=None,
        help="Repeat to customise gist fields (title, topic, hashtag)",
    )
    parser.add_argument(
        "--summary-type",
        action="append",
        dest="summary_types",
        default=None,
        help="Repeat to customise summary variants (summary, chapter, highlight)",
    )
    parser.add_argument(
        "--summary-prompt",
        help="Optional prompt shared across the summary endpoints",
    )
    parser.add_argument(
        "--summary-temperature",
        type=float,
        help="Optional temperature applied to summary generations",
    )
    parser.add_argument(
        "--summary-max-tokens",
        type=int,
        dest="summary_max_tokens",
        help="Optional token limit applied to summary generations",
    )
    parser.add_argument(
        "--search-prompt",
        help="If supplied, run a follow-up semantic search using the prompt",
    )
    parser.add_argument(
        "--search-option",
        "--search-options",
        action="append",
        dest="search_options",
        default=None,
        help="Repeat to customise search modalities (visual, audio)",
    )
    parser.add_argument(
        "--search-group-by",
        default="video",
        help="Group search results by 'video' or 'clip' (default: video)",
    )
    parser.add_argument(
        "--search-threshold",
        default="medium",
        help="Confidence threshold for search results (high, medium, low, none)",
    )
    parser.add_argument(
        "--search-operator",
        default="or",
        help="Logical operator applied to multi-term search queries",
    )
    parser.add_argument(
        "--search-page-limit",
        type=int,
        default=5,
        help="Number of pages to fetch when running semantic search",
    )
    parser.add_argument(
        "--search-sort",
        default="score",
        help="Sort option applied to search results (score or clip_count)",
    )
    parser.add_argument(
        "--include-hls-url",
        action="store_true",
        help="Fetch the HLS playback URL for the analysed video",
    )
    parser.add_argument(
        "--ignore-hls-errors",
        action="store_true",
        help="Suppress errors when HLS metadata retrieval fails",
    )
    parser.add_argument(
        "--base-url",
        default=DEFAULT_BASE_URL,
        help="Override the Twelve Labs API base URL",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=60.0,
        help="HTTP timeout in seconds",
    )
    parser.add_argument(
        "--verify-tls",
        action="store_true",
        help="Enable TLS certificate verification (disabled by default for self-signed deployments)",
    )
    return parser


def main(argv: Optional[List[str]] = None) -> int:
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
    "DEFAULT_BASE_URL",
    "DEFAULT_INDEX_MODEL_NAME",
    "DEFAULT_INDEX_MODEL_OPTIONS",
    "DEFAULT_INDEX_NAME",
    "DEFAULT_GIST_TYPES",
    "DEFAULT_SUMMARY_TYPES",
    "DEFAULT_SEARCH_OPTIONS",
    "extract_index_id",
    "extract_video_id",
    "TwelveLabsClient",
    "TwelveLabsError",
    "build_parser",
    "main",
    "run_pipeline",
]
