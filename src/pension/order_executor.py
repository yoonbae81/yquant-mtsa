from __future__ import annotations

import json
import time
from dataclasses import dataclass
from pathlib import Path

from .adb_device import AdbDevice
from .config import PensionConfig
from .device_profile import DeviceProfile
from .events import AccountPasswordPopupHandler
from .ocr import TesseractOcr
from .routes import InformationContext, InformationRouteRegistry, RouteStep
from .run_artifacts import RunArtifacts
from .run_modes import decide_submit_permission
from .screen_capture import ScreenCapture
from .screen_values import ExpectedOrder, verify_order_text


@dataclass(frozen=True)
class OrderRequest:
    account: str
    side: str
    quantity: int
    symbol_code: str | None = None
    symbol_name: str | None = None
    mode: str = "dry-run"
    explicit_real_run: bool = False
    read_filled_results: bool = True
    psm: int = 6

    @property
    def route_name(self) -> str:
        return normalize_order_side(self.side)

    @property
    def search_text(self) -> str:
        text = self.symbol_code or self.symbol_name
        if not text:
            raise ValueError("symbol_code or symbol_name is required")
        return text


@dataclass(frozen=True)
class OrderExecutionResult:
    request: OrderRequest
    verified: bool
    submitted: bool
    confirmation_verified: bool
    filled_results_read: bool
    decision: dict
    verification: dict
    confirmation_verification: dict | None
    filled_results_text: str | None
    artifacts_dir: str

    def to_dict(self) -> dict:
        return {
            "request": {
                "account": self.request.account,
                "side": self.request.route_name,
                "quantity": self.request.quantity,
                "symbol_code": self.request.symbol_code,
                "symbol_name": self.request.symbol_name,
                "mode": self.request.mode,
                "read_filled_results": self.request.read_filled_results,
            },
            "verified": self.verified,
            "submitted": self.submitted,
            "confirmation_verified": self.confirmation_verified,
            "filled_results_read": self.filled_results_read,
            "decision": self.decision,
            "verification": self.verification,
            "confirmation_verification": self.confirmation_verification,
            "filled_results_text": self.filled_results_text,
            "artifacts_dir": self.artifacts_dir,
        }


