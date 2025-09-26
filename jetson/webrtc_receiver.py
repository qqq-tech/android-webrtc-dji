"""WebRTC subscriber that forwards frames into the YOLO pipeline on Jetson."""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import logging
from typing import Optional
from urllib.parse import parse_qsl, urlencode, urlparse, urlunparse
from aiortc import RTCPeerConnection, RTCSessionDescription
from aiortc.sdp import candidate_from_sdp
from aiortc.contrib.signaling import BYE
from aiortc.mediastreams import MediaStreamError
from av import VideoFrame
import websockets

from websocket_server import DetectionBroadcaster
from yolo_processor import YoloProcessor


class WebSocketDetectionPublisher:
    """Pushes detection payloads to an external WebSocket broadcaster."""

    def __init__(self, url: str, reconnect_delay: float = 2.0) -> None:
        self._url = url
        self._reconnect_delay = reconnect_delay
        self._connection: Optional[websockets.WebSocketClientProtocol] = None
        self._lock = asyncio.Lock()

    async def start(self) -> None:
        await self._ensure_connection()

    async def stop(self) -> None:
        async with self._lock:
            if self._connection is not None:
                await self._connection.close()
                self._connection = None

    async def reset(self) -> None:
        """Drop the current connection so the next send reconnects."""

        await self._reset_connection()

    async def broadcast(self, payload: dict) -> None:
        try:
            message = json.dumps(payload)
        except TypeError:
            logging.exception("Failed to encode detection payload")
            return

        for attempt in range(2):
            websocket = await self._ensure_connection()
            if websocket is None:
                await asyncio.sleep(self._reconnect_delay)
                continue
            try:
                await websocket.send(message)
                return
            except Exception:
                logging.warning("Overlay WebSocket send failed, resetting connection")
                await self._reset_connection()

        logging.error("Dropping detection payload after connection failures")

    async def _ensure_connection(self) -> Optional[websockets.WebSocketClientProtocol]:
        async with self._lock:
            if self._connection is not None:
                if self._is_connection_open(self._connection):
                    return self._connection
                await self._reset_connection_locked()
            try:
                self._connection = await websockets.connect(self._url, max_queue=None)
                logging.info("Connected to overlay WebSocket %s", self._url)
            except Exception:
                logging.exception("Failed to connect to overlay WebSocket %s", self._url)
                self._connection = None
            return self._connection

    async def _reset_connection(self) -> None:
        async with self._lock:
            if self._connection is not None:
                try:
                    await self._connection.close()
                except Exception:
                    logging.debug("Error while closing overlay WebSocket", exc_info=True)
                finally:
                    self._connection = None

    async def _reset_connection_locked(self) -> None:
        """Reset connection without acquiring the public lock again."""

        if self._connection is None:
            return

        try:
            await self._connection.close()
        except Exception:
            logging.debug("Error while closing overlay WebSocket", exc_info=True)
        finally:
            self._connection = None

    @staticmethod
    def _is_connection_open(connection: websockets.WebSocketClientProtocol) -> bool:
        """Best-effort compatibility check for open WebSocket connections."""

        closed_attr = getattr(connection, "closed", None)
        if isinstance(closed_attr, bool):
            return not closed_attr

        if callable(closed_attr):  # pragma: no cover - legacy coroutine property
            try:
                closed_value = closed_attr()
            except TypeError:
                closed_value = None
            if isinstance(closed_value, bool):
                return not closed_value

        state = getattr(connection, "state", None)
        if state is not None:
            # ``ConnectionState`` exists in websockets>=12, older versions expose ``State``.
            state_name = getattr(state, "name", "").upper()
            if state_name:
                return state_name == "OPEN"

        return True


