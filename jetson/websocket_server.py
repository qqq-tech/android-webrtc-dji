"""Utility WebSocket server to push YOLO detections to browser clients."""
from __future__ import annotations

import asyncio
import json
from typing import Optional, Set

import websockets
from websockets.server import WebSocketServer, WebSocketServerProtocol


class DetectionBroadcaster:
    """Simple WebSocket pub-sub helper for detection results."""

    def __init__(self, host: str = "0.0.0.0", port: int = 8765, path: str = "/detections"):
        self._host = host
        self._port = port
        self._path = path
        self._clients: Set[WebSocketServerProtocol] = set()
        self._server: Optional[WebSocketServer] = None

    async def start(self) -> None:
        if self._server is not None:
            return
        self._server = await websockets.serve(self._handler, self._host, self._port, path=self._path)

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

