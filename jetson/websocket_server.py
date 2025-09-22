"""Utility WebSocket server to push YOLO detections to browser clients."""
from __future__ import annotations

import asyncio
import http
import json
import mimetypes
from pathlib import Path
from typing import Optional, Set, Union

import websockets
from websockets.server import WebSocketServer, WebSocketServerProtocol


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
        self._server = await websockets.serve(
            self._handler,
            self._host,
            self._port,
            process_request=self._process_http_request,
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

    async def _process_http_request(self, path: str, request_headers):
        upgrade_header = request_headers.get("Upgrade", "")
        if "websocket" in upgrade_header.lower():
            return None

        if self._static_dir is not None:
            file_path = self._resolve_static_path(path)
            if file_path is not None:
                body = file_path.read_bytes()
                content_type, _ = mimetypes.guess_type(str(file_path))
                headers = [
                    ("Content-Type", content_type or "application/octet-stream"),
                    ("Content-Length", str(len(body))),
                    ("Cache-Control", "no-cache"),
                ]
                return http.HTTPStatus.OK, headers, body

        body = (
            "Detection broadcaster is running. Connect with a WebSocket client at "
            f"{self._path}."
        ).encode("utf-8")
        headers = [
            ("Content-Type", "text/plain; charset=utf-8"),
            ("Content-Length", str(len(body))),
            ("Cache-Control", "no-cache"),
        ]
        return http.HTTPStatus.OK, headers, body

    def _resolve_static_path(self, request_path: str) -> Optional[Path]:
        if self._static_dir is None:
            return None

        if request_path in {"", "/"}:
            candidate = self._static_dir / "dashboard.html"
        else:
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

