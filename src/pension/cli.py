from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

from .config import PensionConfig
from .events import AccountPasswordPopupHandler
from .login_bridge import LoginBridge
from .adb_device import AdbDevice
from .device_profile import DeviceProfile, Region
from .keypad import RandomNumericKeypadMapper
from .holdings_grid import BalanceHoldingsGridParser, HoldingRow, load_ocr_json, merge_holding_rows
from .holdings_service import HoldingsTickerResolver
from .ocr import TesseractOcr
from .order_executor import MarketRoundTripRequest, OrderExecutor, OrderRequest, normalize_order_side
from .recovery import RecoveryDetector
from .routes import InformationContext, InformationRouteRegistry, RouteStep
from .run_artifacts import RunArtifacts
from .run_modes import decide_submit_permission
from .screen_capture import ScreenCapture
from .scenarios import PensionScenarios
from .screen_values import ExpectedBalance, ExpectedOrder, verify_balance_text, verify_order_text
from .screen_state import ScreenStateChecker
from .ticker_cache import DEFAULT_TICKER_CACHE_PATH, HoldingTickerCache


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="mtsa", description="ADB 기반 MTS 자동화 하위 도구")
    parser.add_argument("--adb", default=None, help="adb executable path")
    parser.add_argument("--serial", default=None, help="Android device serial")
    parser.add_argument("--profile", default=None, help="device profile JSON path")
    parser.add_argument("--config", default="config.yaml", help="private config YAML path")

    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("devices", help="list connected devices")
    subparsers.add_parser("device-info", help="print selected device info")
    subparsers.add_parser("current-activity", help="print the current resumed Android activity")

    subparsers.add_parser("login-status", help="check Android login helper readiness")
    login = subparsers.add_parser("login", help="send LOGIN to the Android login helper after readiness check")
    login.add_argument("--wait", action="store_true", help="wait for helper command result")
    login.add_argument("--timeout", type=float, default=90)

    login_command = subparsers.add_parser("login-command", help="send a command to the Android login helper")
    login_command.add_argument("login_command", help="command, for example LOGIN")
    login_command.add_argument("--wait", action="store_true", help="wait for helper command result")
    login_command.add_argument("--timeout", type=float, default=90)

    capture = subparsers.add_parser("capture", help="capture current screen")
    capture.add_argument("output", nargs="?", default=None, help="output PNG path")

    ocr = subparsers.add_parser("ocr", help="run OCR for an image")
    ocr.add_argument("image", help="input image path")
    ocr.add_argument("--json", dest="json_output", default=None, help="write OCR JSON")
    ocr.add_argument("--psm", type=int, default=6)

    crop = subparsers.add_parser("crop", help="crop a named profile region")
    crop.add_argument("image", help="input image path")
    crop.add_argument("region", help="region name, for example order.account_row")
    crop.add_argument("output", help="output image path")

    read_region = subparsers.add_parser("read-region", help="capture/crop/OCR a named profile region")
    read_region.add_argument("region", help="region name, for example order.holding_quantity")
    read_region.add_argument("--image", default=None, help="existing full screenshot; captures if omitted")
    read_region.add_argument("--crop", dest="crop_output", default=None, help="write cropped image")
    read_region.add_argument("--json", dest="json_output", default=None, help="write OCR JSON")
    read_region.add_argument("--psm", type=int, default=6)

    map_keypad = subparsers.add_parser("map-keypad", help="OCR and map a random 4x3 numeric keypad region")
    map_keypad.add_argument("region", help="keypad grid region, for example secure_number_keypad.digit_grid")
    map_keypad.add_argument("--image", default=None, help="existing full screenshot; captures if omitted")
    map_keypad.add_argument("--crop", dest="crop_output", default=None, help="write cropped image")
    map_keypad.add_argument("--json", dest="json_output", default=None, help="write keypad mapping JSON")
    map_keypad.add_argument("--psm", type=int, default=6)

    check = subparsers.add_parser("check", help="check a screen by OCR anchors")
    check.add_argument("screen", help="screen name in profile")
    check.add_argument("--image", default=None, help="existing image path; captures if omitted")
    check.add_argument("--json", dest="json_output", default=None, help="write decision JSON")
    check.add_argument("--psm", type=int, default=6)

    inspect_state = subparsers.add_parser("inspect-info-state", help="inspect current screen/account/tab state by OCR")
    inspect_state.add_argument("--image", default=None, help="existing image path; captures if omitted")
    inspect_state.add_argument("--json", dest="json_output", default=None, help="write state JSON")
    inspect_state.add_argument("--psm", type=int, default=6)

    handle_account_password = subparsers.add_parser(
        "handle-account-password-popup",
        help="handle account password popup using known route/account context",
    )
    handle_account_password.add_argument("--image", default=None, help="existing image path; captures if omitted")
    handle_account_password.add_argument("--json", dest="json_output", default=None, help="write event result JSON")
    handle_account_password.add_argument("--state-json", default=None, help="known current state JSON from this run/session")
    _add_route_context_args(handle_account_password)

    recover = subparsers.add_parser("detect-recovery", help="detect recoverable overlays by OCR")
    recover.add_argument("--image", default=None, help="existing image path; captures if omitted")
    recover.add_argument("--json", dest="json_output", default=None, help="write decision JSON")
    recover.add_argument("--psm", type=int, default=6)

    tap = subparsers.add_parser("tap", help="tap a named profile point")
    tap.add_argument("point", help="point name, for example order.account_password")

    swipe = subparsers.add_parser("swipe", help="run a named profile swipe")
    swipe.add_argument("swipe", help="swipe name, for example menu.scroll_to_order")

    tap_recovery = subparsers.add_parser("tap-recovery", help="tap a named recovery point")
    tap_recovery.add_argument("point", help="recovery point name, for example app_exit_confirm.cancel")

    subparsers.add_parser(
        "open-balance",
        help="open the ETF/REIT balance screen using profile tap points",
    )
    subparsers.add_parser("open-app", help="start the KIS MTS main activity")
    subparsers.add_parser(
        "open-order",
        help="open the ETF/REIT order screen using profile tap points",
    )
    subparsers.add_parser("open-order-search", help="open the ETF/REIT product search from the order screen")
    subparsers.add_parser("select-first-search-result", help="select the first product search result")
    select_account = subparsers.add_parser(
        "select-account",
        help="select an IRP or DC account on the order screen using profile tap points",
    )
    select_account.add_argument("account_type", choices=["IRP", "DC", "irp", "dc"])
    select_balance_account = subparsers.add_parser(
        "select-balance-account",
        help="select an IRP or DC account on the balance screen using profile tap points",
    )
    select_balance_account.add_argument("account_type", choices=["IRP", "DC", "irp", "dc"])
    subparsers.add_parser("ensure-balance-realtime", help="tap the realtime tab on the balance screen")

    validate_scenario = subparsers.add_parser("validate-scenario", help="check required profile keys for a scenario")
    validate_scenario.add_argument(
        "scenario",
        choices=[
            "open-balance",
            "open-order",
            "select-account",
            "select-balance-account",
            "ensure-balance-realtime",
            "open-order-search",
            "select-first-search-result",
        ],
    )
    validate_scenario.add_argument("--account-type", choices=["IRP", "DC", "irp", "dc"], default="IRP")

    verify_balance = subparsers.add_parser("verify-balance", help="verify balance OCR text against expected values")
    _add_text_input_args(verify_balance)
    verify_balance.add_argument("--account-type", choices=["IRP", "DC", "irp", "dc"], default=None)
    verify_balance.add_argument("--account-hint", default=None)
    verify_balance.add_argument("--symbol-code", default=None)
    verify_balance.add_argument("--symbol-name", default=None)
    verify_balance.add_argument("--min-quantity", type=int, default=None)

    verify_order = subparsers.add_parser("verify-order", help="verify order OCR text against expected values")
    _add_text_input_args(verify_order)
    verify_order.add_argument("--account-type", choices=["IRP", "DC", "irp", "dc"], default=None)
    verify_order.add_argument("--account-hint", default=None)
    verify_order.add_argument("--symbol-code", default=None)
    verify_order.add_argument("--symbol-name", default=None)
    verify_order.add_argument("--side", default=None)
    verify_order.add_argument("--quantity", type=int, default=None)
    verify_order.add_argument("--amount", type=int, default=None)

    submit_policy = subparsers.add_parser("submit-policy", help="evaluate whether submit may be tapped")
    submit_policy.add_argument("mode", choices=["inspect", "dry-run", "confirm-run", "manual-submit", "real-run"])
    submit_policy.add_argument("--verified", action="store_true")
    submit_policy.add_argument("--explicit-real-run", action="store_true")
    submit_policy.add_argument("--acknowledge-live-trade", action="store_true")

    extract_holdings = subparsers.add_parser(
        "extract-balance-holdings",
        help="extract holding names, quantities, and average prices from paired balance grid OCR JSON",
    )
    extract_holdings.add_argument("--left-ocr-json", required=True)
    extract_holdings.add_argument("--right-ocr-json", required=True)
    extract_holdings.add_argument("--ticker-cache", default=str(DEFAULT_TICKER_CACHE_PATH))

    holdings = subparsers.add_parser("holdings", help="read current account holdings from the balance realtime screen")
    holdings.add_argument("--account", choices=["IRP", "DC", "irp", "dc"], required=True)
    holdings.add_argument("--ticker-cache", default=str(DEFAULT_TICKER_CACHE_PATH))
    holdings.add_argument("--no-resolve-missing-tickers", action="store_true")
    holdings.add_argument("--max-pages", type=int, default=8, help="maximum vertical balance-grid pages to scan")
    holdings.add_argument("--psm", type=int, default=6)

    order = subparsers.add_parser(
        "order",
        help="place or dry-run an ETF/REIT market order and optionally read filled results",
    )
    order.add_argument("--account", choices=["IRP", "DC", "irp", "dc"], required=True)
    order.add_argument("--side", choices=["buy", "sell", "매수", "매도"], required=True)
    order.add_argument("--symbol-code", default=None)
    order.add_argument("--symbol-name", default=None)
    order.add_argument("--quantity", type=int, required=True)
    order.add_argument("--expected-amount", type=int, default=None)
    order.add_argument("--mode", choices=["inspect", "dry-run", "confirm-run", "manual-submit", "real-run"], default="dry-run")
    order.add_argument("--explicit-real-run", action="store_true")
    order.add_argument("--acknowledge-live-trade", action="store_true")
    order.add_argument("--no-read-filled-results", action="store_true")
    order.add_argument("--psm", type=int, default=6)

    round_trip = subparsers.add_parser(
        "market-roundtrip",
        help="run a guarded 1-share market buy/sell scenario for a single ETF/REIT",
    )
    round_trip.add_argument("--account", choices=["IRP", "DC", "irp", "dc"], required=True)
    round_trip.add_argument("--symbol-code", default="228790")
    round_trip.add_argument("--symbol-name", default="TIGER 화장품")
    round_trip.add_argument("--quantity", type=int, default=1)
    round_trip.add_argument("--mode", choices=["inspect", "dry-run", "confirm-run", "manual-submit", "real-run"], default="confirm-run")
    round_trip.add_argument("--explicit-real-run", action="store_true")
    round_trip.add_argument("--acknowledge-live-trade", action="store_true")
    round_trip.add_argument("--psm", type=int, default=6)

    filled_results = subparsers.add_parser("order-filled-results", help="read the order filled-results tab")
    filled_results.add_argument("--account", choices=["IRP", "DC", "irp", "dc"], default=None)

    subparsers.add_parser("list-info-routes", help="list Korean information route names")
    plan_info_route = subparsers.add_parser("plan-info-route", help="print an information route plan")
    plan_info_route.add_argument("route", help="Korean route name, for example 예수금")
    plan_info_route.add_argument("--account", choices=["IRP", "DC", "irp", "dc"], default=None)
    plan_info_route.add_argument("--state-json", default=None, help="known current state JSON from this run/session")
    _add_route_context_args(plan_info_route)

    validate_info_route = subparsers.add_parser("validate-info-route", help="validate profile keys for an information route")
    validate_info_route.add_argument("route", help="Korean route name, for example 예수금")
    validate_info_route.add_argument("--account", choices=["IRP", "DC", "irp", "dc"], default=None)

    subparsers.add_parser("validate-profile", help="validate a profile directory or JSON file")
    subparsers.add_parser("validate", help="validate a profile directory or JSON file")

    init_profile = subparsers.add_parser("init-profile", help="create a profile from current templates and device info")
    init_profile.add_argument("output", help="output profile path")

    set_point = subparsers.add_parser("set-point", help="write a tap point to a profile")
    set_point.add_argument("point", help="point name, for example order.account_password")
    set_point.add_argument("x", type=int)
    set_point.add_argument("y", type=int)

    set_region = subparsers.add_parser("set-region", help="write a read region to a profile")
    set_region.add_argument("region", help="region name, for example order.holding_quantity")
    set_region.add_argument("x", type=int)
    set_region.add_argument("y", type=int)
    set_region.add_argument("w", type=int)
    set_region.add_argument("h", type=int)

    set_swipe = subparsers.add_parser("set-swipe", help="write a swipe gesture to a profile")
    set_swipe.add_argument("swipe", help="swipe name, for example menu.scroll_to_order")
    set_swipe.add_argument("x1", type=int)
    set_swipe.add_argument("y1", type=int)
    set_swipe.add_argument("x2", type=int)
    set_swipe.add_argument("y2", type=int)
    set_swipe.add_argument("duration_ms", type=int, nargs="?", default=300)

    return parser


