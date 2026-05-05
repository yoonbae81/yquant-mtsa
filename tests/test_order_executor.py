import tempfile
import unittest
from pathlib import Path

from PIL import Image

import pension.order_executor as order_executor_module
from pension.adb_device import ActivityInfo
from pension.config import PensionConfig
from pension.device_profile import DeviceProfile
from pension.login_bridge import LoginCommandResult
from pension.ocr import OcrResult
from pension.order_executor import (
    MarketRoundTripRequest,
    MtsLoginRequiredError,
    OrderExecutionResult,
    OrderExecutor,
    OrderRequest,
    quantity_text_matches,
)
from pension.routes import InformationContext, RouteStep
from pension.run_artifacts import RunArtifacts
from pension.run_modes import decide_submit_permission


class FakeDevice:
    def __init__(self, activity=None):
        self.taps = []
        self.keyevents = []
        self.text_inputs = []
        self.back_count = 0
        self.activity = activity
        self.force_stopped = []
        self.started = []

    def tap(self, x, y):
        self.taps.append((x, y))

    def keyevent(self, key):
        self.keyevents.append(key)

    def input_text(self, text):
        self.text_inputs.append(text)

    def get_current_activity(self):
        if self.activity is None:
            raise RuntimeError("no activity")
        return self.activity

    def press_back(self):
        self.back_count += 1

    def force_stop(self, package):
        self.force_stopped.append(package)

    def start_activity(self, component):
        self.started.append(component)

    def shell(self, *args, **kwargs):
        return ""

    def run(self, args, **kwargs):
        return ""


class FakeCapture:
    def __init__(self, fail_count=0):
        self.fail_count = fail_count

    def capture(self, output_path):
        if self.fail_count:
            self.fail_count -= 1
            raise RuntimeError("blocked screenshot")
        output = Path(output_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (1080, 2340), "white").save(output)
        return output

    def crop(self, image_path, region, output_path):
        output = Path(output_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (region.w, region.h), "white").save(output)
        return output


class FakeOcr:
    def __init__(self, text):
        self.texts = list(text if isinstance(text, list) else [text])

    def recognize(self, image_path, psm=6):
        if len(self.texts) == 1:
            text = self.texts[0]
        else:
            text = self.texts.pop(0)
        return OcrResult(str(image_path), "kor+eng", text, [])


class FakeLoginBridge:
    instances = []

    def __init__(self, device):
        self.device = device
        self.sent = []
        FakeLoginBridge.instances.append(self)

    def status(self):
        class Status:
            ready = True

            def to_dict(self):
                return {"ready": True}

        return Status()

    def send_command_and_wait(self, command, *, timeout=90):
        self.sent.append(command)
        return LoginCommandResult(
            request_id="pension-test",
            command=command,
            finished=True,
            success=True,
            state="LOGGED_IN",
            message="ok",
        )


def make_profile():
    return DeviceProfile(
        {
            "device": {"width": 1080, "height": 2340},
            "screens": {
                "home": {
                    "anchors": {
                        "required": ["상품/연금"],
                        "optional": ["MY", "국내", "해외", "혜택", "홈"],
                        "forbidden": ["오류"],
                        "min_score": 0.65,
                    },
                    "tap_points": {},
                    "regions": {},
                },
                "order": {
                    "tap_points": {
                        "submit": {"x": 740, "y": 1985, "post_delay_ms": 0},
                        "quantity_input": {"x": 740, "y": 1295, "post_delay_ms": 0},
                    },
                    "regions": {
                        "quantity_input": {"x": 445, "y": 1250, "w": 590, "h": 90},
                        "filled_results": {"x": 60, "y": 760, "w": 960, "h": 920},
                    },
                },
                "search": {
                    "anchors": {
                        "required": ["통합검색"],
                        "optional": ["최근 검색어"],
                        "forbidden": [],
                        "min_score": 0.65,
                    },
                    "tap_points": {},
                    "regions": {},
                },
                "order_confirm": {
                    "anchors": {
                        "required": ["주문", "확인"],
                        "optional": ["시장가", "매수", "매도", "수량", "종목"],
                        "forbidden": ["오류"],
                        "min_score": 0.65,
                    },
                    "tap_points": {
                        "submit": {"x": 810, "y": 1985},
                        "cancel": {"x": 270, "y": 1985},
                    },
                    "regions": {
                        "summary": {"x": 60, "y": 760, "w": 960, "h": 920},
                    },
                },
            },
            "recoveries": {
                "common_popup": {
                    "anchors": ["닫기", "오늘 하루"],
                    "tap_points": {"close": {"x": 101, "y": 202}},
                    "allowed_actions": ["close", "confirm", "back"],
                    "max_attempts": 1,
                },
                "dangerous_overlay": {
                    "anchors": ["최종 확인"],
                    "tap_points": {
                        "confirm": {"x": 740, "y": 1985},
                        "submit": {"x": 810, "y": 1985},
                    },
                    "allowed_actions": ["submit"],
                    "max_attempts": 1,
                },
            },
        }
    )


