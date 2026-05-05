from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .device_profile import DeviceProfile
from .ocr import OcrResult
from .screen_state import normalize_text


SAFE_ACTION_ORDER = (
    "cancel",
    "close",
    "dismiss",
    "today_skip",
    "dont_show_today",
    "never_show",
    "extend_session",
    "extend",
    "confirm",
    "back",
)


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


@dataclass(frozen=True)
class RecoveryResult:
    detected: bool
    handled: bool
    candidate: str | None
    action: str | None
    attempts: int
    reason: str

    def to_dict(self) -> dict:
        return {
            "detected": self.detected,
            "handled": self.handled,
            "candidate": self.candidate,
            "action": self.action,
            "attempts": self.attempts,
            "reason": self.reason,
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


class RecoveryHandler:
    def __init__(self, device: Any, profile: DeviceProfile, *, detector: RecoveryDetector | None = None):
        self.device = device
        self.profile = profile
        self.detector = detector or RecoveryDetector(profile.data.get("recoveries", {}))
        self.attempts: dict[str, int] = {}

    def handle(self, ocr_result: OcrResult) -> RecoveryResult:
        candidates = self.detector.detect(ocr_result)
        if not candidates:
            return RecoveryResult(False, False, None, None, 0, "no_recovery_candidate")

        for candidate in candidates:
            result = self._handle_candidate(candidate)
            if result.handled or result.reason != "no_allowed_action":
                return result

        return RecoveryResult(True, False, candidates[0].name, None, 0, "no_allowed_action")

    def _handle_candidate(self, candidate: RecoveryCandidate) -> RecoveryResult:
        attempts = self.attempts.get(candidate.name, 0)
        if attempts >= candidate.max_attempts:
            return RecoveryResult(True, False, candidate.name, None, attempts, "max_attempts_exceeded")

        action = self._select_action(candidate)
        if action is None:
            return RecoveryResult(True, False, candidate.name, None, attempts, "no_allowed_action")

        attempts += 1
        self.attempts[candidate.name] = attempts
        if action == "back":
            if not hasattr(self.device, "press_back"):
                return RecoveryResult(True, False, candidate.name, action, attempts, "back_unavailable")
            self.device.press_back()
            return RecoveryResult(True, True, candidate.name, action, attempts, "handled")

        point = self.profile.recovery_tap_point(f"{candidate.name}.{action}")
        self.device.tap(point.x, point.y)
        return RecoveryResult(True, True, candidate.name, action, attempts, "handled")

    def _select_action(self, candidate: RecoveryCandidate) -> str | None:
        recovery = self.profile.recovery(candidate.name)
        allowed_actions = set(recovery.get("allowed_actions", []))
        available_taps = set(candidate.tap_points)
        for action in SAFE_ACTION_ORDER:
            if action not in allowed_actions:
                continue
            if action == "back":
                return action
            if action in available_taps:
                return action
        return None
