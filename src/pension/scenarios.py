from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from .device_profile import DeviceProfile, Point, Swipe
from .navigator import (
    NavigationContext,
    NavigationTarget,
    Navigator,
    normalize_account,
)

MTS_MAIN_COMPONENT = "com.truefriend.neosmartarenewal/com.truefriend.neosmartarenewal.ui.main.MTSMainActivity"


class TapDevice(Protocol):
    def tap(self, x: int | float, y: int | float) -> None:
        ...

    def start_activity(self, component: str, **kwargs) -> None:
        ...

    def swipe(
        self,
        x1: int | float,
        y1: int | float,
        x2: int | float,
        y2: int | float,
        duration_ms: int = 300,
    ) -> None:
        ...


@dataclass(frozen=True)
class ScenarioStep:
    name: str
    profile_key: str
    action: str
    point: Point | None = None
    swipe: Swipe | None = None

    def to_dict(self) -> dict:
        payload = {
            "name": self.name,
            "action": self.action,
            "profile_key": self.profile_key,
        }
        if self.point is not None:
            payload.update({"x": self.point.x, "y": self.point.y})
        if self.swipe is not None:
            payload.update(
                {
                    "x1": self.swipe.x1,
                    "y1": self.swipe.y1,
                    "x2": self.swipe.x2,
                    "y2": self.swipe.y2,
                    "duration_ms": self.swipe.duration_ms,
                }
            )
        return payload


class PensionScenarios:
    """High-level MTS flows that only reference named profile keys."""

    def __init__(self, device: TapDevice, profile: DeviceProfile | None = None):
        self.device = device
        self.profile = profile

    def open_app(self) -> None:
        self.device.start_activity(MTS_MAIN_COMPONENT)

    def open_balance(self) -> list[ScenarioStep]:
        return self._tap_sequence(self.open_balance_sequence())

    def open_order(self) -> list[ScenarioStep]:
        return self._tap_sequence(self.open_order_sequence())

    def select_account(self, account_type: str) -> list[ScenarioStep]:
        normalized = normalize_account(account_type)
        return self._tap_sequence(self.select_account_sequence(normalized))

    def select_balance_account(self, account_type: str) -> list[ScenarioStep]:
        normalized = normalize_account(account_type)
        return self._tap_sequence(self.select_balance_account_sequence(normalized))

    def ensure_balance_realtime(self) -> list[ScenarioStep]:
        return self._tap_sequence(self.ensure_balance_realtime_sequence())

    def open_order_search(self) -> list[ScenarioStep]:
        return self._tap_sequence(self.open_order_search_sequence())

    def select_first_search_result(self) -> list[ScenarioStep]:
        return self._tap_sequence(self.select_first_search_result_sequence())

    @staticmethod
    def open_balance_sequence() -> list[tuple[str, str, str]]:
        return _navigation_sequence(NavigationTarget(screen="잔고"))

    @staticmethod
    def open_order_sequence() -> list[tuple[str, str, str]]:
        return _navigation_sequence(NavigationTarget(screen="주문"))

    @staticmethod
    def select_account_sequence(account_type: str) -> list[tuple[str, str, str]]:
        return _navigation_sequence(NavigationTarget(screen="주문", account=account_type), context=NavigationContext(current_screen="주문"))

    @staticmethod
    def select_balance_account_sequence(account_type: str) -> list[tuple[str, str, str]]:
        return _navigation_sequence(NavigationTarget(screen="잔고", account=account_type), context=NavigationContext(current_screen="잔고"))

    @staticmethod
    def ensure_balance_realtime_sequence() -> list[tuple[str, str, str]]:
        return _navigation_sequence(NavigationTarget(screen="잔고", tab="실시간"), context=NavigationContext(current_screen="잔고"))

    @staticmethod
    def open_order_search_sequence() -> list[tuple[str, str, str]]:
        return [
            ("tap", "open_order_search", "order.add_product"),
        ]

    @staticmethod
    def select_first_search_result_sequence() -> list[tuple[str, str, str]]:
        return [
            ("tap", "select_first_search_result", "order_search.first_result"),
        ]

    @staticmethod
    def scenario_points(name: str, account_type: str | None = None) -> list[str]:
        if name == "open-balance":
            return [key for _, _, key in PensionScenarios.open_balance_sequence()]
        if name == "open-order":
            return [key for _, _, key in PensionScenarios.open_order_sequence()]
        if name == "select-account":
            return [key for _, _, key in PensionScenarios.select_account_sequence(account_type or "IRP")]
        if name == "select-balance-account":
            return [key for _, _, key in PensionScenarios.select_balance_account_sequence(account_type or "IRP")]
        if name == "ensure-balance-realtime":
            return [key for _, _, key in PensionScenarios.ensure_balance_realtime_sequence()]
        if name == "open-order-search":
            return [key for _, _, key in PensionScenarios.open_order_search_sequence()]
        if name == "select-first-search-result":
            return [key for _, _, key in PensionScenarios.select_first_search_result_sequence()]
        raise ValueError(f"Unknown scenario: {name}")

    @staticmethod
    def missing_points(profile: DeviceProfile, points: list[str]) -> list[str]:
        missing: list[str] = []
        for key in points:
            try:
                if key == "menu.scroll_to_order":
                    profile.swipe(key)
                else:
                    profile.tap_point(key)
            except (KeyError, ValueError):
                missing.append(key)
        return missing

    def _tap_sequence(self, sequence: list[tuple[str, str, str]]) -> list[ScenarioStep]:
        steps: list[ScenarioStep] = []
        if self.profile is None:
            raise ValueError("profile is required for tap scenarios")
        for action, name, profile_key in sequence:
            if action == "tap":
                point = self.profile.tap_point(profile_key)
                self.device.tap(point.x, point.y)
                steps.append(ScenarioStep(name=name, action=action, profile_key=profile_key, point=point))
            elif action == "swipe":
                gesture = self.profile.swipe(profile_key)
                self.device.swipe(gesture.x1, gesture.y1, gesture.x2, gesture.y2, gesture.duration_ms)
                steps.append(ScenarioStep(name=name, action=action, profile_key=profile_key, swipe=gesture))
            else:
                raise ValueError(f"Unknown scenario action: {action}")
        return steps


def _navigation_sequence(
    target: NavigationTarget,
    *,
    context: NavigationContext | None = None,
) -> list[tuple[str, str, str]]:
    return [
        step.as_sequence_item()
        for step in Navigator().plan(target, context=context)
        if step.profile_key is not None and not step.skipped
    ]
