import tempfile
import unittest
from pathlib import Path

from pension.device_profile import DeviceProfile


class DeviceProfileTests(unittest.TestCase):
    def test_loads_tap_points_and_regions(self):
        profile = DeviceProfile(
            {
                "device": {"serial": "abc", "width": 1080, "height": 2400, "density": 420},
                "screens": {
                    "order": {
                        "anchors": ["비밀번호"],
                        "tap_points": {
                            "account_password": {"x": 900, "y": 548, "rx": 0.8333, "ry": 0.2283}
                        },
                        "regions": {
                            "account_row": {"x": 40, "y": 490, "w": 1000, "h": 130}
                        },
                    }
                },
            }
        )

        point = profile.tap_point("order.account_password")
        region = profile.region("order.account_row")

        self.assertEqual((point.x, point.y), (900, 548))
        self.assertEqual((region.x, region.y, region.w, region.h), (40, 490, 1000, 130))
        self.assertEqual(profile.anchors("order"), ["비밀번호"])

    def test_can_create_and_save_minimal_profile(self):
        profile = DeviceProfile.from_device_info(serial="abc", width=1080, height=2400, density=420)

        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "profile.json"
            profile.save(path)
            loaded = DeviceProfile.load(path)

            self.assertIsNone(loaded.serial)
            self.assertEqual(loaded.width, 1080)
            self.assertEqual(loaded.height, 2400)

    def test_can_set_points_and_regions_with_ratios(self):
        profile = DeviceProfile.from_device_info(serial="abc", width=1080, height=2400, density=420)

        point = profile.set_tap_point("order.account_password", 900, 548)
        region = profile.set_region("order.holding_quantity", 580, 1010, 430, 90)
        swipe = profile.set_swipe("menu.scroll_to_order", 540, 1830, 540, 930, 450)

        self.assertEqual((point.x, point.y, point.rx, point.ry), (900, 548, 0.833333, 0.228333))
        self.assertEqual((region.x, region.y, region.w, region.h), (580, 1010, 430, 90))
        self.assertEqual((swipe.x1, swipe.y1, swipe.x2, swipe.y2, swipe.duration_ms), (540, 1830, 540, 930, 450))

    def test_reads_post_action_delay_from_profile_entries(self):
        profile = DeviceProfile(
            {
                "device": {"width": 1080, "height": 2400},
                "screens": {
                    "menu": {
                        "anchors": [],
                        "tap_points": {
                            "pension_tab": {"x": 625, "y": 520, "post_delay_ms": 3000}
                        },
                        "swipes": {
                            "scroll_to_order": {
                                "x1": 540,
                                "y1": 1830,
                                "x2": 540,
                                "y2": 930,
                                "duration_ms": 450,
                                "post_delay_ms": 1200,
                            }
                        },
                    }
                },
            }
        )

        self.assertEqual(profile.post_delay_ms("menu.pension_tab"), 3000)
        self.assertEqual(profile.post_delay_ms("menu.scroll_to_order"), 1200)
        self.assertEqual(profile.post_delay_ms("menu.unknown", default=700), 700)

    def test_can_save_and_load_profile_directory(self):
        profile = DeviceProfile.from_device_info(serial="abc", width=1080, height=2340, density=420)
        profile.data["screens"]["order"] = {
            "anchors": {
                "required": ["비밀번호"],
                "optional": ["매수", "매도"],
                "forbidden": ["오류"],
                "min_score": 0.8,
            },
            "tap_points": {"account_password": {"x": 900, "y": 548}},
            "regions": {"account_row": {"x": 40, "y": 490, "w": 1000, "h": 130}},
        }

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "1080x2340"
            profile.save(root)
            loaded = DeviceProfile.load(root)

            self.assertEqual(loaded.width, 1080)
            self.assertIsNone(loaded.serial)
            self.assertEqual(loaded.tap_point("order.account_password").x, 900)
            self.assertEqual(loaded.anchors("order")["required"], ["비밀번호"])

    def test_load_profile_directory_merges_duplicate_screen_files(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "1080x2340"
            root.mkdir()
            (root / "manifest.json").write_text(
                '{"schema_version": 1, "device": {"width": 1080, "height": 2340}}\n',
                encoding="utf-8",
            )
            (root / "keypads.json").write_text(
                """
{
  "screen": "secure_number_keypad",
  "tap_points": {"complete": {"x": 810, "y": 2125}},
  "regions": {"digit_grid": {"x": 0, "y": 1595, "w": 1080, "h": 465}}
}
""".strip()
                + "\n",
                encoding="utf-8",
            )
            (root / "secure-number-keypad.json").write_text(
                """
{
  "screen": "secure_number_keypad",
  "anchors": {"required": [], "optional": ["입력"], "forbidden": []}
}
""".strip()
                + "\n",
                encoding="utf-8",
            )

            loaded = DeviceProfile.load(root)

        self.assertEqual(loaded.tap_point("secure_number_keypad.complete").x, 810)
        self.assertEqual(loaded.region("secure_number_keypad.digit_grid").h, 465)
        self.assertEqual(loaded.anchors("secure_number_keypad")["optional"], ["입력"])

    def test_validate_rejects_serial_and_out_of_bounds_regions(self):
        profile = DeviceProfile(
            {
                "device": {"serial": "abc", "width": 1080, "height": 2340},
                "screens": {
                    "order": {
                        "anchors": {"required": ["퇴직연금"]},
                        "tap_points": {"bad": {"x": 1200, "y": 10}},
                        "regions": {"bad": {"x": 1000, "y": 0, "w": 200, "h": 100}},
                    }
                },
            }
        )

        errors = profile.validate()

        self.assertIn("device.serial must not be stored in a shared profile", errors)
        self.assertIn("order.tap_points.bad is outside device bounds", errors)
        self.assertIn("order.regions.bad is outside device bounds", errors)

    def test_reads_recovery_tap_point(self):
        profile = DeviceProfile(
            {
                "device": {"width": 1080, "height": 2340},
                "screens": {},
                "recoveries": {
                    "app_exit_confirm": {
                        "tap_points": {
                            "cancel": {"x": 340, "y": 1320, "rx": 0.314815, "ry": 0.564103}
                        }
                    }
                },
            }
        )

        point = profile.recovery_tap_point("app_exit_confirm.cancel")

        self.assertEqual((point.x, point.y, point.rx, point.ry), (340, 1320, 0.314815, 0.564103))


if __name__ == "__main__":
    unittest.main()
