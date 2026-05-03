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
            ]
        )

        self.assertEqual(args.command, "order")

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


if __name__ == "__main__":
    unittest.main()
