from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .device_profile import DeviceProfile
from .ocr import OcrResult, OcrWord
from .screen_state import normalize_text


SAFE_ACTION_ORDER = (
    "cancel",
    "close",
    "dismiss",
    "later",
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
            min_matches = int(recovery.get("min_matches", 1))
            dynamic_tap_points = _dynamic_tap_points(recovery, ocr_result, text)
            if len(matched) < min_matches and not dynamic_tap_points:
                continue
            tap_points = dict(recovery.get("tap_points", {}))
            tap_points.update(dynamic_tap_points)
            candidates.append(
                RecoveryCandidate(
                    name=name,
                    anchors=anchors,
                    matched=matched,
                    max_attempts=int(recovery.get("max_attempts", 1)),
                    tap_points=tap_points,
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

        point = candidate.tap_points[action]
        self.device.tap(int(point["x"]), int(point["y"]))
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


def _dynamic_tap_points(recovery: dict[str, Any], ocr_result: OcrResult, normalized_text: str) -> dict[str, dict[str, Any]]:
    tap_points: dict[str, dict[str, Any]] = {}
    button_labels = recovery.get("button_labels", {})
    for action, labels in button_labels.items():
        point = _find_button_point(ocr_result.words, labels)
        if point is not None:
            tap_points[action] = point

    fallback_actions = recovery.get("fallback_actions", {})
    configured_taps = recovery.get("tap_points", {})
    for action, anchors in fallback_actions.items():
        if action in tap_points or action not in configured_taps:
            continue
        if any(normalize_text(anchor) in normalized_text for anchor in anchors):
            tap_points[action] = dict(configured_taps[action])
    return tap_points


def _find_button_point(words: list[OcrWord], labels: list[str]) -> dict[str, Any] | None:
    normalized_labels = {normalize_text(label) for label in labels}
    useful_words = [word for word in words if word.confidence >= 0 and normalize_text(word.text)]
    for size in (1, 2, 3):
        for index in range(0, len(useful_words) - size + 1):
            group = useful_words[index : index + size]
            if not _same_line(group):
                continue
            text = normalize_text("".join(word.text for word in group))
            if text not in normalized_labels:
                continue
            left = min(word.left for word in group)
            top = min(word.top for word in group)
            right = max(word.left + word.width for word in group)
            bottom = max(word.top + word.height for word in group)
            return {"x": (left + right) // 2, "y": (top + bottom) // 2, "source": "ocr_button"}
    return None


def _same_line(words: list[OcrWord]) -> bool:
    if not words:
        return False
    top = min(word.top for word in words)
    bottom = max(word.top + word.height for word in words)
    tallest = max(word.height for word in words)
    return bottom - top <= max(24, tallest * 2)
