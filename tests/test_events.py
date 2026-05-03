import tempfile
import unittest
from pathlib import Path

from pension.config import PensionConfig
from pension.device_profile import DeviceProfile
from pension.events import AccountPasswordPopupHandler, EventResult, ToggleState
from pension.ocr import OcrResult, OcrWord
from pension.routes import InformationContext


class FakeDevice:
    def __init__(self):
        self.taps = []

    def tap(self, x, y):
        self.taps.append((x, y))

    def screencap(self, output_path):
        from PIL import Image

        image = Image.new("RGB", (1080, 2340), "white")
        image.save(output_path)
        return output_path


class FakeOcr:
    def __init__(self, result, *, words_result=None):
        self.results = list(result if isinstance(result, list) else [result])
        self.words_result = words_result or OcrResult("keypad.png", "kor+eng", "", [])

    def recognize(self, image_path, psm=6):
        if len(self.results) == 1:
            return self.results[0]
        return self.results.pop(0)

    def recognize_words(self, image_path, psm=6):
        return self.words_result


def make_profile():
    return DeviceProfile(
        {
            "device": {"width": 1080, "height": 2340},
            "screens": {
                "account_password_popup": {
                    "anchors": {
                        "required": ["계좌 비밀번호"],
                        "optional": ["입력", "자동저장", "입력완료"],
                        "forbidden": ["오류"],
                        "min_score": 0.6,
                    },
                    "tap_points": {
                        "auto_save_toggle": {"x": 920, "y": 1510},
                    },
                    "regions": {
                        "password_dots": {"x": 330, "y": 1220, "w": 420, "h": 90},
                        "auto_save_toggle": {"x": 0, "y": 0, "w": 140, "h": 95},
                        "account_label": {"x": 0, "y": 100, "w": 400, "h": 80},
                    },
                },
                "secure_number_keypad": {
                    "anchors": [],
                    "tap_points": {
                        "complete": {"x": 810, "y": 2125},
                    },
                    "regions": {
                        "digit_grid": {"x": 0, "y": 1595, "w": 1080, "h": 465},
                    },
                },
            },
        }
    )


