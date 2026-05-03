from __future__ import annotations

import json
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from .config import PensionConfig
from .device_profile import DeviceProfile, Region
from .keypad import RandomNumericKeypadMapper
from .ocr import OcrResult, TesseractOcr
from .routes import InformationContext
from .screen_capture import ScreenCapture
from .screen_state import ScreenStateChecker


@dataclass(frozen=True)
class EventResult:
    event: str
    detected: bool
    handled: bool
    account: str | None = None
    reason: str | None = None
    digit_count: int = 0
    auto_save_enabled: bool | None = None
    auto_save_toggled: bool = False
    skipped_by_session_state: bool = False

    def to_dict(self) -> dict:
        return {
            "event": self.event,
            "detected": self.detected,
            "handled": self.handled,
            "account": self.account,
            "reason": self.reason,
            "digit_count": self.digit_count,
            "auto_save_enabled": self.auto_save_enabled,
            "auto_save_toggled": self.auto_save_toggled,
            "skipped_by_session_state": self.skipped_by_session_state,
        }

    def apply_to_context(self, context: InformationContext) -> InformationContext:
        if self.handled and self.auto_save_enabled and self.account:
            return context.with_password_saved(self.account)
        return context


class ToggleState(Enum):
    ON = "on"
    OFF = "off"
    UNKNOWN = "unknown"


class AccountPasswordPopupHandler:
    event_name = "계좌 비밀번호 입력"

    def __init__(
        self,
        device,
        profile: DeviceProfile,
        config: PensionConfig,
        *,
        ocr: TesseractOcr | None = None,
    ):
        self.device = device
        self.profile = profile
        self.config = config
        self.ocr = ocr or TesseractOcr()

    def detect(self, ocr_result: OcrResult) -> bool:
        check = ScreenStateChecker().check_anchors(
            "account_password_popup",
            self.profile.anchors("account_password_popup"),
            ocr_result,
        )
        return check.passed

    def handle(
        self,
        context: InformationContext,
        *,
        image: str | Path | None = None,
        output_json: str | Path | None = None,
    ) -> EventResult:
        if context.is_password_saved():
            return self._save(
                EventResult(
                    self.event_name,
                    detected=False,
                    handled=False,
                    account=context.account,
                    reason="password_already_saved_for_session",
                    auto_save_enabled=True,
                    skipped_by_session_state=True,
                ),
                output_json,
            )

        capture = ScreenCapture(self.device)
        source_image = Path(image) if image else Path("runs/account-password-event-source.png")
        if image is None:
            capture.capture(source_image)
        screen_ocr = self.ocr.recognize(source_image, psm=6)
        if not self.detect(screen_ocr):
            return self._save(EventResult(self.event_name, detected=False, handled=False, reason="not_detected"), output_json)

        account = context.account
        password = self.config.password_for_account(account)
        if not account:
            return self._save(EventResult(self.event_name, detected=True, handled=False, reason="missing_known_account"), output_json)
        if not password:
            return self._save(EventResult(self.event_name, detected=True, handled=False, account=account, reason="missing_account_password"), output_json)
        if not password.isdigit():
            return self._save(EventResult(self.event_name, detected=True, handled=False, account=account, reason="password_contains_non_digit"), output_json)

        auto_save_toggled = self._ensure_auto_save_enabled(source_image)
        if auto_save_toggled is None:
            return self._save(
                EventResult(
                    self.event_name,
                    detected=True,
                    handled=False,
                    account=account,
                    reason="unknown_auto_save_toggle_state",
                    auto_save_enabled=None,
                ),
                output_json,
            )

        self._open_keypad()
        keypad_image = source_image.parent / "account-password-keypad.png"
        capture.capture(keypad_image)
        mapping = self._map_keypad(capture, keypad_image)
        if not mapping.complete:
            return self._save(
                EventResult(
                    self.event_name,
                    detected=True,
                    handled=False,
                    account=account,
                    reason="incomplete_keypad_mapping",
                    auto_save_enabled=True,
                    auto_save_toggled=auto_save_toggled,
                ),
                output_json,
            )

        for digit in password:
            slot = mapping.digits[digit]
            self.device.tap(slot.x, slot.y)

        complete = self.profile.tap_point("secure_number_keypad.complete")
        self.device.tap(complete.x, complete.y)
        return self._save(
            EventResult(
                self.event_name,
                detected=True,
                handled=True,
                account=account,
                digit_count=len(password),
                auto_save_enabled=True,
                auto_save_toggled=auto_save_toggled,
            ),
            output_json,
        )

    def _ensure_auto_save_enabled(self, image: Path) -> bool | None:
        state = self._auto_save_toggle_state(image)
        if state is ToggleState.ON:
            return False
        if state is ToggleState.OFF:
            point = self.profile.tap_point("account_password_popup.auto_save_toggle")
            self.device.tap(point.x, point.y)
            return True
        return None

    def _auto_save_toggle_state(self, image: Path) -> ToggleState:
        try:
            from PIL import Image
        except ImportError as exc:
            raise RuntimeError("Pillow is required for account password auto-save toggle detection.") from exc

        region = self.profile.region("account_password_popup.auto_save_toggle")
        with Image.open(image) as source:
            crop = source.crop((region.x, region.y, region.x + region.w, region.y + region.h)).convert("RGB")

        white_pixels: list[tuple[int, int]] = []
        blue_pixels = 0
        for y in range(crop.height):
            for x in range(crop.width):
                r, g, b = crop.getpixel((x, y))
                if r > 230 and g > 230 and b > 230:
                    white_pixels.append((x, y))
                if b > 160 and g > 110 and r < 120:
                    blue_pixels += 1

        if not white_pixels:
            return ToggleState.UNKNOWN

        white_center_x = sum(x for x, _ in white_pixels) / len(white_pixels)
        relative_center = white_center_x / crop.width
        if blue_pixels > crop.width * crop.height * 0.08:
            return ToggleState.ON
        if relative_center < 0.45:
            return ToggleState.OFF
        if relative_center > 0.55:
            return ToggleState.ON
        return ToggleState.UNKNOWN

    def _open_keypad(self) -> None:
        dots = self.profile.region("account_password_popup.password_dots")
        self.device.tap(dots.x + dots.w / 2, dots.y + dots.h / 2)

    def _map_keypad(self, capture: ScreenCapture, image: Path):
        region = self.profile.region("secure_number_keypad.digit_grid")
        crop_output = image.parent / "account-password-digit-grid.png"
        capture.crop(image, region, crop_output)
        ocr_result = self.ocr.recognize_words(crop_output, psm=6)
        crop_region = Region(0, 0, region.w, region.h)
        return RandomNumericKeypadMapper(crop_region).map_digits(ocr_result).translated(region.x, region.y)

    @staticmethod
    def _save(result: EventResult, output_json: str | Path | None) -> EventResult:
        if output_json:
            output = Path(output_json)
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(json.dumps(result.to_dict(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        return result
