"""Runtime helpers for running the WebRTC/YOLO stack on Jetson devices."""

from importlib import import_module
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover - import-time type hints only
    from .twelvelabs_client import TwelveLabsClient, TwelveLabsError
    from .twelvelabs_service import (
        AnalysisResult,
        AnalysisServiceError,
        RecordingNotFoundError,
        TwelveLabsAnalysisService,
    )

__all__ = [
    "AnalysisResult",
    "AnalysisServiceError",
    "RecordingNotFoundError",
    "TwelveLabsAnalysisService",
    "TwelveLabsClient",
    "TwelveLabsError",
]

_EXPORTS = {
    "TwelveLabsClient": (".twelvelabs_client", "TwelveLabsClient"),
    "TwelveLabsError": (".twelvelabs_client", "TwelveLabsError"),
    "AnalysisResult": (".twelvelabs_service", "AnalysisResult"),
    "AnalysisServiceError": (".twelvelabs_service", "AnalysisServiceError"),
    "RecordingNotFoundError": (".twelvelabs_service", "RecordingNotFoundError"),
    "TwelveLabsAnalysisService": (".twelvelabs_service", "TwelveLabsAnalysisService"),
}


def __getattr__(name: str) -> Any:
    try:
        module_name, attr_name = _EXPORTS[name]
    except KeyError as exc:  # pragma: no cover - mirrors default module behaviour
        raise AttributeError(f"module 'jetson' has no attribute {name!r}") from exc
    module = import_module(module_name, __name__)
    value = getattr(module, attr_name)
    globals()[name] = value
    return value