class WebRTCYOLOPipeline:
    def __init__(
        self,
        stream_id: str,
        signaling_host: str = "localhost",
        signaling_port: int = 8080,
        signaling_url: Optional[str] = None,
        detection_host: str = "0.0.0.0",
        detection_port: int = 8765,
        overlay_ws: Optional[str] = None,
        model_path: str = "yolov8n.pt",
        confidence_threshold: float = 0.25,
        recordings_dir: Optional[str] = None,
        reconnect_delay: float = 2.0,
    ) -> None:
        self._stream_id = stream_id
        self._signaling_url = self._build_signaling_url(
            stream_id=stream_id,
            explicit_url=signaling_url,
            host=signaling_host,
            port=signaling_port,
        )
        self._pc: Optional[RTCPeerConnection] = None
        self._yolo = YoloProcessor(model_path=model_path, confidence_threshold=confidence_threshold)
        self._sinks = []
        self._overlay_client: Optional[WebSocketDetectionPublisher] = None
        self._broadcaster: Optional[DetectionBroadcaster] = None

        if overlay_ws:
            self._overlay_client = WebSocketDetectionPublisher(overlay_ws)
            self._sinks.append(self._overlay_client)
        else:
            self._broadcaster = DetectionBroadcaster(
                host=detection_host,
                port=detection_port,
                recordings_dir=recordings_dir,
            )
            self._sinks.append(self._broadcaster)

        self._video_task: Optional[asyncio.Task] = None
        self._signaling: Optional[websockets.WebSocketClientProtocol] = None
        self._reconnect_delay = reconnect_delay
        self._closed = False
        self._restart_requested = False
        self._sinks_started = False
        self._sleep_task: Optional[asyncio.Task] = None

    async def run(self) -> None:
        if not self._sinks_started:
            for sink in self._sinks:
                await sink.start()
            self._sinks_started = True

        while not self._closed:
            try:
                await self._connect_once()
            except asyncio.CancelledError:
                raise
            except Exception:
                logging.exception("WebRTC session failed; attempting to reconnect")
            finally:
                await self._cleanup_connection()

            if self._closed:
                break

            logging.info("Reconnecting in %.1f seconds", self._reconnect_delay)
            self._sleep_task = asyncio.create_task(asyncio.sleep(self._reconnect_delay))
            try:
                await self._sleep_task
            except asyncio.CancelledError:
                if self._closed:
                    break
                raise
            finally:
                self._sleep_task = None

    async def _signaling_loop(self) -> None:
        assert self._signaling is not None
        async for raw_message in self._signaling:
            if raw_message == BYE:
                logging.info("Signaling server ended session")
                break
            try:
                message = json.loads(raw_message)
            except json.JSONDecodeError:
                logging.warning("Received invalid JSON from signaling server: %s", raw_message)
                continue

            if "error" in message:
                logging.error("Signaling error: %s", message)
                continue

            msg_type = message.get("type")
            if msg_type == "sdp":
                sdp = message.get("sdp")
                if not sdp:
                    logging.warning("SDP message missing payload: %s", message)
                    continue
                sdp_type = message.get("sdpType", "offer")
                desc = RTCSessionDescription(sdp=sdp, type=sdp_type)
                if self._pc is None:
                    logging.warning("Peer connection missing while processing SDP")
                    continue
                await self._pc.setRemoteDescription(desc)
                if desc.type == "offer":
                    answer = await self._pc.createAnswer()
                    await self._pc.setLocalDescription(answer)
                    await self._signaling.send(
                        json.dumps(
                            {
                                "type": "sdp",
                                "sdpType": answer.type,
                                "sdp": answer.sdp,
                            }
                        )
                    )
            elif msg_type == "ice":
                candidate_value = message.get("candidate")
                if not candidate_value:
                    if self._pc is not None:
                        await self._pc.addIceCandidate(None)
                    else:
                        logging.warning("Peer connection missing while clearing ICE candidate")
                    continue

                try:
                    rtc_candidate = candidate_from_sdp(candidate_value)
                except Exception:
                    logging.exception(
                        "Failed to parse ICE candidate from payload: %s", message
                    )
                    continue

                sdp_mid = message.get("sdpMid")
                if sdp_mid:
                    rtc_candidate.sdpMid = sdp_mid

                sdp_mline_index = message.get("sdpMLineIndex")
                if sdp_mline_index is not None:
                    try:
                        rtc_candidate.sdpMLineIndex = int(sdp_mline_index)
                    except (TypeError, ValueError):
                        logging.warning(
                            "Discarding invalid sdpMLineIndex in ICE candidate: %s",
                            message,
                        )

                if self._pc is None:
                    logging.warning("Peer connection missing while adding ICE candidate")
                    continue
                try:
                    await self._pc.addIceCandidate(rtc_candidate)
                except Exception as exc:
                    logging.warning("Failed to add ICE candidate: %s", exc)
            else:
                logging.debug("Ignoring unsupported message: %s", message)

    async def _consume_video(self, track) -> None:
        logging.info("Starting YOLO video loop")
        try:
            while True:
                frame: VideoFrame = await track.recv()
                image = frame.to_ndarray(format="bgr24")
                result = self._yolo.process(image)
                await asyncio.gather(*(sink.broadcast(result) for sink in self._sinks))
        except asyncio.CancelledError:
            logging.info("Video processing task cancelled")
        except MediaStreamError:
            logging.warning("Video stream interrupted; waiting for reconnection")
        except Exception:  # pragma: no cover - best effort logging
            logging.exception("Video processing failed")

    async def _connect_once(self) -> None:
        logging.info("Connecting to signaling server %s", self._signaling_url)
        self._restart_requested = False
        self._pc = RTCPeerConnection()
        pc = self._pc

        @pc.on("icecandidate")
        async def on_icecandidate(event):
            if self._signaling is None:
                return
            candidate = event.candidate
            if candidate is None:
                return
            payload = {
                "type": "ice",
                "candidate": candidate.candidate,
            }
            if candidate.sdpMid is not None:
                payload["sdpMid"] = candidate.sdpMid
            if candidate.sdpMLineIndex is not None:
                payload["sdpMLineIndex"] = candidate.sdpMLineIndex
            await self._signaling.send(json.dumps(payload))

        @pc.on("track")
        async def on_track(track):
            logging.info("Received remote track kind=%s", track.kind)
            if track.kind == "video":
                await self._cancel_video_task()
                self._video_task = asyncio.create_task(self._consume_video(track))
                self._video_task.add_done_callback(self._on_video_task_done)

        @pc.on("connectionstatechange")
        async def on_connectionstatechange():
            state = pc.connectionState
            logging.info("Peer connection state changed to %s", state)
            if state in {"failed", "closed"}:
                await self._trigger_restart()

        async with websockets.connect(self._signaling_url) as websocket:
            self._signaling = websocket
            try:
                await self._signaling_loop()
            finally:
                self._signaling = None

    async def _cleanup_connection(self) -> None:
        await self._cancel_video_task()
        if self._pc is not None:
            try:
                await self._pc.close()
            except Exception:
                logging.debug("Error while closing peer connection", exc_info=True)
            finally:
                self._pc = None

    async def _cancel_video_task(self) -> None:
        if self._video_task is None:
            return
        task = self._video_task
        self._video_task = None
        if not task.done():
            task.cancel()
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await task

    def _on_video_task_done(self, task: asyncio.Task) -> None:
        if task.cancelled():
            return
        if self._closed:
            return
        if self._video_task is task:
            self._video_task = None
        with contextlib.suppress(asyncio.CancelledError):
            try:
                task.result()
            except MediaStreamError:
                logging.warning("Video processing task ended due to stream error")
            except Exception:
                logging.exception("Video processing task ended with an error")
            else:
                logging.warning("Video processing task ended unexpectedly")
        asyncio.create_task(self._trigger_restart())

    async def _trigger_restart(self) -> None:
        if self._closed or self._restart_requested:
            return
        self._restart_requested = True
        await self._reset_detection_sinks()
        if self._signaling is not None:
            try:
                await self._signaling.close()
            except Exception:
                logging.debug("Error while closing signaling connection", exc_info=True)

    async def _reset_detection_sinks(self) -> None:
        for sink in self._sinks:
            reset = getattr(sink, "reset", None)
            if not callable(reset):
                continue
            try:
                await reset()
            except Exception:
                logging.exception("Failed to reset detection sink")

    async def close(self) -> None:
        self._closed = True
        if self._sleep_task is not None:
            self._sleep_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._sleep_task
            self._sleep_task = None
        if self._signaling is not None:
            try:
                await self._signaling.close()
            except Exception:
                logging.debug("Error while closing signaling connection", exc_info=True)
        if self._video_task is not None:
            self._video_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._video_task
            self._video_task = None
        await self._cleanup_connection()
        for sink in self._sinks:
            try:
                await sink.stop()
            except Exception:
                logging.exception("Failed to stop detection sink")

    @staticmethod
    def _build_signaling_url(
        stream_id: str,
        explicit_url: Optional[str],
        host: str,
        port: int,
    ) -> str:
        if explicit_url:
            parsed = urlparse(explicit_url)
            if not parsed.scheme:
                parsed = urlparse(f"ws://{explicit_url}")
        else:
            parsed = urlparse(f"ws://{host}:{port}/ws")

        query_params = dict(parse_qsl(parsed.query))
        query_params.update({"role": "subscriber", "streamId": stream_id})

        path = parsed.path or "/ws"
        if path == "/":
            path = "/ws"

        rebuilt = parsed._replace(path=path, query=urlencode(query_params))
        return urlunparse(rebuilt)


