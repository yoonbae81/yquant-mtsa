import unittest

from pension.ocr import OcrResult, OcrWord
from pension.device_profile import DeviceProfile
from pension.recovery import RecoveryDetector, RecoveryHandler


class FakeDevice:
    def __init__(self):
        self.taps = []
        self.back_count = 0

    def tap(self, x, y):
        self.taps.append((x, y))

    def press_back(self):
        self.back_count += 1


class NoBackDevice:
    def __init__(self):
        self.taps = []

    def tap(self, x, y):
        self.taps.append((x, y))


def ocr(text):
    return OcrResult(image_path="screen.png", language="kor+eng", text=text, words=[])


def word(text, left, top, width=40, height=30, confidence=90):
    return OcrWord(text=text, left=left, top=top, width=width, height=height, confidence=confidence)


def ocr_with_words(text, words):
    return OcrResult(image_path="screen.png", language="kor+eng", text=text, words=words)


def make_profile(recoveries):
    return DeviceProfile({"device": {"width": 1080, "height": 2340}, "screens": {}, "recoveries": recoveries})


class RecoveryTests(unittest.TestCase):
    def test_detects_matching_recovery_candidate(self):
        detector = RecoveryDetector(
            {
                "common_popup": {
                    "anchors": ["확인", "오늘 하루 보지 않기"],
                    "tap_points": {"confirm": {"x": 1, "y": 2}},
                    "max_attempts": 2,
                }
            }
        )
        result = ocr("새 이벤트 안내\n확인")

        candidates = detector.detect(result)

        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].name, "common_popup")
        self.assertEqual(candidates[0].matched, ["확인"])

    def test_handler_taps_only_recovery_point(self):
        device = FakeDevice()
        profile = make_profile(
            {
                "common_popup": {
                    "anchors": ["닫기"],
                    "tap_points": {"close": {"x": 10, "y": 20}},
                    "allowed_actions": ["close"],
                    "max_attempts": 1,
                }
            }
        )

        result = RecoveryHandler(device, profile).handle(ocr("이벤트 안내 닫기"))

        self.assertTrue(result.handled)
        self.assertEqual(result.to_dict()["action"], "close")
        self.assertEqual(device.taps, [(10, 20)])

    def test_handler_respects_allowed_actions(self):
        device = FakeDevice()
        profile = make_profile(
            {
                "common_popup": {
                    "anchors": ["닫기"],
                    "tap_points": {"close": {"x": 10, "y": 20}, "confirm": {"x": 30, "y": 40}},
                    "allowed_actions": ["confirm"],
                    "max_attempts": 1,
                }
            }
        )

        result = RecoveryHandler(device, profile).handle(ocr("이벤트 안내 닫기"))

        self.assertTrue(result.handled)
        self.assertEqual(result.action, "confirm")
        self.assertEqual(device.taps, [(30, 40)])

    def test_universal_popup_taps_ocr_button_text(self):
        device = FakeDevice()
        profile = make_profile(
            {
                "universal_popup": {
                    "anchors": ["안내", "공지", "알림"],
                    "button_labels": {"later": ["다음에"]},
                    "tap_points": {"later": {"x": 200, "y": 2060}},
                    "allowed_actions": ["later"],
                    "max_attempts": 1,
                }
            }
        )

        result = RecoveryHandler(device, profile).handle(
            ocr_with_words("안내", [word("다음", 120, 2030), word("에", 170, 2030, width=24)])
        )

        self.assertTrue(result.handled)
        self.assertEqual(result.candidate, "universal_popup")
        self.assertEqual(result.action, "later")
        self.assertEqual(device.taps, [(157, 2045)])

    def test_universal_popup_uses_safe_fallback_when_button_ocr_is_missing(self):
        device = FakeDevice()
        profile = make_profile(
            {
                "universal_popup": {
                    "anchors": ["안내", "공지", "알림"],
                    "button_labels": {"later": ["다음에"]},
                    "fallback_actions": {"later": ["알려드릴게요", "다음"]},
                    "tap_points": {"later": {"x": 200, "y": 2060}},
                    "allowed_actions": ["later"],
                    "max_attempts": 1,
                }
            }
        )

        result = RecoveryHandler(device, profile).handle(ocr("다음 매수 가능한 시간에 알려드릴게요"))

        self.assertTrue(result.handled)
        self.assertEqual(result.candidate, "universal_popup")
        self.assertEqual(result.action, "later")
        self.assertEqual(device.taps, [(200, 2060)])

    def test_handler_rejects_confirm_when_action_is_missing(self):
        device = FakeDevice()
        profile = make_profile(
            {
                "common_popup": {
                    "anchors": ["확인"],
                    "tap_points": {"close": {"x": 10, "y": 20}},
                    "allowed_actions": ["confirm"],
                    "max_attempts": 1,
                }
            }
        )

        result = RecoveryHandler(device, profile).handle(ocr("확인"))

        self.assertTrue(result.detected)
        self.assertFalse(result.handled)
        self.assertEqual(result.reason, "no_allowed_action")
        self.assertEqual(device.taps, [])

    def test_handler_handles_back_action(self):
        device = FakeDevice()
        profile = make_profile(
            {
                "exit_prompt": {
                    "anchors": ["종료하시겠습니까"],
                    "tap_points": {},
                    "allowed_actions": ["back"],
                    "max_attempts": 1,
                }
            }
        )

        result = RecoveryHandler(device, profile).handle(ocr("종료하시겠습니까"))

        self.assertTrue(result.handled)
        self.assertEqual(result.action, "back")
        self.assertEqual(device.back_count, 1)
        self.assertEqual(device.taps, [])

    def test_handler_rejects_candidate_with_no_allowed_action(self):
        device = FakeDevice()
        profile = make_profile(
            {
                "common_popup": {
                    "anchors": ["확인"],
                    "tap_points": {"confirm": {"x": 30, "y": 40}},
                    "allowed_actions": ["close"],
                    "max_attempts": 1,
                }
            }
        )

        result = RecoveryHandler(device, profile).handle(ocr("확인"))

        self.assertTrue(result.detected)
        self.assertFalse(result.handled)
        self.assertEqual(result.reason, "no_allowed_action")
        self.assertEqual(device.taps, [])

    def test_handler_marks_back_unhandled_when_device_lacks_press_back(self):
        device = NoBackDevice()
        profile = make_profile(
            {
                "exit_prompt": {
                    "anchors": ["종료하시겠습니까"],
                    "tap_points": {},
                    "allowed_actions": ["back"],
                    "max_attempts": 1,
                }
            }
        )

        result = RecoveryHandler(device, profile).handle(ocr("종료하시겠습니까"))

        self.assertFalse(result.handled)
        self.assertEqual(result.reason, "back_unavailable")
        self.assertEqual(device.taps, [])

    def test_handler_respects_max_attempts(self):
        device = FakeDevice()
        profile = make_profile(
            {
                "common_popup": {
                    "anchors": ["닫기"],
                    "tap_points": {"close": {"x": 10, "y": 20}},
                    "allowed_actions": ["close"],
                    "max_attempts": 1,
                }
            }
        )
        handler = RecoveryHandler(device, profile)

        first = handler.handle(ocr("닫기"))
        second = handler.handle(ocr("닫기"))

        self.assertTrue(first.handled)
        self.assertFalse(second.handled)
        self.assertEqual(second.reason, "max_attempts_exceeded")
        self.assertEqual(device.taps, [(10, 20)])

    def test_detector_respects_min_matches(self):
        detector = RecoveryDetector(
            {
                "common_popup": {
                    "anchors": ["닫기", "확인", "오늘 하루"],
                    "tap_points": {},
                    "min_matches": 2,
                    "max_attempts": 1,
                }
            }
        )

        self.assertEqual(detector.detect(ocr("메뉴 닫기")), [])
        candidates = detector.detect(ocr("안내 팝업 닫기 확인"))
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].matched, ["닫기", "확인"])


if __name__ == "__main__":
    unittest.main()
