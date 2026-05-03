from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .ocr import OcrResult
from .screen_state import normalize_text


@dataclass(frozen=True)
class RecoveryCandidate:
    name: str
    anchors: list[str]
    matched: list[str]
    max_attempts: int
    tap_points: dict[str, dict[str, Any]]

    @property
    def matched_any(self) -> bool:
        return bool(self.matched)

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "anchors": self.anchors,
            "matched": self.matched,
            "max_attempts": self.max_attempts,
            "tap_points": self.tap_points,
            "matched_any": self.matched_any,
        }


class RecoveryDetector:
    def __init__(self, recoveries: dict[str, Any]):
        self.recoveries = recoveries

    def detect(self, ocr_result: OcrResult) -> list[RecoveryCandidate]:
        text = normalize_text(ocr_result.text)
        candidates: list[RecoveryCandidate] = []
        for name, recovery in self.recoveries.items():
            anchors = list(recovery.get("anchors", []))
            matched = [anchor for anchor in anchors if normalize_text(anchor) in text]
            if not matched:
                continue
            candidates.append(
                RecoveryCandidate(
                    name=name,
                    anchors=anchors,
                    matched=matched,
                    max_attempts=int(recovery.get("max_attempts", 1)),
                    tap_points=dict(recovery.get("tap_points", {})),
                )
            )
        return candidates
