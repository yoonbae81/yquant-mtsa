import unittest

from pension.device_profile import DeviceProfile
from pension.scenarios import MTS_MAIN_COMPONENT, PensionScenarios


class FakeDevice:
    def __init__(self):
        self.taps = []
        self.swipes = []
        self.started = []

    def tap(self, x, y):
        self.taps.append((x, y))

    def swipe(self, x1, y1, x2, y2, duration_ms=300):
        self.swipes.append((x1, y1, x2, y2, duration_ms))

    def start_activity(self, component, **kwargs):
        self.started.append((component, kwargs))


def make_profile():
    return DeviceProfile(
        {
            "device": {"width": 1080, "height": 2340},
            "screens": {
                "home": {
                    "tap_points": {
                        "bottom_menu": {"x": 100, "y": 2160},
                    },
                    "regions": {},
                    "swipes": {
                        "scroll_to_order": {
                            "x1": 540,
                            "y1": 1830,
                            "x2": 540,
                            "y2": 930,
                            "duration_ms": 450,
                        }
                    },
                    "anchors": [],
                },
                "menu": {
                    "tap_points": {
                        "pension_tab": {"x": 625, "y": 520},
                        "balance": {"x": 420, "y": 760},
                        "order": {"x": 460, "y": 840},
                    },
                    "regions": {},
                    "anchors": [],
                },
                "order": {
                    "tap_points": {
                        "account_select": {"x": 240, "y": 410},
                        "add_product": {"x": 780, "y": 2025},
                    },
                    "regions": {},
                    "anchors": [],
                },
                "order_search": {
                    "tap_points": {
                        "first_result": {"x": 520, "y": 800},
                    },
                    "regions": {},
                    "anchors": [],
                },
                "account_select_sheet": {
                    "tap_points": {
                        "irp_account": {"x": 540, "y": 1170},
                        "dc_account": {"x": 540, "y": 1320},
                    },
                    "regions": {},
                    "anchors": [],
                },
                "balance": {
                    "tap_points": {
                        "account_select": {"x": 540, "y": 370},
                        "realtime": {"x": 105, "y": 505},
                    },
                    "regions": {},
                    "anchors": [],
                },
                "balance_account_sheet": {
                    "tap_points": {
                        "irp_account": {"x": 300, "y": 1785},
                        "dc_account": {"x": 300, "y": 1995},
                    },
                    "regions": {},
                    "anchors": [],
                },
            },
        }
    )


class PensionScenariosTests(unittest.TestCase):
    def test_open_app_starts_mts_main_activity(self):
        device = FakeDevice()

        PensionScenarios(device).open_app()

        self.assertEqual(device.started, [(MTS_MAIN_COMPONENT, {})])

    def test_open_balance_uses_only_profile_points(self):
        device = FakeDevice()

        steps = PensionScenarios(device, make_profile()).open_balance()

        self.assertEqual(device.taps, [(100, 2160), (625, 520), (420, 760)])
        self.assertEqual(
            [step.profile_key for step in steps],
            ["home.bottom_menu", "menu.pension_tab", "menu.balance"],
        )

    def test_open_order_uses_only_profile_points(self):
        device = FakeDevice()

        steps = PensionScenarios(device, make_profile()).open_order()

        self.assertEqual(device.taps, [(100, 2160), (625, 520), (460, 840)])
        self.assertEqual(device.swipes, [])
        self.assertEqual(
            [step.profile_key for step in steps],
            ["home.bottom_menu", "menu.pension_tab", "menu.order"],
        )

    def test_select_account_supports_irp_and_dc(self):
        irp_device = FakeDevice()
        dc_device = FakeDevice()

        PensionScenarios(irp_device, make_profile()).select_account("IRP")
        PensionScenarios(dc_device, make_profile()).select_account("dc")

        self.assertEqual(irp_device.taps, [(240, 410), (540, 1170)])
        self.assertEqual(dc_device.taps, [(240, 410), (540, 1320)])

    def test_balance_account_and_realtime_use_balance_profile_points(self):
        account_device = FakeDevice()
        realtime_device = FakeDevice()

        PensionScenarios(account_device, make_profile()).select_balance_account("IRP")
        PensionScenarios(realtime_device, make_profile()).ensure_balance_realtime()

        self.assertEqual(account_device.taps, [(540, 370), (300, 1785)])
        self.assertEqual(realtime_device.taps, [(105, 505)])

    def test_order_search_actions_use_profile_points(self):
        search_device = FakeDevice()
        result_device = FakeDevice()

        PensionScenarios(search_device, make_profile()).open_order_search()
        PensionScenarios(result_device, make_profile()).select_first_search_result()

        self.assertEqual(search_device.taps, [(780, 2025)])
        self.assertEqual(result_device.taps, [(520, 800)])

    def test_select_account_rejects_unknown_type(self):
        with self.assertRaises(ValueError):
            PensionScenarios(FakeDevice(), make_profile()).select_account("ISA")

    def test_missing_points_reports_uncalibrated_profile_keys(self):
        profile = make_profile()
        del profile.data["screens"]["menu"]["tap_points"]["balance"]

        missing = PensionScenarios.missing_points(
            profile,
            PensionScenarios.scenario_points("open-balance"),
        )

        self.assertEqual(missing, ["menu.balance"])


if __name__ == "__main__":
    unittest.main()
