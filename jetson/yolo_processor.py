"""YOLO wrapper for running inference on Jetson devices.

The module encapsulates the model loading and inference logic so it can be
reused by the WebRTC receiver. The output complies with the JSON structure
used by the dashboard and the Android sender.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

import numpy as np

try:
    from ultralytics import YOLO
except ImportError as exc:  # pragma: no cover - optional dependency
    raise RuntimeError(
        "The ultralytics package is required to run the YOLO processor. "
        "Install dependencies from requirements.txt"
    ) from exc


@dataclass
class Detection:
    bbox: List[float]
    label: str
    confidence: float

    def to_dict(self) -> Dict[str, Any]:
        return {
            "bbox": self.bbox,
            "class": self.label,
            "confidence": self.confidence,
        }


class YoloProcessor:
    """Runs YOLO inference on frames received from the WebRTC stream."""

    def __init__(self, model_path: str = "yolov8n.pt", confidence_threshold: float = 0.25):
        self.model = YOLO(model_path)
        self.confidence_threshold = confidence_threshold
        self.frame_id = 0
        # YOLO models expose the label map on the model instance.
        self._label_map = getattr(self.model.model, "names", {})

    def process(self, frame: np.ndarray) -> Dict[str, Any]:
        """Runs inference on a BGR frame and returns a serialized detection result."""
        if frame is None:
            raise ValueError("Frame is None")

        # YOLO expects RGB input.
        rgb_frame = frame[:, :, ::-1]
        prediction = self.model.predict(rgb_frame, verbose=False)[0]

        detections: List[Detection] = []
        for box in prediction.boxes:
            confidence = float(box.conf[0])
            if confidence < self.confidence_threshold:
                continue

            cls_idx = int(box.cls[0])
            label = self._label_map.get(cls_idx, str(cls_idx))
            x1, y1, x2, y2 = box.xyxy[0].tolist()
            detections.append(
                Detection(
                    bbox=[float(x1), float(y1), float(x2 - x1), float(y2 - y1)],
                    label=label,
                    confidence=confidence,
                )
            )

        self.frame_id += 1
        return {
            "frame_id": self.frame_id,
            "detections": [d.to_dict() for d in detections],
            "timestamp": int(time.time() * 1000),
        }

    def warmup(self, frame_shape: Optional[tuple[int, int, int]] = None) -> None:
        """Runs an optional warmup inference to load weights into memory."""
        if frame_shape is None:
            frame_shape = (640, 640, 3)
        dummy = np.zeros(frame_shape, dtype=np.uint8)
        self.process(dummy)
        self.frame_id = 0
