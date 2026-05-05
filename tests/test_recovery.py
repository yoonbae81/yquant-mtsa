import unittest

from pension.ocr import OcrResult
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


if __name__ == "__main__":
    unittest.main()