def _add_text_input_args(parser: argparse.ArgumentParser) -> None:
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--text", default=None, help="OCR text literal")
    source.add_argument("--text-file", default=None, help="path to a UTF-8 text file")
    source.add_argument("--ocr-json", default=None, help="path to an OCR JSON file with a text field")


def _add_route_context_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--current-screen", default=None, help="current Korean screen name if already known")
    parser.add_argument("--current-account", choices=["IRP", "DC", "irp", "dc"], default=None)
    parser.add_argument("--current-tab", default=None, help="current Korean tab name if already known")
    parser.add_argument("--expanded", action="append", default=[], help="already expanded Korean section name")


def _load_text_arg(args: argparse.Namespace) -> str:
    if args.text is not None:
        return args.text
    if args.text_file is not None:
        return Path(args.text_file).read_text(encoding="utf-8")
    with Path(args.ocr_json).open("r", encoding="utf-8") as f:
        return json.load(f).get("text", "")


def _route_context_from_args(args: argparse.Namespace) -> InformationContext:
    if getattr(args, "state_json", None):
        with Path(args.state_json).open("r", encoding="utf-8") as f:
            return InformationContext.from_dict(json.load(f))
    current_account = args.current_account.upper() if args.current_account else None
    return InformationContext(
        current_screen=args.current_screen,
        account=current_account,
        tab=args.current_tab,
        expanded=frozenset(args.expanded or []),
    )


