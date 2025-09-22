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
from aiortc.contrib.signaling import BYE
from av import VideoFrame
import websockets

from websocket_server import DetectionBroadcaster
from yolo_processor import YoloProcessor


class WebRTCYOLOPipeline:
    def __init__(
        self,
        stream_id: str,
        signaling_host: str = "localhost",
        signaling_port: int = 8080,
        signaling_url: Optional[str] = None,
        detection_host: str = "0.0.0.0",
        detection_port: int = 8765,
        model_path: str = "yolov8n.pt",
        confidence_threshold: float = 0.25,
    ) -> None:
        self._stream_id = stream_id
        self._signaling_url = self._build_signaling_url(
            stream_id=stream_id,
            explicit_url=signaling_url,
            host=signaling_host,
            port=signaling_port,
        )
        self._pc = RTCPeerConnection()
        self._yolo = YoloProcessor(model_path=model_path, confidence_threshold=confidence_threshold)
        self._broadcaster = DetectionBroadcaster(host=detection_host, port=detection_port)
        self._video_task: Optional[asyncio.Task] = None
        self._signaling: Optional[websockets.WebSocketClientProtocol] = None

    async def run(self) -> None:
        logging.info("Connecting to signaling server %s", self._signaling_url)
        await self._broadcaster.start()

        async with websockets.connect(self._signaling_url) as websocket:
            self._signaling = websocket

            @self._pc.on("icecandidate")
            async def on_icecandidate(event):
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
                await websocket.send(json.dumps(payload))

            @self._pc.on("track")
            async def on_track(track):
                logging.info("Received remote track kind=%s", track.kind)
                if track.kind == "video":
                    if self._video_task is not None:
                        self._video_task.cancel()
                    self._video_task = asyncio.create_task(self._consume_video(track))

            await self._signaling_loop()

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
                candidate = message.get("candidate")
                if not candidate:
                    continue
                ice_payload = {
                    "candidate": candidate,
                }
                if "sdpMid" in message:
                    ice_payload["sdpMid"] = message["sdpMid"]
                if "sdpMLineIndex" in message:
                    ice_payload["sdpMLineIndex"] = message["sdpMLineIndex"]
                try:
                    await self._pc.addIceCandidate(ice_payload)
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
                await self._broadcaster.broadcast(result)
        except asyncio.CancelledError:
            logging.info("Video processing task cancelled")
        except Exception:  # pragma: no cover - best effort logging
            logging.exception("Video processing failed")

    async def close(self) -> None:
        if self._video_task is not None:
            self._video_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._video_task
            self._video_task = None
        await self._pc.close()
        await self._broadcaster.stop()

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
    parser.add_argument("--model", default="yolov8n.pt")
    parser.add_argument("--confidence", type=float, default=0.25)

    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s %(message)s")

    pipeline = WebRTCYOLOPipeline(
        stream_id=args.stream_id,
        signaling_host=args.signaling_host,
        signaling_port=args.signaling_port,
        signaling_url=args.signaling_url,
        detection_host=args.detection_host,
        detection_port=args.detection_port,
        model_path=args.model,
        confidence_threshold=args.confidence,
    )

    try:
        await pipeline.run()
    except KeyboardInterrupt:
        logging.info("Interrupted by user")
    finally:
        await pipeline.close()


if __name__ == "__main__":
    asyncio.run(main())
