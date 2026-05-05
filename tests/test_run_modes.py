import unittest

from pension.run_modes import decide_submit_permission


class RunModeTests(unittest.TestCase):
    def test_inspect_and_dry_run_do_not_open_confirmation(self):
        for mode in ["inspect", "dry-run"]:
            decision = decide_submit_permission(mode, verified=True, explicit_real_run=True)
            self.assertFalse(decision.may_open_confirmation)
            self.assertFalse(decision.may_tap_submit)

    def test_confirm_run_opens_confirmation_but_never_taps_final_submit(self):
        decision = decide_submit_permission("confirm-run", verified=True, explicit_real_run=True)

        self.assertTrue(decision.may_open_confirmation)
        self.assertFalse(decision.may_tap_submit)
        self.assertFalse(decision.may_tap_final_submit)

    def test_confirm_run_requires_verification(self):
        decision = decide_submit_permission("confirm-run", verified=False)

        self.assertFalse(decision.may_open_confirmation)

    def test_manual_submit_opens_confirmation_without_auto_submit_or_cancel(self):
        decision = decide_submit_permission("manual-submit", verified=True, explicit_real_run=True)

        self.assertTrue(decision.may_open_confirmation)
        self.assertFalse(decision.may_tap_submit)
        self.assertFalse(decision.may_tap_final_submit)
        self.assertFalse(decision.should_cancel_confirmation)

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
        self.assertTrue(
            decide_submit_permission("real-run", verified=True, explicit_real_run=True).may_open_confirmation
        )

    def test_real_run_requires_config_allow_flag_when_provided(self):
        self.assertFalse(
            decide_submit_permission(
                "real-run",
                verified=True,
                explicit_real_run=True,
                config_allows_real_run=False,
            ).may_tap_submit
        )


if __name__ == "__main__":
    unittest.main()