def _inspect_route_context(
    *,
    profile: DeviceProfile,
    capture: ScreenCapture,
    ocr: TesseractOcr,
    artifacts: RunArtifacts,
    label: str,
    psm: int = 6,
) -> InformationContext:
    image = artifacts.next_path(f"{label}-source", ext="png")
    ocr_json = artifacts.next_path(f"{label}-state", ext="json")
    capture.capture(image)
    result = ocr.recognize(image, psm=psm)
    snapshot = ScreenStateChecker().inspect(profile, result)
    with ocr_json.open("w", encoding="utf-8") as f:
        json.dump(snapshot.to_dict(), f, indent=2, ensure_ascii=False)
        f.write("\n")
    return InformationContext.from_dict(snapshot.to_dict())


def _execute_route_step(device: AdbDevice, profile: DeviceProfile, step: RouteStep) -> None:
    if step.skipped or step.profile_key is None or step.action in {"읽기", "화면확인", "계좌확인", "탭확인", "펼침확인"}:
        return
    if step.action == "탭":
        point = profile.tap_point(step.profile_key)
        device.tap(point.x, point.y)
        return
    if step.action == "스와이프":
        swipe = profile.swipe(step.profile_key)
        device.swipe(swipe.x1, swipe.y1, swipe.x2, swipe.y2, swipe.duration_ms)
        return
    raise ValueError(f"Unsupported route step action: {step.action}")


