import unittest
from contextlib import redirect_stderr
from io import StringIO

from pension.cli import build_parser


class CliParserTests(unittest.TestCase):
    def test_order_command_replaces_retirement_order(self):
        args = build_parser().parse_args(
            [
                "order",
                "--account",
                "IRP",
                "--side",
                "buy",
                "--symbol-code",
                "360750",
                "--quantity",
                "1",
                "--expected-amount",
                "45000",
            ]
        )

        self.assertEqual(args.command, "order")
        self.assertEqual(args.expected_amount, 45000)

    def test_retirement_order_command_is_not_registered(self):
        with redirect_stderr(StringIO()), self.assertRaises(SystemExit):
            build_parser().parse_args(
                [
                    "retirement-order",
                    "--account",
                    "IRP",
                    "--side",
                    "buy",
                    "--symbol-code",
                    "360750",
                    "--quantity",
                    "1",
                ]
            )

    def test_market_roundtrip_defaults_to_guarded_tiger_cosmetics_confirm_run(self):
        args = build_parser().parse_args(
            [
                "market-roundtrip",
                "--account",
                "IRP",
            ]
        )

        self.assertEqual(args.command, "market-roundtrip")
        self.assertEqual(args.symbol_code, "228790")
        self.assertEqual(args.symbol_name, "TIGER 화장품")
        self.assertEqual(args.quantity, 1)
        self.assertEqual(args.mode, "confirm-run")
        self.assertFalse(args.acknowledge_live_trade)

    def test_market_roundtrip_real_run_acknowledgement_flag(self):
        args = build_parser().parse_args(
            [
                "market-roundtrip",
                "--account",
                "IRP",
                "--mode",
                "real-run",
                "--explicit-real-run",
                "--acknowledge-live-trade",
            ]
        )

        self.assertTrue(args.explicit_real_run)
        self.assertTrue(args.acknowledge_live_trade)

    def test_order_accepts_manual_submit_mode(self):
        args = build_parser().parse_args(
            [
                "order",
                "--account",
                "IRP",
                "--side",
                "buy",
                "--symbol-code",
                "228790",
                "--symbol-name",
                "TIGER 화장품",
                "--quantity",
                "1",
                "--mode",
                "manual-submit",
            ]
        )

        self.assertEqual(args.mode, "manual-submit")


if __name__ == "__main__":
    unittest.main()
