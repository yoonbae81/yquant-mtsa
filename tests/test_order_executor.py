import tempfile
import unittest
from pathlib import Path

from PIL import Image

from pension.config import PensionConfig
from pension.device_profile import DeviceProfile
from pension.ocr import OcrResult
from pension.order_executor import OrderExecutor, OrderRequest, quantity_text_matches
from pension.run_artifacts import RunArtifacts


class FakeDevice:
    def __init__(self):
        self.taps = []
        self.keyevents = []
        self.text_inputs = []

    def tap(self, x, y):
        self.taps.append((x, y))

    def keyevent(self, key):
        self.keyevents.append(key)

    def input_text(self, text):
        self.text_inputs.append(text)


class FakeCapture:
    def capture(self, output_path):
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


def make_profile():
    return DeviceProfile(
        {
            "device": {"width": 1080, "height": 2340},
            "screens": {
                "order": {
                    "tap_points": {
                        "quantity_input": {"x": 740, "y": 1295, "post_delay_ms": 0},
                    },
                    "regions": {
                        "quantity_input": {"x": 445, "y": 1250, "w": 590, "h": 90},
                    },
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
                    },
                    "regions": {
                        "summary": {"x": 60, "y": 760, "w": 960, "h": 920},
                    },
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


if __name__ == "__main__":
    unittest.main()