class OrderExecutorQuantityTests(unittest.TestCase):
    def test_quantity_text_matches_isolated_number(self):
        self.assertTrue(quantity_text_matches("주문수량 12 주", 12))
        self.assertFalse(quantity_text_matches("주문수량 120 주", 12))

    def test_input_quantity_verifies_cropped_region(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr("주문수량 3"),
                artifacts=RunArtifacts(temp_dir, run_id="quantity-ok"),
            )

            result = executor._input_quantity(3)

        self.assertTrue(result.passed)
        self.assertEqual(device.taps, [(740, 1295)])
        self.assertEqual(device.text_inputs, ["3"])
        self.assertEqual(device.keyevents, [67] * 12 + [66])

    def test_input_quantity_rejects_unmatched_value(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            executor = OrderExecutor(
                FakeDevice(),
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr("주문수량 2"),
                artifacts=RunArtifacts(temp_dir, run_id="quantity-fail"),
            )

            result = executor._input_quantity(3)
            decision_files = list((Path(temp_dir) / "quantity-fail").glob("*quantity-input-verification.json"))

        self.assertFalse(result.passed)
        self.assertEqual(len(decision_files), 1)

    def test_confirmation_popup_verifies_anchor_and_summary(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            executor = OrderExecutor(
                FakeDevice(),
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(
                    [
                        "주문 확인 매수 시장가 수량 종목",
                        "IRP TIGER 미국S&P500 매수 시장가 수량 3 금액 45000",
                    ]
                ),
                artifacts=RunArtifacts(temp_dir, run_id="confirm-ok"),
            )

            result = executor._verify_confirmation_popup(
                OrderRequest(
                    account="IRP",
                    side="매수",
                    symbol_name="TIGER 미국S&P500",
                    quantity=3,
                    expected_amount=45000,
                )
            )

        self.assertTrue(result["passed"])
        self.assertTrue(result["anchor_check"]["passed"])
        self.assertTrue(result["summary_verification"]["passed"])

    def test_confirmation_popup_rejects_missing_summary_field(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            executor = OrderExecutor(
                FakeDevice(),
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(
                    [
                        "주문 확인 매수 시장가 수량 종목",
                        "IRP TIGER 미국S&P500 매수 시장가 수량 2",
                    ]
                ),
                artifacts=RunArtifacts(temp_dir, run_id="confirm-fail"),
            )

            result = executor._verify_confirmation_popup(
                OrderRequest(account="IRP", side="매수", symbol_name="TIGER 미국S&P500", quantity=3)
            )

        self.assertFalse(result["passed"])
        self.assertIn("quantity:3", result["summary_verification"]["missing"])

    def test_confirm_run_cancels_after_verified_confirmation(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(
                    [
                        "주문 확인 매수 시장가 수량 종목",
                        "IRP TIGER 미국S&P500 매수 시장가 수량 1",
                    ]
                ),
                artifacts=RunArtifacts(temp_dir, run_id="confirm-cancel"),
            )

            decision = decide_submit_permission("confirm-run", verified=True)
            self.assertTrue(decision.may_open_confirmation)
            self.assertFalse(decision.may_tap_final_submit)
            executor._tap_profile_point("order.submit")
            confirmation = executor._verify_confirmation_popup(
                OrderRequest(account="IRP", side="매수", symbol_name="TIGER 미국S&P500", quantity=1)
            )
            if confirmation["passed"] and not decision.may_tap_final_submit:
                executor._tap_profile_point("order_confirm.cancel")

        self.assertEqual(device.taps[-2:], [(740, 1985), (270, 1985)])

    def test_manual_submit_leaves_verified_confirmation_open(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(
                    [
                        "주문 확인 매수 시장가 수량 종목",
                        "IRP TIGER 화장품 228790 매수 시장가 수량 1",
                    ]
                ),
                artifacts=RunArtifacts(temp_dir, run_id="manual-submit"),
            )

            decision = decide_submit_permission("manual-submit", verified=True)
            executor._tap_profile_point("order.submit")
            confirmation = executor._verify_confirmation_popup(
                OrderRequest(
                    account="IRP",
                    side="매수",
                    symbol_code="228790",
                    symbol_name="TIGER 화장품",
                    quantity=1,
                )
            )
            if confirmation["passed"] and decision.should_cancel_confirmation:
                executor._tap_profile_point("order_confirm.cancel")

        self.assertTrue(confirmation["passed"])
        self.assertEqual(device.taps, [(740, 1985)])

    def test_inspects_current_order_context_before_planning(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            executor = OrderExecutor(
                FakeDevice(),
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr("개인형IRP 비밀번호 매수 주문금액 원"),
                artifacts=RunArtifacts(temp_dir, run_id="route-context"),
            )

            context = executor._inspect_current_route_context()

        self.assertEqual(context.current_screen, "주문")
        self.assertEqual(context.account, "IRP")
        self.assertEqual(context.tab, "매수")

    def test_confirm_recovery_uses_recovery_coordinates(self):
        device = FakeDevice()
        profile = make_profile()
        profile.data["recoveries"]["dangerous_overlay"]["tap_points"]["confirm"] = {"x": 333, "y": 444}
        profile.data["recoveries"]["dangerous_overlay"]["allowed_actions"] = ["confirm"]

        from pension.recovery import RecoveryHandler
        handler = RecoveryHandler(device, profile)
        ocr_result = OcrResult(image_path="screen.png", language="kor+eng", text="최종 확인", words=[])
        result = handler.handle(ocr_result)

        self.assertTrue(result.handled)
        self.assertEqual(result.action, "confirm")
        self.assertEqual(device.taps, [(333, 444)])

    def test_route_step_recovers_popup_then_tap_proceeds(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr("이벤트 안내 닫기"),
                artifacts=RunArtifacts(temp_dir, run_id="route-step-recovery"),
            )
            step = RouteStep(name="수량 입력", action="탭", profile_key="order.quantity_input")

            recovery = executor._recover_before_route_step(step, label="order-route-quantity-input")
            executor._execute_route_step(step)
            recovery_files = list((Path(temp_dir) / "route-step-recovery").glob("*order-route-quantity-input-recovery.json"))

        self.assertTrue(recovery["handled"])
        self.assertEqual(recovery["action"], "close")
        self.assertEqual(device.taps, [(101, 202), (740, 1295)])
        self.assertTrue(recovery_files)

    def test_open_order_route_recovers_popup_before_route_tap_then_proceeds(self):
        class FakeRegistry:
            def plan(self, route_name, account=None, context=None):
                return [RouteStep(name="수량 입력", action="탭", profile_key="order.quantity_input")]

            def context_after(self, route_name, account=None, context=None):
                return InformationContext(current_screen="주문", account=account, tab=route_name)

        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(["MY 국내 해외 상품/연금 혜택 홈", "이벤트 안내 닫기"]),
                artifacts=RunArtifacts(temp_dir, run_id="open-route-recovery"),
            )
            old_registry = order_executor_module.InformationRouteRegistry
            order_executor_module.InformationRouteRegistry = FakeRegistry
            try:
                context = executor._open_order_route("매수", "IRP")
            finally:
                order_executor_module.InformationRouteRegistry = old_registry
            recovery_files = list((Path(temp_dir) / "open-route-recovery").glob("*order-route-수량 입력-recovery.json"))

        self.assertEqual(context.current_screen, "주문")
        self.assertEqual(device.taps, [(101, 202), (740, 1295)])
        self.assertTrue(recovery_files)

    def test_read_filled_results_recovers_popup_before_route_tap_then_reads(self):
        class FakeRegistry:
            def plan(self, route_name, account=None, context=None):
                return [
                    RouteStep(name="체결 탭", action="탭", profile_key="order.quantity_input"),
                    RouteStep(name="체결결과 읽기", action="읽기", profile_key="order.filled_results"),
                ]

        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(["이벤트 안내 닫기", "체결결과 IRP 매수 체결수량 1"]),
                artifacts=RunArtifacts(temp_dir, run_id="filled-route-recovery"),
            )
            old_registry = order_executor_module.InformationRouteRegistry
            order_executor_module.InformationRouteRegistry = FakeRegistry
            try:
                text = executor.read_filled_results(InformationContext(current_screen="주문", account="IRP"))
            finally:
                order_executor_module.InformationRouteRegistry = old_registry
            recovery_files = list((Path(temp_dir) / "filled-route-recovery").glob("*filled-results-route-체결 탭-recovery.json"))

        self.assertIn("체결결과", text)
        self.assertEqual(device.taps, [(101, 202), (740, 1295)])
        self.assertTrue(recovery_files)

    def test_route_recovery_detected_unhandled_raises_and_no_tap(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr("최종 확인"),
                artifacts=RunArtifacts(temp_dir, run_id="route-unsafe-submit"),
            )

            class FakeRegistryUnsafe:
                def plan(self, route_name, account=None, context=None):
                    return [RouteStep(name="수량 입력", action="탭", profile_key="order.quantity_input")]

                def context_after(self, route_name, account=None, context=None):
                    return InformationContext(current_screen="주문", account=account, tab=route_name)

            old_registry = order_executor_module.InformationRouteRegistry
            order_executor_module.InformationRouteRegistry = FakeRegistryUnsafe
            try:
                with self.assertRaisesRegex(RuntimeError, "recovery detected but not handled"):
                    executor._open_order_route("매수", "IRP")
            finally:
                order_executor_module.InformationRouteRegistry = old_registry
            # No taps should have occurred due to fail-closed behavior
            self.assertEqual(device.taps, [])

    def test_route_step_no_recovery_proceeds_normally(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr("일반 상태"),
                artifacts=RunArtifacts(temp_dir, run_id="route-no-recovery"),
            )
            step = RouteStep(name="수량 입력", action="탭", profile_key="order.quantity_input")

            recovery = executor._recover_before_route_step(step, label="order-route-quantity-input-no-recovery")
            # No recovery detected; should proceed without exception
            self.assertFalse(recovery["detected"])
            executor._execute_route_step(step)

        self.assertEqual(device.taps, [(740, 1295)])

    def test_order_verification_does_not_recover_popup_mismatch(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr("이벤트 안내 닫기"),
                artifacts=RunArtifacts(temp_dir, run_id="verify-no-recovery"),
            )

            result = executor._verify_current_order(
                OrderRequest(account="IRP", side="매수", symbol_name="TIGER 미국S&P500", quantity=3),
                label="order-before-submit",
            )

        self.assertFalse(result["passed"])
        self.assertEqual(device.taps, [])

    def test_confirmation_verification_does_not_recover_popup_mismatch(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(["이벤트 안내 닫기", "이벤트 안내 닫기"]),
                artifacts=RunArtifacts(temp_dir, run_id="confirm-no-recovery"),
            )

            result = executor._verify_confirmation_popup(
                OrderRequest(account="IRP", side="매수", symbol_name="TIGER 미국S&P500", quantity=3)
            )

        self.assertFalse(result["passed"])
        self.assertEqual(device.taps, [])

    def test_rejects_order_execution_when_login_activity_is_foreground(self):
        executor = OrderExecutor(
            FakeDevice(
                ActivityInfo(
                    package="com.truefriend.neosmartarenewal",
                    activity="com.truefriend.neosmartarenewal.ui.login.loginmain.LoginMainActivity",
                    component="com.truefriend.neosmartarenewal/.ui.login.loginmain.LoginMainActivity",
                )
            ),
            make_profile(),
            PensionConfig({}),
            capture=FakeCapture(),
            ocr=FakeOcr(""),
        )

        with self.assertRaisesRegex(MtsLoginRequiredError, "login activity"):
            executor._ensure_not_login_activity()

    def test_prepare_route_start_presses_back_from_search_before_menu_route(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(
                    [
                        "통합검색 최근 검색어 OTP/모바일OTP",
                        "MY 국내 해외 상품/연금 혜택 홈",
                    ]
                ),
                artifacts=RunArtifacts(temp_dir, run_id="route-search-back"),
            )

            context = executor._prepare_route_start_context()

        self.assertEqual(device.back_count, 1)
        self.assertEqual(context.current_screen, "홈")

    def test_requires_order_screen_before_symbol_input(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            executor = OrderExecutor(
                FakeDevice(),
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr("통합검색 최근 검색어"),
                artifacts=RunArtifacts(temp_dir, run_id="not-order-screen"),
            )

            with self.assertRaisesRegex(RuntimeError, "expected 주문 screen"):
                executor._require_current_screen("주문", label="order-route-complete")

    def test_screen_requirement_recovers_known_overlay_then_revalidates(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(
                    [
                        "이벤트 안내 닫기",
                        "이벤트 안내 닫기",
                        "개인형IRP 비밀번호 매수 주문금액 원",
                    ]
                ),
                artifacts=RunArtifacts(temp_dir, run_id="screen-recovery-ok"),
            )

            executor._require_current_screen("주문", label="order-route-complete")
            recovery_files = list((Path(temp_dir) / "screen-recovery-ok").glob("*order-route-complete-recovery.json"))

        self.assertEqual(device.taps, [(101, 202)])
        self.assertTrue(recovery_files)

    def test_screen_requirement_fails_unknown_overlay(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(["알 수 없는 안내", "알 수 없는 안내"]),
                artifacts=RunArtifacts(temp_dir, run_id="screen-recovery-unknown"),
            )

            with self.assertRaisesRegex(RuntimeError, "expected 주문 screen"):
                executor._require_current_screen("주문", label="order-route-complete")

        self.assertEqual(device.taps, [])

    def test_screen_recovery_never_taps_order_or_final_submit_points(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr(["최종 확인", "최종 확인"]),
                artifacts=RunArtifacts(temp_dir, run_id="screen-recovery-dangerous"),
            )

            with self.assertRaisesRegex(RuntimeError, "expected 주문 screen"):
                executor._require_current_screen("주문", label="order-route-complete")

        self.assertNotIn((740, 1985), device.taps)
        self.assertNotIn((810, 1985), device.taps)
        self.assertEqual(device.taps, [])

    def test_screen_requirement_rejects_login_activity_before_screencap(self):
        device = FakeDevice(
            ActivityInfo(
                package="com.truefriend.neosmartarenewal",
                activity="com.truefriend.neosmartarenewal.ui.login.loginmain.LoginMainActivity",
                component="com.truefriend.neosmartarenewal/.ui.login.loginmain.LoginMainActivity",
            )
        )
        executor = OrderExecutor(
            device,
            make_profile(),
            PensionConfig({}),
            capture=FakeCapture(),
            ocr=FakeOcr("주문 매수 비밀번호"),
        )

        with self.assertRaisesRegex(MtsLoginRequiredError, "login activity"):
            executor._require_current_screen("주문", label="before-account-password-event")

    def test_screen_requirement_treats_blocked_screenshot_as_login_required(self):
        executor = OrderExecutor(
            FakeDevice(),
            make_profile(),
            PensionConfig({}),
            capture=FakeCapture(fail_count=1),
            ocr=FakeOcr(""),
        )

        with self.assertRaisesRegex(MtsLoginRequiredError, "blocking screenshots"):
            executor._require_current_screen("주문", label="before-account-password-event")

    def test_login_recovery_calls_helper_and_retries_route(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(),
                ocr=FakeOcr("MY 국내 해외 상품/연금 혜택 홈"),
                artifacts=RunArtifacts(temp_dir, run_id="login-recovery"),
            )
            calls = {"route": 0}

            def fake_open_route(route_name, account):
                calls["route"] += 1
                if calls["route"] == 1:
                    raise MtsLoginRequiredError("login")
                return __import__("pension.routes", fromlist=["InformationContext"]).InformationContext(
                    current_screen="주문",
                    account=account,
                    tab=route_name,
                )

            old_bridge = order_executor_module.LoginBridge
            order_executor_module.LoginBridge = FakeLoginBridge
            try:
                executor._open_order_route = fake_open_route
                context = executor._open_order_route_with_login_recovery("매수", "IRP")
            finally:
                order_executor_module.LoginBridge = old_bridge

        self.assertEqual(calls["route"], 2)
        self.assertEqual(context.current_screen, "주문")
        self.assertEqual(FakeLoginBridge.instances[-1].sent, ["LOGIN"])

    def test_post_login_wait_requires_capture_available(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            executor = OrderExecutor(
                FakeDevice(),
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(fail_count=1),
                ocr=FakeOcr("MY 국내 해외 상품/연금 혜택 홈"),
                artifacts=RunArtifacts(temp_dir, run_id="post-login-wait"),
            )

            ready = executor._wait_for_post_login_screen(timeout_seconds=3, interval_seconds=0)

        self.assertTrue(ready)

    def test_post_login_wait_restarts_mts_after_repeated_blocked_screenshots(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            device = FakeDevice()
            executor = OrderExecutor(
                device,
                make_profile(),
                PensionConfig({}),
                capture=FakeCapture(fail_count=2),
                ocr=FakeOcr("MY 국내 해외 상품/연금 혜택 홈"),
                artifacts=RunArtifacts(temp_dir, run_id="post-login-restart"),
            )
            executor._restart_mts_after_login = lambda: (
                device.force_stop("com.truefriend.neosmartarenewal"),
                device.start_activity("com.truefriend.neosmartarenewal/com.truefriend.neosmartarenewal.ui.main.MTSMainActivity"),
            )

            ready = executor._wait_for_post_login_screen(timeout_seconds=3, interval_seconds=0)

        self.assertTrue(ready)
        self.assertEqual(device.force_stopped, ["com.truefriend.neosmartarenewal"])
        self.assertEqual(
            device.started,
            ["com.truefriend.neosmartarenewal/com.truefriend.neosmartarenewal.ui.main.MTSMainActivity"],
        )

    def test_filled_results_verification_requires_symbol_side_and_quantity(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            executor = OrderExecutor(
                FakeDevice(),
                make_profile(),
                PensionConfig({}),
                artifacts=RunArtifacts(temp_dir, run_id="filled-results"),
            )

            result = executor._verify_filled_results_text(
                OrderRequest(
                    account="IRP",
                    side="매수",
                    symbol_code="228790",
                    symbol_name="TIGER 화장품",
                    quantity=1,
                ),
                "체결결과 IRP 228790 TIGER 화장품 매수 시장가 체결수량 1",
            )

        self.assertTrue(result["passed"])

    def test_market_roundtrip_blocks_sell_when_real_buy_filled_result_is_unverified(self):
        executor = OrderExecutor(FakeDevice(), make_profile(), PensionConfig({}))
        calls = []

        def fake_execute(request):
            calls.append(request.side)
            return OrderExecutionResult(
                request=request,
                verified=True,
                submitted=True,
                confirmation_verified=True,
                filled_results_read=True,
                filled_results_verified=False,
                decision={"may_open_confirmation": True, "may_tap_final_submit": True},
                verification={"passed": True},
                confirmation_verification={"passed": True},
                filled_results_verification={"passed": False},
                filled_results_text="체결결과 없음",
                artifacts_dir="runs/test",
            )

        executor.execute = fake_execute
        result = executor.execute_market_round_trip(
            MarketRoundTripRequest(
                account="IRP",
                mode="real-run",
                explicit_real_run=True,
                acknowledge_live_trade=True,
            )
        )

        self.assertFalse(result.completed)
        self.assertEqual(result.blocked_reason, "buy_filled_results_not_verified")
        self.assertEqual(calls, ["매수"])

    def test_market_roundtrip_real_run_requires_acknowledgement(self):
        executor = OrderExecutor(FakeDevice(), make_profile(), PensionConfig({}))

        with self.assertRaisesRegex(ValueError, "acknowledge_live_trade"):
            executor.execute_market_round_trip(
                MarketRoundTripRequest(account="IRP", mode="real-run", explicit_real_run=True)
            )

    def test_market_roundtrip_real_run_rejects_non_default_symbol_or_quantity(self):
        executor = OrderExecutor(FakeDevice(), make_profile(), PensionConfig({}))

        with self.assertRaisesRegex(ValueError, "228790"):
            executor.execute_market_round_trip(
                MarketRoundTripRequest(
                    account="IRP",
                    symbol_code="360750",
                    mode="real-run",
                    explicit_real_run=True,
                    acknowledge_live_trade=True,
                )
            )
        with self.assertRaisesRegex(ValueError, "quantity must be 1"):
            executor.execute_market_round_trip(
                MarketRoundTripRequest(
                    account="IRP",
                    quantity=2,
                    mode="real-run",
                    explicit_real_run=True,
                    acknowledge_live_trade=True,
                )
            )


if __name__ == "__main__":
    unittest.main()