class OrderExecutor:
    def __init__(
        self,
        device: AdbDevice,
        profile: DeviceProfile,
        config: PensionConfig,
        *,
        capture: ScreenCapture | None = None,
        ocr: TesseractOcr | None = None,
        artifacts: RunArtifacts | None = None,
    ):
        self.device = device
        self.profile = profile
        self.config = config
        self.capture = capture or ScreenCapture(device)
        self.ocr = ocr or TesseractOcr()
        self.artifacts = artifacts or RunArtifacts()

    def execute(self, request: OrderRequest) -> OrderExecutionResult:
        if request.quantity <= 0:
            raise ValueError("quantity must be positive")
        account = request.account.strip().upper()
        context = self._open_order_route(request.route_name, account)
        self._select_symbol(request)
        self._select_market_price()
        self._input_quantity(request.quantity)

        verification = self._verify_current_order(request, label="order-before-submit")
        decision = decide_submit_permission(
            request.mode,
            verified=verification["passed"],
            explicit_real_run=request.explicit_real_run,
            config_allows_real_run=self.config.allow_real_run,
        )
        submitted = False
        confirmation_verification: dict | None = None
        confirmation_verified = False
        filled_results_text: str | None = None
        filled_results_read = False

        if decision.may_tap_submit:
            self._tap_profile_point("order.submit")
            confirmation_verification = self._verify_current_order(request, label="order-confirm")
            confirmation_verified = confirmation_verification["passed"]
            if not confirmation_verified:
                decision_payload = {**decision.to_dict(), "blocked_after_submit": "confirmation_verification_failed"}
                return self._result(
                    request,
                    verification=verification,
                    decision=decision_payload,
                    submitted=False,
                    confirmation_verified=False,
                    confirmation_verification=confirmation_verification,
                    filled_results_text=None,
                    filled_results_read=False,
                )
            self._tap_profile_point("order_confirm.submit")
            submitted = True
            time.sleep(2)
            if request.read_filled_results:
                filled_results_text = self.read_filled_results(context)
                filled_results_read = True

        return self._result(
            request,
            verification=verification,
            decision=decision.to_dict(),
            submitted=submitted,
            confirmation_verified=confirmation_verified,
            confirmation_verification=confirmation_verification,
            filled_results_text=filled_results_text,
            filled_results_read=filled_results_read,
        )

    def read_filled_results(self, context: InformationContext | None = None) -> str:
        context = context or InformationContext(current_screen="주문")
        steps = InformationRouteRegistry().plan("체결결과", account=context.account, context=context)
        for step in steps:
            if step.action == "읽기":
                continue
            self._execute_route_step(step)
            self._delay_for_step(step)
        image = self.artifacts.next_path("filled-results-source", ext="png")
        crop = self.artifacts.next_path("filled-results", ext="png")
        ocr_json = self.artifacts.next_path("filled-results", ext="json")
        self.capture.capture(image)
        self.capture.crop(image, self.profile.region("order.filled_results"), crop)
        result = self.ocr.recognize(crop, psm=6)
        result.save_json(ocr_json)
        return result.text

    def _open_order_route(self, route_name: str, account: str) -> InformationContext:
        registry = InformationRouteRegistry()
        context = InformationContext()
        steps = registry.plan(route_name, account=account, context=context)
        for step in steps:
            if step.action == "읽기":
                break
            if step.action == "이벤트":
                event_json = self.artifacts.next_path("account-password-event", ext="json")
                result = AccountPasswordPopupHandler(self.device, self.profile, self.config, ocr=self.ocr).handle(
                    InformationContext(current_screen="주문", account=account, tab=route_name),
                    output_json=event_json,
                )
                context = result.apply_to_context(context)
                if result.detected and not result.handled:
                    raise RuntimeError(f"account password event failed: {result.reason}")
                continue
            self._execute_route_step(step)
            self._delay_for_step(step)
        return registry.context_after(route_name, account=account, context=context)

    def _select_symbol(self, request: OrderRequest) -> None:
        self._tap_profile_point("order.add_product")
        self._tap_profile_point("order_search.search_field")
        self.device.input_text(request.search_text)
        self.device.keyevent(66)
        time.sleep(1)
        self._tap_profile_point("order_search.first_result")

    def _select_market_price(self) -> None:
        self._tap_profile_point("order.market_price")

    def _input_quantity(self, quantity: int) -> None:
        self._tap_profile_point("order.quantity_input")
        for _ in range(12):
            self.device.keyevent(67)
        self.device.input_text(str(quantity))
        self.device.keyevent(66)
        time.sleep(1)

    def _verify_current_order(self, request: OrderRequest, *, label: str) -> dict:
        image = self.artifacts.next_path(f"{label}-source", ext="png")
        ocr_json = self.artifacts.next_path(label, ext="json")
        decision_json = self.artifacts.next_path(f"{label}-verification", ext="json")
        self.capture.capture(image)
        result = self.ocr.recognize(image, psm=request.psm)
        result.save_json(ocr_json)
        verification = verify_order_text(
            result.text,
            ExpectedOrder(
                account_type=request.account,
                symbol_code=request.symbol_code,
                symbol_name=request.symbol_name,
                side=request.route_name,
                quantity=request.quantity,
                price_type="시장가",
            ),
        ).to_dict()
        decision_json.write_text(json.dumps(verification, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        return verification

    def _execute_route_step(self, step: RouteStep) -> None:
        if step.skipped or step.profile_key is None or step.action in {"화면확인", "계좌확인", "탭확인", "펼침확인"}:
            return
        if step.action == "탭":
            self._tap_profile_point(step.profile_key)
            return
        if step.action == "스와이프":
            swipe = self.profile.swipe(step.profile_key)
            self.device.swipe(swipe.x1, swipe.y1, swipe.x2, swipe.y2, swipe.duration_ms)
            return
        raise ValueError(f"Unsupported route step action: {step.action}")

    def _tap_profile_point(self, profile_key: str) -> None:
        point = self.profile.tap_point(profile_key)
        self.device.tap(point.x, point.y)
        time.sleep(self.profile.post_delay_ms(profile_key) / 1000)

    def _delay_for_step(self, step: RouteStep) -> None:
        if not step.skipped and step.profile_key:
            time.sleep(self.profile.post_delay_ms(step.profile_key) / 1000)

    def _result(
        self,
        request: OrderRequest,
        *,
        verification: dict,
        decision: dict,
        submitted: bool,
        confirmation_verified: bool,
        confirmation_verification: dict | None,
        filled_results_text: str | None,
        filled_results_read: bool,
    ) -> OrderExecutionResult:
        result = OrderExecutionResult(
            request=request,
            verified=bool(verification["passed"]),
            submitted=submitted,
            confirmation_verified=confirmation_verified,
            filled_results_read=filled_results_read,
            decision=decision,
            verification=verification,
            confirmation_verification=confirmation_verification,
            filled_results_text=filled_results_text,
            artifacts_dir=str(self.artifacts.root),
        )
        self.artifacts.write_manifest(result.to_dict())
        return result


def normalize_order_side(side: str) -> str:
    normalized = side.strip().lower()
    if normalized in {"buy", "b", "매수"}:
        return "매수"
    if normalized in {"sell", "s", "매도"}:
        return "매도"
    raise ValueError("side must be buy/sell or 매수/매도")