def _trace_route_step(capture: ScreenCapture, artifacts: RunArtifacts, index: int, step: RouteStep) -> None:
    if step.skipped or step.action == "읽기":
        return
    label = step.profile_key or step.name
    safe_label = "".join(ch if ch.isalnum() else "-" for ch in label).strip("-").lower()
    capture.capture(artifacts.next_path(f"holdings-route-{index:02d}-{safe_label}", ext="png"))


def _execute_holdings_route(
    *,
    device: AdbDevice,
    profile: DeviceProfile,
    account: str,
    capture: ScreenCapture,
    artifacts: RunArtifacts,
) -> InformationContext:
    registry = InformationRouteRegistry()
    context = InformationContext()
    steps = registry.plan("보유종목", account=account, context=context)

    for index, step in enumerate(steps, start=1):
        if step.action == "읽기":
            break
        _execute_route_step(device, profile, step)
        if not step.skipped and step.profile_key is not None:
            time.sleep(profile.post_delay_ms(step.profile_key) / 1000)
            _trace_route_step(capture, artifacts, index, step)

    return registry.context_after("보유종목", account=account, context=context)


def _capture_holdings_page(
    *,
    device: AdbDevice,
    profile: DeviceProfile,
    capture: ScreenCapture,
    ocr: TesseractOcr,
    artifacts: RunArtifacts,
    page: int,
    psm: int,
) -> list[HoldingRow]:
    page_label = f"holdings-page-{page:02d}"
    left_image = artifacts.next_path(f"{page_label}-left-source", ext="png")
    left_crop = artifacts.next_path(f"{page_label}-left-grid", ext="png")
    left_json = artifacts.next_path(f"{page_label}-left-grid", ext="json")
    capture.capture(left_image)
    capture.crop(left_image, profile.region("balance.holdings_grid"), left_crop)
    left_ocr = ocr.recognize(left_image, psm=psm)
    left_ocr.save_json(left_json)

    scroll_right = profile.swipe("balance.scroll_grid_right")
    device.swipe(scroll_right.x1, scroll_right.y1, scroll_right.x2, scroll_right.y2, scroll_right.duration_ms)
    time.sleep(profile.post_delay_ms("balance.scroll_grid_right") / 1000)

    right_image = artifacts.next_path(f"{page_label}-right-source", ext="png")
    right_crop = artifacts.next_path(f"{page_label}-right-grid", ext="png")
    right_json = artifacts.next_path(f"{page_label}-right-grid", ext="json")
    capture.capture(right_image)
    capture.crop(right_image, profile.region("balance.holdings_grid"), right_crop)
    right_ocr = ocr.recognize(right_image, psm=psm)
    right_ocr.save_json(right_json)

    scroll_left = profile.swipe("balance.scroll_grid_left")
    device.swipe(scroll_left.x1, scroll_left.y1, scroll_left.x2, scroll_left.y2, scroll_left.duration_ms)
    time.sleep(profile.post_delay_ms("balance.scroll_grid_left") / 1000)

    return BalanceHoldingsGridParser().parse(left_ocr, right_ocr)


