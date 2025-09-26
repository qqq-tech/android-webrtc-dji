"""Utility WebSocket server to push YOLO detections to browser clients."""
from __future__ import annotations

import argparse
import contextlib
import importlib
import inspect
import asyncio
import http
import json
import logging
import mimetypes
import os
import signal
import ssl
from datetime import datetime, timezone
from urllib.parse import parse_qs, quote, unquote, urlsplit
from collections.abc import Iterable, Mapping
from pathlib import Path
from typing import TYPE_CHECKING, Any, Optional, Set, Union

import websockets

if TYPE_CHECKING:  # pragma: no cover - import only for static type checking
    from websockets.server import WebSocketServerProtocol
else:  # pragma: no cover - allow runtime without importing deprecated names
    WebSocketServerProtocol = Any  # type: ignore[assignment]

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
        recordings_dir: Optional[Union[str, Path]] = None,
        ssl_context: Optional[ssl.SSLContext] = None,
    ):
        self._host = host
        self._port = port
        self._path = path
        self._clients: Set[WebSocketServerProtocol] = set()
        self._servers: list[tuple[Any, str, int, Optional[ssl.SSLContext]]] = []
        self._recordings_mount_path = "/recordings"
        self._ssl_context = ssl_context
        if static_dir is None:
            default_static = Path(__file__).resolve().parents[1] / "browser"
            self._static_dir = default_static if default_static.exists() else None
        else:
            self._static_dir = Path(static_dir).resolve()
        self._recordings_dir = self._resolve_recordings_dir(recordings_dir)

    async def start(
        self,
        listeners: Optional[Iterable[tuple[str, int, Optional[ssl.SSLContext]]]] = None,
    ) -> None:
        if self._servers:
            return
        listener_specs: list[tuple[str, int, Optional[ssl.SSLContext]]]
        if listeners is None:
            listener_specs = [(self._host, self._port, self._ssl_context)]
        else:
            listener_specs = list(listeners)
        if not listener_specs:
            raise ValueError("At least one listener must be specified")

        seen_bindings: Set[tuple[str, int]] = set()
        for host, port, _ in listener_specs:
            binding = (host, port)
            if binding in seen_bindings:
                raise ValueError(f"Duplicate listener binding requested for {host}:{port}")
            seen_bindings.add(binding)

        base_logger = logging.getLogger("websockets.server")
        try:
            websockets_server_module = importlib.import_module("websockets.server")
        except ModuleNotFoundError:
            pass
        else:
            base_logger = getattr(websockets_server_module, "logger", base_logger)
        websocket_logger = _StaticRequestSilencingLogger(base_logger)
        servers: list[tuple[Any, str, int, Optional[ssl.SSLContext]]] = []
        for host, port, ssl_ctx in listener_specs:
            server = await websockets.serve(
                self._handler,
                host,
                port,
                process_request=self._process_http_request,
                logger=websocket_logger,
                ssl=ssl_ctx,
            )
            servers.append((server, host, port, ssl_ctx))

        self._servers = servers

    async def stop(self) -> None:
        if not self._servers:
            return
        for server, *_ in self._servers:
            server.close()
        await asyncio.gather(*(server.wait_closed() for server, *_ in self._servers))
        self._servers.clear()

    @property
    def active_listeners(self) -> list[tuple[str, int, bool]]:
        return [
            (host, port, ssl_ctx is not None)
            for _server, host, port, ssl_ctx in self._servers
        ]

    async def broadcast(self, payload: Any, sender: Optional[WebSocketServerProtocol] = None) -> None:
        if not self._clients:
            return

        if isinstance(payload, (bytes, str)):
            message = payload
        else:
            try:
                message = json.dumps(payload)
            except TypeError:
                logging.exception("Unable to serialize detection payload")
                return

        targets = [client for client in list(self._clients) if client is not sender]
        if not targets:
            return

        await asyncio.gather(*(self._send(client, message) for client in targets), return_exceptions=True)

    async def _handler(self, websocket: WebSocketServerProtocol):
        if self._path and getattr(websocket, "path", self._path) != self._path:
            await websocket.close(code=1008, reason="Invalid path")
            return

        self._clients.add(websocket)
        try:
            async for message in websocket:
                await self._relay_message(websocket, message)
        except Exception:
            logging.exception("WebSocket handler error")
        finally:
            self._clients.discard(websocket)

    async def _send(self, websocket: WebSocketServerProtocol, message: str) -> None:
        try:
            await websocket.send(message)
        except Exception:
            self._clients.discard(websocket)

    async def _relay_message(self, websocket: WebSocketServerProtocol, message: Any) -> None:
        if isinstance(message, (str, bytes)):
            await self.broadcast(message, sender=websocket)
            return

        try:
            serialised = json.dumps(message)
        except TypeError:
            address = getattr(websocket, "remote_address", None)
            logging.warning("Ignoring non-serialisable message from %s", address)
            return

        await self.broadcast(serialised, sender=websocket)

    async def _process_http_request(self, path_or_connection, request_headers=None):
        request_path, request_headers, query_string = self._normalise_http_request(
            path_or_connection, request_headers
        )

        upgrade_header = self._get_header_value(request_headers, "Upgrade")
        if "websocket" in upgrade_header.lower():
            return None

        recordings_response = self._handle_recordings_request(request_path, query_string)
        if recordings_response is not None:
            return recordings_response

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

    def _resolve_recordings_dir(
        self, recordings_dir: Optional[Union[str, Path]]
    ) -> Optional[Path]:
        candidates: list[Path] = []
        if recordings_dir is not None:
            candidates.append(Path(recordings_dir))

        env_dir = os.environ.get("RECORDINGS_DIR")
        if env_dir:
            candidates.append(Path(env_dir))

        script_root = Path(__file__).resolve().parents[1]
        candidates.extend(
            [
                Path.cwd() / "recordings",
                script_root / "recordings",
                script_root / "pion-server" / "recordings",
            ]
        )

        fallback: Optional[Path] = None
        seen: set[Path] = set()

        for candidate in candidates:
            try:
                resolved = candidate.expanduser().resolve()
            except Exception:
                continue
            if resolved in seen:
                continue
            seen.add(resolved)
            if fallback is None:
                fallback = resolved
            if resolved.exists() and resolved.is_dir():
                return resolved

        return fallback

    def _handle_recordings_request(self, request_path: str, query_string: str):
        mount = self._recordings_mount_path
        if not mount:
            return None

        if request_path == mount or request_path == f"{mount}/":
            try:
                params = parse_qs(query_string or "", keep_blank_values=False)
            except Exception:
                params = {}
            stream_id_values = params.get("streamId") or []
            stream_id = stream_id_values[0].strip() if stream_id_values else ""
            try:
                entries = self._list_recordings(stream_id)
            except Exception:
                logging.exception("Failed to list recordings")
                return self._json_response(
                    {"error": "failed to list recordings"},
                    status=http.HTTPStatus.INTERNAL_SERVER_ERROR,
                )
            return self._json_response(entries)

        prefix = f"{mount}/"
        if request_path.startswith(prefix):
            relative = request_path[len(prefix) :]
            return self._serve_recording_file(relative)

        return None

    def _list_recordings(self, stream_id: str) -> list[dict[str, Any]]:
        base = self._recordings_dir
        if base is None:
            return []

        entries: list[tuple[float, dict[str, Any]]] = []

        if stream_id:
            stream_ids = [stream_id]
        else:
            try:
                stream_ids = [
                    entry.name
                    for entry in sorted(base.iterdir(), key=lambda item: item.name)
                    if entry.is_dir()
                ]
            except FileNotFoundError:
                return []

        for stream in stream_ids:
            stream_dir = base / stream
            try:
                files = list(stream_dir.iterdir())
            except FileNotFoundError:
                continue
            for file_path in files:
                if not file_path.is_file():
                    continue
                if file_path.suffix.lower() != ".mp4":
                    continue
                try:
                    stat_result = file_path.stat()
                except FileNotFoundError:
                    continue
                modified = datetime.fromtimestamp(stat_result.st_mtime, tz=timezone.utc)
                payload = {
                    "streamId": stream,
                    "fileName": file_path.name,
                    "size": stat_result.st_size,
                    "modified": modified.isoformat().replace("+00:00", "Z"),
                    "URL": f"{self._recordings_mount_path}/{quote(stream)}/{quote(file_path.name)}",
                }
                entries.append((modified.timestamp(), payload))

        entries.sort(key=lambda item: item[0], reverse=True)
        return [payload for _, payload in entries]

    def _serve_recording_file(self, relative_path: str):
        base = self._recordings_dir
        if base is None:
            return self._build_http_response(http.HTTPStatus.NOT_FOUND, [], b"")

        safe_relative = relative_path.lstrip("/")
        candidate = (base / safe_relative).resolve()
        try:
            candidate.relative_to(base)
        except ValueError:
            return self._build_http_response(http.HTTPStatus.NOT_FOUND, [], b"")

        if not candidate.exists() or candidate.is_dir():
            return self._build_http_response(http.HTTPStatus.NOT_FOUND, [], b"")

        body = candidate.read_bytes()
        content_type, _ = mimetypes.guess_type(str(candidate))
        headers = [
            ("Content-Type", content_type or "application/octet-stream"),
            ("Content-Length", str(len(body))),
            ("Cache-Control", "no-store"),
        ]
        return self._build_http_response(http.HTTPStatus.OK, headers, body)

    def _json_response(self, payload: Any, status: http.HTTPStatus = http.HTTPStatus.OK):
        body = json.dumps(payload).encode("utf-8")
        headers = [
            ("Content-Type", "application/json; charset=utf-8"),
            ("Content-Length", str(len(body))),
            ("Cache-Control", "no-store"),
        ]
        return self._build_http_response(status, headers, body)

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

    def _normalise_http_request(
        self, path_or_connection, request_headers
    ) -> tuple[str, Any, str]:
        """Return a ``(path, headers, query)`` tuple across websockets versions."""

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

        request_path, query_string = self._coerce_request_target(request_path)

        return request_path, request_headers, query_string

    def _coerce_request_target(self, request_path: Any) -> tuple[str, str]:
        """Best-effort conversion of ``request_path`` to ``(path, query)``."""

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
            return "/", ""

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

        query = parsed.query or ""

        return normalised, query

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
            headers_value: Any = header_items
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

            try:
                return self._create_websockets_response(
                    status_code, headers_value, body, reason_phrase
                )
            except Exception:
                logging.debug(
                    "Falling back to tuple HTTP response for websockets", exc_info=True
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


def _create_ssl_context(
    certfile: Optional[str], keyfile: Optional[str]
) -> Optional[ssl.SSLContext]:
    if not certfile or not keyfile:
        return None

    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(certfile=certfile, keyfile=keyfile)
    return context


async def _serve_forever(args) -> None:
    ssl_context = _create_ssl_context(args.certfile, args.keyfile)
    broadcaster = DetectionBroadcaster(
        host=args.host,
        port=args.port,
        path=args.path,
        static_dir=args.static_dir,
        recordings_dir=args.recordings_dir,
        ssl_context=ssl_context,
    )

    listeners: list[tuple[str, int, Optional[ssl.SSLContext]]] = []
    listeners.append((args.host, args.port, ssl_context))
    if args.insecure_port is not None:
        listeners.append((args.host, args.insecure_port, None))

    await broadcaster.start(listeners=listeners)
    for host, port, is_secure in broadcaster.active_listeners:
        scheme = "wss" if is_secure else "ws"
        logging.info(
            "Detection broadcaster listening on %s://%s:%s%s",
            scheme,
            host,
            port,
            args.path,
        )

    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        with contextlib.suppress(NotImplementedError):
            loop.add_signal_handler(sig, stop_event.set)

    try:
        await stop_event.wait()
    finally:
        await broadcaster.stop()


def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="YOLO detection WebSocket broadcaster")
    parser.add_argument("--host", default="0.0.0.0", help="Interface to bind the WebSocket server to")
    parser.add_argument("--port", type=int, default=8765, help="Port for the WebSocket server")
    parser.add_argument("--path", default="/detections", help="WebSocket path for detection messages")
    parser.add_argument(
        "--static-dir",
        help="Optional directory with dashboard assets to serve over HTTP",
    )
    parser.add_argument(
        "--recordings-dir",
        help="Directory containing MP4 recordings exposed via the /recordings mount",
    )
    parser.add_argument(
        "--certfile",
        help="Enable TLS using the provided certificate file (PEM). Requires --keyfile.",
    )
    parser.add_argument(
        "--keyfile",
        help="TLS private key file (PEM) used alongside --certfile.",
    )
    parser.add_argument(
        "--insecure-port",
        type=int,
        help="Optional additional port that serves plain WS alongside TLS.",
    )
    return parser


def main(argv: Optional[list[str]] = None) -> None:
    parser = _build_arg_parser()
    args = parser.parse_args(argv)

    if (args.certfile is None) != (args.keyfile is None):
        parser.error("--certfile and --keyfile must be provided together to enable TLS")

    logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s %(message)s")

    try:
        asyncio.run(_serve_forever(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":  # pragma: no cover - CLI entry point
    main()

