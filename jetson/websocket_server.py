"""Utility WebSocket server to push YOLO detections to browser clients."""
from __future__ import annotations

import importlib
import inspect
import asyncio
import http
import json
import logging
import mimetypes
from urllib.parse import unquote, urlsplit
from collections.abc import Iterable, Mapping
from pathlib import Path
from typing import Any, Optional, Set, Union

import websockets
from websockets.server import WebSocketServer, WebSocketServerProtocol

try:  # websockets >= 12 exposes an explicit HTTP response type
    from websockets.datastructures import Headers as WebsocketsHeaders
except Exception:  # pragma: no cover - optional dependency across versions
    WebsocketsHeaders = None

def _load_websockets_response():
    """Best-effort import of the websockets HTTP ``Response`` type."""

    module_names = (
        "websockets.http",
        "websockets.http11",
        "websockets.asyncio.http11",
        "websockets.server",
    )
    for module_name in module_names:
        try:
            module = importlib.import_module(module_name)
        except Exception:  # pragma: no cover - optional dependency across versions
            continue
        response = getattr(module, "Response", None)
        if response is not None:
            return response
    return None


WebsocketsResponse = _load_websockets_response()
if WebsocketsResponse is not None:
    try:
        _WebsocketsResponseParameters = inspect.signature(WebsocketsResponse).parameters
    except (TypeError, ValueError):  # pragma: no cover - depends on websockets internals
        _WebsocketsResponseParameters = {}
else:  # pragma: no cover - Response discovery failed
    _WebsocketsResponseParameters = {}

_WEBSOCKETS_REASON_PARAM = None
for _candidate in ("reason_phrase", "reason"):
    if _candidate in _WebsocketsResponseParameters:
        _WEBSOCKETS_REASON_PARAM = _candidate
        break


class _StaticRequestSilencingLogger:
    """Filter websockets info logs for successful HTTP responses."""

    def __init__(self, base_logger: logging.Logger):
        self._base_logger = base_logger

    def info(self, message: str, *args, **kwargs):
        if (
            message == "connection rejected (%d %s)"
            and args
            and args[0] == http.HTTPStatus.OK.value
        ):
            return
        self._base_logger.info(message, *args, **kwargs)

    def __getattr__(self, name):  # pragma: no cover - passthrough helper
        return getattr(self._base_logger, name)