async def main() -> None:
    parser = argparse.ArgumentParser(description="Jetson WebRTC -> YOLO pipeline")
    parser.add_argument("stream_id", help="Identifier of the stream to subscribe to")
    parser.add_argument("--signaling-host", default="localhost")
    parser.add_argument("--signaling-port", type=int, default=8080)
    parser.add_argument(
        "--signaling-url",
        help=(
            "Complete WebSocket URL to the Pion relay. Overrides --signaling-host/--signaling-port"
        ),
    )
    parser.add_argument("--detection-host", default="0.0.0.0")
    parser.add_argument("--detection-port", type=int, default=8765)
    parser.add_argument(
        "--overlay-ws",
        help="WebSocket URL of the detection broadcaster (overrides --detection-host/--detection-port)",
    )
    parser.add_argument("--model", default="yolov8n.pt")
    parser.add_argument("--confidence", type=float, default=0.25)
    parser.add_argument(
        "--recordings-dir",
        help="Directory containing recorded stream files served over HTTP",
    )

    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s %(message)s")

    pipeline = WebRTCYOLOPipeline(
        stream_id=args.stream_id,
        signaling_host=args.signaling_host,
        signaling_port=args.signaling_port,
        signaling_url=args.signaling_url,
        detection_host=args.detection_host,
        detection_port=args.detection_port,
        overlay_ws=args.overlay_ws,
        model_path=args.model,
        confidence_threshold=args.confidence,
        recordings_dir=args.recordings_dir,
    )

    try:
        await pipeline.run()
    except KeyboardInterrupt:
        logging.info("Interrupted by user")
    finally:
        await pipeline.close()


if __name__ == "__main__":
    asyncio.run(main())