def _read_holdings_rows(
    *,
    device: AdbDevice,
    profile: DeviceProfile,
    capture: ScreenCapture,
    ocr: TesseractOcr,
    artifacts: RunArtifacts,
    ticker_resolver: HoldingsTickerResolver | None,
    max_pages: int,
    psm: int,
) -> list[HoldingRow]:
    all_rows: list[HoldingRow] = []
    seen_names: set[str] = set()
    page_count = max(1, max_pages)
    for page in range(1, page_count + 1):
        page_rows = _capture_holdings_page(
            device=device,
            profile=profile,
            capture=capture,
            ocr=ocr,
            artifacts=artifacts,
            page=page,
            psm=psm,
        )
        new_rows = [row for row in page_rows if row.name not in seen_names]
        if ticker_resolver is not None:
            ticker_resolver.enrich(new_rows)
        all_rows.extend(page_rows)
        seen_names.update(row.name for row in page_rows)
        if not page_rows or not new_rows:
            break
        if page == page_count:
            break
        scroll_down = profile.swipe("balance.scroll_grid_down")
        device.swipe(scroll_down.x1, scroll_down.y1, scroll_down.x2, scroll_down.y2, scroll_down.duration_ms)
        time.sleep(profile.post_delay_ms("balance.scroll_grid_down") / 1000)
    return merge_holding_rows(all_rows)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    profile_path = args.profile or _default_profile_from_config(args.config)
    profile = DeviceProfile.load(profile_path) if profile_path else None
    serial = args.serial or (profile.serial if profile else None)
    device = AdbDevice(serial=serial, adb_path=args.adb)

    if args.command == "devices":
        print("\n".join(device.list_devices()))
        return 0

    if args.command == "device-info":
        info = device.get_device_info()
        print(json.dumps(info.__dict__, indent=2, ensure_ascii=False))
        return 0

    if args.command == "current-activity":
        activity = device.get_current_activity()
        print(json.dumps(activity.__dict__, indent=2, ensure_ascii=False))
        return 0

    if args.command == "login-status":
        status = LoginBridge(device).status()
        print(json.dumps(status.to_dict(), indent=2, ensure_ascii=False))
        return 0 if status.ready else 2

    if args.command == "login":
        bridge = LoginBridge(device)
        status = bridge.status()
        if not status.ready:
            print(json.dumps(status.to_dict(), indent=2, ensure_ascii=False))
            return 2
        if args.wait:
            result = bridge.send_command_and_wait("LOGIN", timeout=args.timeout)
            print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
            return 0 if result.success and result.state == "LOGGED_IN" else 2
        request_id = bridge.send_command("LOGIN")
        print(json.dumps({"sent": "LOGIN", "request_id": request_id}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "login-command":
        bridge = LoginBridge(device)
        status = bridge.status()
        if not status.ready:
            print(json.dumps(status.to_dict(), indent=2, ensure_ascii=False))
            return 2
        if args.wait:
            result = bridge.send_command_and_wait(args.login_command, timeout=args.timeout)
            print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
            return 0 if result.success else 2
        request_id = bridge.send_command(args.login_command)
        print(json.dumps({"sent": args.login_command, "request_id": request_id}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "capture":
        artifacts = RunArtifacts()
        output = Path(args.output) if args.output else artifacts.next_path("capture", ext="png")
        ScreenCapture(device).capture(output)
        print(output)
        return 0

    if args.command == "ocr":
        result = TesseractOcr().recognize(args.image, psm=args.psm)
        if args.json_output:
            result.save_json(args.json_output)
        print(result.text)
        return 0

    if args.command == "crop":
        if profile is None:
            raise SystemExit("--profile is required for crop")
        region = profile.region(args.region)
        ScreenCapture(device).crop(args.image, region, args.output)
        print(args.output)
        return 0

    if args.command == "read-region":
        if profile is None:
            raise SystemExit("--profile is required for read-region")
        artifacts = RunArtifacts()
        capture = ScreenCapture(device)
        image = Path(args.image) if args.image else artifacts.next_path("read-region-source", ext="png")
        if args.image is None:
            capture.capture(image)
        crop_output = Path(args.crop_output) if args.crop_output else artifacts.next_path(args.region, ext="png")
        capture.crop(image, profile.region(args.region), crop_output)
        result = TesseractOcr().recognize(crop_output, psm=args.psm)
        if args.json_output:
            result.save_json(args.json_output)
        print(result.text)
        return 0

    if args.command == "map-keypad":
        if profile is None:
            raise SystemExit("--profile is required for map-keypad")
        artifacts = RunArtifacts()
        capture = ScreenCapture(device)
        region = profile.region(args.region)
        image = Path(args.image) if args.image else artifacts.next_path("map-keypad-source", ext="png")
        if args.image is None:
            capture.capture(image)
        crop_output = Path(args.crop_output) if args.crop_output else artifacts.next_path(args.region, ext="png")
        capture.crop(image, region, crop_output)
        ocr_result = TesseractOcr().recognize_words(crop_output, psm=args.psm)
        crop_region = Region(0, 0, region.w, region.h)
        mapping = RandomNumericKeypadMapper(crop_region).map_digits(ocr_result).translated(region.x, region.y)
        payload = mapping.to_dict()
        payload["ocr_strategy"] = "digit_grid_tsv"
        if args.json_output:
            Path(args.json_output).parent.mkdir(parents=True, exist_ok=True)
            Path(args.json_output).write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0 if mapping.complete else 2

    if args.command == "check":
        if profile is None:
            raise SystemExit("--profile is required for check")
        artifacts = RunArtifacts()
        image = Path(args.image) if args.image else artifacts.next_path(f"check-{args.screen}", ext="png")
        if args.image is None:
            ScreenCapture(device).capture(image)
        ocr_result = TesseractOcr().recognize(image, psm=args.psm)
        decision = ScreenStateChecker().check_anchors(args.screen, profile.anchors(args.screen), ocr_result)
        payload = decision.to_dict()
        if args.json_output:
            Path(args.json_output).parent.mkdir(parents=True, exist_ok=True)
            Path(args.json_output).write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0 if decision.passed else 2

    if args.command == "inspect-info-state":
        if profile is None:
            raise SystemExit("--profile is required for inspect-info-state")
        artifacts = RunArtifacts()
        image = Path(args.image) if args.image else artifacts.next_path("inspect-info-state", ext="png")
        if args.image is None:
            ScreenCapture(device).capture(image)
        ocr_result = TesseractOcr().recognize(image, psm=args.psm)
        snapshot = ScreenStateChecker().inspect(profile, ocr_result)
        payload = snapshot.to_dict()
        if args.json_output:
            Path(args.json_output).parent.mkdir(parents=True, exist_ok=True)
            Path(args.json_output).write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0 if snapshot.current_screen else 2

    if args.command == "handle-account-password-popup":
        if profile is None:
            raise SystemExit("--profile is required for handle-account-password-popup")
        config_path = Path(args.config)
        if not config_path.exists():
            raise SystemExit(f"{args.config} is required for handle-account-password-popup")
        context = _route_context_from_args(args)
        result = AccountPasswordPopupHandler(
            device,
            profile,
            PensionConfig.load(config_path),
        ).handle(context, image=args.image, output_json=args.json_output)
        payload = result.to_dict()
        payload["updated_context"] = result.apply_to_context(context).to_dict()
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0 if result.handled or not result.detected else 2

    if args.command == "detect-recovery":
        if profile is None:
            raise SystemExit("--profile is required for detect-recovery")
        artifacts = RunArtifacts()
        image = Path(args.image) if args.image else artifacts.next_path("detect-recovery", ext="png")
        if args.image is None:
            ScreenCapture(device).capture(image)
        ocr_result = TesseractOcr().recognize(image, psm=args.psm)
        candidates = RecoveryDetector(profile.data.get("recoveries", {})).detect(ocr_result)
        payload = {"candidates": [candidate.to_dict() for candidate in candidates]}
        if args.json_output:
            Path(args.json_output).parent.mkdir(parents=True, exist_ok=True)
            Path(args.json_output).write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0 if candidates else 2

    if args.command == "tap":
        if profile is None:
            raise SystemExit("--profile is required for tap")
        point = profile.tap_point(args.point)
        device.tap(point.x, point.y)
        print(f"tapped {args.point} at ({point.x}, {point.y})")
        return 0

    if args.command == "swipe":
        if profile is None:
            raise SystemExit("--profile is required for swipe")
        gesture = profile.swipe(args.swipe)
        device.swipe(gesture.x1, gesture.y1, gesture.x2, gesture.y2, gesture.duration_ms)
        print(
            f"swiped {args.swipe} from ({gesture.x1}, {gesture.y1}) "
            f"to ({gesture.x2}, {gesture.y2}) in {gesture.duration_ms}ms"
        )
        return 0

    if args.command == "tap-recovery":
        if profile is None:
            raise SystemExit("--profile is required for tap-recovery")
        point = profile.recovery_tap_point(args.point)
        device.tap(point.x, point.y)
        print(f"tapped recovery {args.point} at ({point.x}, {point.y})")
        return 0

    if args.command == "open-balance":
        if profile is None:
            raise SystemExit("--profile is required for open-balance")
        steps = PensionScenarios(device, profile).open_balance()
        print(json.dumps({"scenario": args.command, "steps": [step.to_dict() for step in steps]}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "open-app":
        PensionScenarios(device).open_app()
        print(json.dumps({"scenario": args.command, "started": True}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "open-order":
        if profile is None:
            raise SystemExit("--profile is required for open-order")
        steps = PensionScenarios(device, profile).open_order()
        print(json.dumps({"scenario": args.command, "steps": [step.to_dict() for step in steps]}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "open-order-search":
        if profile is None:
            raise SystemExit("--profile is required for open-order-search")
        steps = PensionScenarios(device, profile).open_order_search()
        print(json.dumps({"scenario": args.command, "steps": [step.to_dict() for step in steps]}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "select-first-search-result":
        if profile is None:
            raise SystemExit("--profile is required for select-first-search-result")
        steps = PensionScenarios(device, profile).select_first_search_result()
        print(json.dumps({"scenario": args.command, "steps": [step.to_dict() for step in steps]}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "select-account":
        if profile is None:
            raise SystemExit("--profile is required for select-account")
        steps = PensionScenarios(device, profile).select_account(args.account_type)
        print(json.dumps({"scenario": args.command, "steps": [step.to_dict() for step in steps]}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "select-balance-account":
        if profile is None:
            raise SystemExit("--profile is required for select-balance-account")
        steps = PensionScenarios(device, profile).select_balance_account(args.account_type)
        print(json.dumps({"scenario": args.command, "steps": [step.to_dict() for step in steps]}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "ensure-balance-realtime":
        if profile is None:
            raise SystemExit("--profile is required for ensure-balance-realtime")
        steps = PensionScenarios(device, profile).ensure_balance_realtime()
        print(json.dumps({"scenario": args.command, "steps": [step.to_dict() for step in steps]}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "validate-scenario":
        if profile is None:
            raise SystemExit("--profile is required for validate-scenario")
        points = PensionScenarios.scenario_points(args.scenario, args.account_type)
        missing = PensionScenarios.missing_points(profile, points)
        payload = {
            "scenario": args.scenario,
            "required_points": points,
            "missing_points": missing,
            "valid": not missing,
        }
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0 if not missing else 2

    if args.command == "verify-balance":
        result = verify_balance_text(
            _load_text_arg(args),
            ExpectedBalance(
                account_type=args.account_type.upper() if args.account_type else None,
                account_hint=args.account_hint,
                symbol_code=args.symbol_code,
                symbol_name=args.symbol_name,
                min_quantity=args.min_quantity,
            ),
        )
        print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
        return 0 if result.passed else 2

    if args.command == "verify-order":
        result = verify_order_text(
            _load_text_arg(args),
            ExpectedOrder(
                account_type=args.account_type.upper() if args.account_type else None,
                account_hint=args.account_hint,
                symbol_code=args.symbol_code,
                symbol_name=args.symbol_name,
                side=args.side,
                quantity=args.quantity,
                amount=args.amount,
            ),
        )
        print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
        return 0 if result.passed else 2

    if args.command == "submit-policy":
        decision = decide_submit_permission(
            args.mode,
            verified=args.verified,
            explicit_real_run=args.explicit_real_run,
            acknowledge_live_trade=args.acknowledge_live_trade,
        )
        print(json.dumps(decision.to_dict(), indent=2, ensure_ascii=False))
        return 0 if decision.may_tap_submit or args.mode != "real-run" else 2

    if args.command == "extract-balance-holdings":
        rows = BalanceHoldingsGridParser().parse(
            load_ocr_json(args.left_ocr_json),
            load_ocr_json(args.right_ocr_json),
        )
        cache = HoldingTickerCache(args.ticker_cache)
        holdings = HoldingsTickerResolver(cache).enrich(rows)
        print(json.dumps({"holdings": [row.to_dict() for row in holdings]}, indent=2, ensure_ascii=False))
        return 0 if rows else 2

    if args.command == "holdings":
        if profile is None:
            raise SystemExit("--profile is required for holdings")
        account = args.account.upper()
        artifacts = RunArtifacts()
        capture = ScreenCapture(device)
        ocr = TesseractOcr()
        try:
            _execute_holdings_route(
                device=device,
                profile=profile,
                account=account,
                capture=capture,
                artifacts=artifacts,
            )
        except RuntimeError as exc:
            print(json.dumps({"account": account, "error": str(exc), "holdings": []}, indent=2, ensure_ascii=False))
            return 2

        cache = HoldingTickerCache(args.ticker_cache)
        page_ticker_resolver = None
        if not args.no_resolve_missing_tickers:
            page_ticker_resolver = HoldingsTickerResolver(
                cache,
                device=device,
                profile=profile,
                capture=capture,
                ocr=ocr,
            )
        rows = _read_holdings_rows(
            device=device,
            profile=profile,
            capture=capture,
            ocr=ocr,
            artifacts=artifacts,
            ticker_resolver=page_ticker_resolver,
            max_pages=args.max_pages,
            psm=args.psm,
        )
        cache_only_resolver = HoldingsTickerResolver(cache)
        holdings = cache_only_resolver.enrich(rows)
        missing_tickers = cache_only_resolver.missing_ticker_names(rows)
        if missing_tickers and not args.no_resolve_missing_tickers:
            print(
                json.dumps(
                    {
                        "account": account,
                        "error": "ticker cache is incomplete",
                        "missing_tickers": missing_tickers,
                        "holdings": [row.to_dict() for row in holdings],
                    },
                    indent=2,
                    ensure_ascii=False,
                )
            )
            return 2
        print(json.dumps({"account": account, "holdings": [row.to_dict() for row in holdings]}, indent=2, ensure_ascii=False))
        return 0 if rows else 2

    if args.command == "order":
        if profile is None:
            raise SystemExit("--profile is required for order")
        config_path = Path(args.config)
        if not config_path.exists():
            raise SystemExit(f"{args.config} is required for order")
        if not args.symbol_code and not args.symbol_name:
            raise SystemExit("--symbol-code or --symbol-name is required")
        result = OrderExecutor(device, profile, PensionConfig.load(config_path)).execute(
            OrderRequest(
                account=args.account.upper(),
                side=normalize_order_side(args.side),
                symbol_code=args.symbol_code,
                symbol_name=args.symbol_name,
                expected_amount=args.expected_amount,
                quantity=args.quantity,
                mode=args.mode,
                explicit_real_run=args.explicit_real_run,
                acknowledge_live_trade=args.acknowledge_live_trade,
                read_filled_results=not args.no_read_filled_results,
                psm=args.psm,
            )
        )
        print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
        confirmation_required = bool(result.decision.get("may_open_confirmation"))
        return 0 if result.verified and (not confirmation_required or result.confirmation_verified) else 2

    if args.command == "market-roundtrip":
        if profile is None:
            raise SystemExit("--profile is required for market-roundtrip")
        config_path = Path(args.config)
        if not config_path.exists():
            raise SystemExit(f"{args.config} is required for market-roundtrip")
        result = OrderExecutor(device, profile, PensionConfig.load(config_path)).execute_market_round_trip(
            MarketRoundTripRequest(
                account=args.account.upper(),
                symbol_code=args.symbol_code,
                symbol_name=args.symbol_name,
                quantity=args.quantity,
                mode=args.mode,
                explicit_real_run=args.explicit_real_run,
                acknowledge_live_trade=args.acknowledge_live_trade,
                psm=args.psm,
            )
        )
        print(json.dumps(result.to_dict(), indent=2, ensure_ascii=False))
        return 0 if result.completed else 2

    if args.command == "order-filled-results":
        if profile is None:
            raise SystemExit("--profile is required for order-filled-results")
        context = InformationContext(current_screen="주문", account=args.account.upper() if args.account else None)
        text = OrderExecutor(device, profile, PensionConfig({}, default_profile=profile_path)).read_filled_results(context)
        print(text)
        return 0 if text.strip() else 2

    if args.command == "list-info-routes":
        print(json.dumps({"routes": InformationRouteRegistry().names()}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "plan-info-route":
        route_registry = InformationRouteRegistry()
        context = _route_context_from_args(args)
        steps = route_registry.plan(
            args.route,
            account=args.account.upper() if args.account else None,
            context=context,
        )
        predicted_context = route_registry.context_after(
            args.route,
            account=args.account.upper() if args.account else None,
            context=context,
        )
        payload = {
            "route": args.route,
            "account": args.account.upper() if args.account else None,
            "current_context": context.to_dict(),
            "steps": [step.to_dict() for step in steps],
            "predicted_context_after_execution": predicted_context.to_dict(),
        }
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0

    if args.command == "validate-info-route":
        if profile is None:
            raise SystemExit("--profile is required for validate-info-route")
        route_registry = InformationRouteRegistry()
        missing = route_registry.validate(
            args.route,
            profile,
            account=args.account.upper() if args.account else None,
        )
        payload = {
            "route": args.route,
            "account": args.account.upper() if args.account else None,
            "missing_points": missing,
            "valid": not missing,
        }
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0 if not missing else 2

    if args.command in {"validate-profile", "validate"}:
        if profile is None:
            raise SystemExit("--profile is required for validate")
        errors = profile.validate()
        payload = {"profile": str(profile.source_path or profile_path), "valid": not errors, "errors": errors}
        print(json.dumps(payload, indent=2, ensure_ascii=False))
        return 0 if not errors else 2

    if args.command == "init-profile":
        info = device.get_device_info()
        DeviceProfile.from_device_info(
            serial=info.serial,
            width=info.width,
            height=info.height,
            density=info.density,
        ).save(args.output)
        print(args.output)
        return 0

    if args.command == "set-point":
        if profile is None or not profile.source_path:
            raise SystemExit("--profile is required for set-point")
        point = profile.set_tap_point(args.point, args.x, args.y)
        profile.save()
        print(json.dumps({"point": args.point, **point.__dict__}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "set-region":
        if profile is None or not profile.source_path:
            raise SystemExit("--profile is required for set-region")
        region = profile.set_region(args.region, args.x, args.y, args.w, args.h)
        profile.save()
        print(json.dumps({"region": args.region, **region.__dict__}, indent=2, ensure_ascii=False))
        return 0

    if args.command == "set-swipe":
        if profile is None or not profile.source_path:
            raise SystemExit("--profile is required for set-swipe")
        gesture = profile.set_swipe(args.swipe, args.x1, args.y1, args.x2, args.y2, args.duration_ms)
        profile.save()
        print(json.dumps({"swipe": args.swipe, **gesture.__dict__}, indent=2, ensure_ascii=False))
        return 0

    raise SystemExit(f"Unknown command: {args.command}")


def _default_profile_from_config(config_path: str | Path) -> str | None:
    path = Path(config_path)
    if not path.exists():
        return None
    return PensionConfig.load(path).default_profile


if __name__ == "__main__":
    raise SystemExit(main())