class DetectionBroadcaster:
    """Simple WebSocket pub-sub helper for detection results."""

    def __init__(
        self,
        host: str = "0.0.0.0",
        port: int = 8765,
        path: str = "/detections",
        static_dir: Optional[Union[str, Path]] = None,
    ):
        self._host = host
        self._port = port
        self._path = path
        self._clients: Set[WebSocketServerProtocol] = set()
        self._server: Optional[WebSocketServer] = None
        if static_dir is None:
            default_static = Path(__file__).resolve().parents[1] / "browser"
            self._static_dir = default_static if default_static.exists() else None
        else:
            self._static_dir = Path(static_dir).resolve()

    async def start(self) -> None:
        if self._server is not None:
            return
        base_logger = getattr(websockets.server, "logger", logging.getLogger("websockets.server"))
        websocket_logger = _StaticRequestSilencingLogger(base_logger)
        self._server = await websockets.serve(
            self._handler,
            self._host,
            self._port,
            process_request=self._process_http_request,
            logger=websocket_logger,
        )

    async def stop(self) -> None:
        if self._server is None:
            return
        self._server.close()
        await self._server.wait_closed()
        self._server = None

    async def broadcast(self, payload: dict) -> None:
        if not self._clients:
            return
        message = json.dumps(payload)
        await asyncio.gather(*(self._send(client, message) for client in list(self._clients)))

    async def _handler(self, websocket: WebSocketServerProtocol):
        if self._path and getattr(websocket, "path", self._path) != self._path:
            await websocket.close(code=1008, reason="Invalid path")
            return

        self._clients.add(websocket)
        try:
            await websocket.wait_closed()
        finally:
            self._clients.discard(websocket)

    async def _send(self, websocket: WebSocketServerProtocol, message: str) -> None:
        try:
            await websocket.send(message)
        except Exception:
            self._clients.discard(websocket)

    async def _process_http_request(self, path_or_connection, request_headers=None):
        request_path, request_headers = self._normalise_http_request(
            path_or_connection, request_headers
        )

        upgrade_header = self._get_header_value(request_headers, "Upgrade")
        if "websocket" in upgrade_header.lower():
            return None

        if self._static_dir is not None:
            file_path = self._resolve_static_path(request_path)
            if file_path is not None:
                body = file_path.read_bytes()
                content_type, _ = mimetypes.guess_type(str(file_path))
                headers = [
                    ("Content-Type", content_type or "application/octet-stream"),
                    ("Content-Length", str(len(body))),
                    ("Cache-Control", "no-cache"),
                ]
                return self._build_http_response(http.HTTPStatus.OK, headers, body)

        body = (
            "Detection broadcaster is running. Connect with a WebSocket client at "
            f"{self._path}."
        ).encode("utf-8")
        headers = [
            ("Content-Type", "text/plain; charset=utf-8"),
            ("Content-Length", str(len(body))),
            ("Cache-Control", "no-cache"),
        ]
        return self._build_http_response(http.HTTPStatus.OK, headers, body)

    def _resolve_static_path(self, request_path: str) -> Optional[Path]:
        if self._static_dir is None:
            return None

        relative = request_path.lstrip("/")
        candidate = (self._static_dir / relative).resolve()
        try:
            candidate.relative_to(self._static_dir)
        except ValueError:
            return None

        if candidate.is_dir():
            candidate = candidate / "index.html"
        if candidate.is_file():
            return candidate
        return None

    def _normalise_http_request(self, path_or_connection, request_headers) -> tuple[str, Any]:
        """Return a ``(path, headers)`` tuple that works across websockets versions."""

        # websockets >= 12 passes the ServerConnection instance as the first argument.
        connection = None
        if hasattr(path_or_connection, "path") and hasattr(path_or_connection, "request_headers"):
            connection = path_or_connection
            request_path = getattr(connection, "path", None)
            if request_headers is None:
                request_headers = getattr(connection, "request_headers", None)

            # websockets 12+ exposes the HTTP request on the connection object.
            # ``connection.path`` may be another object (including the
            # ``ServerConnection`` itself) which later ends up stringified into
            # a representation like ``<ServerConnection ...>``. Use the nested
            # request object if available to recover the original HTTP path and
            # headers provided by the client.
            request_obj = None
            for attr in ("request", "_request", "http_request", "_http_request"):
                request_obj = getattr(connection, attr, None)
                if request_obj is not None:
                    break

            if request_obj is not None:
                if request_path is None or request_path is connection or request_path is request_obj:
                    request_path = getattr(request_obj, "path", None) or getattr(
                        request_obj, "raw_path", request_path
                    )
                if request_headers is None:
                    request_headers = getattr(request_obj, "headers", None)

            if request_path is connection:
                request_path = getattr(connection, "raw_request_line", None) or getattr(
                    connection, "request_line", None
                )
        else:
            request_path = path_or_connection

        # When only the connection is provided fall back to attributes available on the
        # headers-like object if it exposes the request path.
        if request_path is None and hasattr(request_headers, "path"):
            request_path = getattr(request_headers, "path")
        if request_path is None and hasattr(request_headers, "raw_path"):
            request_path = getattr(request_headers, "raw_path")

        if connection is not None and request_path is connection:
            request_path = None

        request_path = self._coerce_request_path(request_path)

        return request_path, request_headers

    def _coerce_request_path(self, request_path: Any) -> str:
        """Best-effort conversion of ``request_path`` to a normalised string."""

        visited: set[int] = set()
        value = request_path

        while value is not None and id(value) not in visited:
            visited.add(id(value))

            if isinstance(value, bytes):
                value = value.decode("utf-8", "ignore")
                break

            if isinstance(value, str):
                break

            raw_path = getattr(value, "raw_path", None)
            if raw_path is not None and raw_path is not value:
                value = raw_path
                continue

            for attr in ("request", "_request", "http_request", "_http_request"):
                request_obj = getattr(value, attr, None)
                if request_obj is not None and request_obj is not value:
                    value = request_obj
                    break
            else:
                request_obj = None
            if request_obj is not None:
                continue

            for attr in ("path", "raw_path", "uri", "target", "resource", "path_info"):
                nested_path = getattr(value, attr, None)
                if nested_path is not None and nested_path is not value:
                    value = nested_path
                    break
            else:
                nested_path = None
            if nested_path is not None:
                continue

            request_line = getattr(value, "request_line", None)
            if request_line is not None and request_line is not value:
                value = request_line
                continue

            request_line = getattr(value, "raw_request_line", None)
            if request_line is not None and request_line is not value:
                value = request_line
                continue

            scope = getattr(value, "scope", None)
            if isinstance(scope, Mapping) and "path" in scope:
                value = scope["path"]
                continue

            if callable(value):
                try:
                    value = value()
                    continue
                except TypeError:
                    pass

            try:
                value = str(value)
            except Exception:
                value = None
            break

        if value is None:
            return "/"

        if not isinstance(value, str):
            value = str(value)

        if " " in value:
            method, _, remainder = value.partition(" ")
            if method.upper() in {"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH"}:
                candidate_path, _, _ = remainder.partition(" ")
                if candidate_path:
                    value = candidate_path

        parsed = urlsplit(value)
        path_value = parsed.path or "/"
        normalised = unquote(path_value)

        if not normalised.startswith("/"):
            normalised = "/" + normalised

        return normalised

    def _get_header_value(self, headers_like, name: str) -> str:
        """Best-effort retrieval of a HTTP header from websockets request objects."""

        if headers_like is None:
            return ""

        # websockets >= 11 passes a Request object with a ``headers`` attribute.
        if hasattr(headers_like, "headers"):
            return self._get_header_value(headers_like.headers, name)

        # Headers behave like a Mapping but the keys can be case-sensitive depending
        # on the implementation, so we normalise while searching.
        if isinstance(headers_like, Mapping):
            for key in (name, name.lower(), name.upper()):
                value = headers_like.get(key)
                if value:
                    return value

        items = getattr(headers_like, "items", None)
        if callable(items):
            for key, value in items():
                if isinstance(key, str) and key.lower() == name.lower() and value:
                    return value

        # websockets.datastructures.Headers implements ``get_all`` for case-insensitive
        # lookups. Use it if available before falling back to an iterable search.
        get_all = getattr(headers_like, "get_all", None)
        if callable(get_all):
            values = get_all(name)
            if values:
                return values[0]

        if hasattr(headers_like, "get"):
            value = headers_like.get(name)
            if value:
                return value

        if isinstance(headers_like, Iterable):
            for item in headers_like:
                if isinstance(item, tuple):
                    if not item:
                        continue
                    key = item[0]
                    value = item[1] if len(item) > 1 else ""
                else:
                    key = item
                    value = getattr(headers_like, "__getitem__", lambda *_: "")(item)
                if isinstance(key, str) and key.lower() == name.lower() and value:
                    return value

        return ""

    def _build_http_response(self, status, headers, body):
        """Return a websockets-compatible HTTP response object or tuple."""

        status_code = int(status)
        header_items = []
        for key, value in headers or []:
            if key is None:
                continue
            header_items.append((str(key), "" if value is None else str(value)))

        if WebsocketsResponse is not None:
            headers_value = header_items
            if WebsocketsHeaders is not None:
                headers_obj = WebsocketsHeaders()
                for key, value in header_items:
                    headers_obj[key] = value
                headers_value = headers_obj

            reason_phrase = ""
            if isinstance(status, http.HTTPStatus):
                reason_phrase = status.phrase
            else:
                try:
                    reason_phrase = http.HTTPStatus(status_code).phrase
                except ValueError:
                    reason_phrase = ""

            return self._create_websockets_response(
                status_code, headers_value, body, reason_phrase
            )

        return status_code, header_items, body

    def _create_websockets_response(self, status_code, headers_value, body, reason_phrase):
        """Instantiate websockets' ``Response`` regardless of signature changes."""

        if WebsocketsResponse is None:
            raise RuntimeError("websockets Response type is unavailable")

        base_kwargs = {
            "status_code": status_code,
            "headers": headers_value,
            "body": body,
        }

        attempts: list[tuple[tuple[Any, ...], dict[str, Any]]] = []

        if _WEBSOCKETS_REASON_PARAM:
            attempts.append(((), {**base_kwargs, _WEBSOCKETS_REASON_PARAM: reason_phrase}))

        attempts.append(((), base_kwargs))

        positional_base = (status_code, headers_value, body)
        attempts.append((positional_base + (reason_phrase,), {}))
        attempts.append((positional_base, {}))

        last_error: Optional[Exception] = None
        for args, kwargs in attempts:
            try:
                return WebsocketsResponse(*args, **kwargs)
            except TypeError as exc:
                last_error = exc
                continue

        if last_error is not None:
            raise last_error

        raise RuntimeError("Unable to instantiate websockets Response")

