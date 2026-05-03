import unittest

from pension.run_modes import decide_submit_permission


class RunModeTests(unittest.TestCase):
    def test_non_real_modes_never_tap_submit(self):
        for mode in ["inspect", "dry-run", "confirm-run"]:
            decision = decide_submit_permission(mode, verified=True, explicit_real_run=True)
            self.assertFalse(decision.may_tap_submit)

    def test_real_run_requires_verification_and_explicit_flag(self):
        self.assertFalse(
            decide_submit_permission("real-run", verified=False, explicit_real_run=True).may_tap_submit
        )
        self.assertFalse(
            decide_submit_permission("real-run", verified=True, explicit_real_run=False).may_tap_submit
        )
        self.assertTrue(
            decide_submit_permission("real-run", verified=True, explicit_real_run=True).may_tap_submit
        )


if __name__ == "__main__":
    unittest.main()