class AccountPasswordPopupHandlerTests(unittest.TestCase):
    def test_detects_account_password_popup(self):
        handler = AccountPasswordPopupHandler(FakeDevice(), make_profile(), PensionConfig({}))
        result = OcrResult("screen.png", "kor+eng", "계좌 비밀번호 입력 자동저장", [])

        self.assertTrue(handler.detect(result))

    def test_detected_popup_requires_known_account_context(self):
        device = FakeDevice()
        handler = AccountPasswordPopupHandler(
            device,
            make_profile(),
            PensionConfig({"IRP": "1234"}),
            ocr=FakeOcr(OcrResult("screen.png", "kor+eng", "계좌 비밀번호 입력 자동저장", [])),
        )
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "screen.png"
            image.write_bytes(b"unused")

            result = handler.handle(InformationContext(), image=image)

        self.assertTrue(result.detected)
        self.assertFalse(result.handled)
        self.assertEqual(result.reason, "missing_known_account")
        self.assertEqual(device.taps, [])

    def test_detected_popup_requires_password_for_known_account(self):
        handler = AccountPasswordPopupHandler(
            FakeDevice(),
            make_profile(),
            PensionConfig({}),
            ocr=FakeOcr(OcrResult("screen.png", "kor+eng", "계좌 비밀번호 입력 자동저장", [])),
        )
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "screen.png"
            image.write_bytes(b"unused")

            result = handler.handle(InformationContext(account="IRP"), image=image)

        self.assertTrue(result.detected)
        self.assertFalse(result.handled)
        self.assertEqual(result.account, "IRP")
        self.assertEqual(result.reason, "missing_account_password")

    def test_skips_ocr_when_password_is_already_saved_for_session(self):
        device = FakeDevice()
        handler = AccountPasswordPopupHandler(
            device,
            make_profile(),
            PensionConfig({"IRP": "1234"}),
            ocr=FakeOcr(OcrResult("screen.png", "kor+eng", "would not matter", [])),
        )

        result = handler.handle(InformationContext(account="IRP", account_password_saved_accounts=frozenset({"IRP"})))

        self.assertFalse(result.detected)
        self.assertFalse(result.handled)
        self.assertTrue(result.skipped_by_session_state)
        self.assertEqual(result.reason, "password_already_saved_for_session")
        self.assertEqual(device.taps, [])

    def test_does_not_skip_when_different_account_password_was_saved(self):
        device = FakeDevice()
        handler = AccountPasswordPopupHandler(
            device,
            make_profile(),
            PensionConfig({"DC": "1234"}),
            ocr=FakeOcr(OcrResult("screen.png", "kor+eng", "not popup", [])),
        )
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "screen.png"
            image.write_bytes(b"unused")

            result = handler.handle(
                InformationContext(account="DC", account_password_saved_accounts=frozenset({"IRP"})),
                image=image,
            )

        self.assertFalse(result.skipped_by_session_state)
        self.assertEqual(result.reason, "not_detected")

    def test_successful_auto_save_event_marks_account_password_saved_in_context(self):
        context = InformationContext(account="IRP")
        result = EventResult(
            "계좌 비밀번호 입력",
            detected=True,
            handled=True,
            account="IRP",
            auto_save_enabled=True,
        )

        updated = result.apply_to_context(context)

        self.assertTrue(updated.is_password_saved("IRP"))
        self.assertFalse(updated.is_password_saved("DC"))

    def test_detects_auto_save_toggle_off_when_white_knob_is_left(self):
        handler = AccountPasswordPopupHandler(FakeDevice(), make_profile(), PensionConfig({}))
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "toggle.png"
            self._write_toggle_image(image, knob_left=True, blue_track=False)

            state = handler._auto_save_toggle_state(image)

        self.assertEqual(state, ToggleState.OFF)

    def test_detects_auto_save_toggle_on_when_white_knob_is_right(self):
        handler = AccountPasswordPopupHandler(FakeDevice(), make_profile(), PensionConfig({}))
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "toggle.png"
            self._write_toggle_image(image, knob_left=False, blue_track=True)

            state = handler._auto_save_toggle_state(image)

        self.assertEqual(state, ToggleState.ON)

    def test_turns_on_auto_save_before_password_input(self):
        device = FakeDevice()
        handler = AccountPasswordPopupHandler(device, make_profile(), PensionConfig({}))
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "toggle.png"
            self._write_toggle_image(image, knob_left=True, blue_track=False)

            toggled = handler._ensure_auto_save_enabled(image)

        self.assertTrue(toggled)
        self.assertEqual(device.taps, [(920, 1510)])

    def test_successful_input_confirms_popup_closed(self):
        device = FakeDevice()
        handler = AccountPasswordPopupHandler(
            device,
            make_profile(),
            PensionConfig({"IRP": "1209"}),
            ocr=FakeOcr(
                [
                    OcrResult("source.png", "kor+eng", "계좌 비밀번호 입력 자동저장", []),
                    OcrResult("label.png", "kor+eng", "IRP 1234", []),
                    OcrResult("after.png", "kor+eng", "매수 주문 화면", []),
                ],
                words_result=OcrResult("keypad.png", "kor+eng", "", self._keypad_words()),
            ),
        )
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "source.png"
            self._write_full_source_image(image)

            result = handler.handle(InformationContext(account="IRP"), image=image)

        self.assertTrue(result.handled)
        self.assertTrue(result.input_confirmed)
        self.assertEqual(result.failure_count, 0)
        self.assertEqual([tap for tap in device.taps[-5:]], [(135, 1673), (405, 1673), (405, 1983), (135, 1983), (810, 2125)])

    def test_reports_failure_text_after_password_input(self):
        device = FakeDevice()
        handler = AccountPasswordPopupHandler(
            device,
            make_profile(),
            PensionConfig({"IRP": "1209"}),
            ocr=FakeOcr(
                [
                    OcrResult("source.png", "kor+eng", "계좌 비밀번호 입력 자동저장", []),
                    OcrResult("label.png", "kor+eng", "IRP 1234", []),
                    OcrResult("after.png", "kor+eng", "계좌 비밀번호 오류 다시 입력", []),
                ],
                words_result=OcrResult("keypad.png", "kor+eng", "", self._keypad_words()),
            ),
        )
        with tempfile.TemporaryDirectory() as tmp:
            image = Path(tmp) / "source.png"
            self._write_full_source_image(image)

            result = handler.handle(InformationContext(account="IRP"), image=image)

        self.assertFalse(result.handled)
        self.assertEqual(result.reason, "account_password_input_failed")
        self.assertGreater(result.failure_count, 0)

    @staticmethod
    def _write_toggle_image(path: Path, *, knob_left: bool, blue_track: bool) -> None:
        from PIL import Image, ImageDraw

        image = Image.new("RGB", (140, 95), "white")
        draw = ImageDraw.Draw(image)
        track_color = (70, 150, 240) if blue_track else (200, 204, 208)
        draw.rounded_rectangle((10, 20, 130, 75), radius=28, fill=track_color)
        knob_x = 42 if knob_left else 98
        draw.ellipse((knob_x - 32, 16, knob_x + 32, 80), fill=(255, 255, 255))
        image.save(path)

    @staticmethod
    def _write_full_source_image(path: Path) -> None:
        from PIL import Image, ImageDraw

        image = Image.new("RGB", (1080, 2340), "white")
        draw = ImageDraw.Draw(image)
        draw.rounded_rectangle((10, 20, 130, 75), radius=28, fill=(70, 150, 240))
        draw.ellipse((66, 16, 130, 80), fill=(255, 255, 255))
        image.save(path)

    @staticmethod
    def _keypad_words() -> list[OcrWord]:
        slots = [
            ("1", 135, 78),
            ("2", 405, 78),
            ("3", 675, 78),
            ("4", 945, 78),
            ("5", 135, 232),
            ("6", 405, 232),
            ("7", 675, 232),
            ("8", 945, 232),
            ("9", 135, 388),
            ("0", 405, 388),
        ]
        return [OcrWord(text, x - 10, y - 10, 20, 20, 95.0) for text, x, y in slots]


if __name__ == "__main__":
    unittest.main()
