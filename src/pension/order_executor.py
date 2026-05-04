from __future__ import annotations

import json
import re
import time
from dataclasses import dataclass
from pathlib import Path

from .adb_device import AdbCommandError, AdbDevice
from .config import PensionConfig
from .device_profile import DeviceProfile
from .events import AccountPasswordPopupHandler
from .login_bridge import LoginBridge, LoginCommandResult
from .ocr import TesseractOcr
from .routes import InformationContext, InformationRouteRegistry, RouteStep
from .run_artifacts import RunArtifacts
from .run_modes import decide_submit_permission
from .screen_capture import ScreenCapture
from .screen_state import ScreenStateChecker
from .screen_values import ExpectedOrder, verify_order_text


class MtsLoginRequiredError(RuntimeError):
    pass


@dataclass(frozen=True)
class OrderRequest:
    account: str
    side: str
    quantity: int
    symbol_code: str | None = None
    symbol_name: str | None = None
    expected_amount: int | None = None
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
                "expected_amount": self.request.expected_amount,
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


@dataclass(frozen=True)
class QuantityInputResult:
    expected_quantity: int
    text: str
    passed: bool
    source_image: str
    crop_image: str
    ocr_json: str

    def to_dict(self) -> dict:
        return {
            "expected_quantity": self.expected_quantity,
            "text": self.text,
            "passed": self.passed,
            "source_image": self.source_image,
            "crop_image": self.crop_image,
            "ocr_json": self.ocr_json,
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
        context = self._open_order_route_with_login_recovery(request.route_name, account)
        self._require_current_screen("주문", label="order-route-complete")
        self._select_symbol(request)
        self._select_market_price()
        quantity_result = self._input_quantity(request.quantity, psm=request.psm)
        if not quantity_result.passed:
            raise RuntimeError("quantity input verification failed")

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

        if decision.may_open_confirmation:
            self._tap_profile_point("order.submit")
            confirmation_verification = self._verify_confirmation_popup(request)
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
            if decision.may_tap_final_submit:
                self._tap_profile_point("order_confirm.submit")
                submitted = True
                time.sleep(2)
                if request.read_filled_results:
                    filled_results_text = self.read_filled_results(context)
                    filled_results_read = True
            else:
                self._tap_profile_point("order_confirm.cancel")

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

    def _open_order_route_with_login_recovery(self, route_name: str, account: str) -> InformationContext:
        attempted_login_recovery = False
        while True:
            try:
                self._ensure_not_login_activity()
                return self._open_order_route(route_name, account)
            except MtsLoginRequiredError as exc:
                if attempted_login_recovery:
                    raise RuntimeError(f"login recovery already attempted: {exc}") from exc
                attempted_login_recovery = True
                result = self._recover_certificate_login()
                if not result.success or result.state != "LOGGED_IN":
                    raise RuntimeError(f"certificate login recovery failed: {result.state}: {result.message}") from exc
                if not self._wait_for_post_login_screen():
                    raise RuntimeError("certificate login recovery did not leave the login screen") from exc

    def _recover_certificate_login(self) -> LoginCommandResult:
        bridge = LoginBridge(self.device)
        status = bridge.status()
        self.artifacts.write_json("login-recovery-status", status.to_dict())
        if not status.ready:
            return LoginCommandResult(
                request_id="",
                command="LOGIN",
                finished=True,
                success=False,
                state="HELPER_NOT_READY",
                message="Android login helper is not ready",
            )
        result = bridge.send_command_and_wait("LOGIN")
        self.artifacts.write_json("login-recovery-result", result.to_dict())
        return result

    def _wait_for_post_login_screen(self, *, timeout_seconds: float = 30, interval_seconds: float = 2) -> bool:
        deadline = time.monotonic() + timeout_seconds
        attempts: list[dict] = []
        while time.monotonic() < deadline:
            try:
                self._ensure_not_login_activity()
            except MtsLoginRequiredError as exc:
                attempts.append({"ready": False, "reason": str(exc)})
                time.sleep(interval_seconds)
                continue

            try:
                payload = self._inspect_current_state_payload(label="post-login")
            except MtsLoginRequiredError as exc:
                attempts.append({"ready": False, "reason": str(exc)})
                time.sleep(interval_seconds)
                continue
            if payload is not None:
                attempts.append(
                    {
                        "ready": True,
                        "screen_key": payload.get("screen_key"),
                        "current_screen": payload.get("current_screen"),
                    }
                )
                self.artifacts.write_json("post-login-wait", {"success": True, "attempts": attempts})
                return True
            attempts.append({"ready": False, "reason": "screencap_or_ocr_failed"})
            time.sleep(interval_seconds)
        self.artifacts.write_json("post-login-wait", {"success": False, "attempts": attempts})
        return False

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
        context = self._prepare_route_start_context()
        steps = registry.plan(route_name, account=account, context=context)
        for step in steps:
            if step.action == "읽기":
                break
            if step.action == "이벤트":
                self._require_current_screen("주문", label="before-account-password-event")
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

    def _require_current_screen(self, expected_screen: str, *, label: str) -> None:
        self._ensure_not_login_activity()
        payload = self._inspect_current_state_payload(label=label)
        current_screen = payload.get("current_screen") if payload else None
        if current_screen != expected_screen:
            raise RuntimeError(f"expected {expected_screen} screen, got {current_screen or 'UNKNOWN'}")

    def _ensure_not_login_activity(self) -> None:
        if not hasattr(self.device, "get_current_activity"):
            return
        try:
            activity = self.device.get_current_activity()
        except Exception:
            return
        if "login" in activity.activity.casefold():
            raise MtsLoginRequiredError(f"MTS login activity is active: {activity.component}")

    def _inspect_current_route_context(self) -> InformationContext:
        payload = self._inspect_current_state_payload()
        if payload and payload.get("current_screen") in {"주문", "잔고", "메뉴", "홈"}:
            return InformationContext.from_dict(payload)
        return InformationContext()

    def _prepare_route_start_context(self) -> InformationContext:
        for _ in range(2):
            payload = self._inspect_current_state_payload()
            if payload and payload.get("current_screen") in {"주문", "잔고", "메뉴", "홈"}:
                return InformationContext.from_dict(payload)
            if not payload or payload.get("screen_key") not in {"search", "balance_holding_detail"}:
                break
            if hasattr(self.device, "press_back"):
                self.device.press_back()
                time.sleep(1)
        return InformationContext()

    def _inspect_current_state_payload(self, *, label: str = "route-start") -> dict | None:
        try:
            image = self.artifacts.next_path(f"{label}-source", ext="png")
            state_json = self.artifacts.next_path(f"{label}-state", ext="json")
            self.capture.capture(image)
            result = self.ocr.recognize(image, psm=6)
            snapshot = ScreenStateChecker().inspect(self.profile, result)
            payload = snapshot.to_dict()
            state_json.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            return payload
        except Exception as exc:
            if self._is_blocked_screenshot_error(exc):
                raise MtsLoginRequiredError(f"MTS screen is blocking screenshots: {exc}") from exc
            self.artifacts.write_json("route-start-inspect-failed", {"error": str(exc)})
        return None

    @staticmethod
    def _is_blocked_screenshot_error(exc: Exception) -> bool:
        message = str(exc).casefold()
        if isinstance(exc, AdbCommandError):
            return "screencap returned no data" in message or "block screenshots" in message
        return "blocked screenshot" in message or "screencap returned no data" in message

    def _select_symbol(self, request: OrderRequest) -> None:
        self._tap_profile_point("order.add_product")
        self._tap_profile_point("order_search.search_field")
        self.device.input_text(request.search_text)
        self.device.keyevent(66)
        time.sleep(1)
        self._tap_profile_point("order_search.first_result")

    def _select_market_price(self) -> None:
        self._tap_profile_point("order.market_price")

    def _input_quantity(self, quantity: int, *, psm: int = 7) -> QuantityInputResult:
        self._tap_profile_point("order.quantity_input")
        for _ in range(12):
            self.device.keyevent(67)
        self.device.input_text(str(quantity))
        self.device.keyevent(66)
        time.sleep(1)
        return self._verify_quantity_input(quantity, psm=psm)

    def _verify_quantity_input(self, quantity: int, *, psm: int) -> QuantityInputResult:
        image = self.artifacts.next_path("quantity-input-source", ext="png")
        crop = self.artifacts.next_path("quantity-input", ext="png")
        ocr_json = self.artifacts.next_path("quantity-input", ext="json")
        decision_json = self.artifacts.next_path("quantity-input-verification", ext="json")
        self.capture.capture(image)
        self.capture.crop(image, self.profile.region("order.quantity_input"), crop)
        result = self.ocr.recognize(crop, psm=psm)
        result.save_json(ocr_json)
        passed = quantity_text_matches(result.text, quantity)
        quantity_result = QuantityInputResult(
            expected_quantity=quantity,
            text=result.text,
            passed=passed,
            source_image=str(image),
            crop_image=str(crop),
            ocr_json=str(ocr_json),
        )
        decision_json.write_text(
            json.dumps(quantity_result.to_dict(), indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        return quantity_result

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
                amount=request.expected_amount,
                price_type="시장가",
            ),
        ).to_dict()
        decision_json.write_text(json.dumps(verification, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        return verification

    def _verify_confirmation_popup(self, request: OrderRequest) -> dict:
        image = self.artifacts.next_path("order-confirm-source", ext="png")
        screen_ocr_json = self.artifacts.next_path("order-confirm-screen", ext="json")
        crop = self.artifacts.next_path("order-confirm-summary", ext="png")
        summary_ocr_json = self.artifacts.next_path("order-confirm-summary", ext="json")
        decision_json = self.artifacts.next_path("order-confirm-verification", ext="json")

        self.capture.capture(image)
        screen_ocr = self.ocr.recognize(image, psm=request.psm)
        screen_ocr.save_json(screen_ocr_json)
        anchor_check = ScreenStateChecker().check_anchors(
            "order_confirm",
            self.profile.anchors("order_confirm"),
            screen_ocr,
        )

        self.capture.crop(image, self.profile.region("order_confirm.summary"), crop)
        summary_ocr = self.ocr.recognize(crop, psm=request.psm)
        summary_ocr.save_json(summary_ocr_json)
        summary_verification = verify_order_text(
            summary_ocr.text,
            ExpectedOrder(
                account_type=request.account,
                symbol_code=request.symbol_code,
                symbol_name=request.symbol_name,
                side=request.route_name,
                quantity=request.quantity,
                amount=request.expected_amount,
                price_type="시장가",
            ),
        ).to_dict()
        verification = {
            "target": "order_confirm",
            "passed": anchor_check.passed and summary_verification["passed"],
            "anchor_check": anchor_check.to_dict(),
            "summary_verification": summary_verification,
            "source_image": str(image),
            "summary_crop": str(crop),
            "screen_ocr_json": str(screen_ocr_json),
            "summary_ocr_json": str(summary_ocr_json),
        }
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


def quantity_text_matches(text: str, expected_quantity: int) -> bool:
    return str(expected_quantity) in re.sub(r"[^0-9]", " ", text).split()
