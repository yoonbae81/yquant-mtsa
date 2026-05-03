import unittest

from pension.ocr import OcrResult
from pension.recovery import RecoveryDetector


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
        result = OcrResult(
            image_path="screen.png",
            language="kor+eng",
            text="새 이벤트 안내\n확인",
            words=[],
        )

        candidates = detector.detect(result)

        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].name, "common_popup")
        self.assertEqual(candidates[0].matched, ["확인"])


if __name__ == "__main__":
    unittest.main()
