import unittest

from pension.screen_values import (
    ExpectedBalance,
    ExpectedFilledResult,
    ExpectedOrder,
    normalize_account,
    parse_number,
    verify_balance_text,
    verify_filled_result_text,
    verify_order_text,
)


class ScreenValuesTests(unittest.TestCase):
    def test_parse_number_ignores_formatting(self):
        self.assertEqual(parse_number("1,234주"), 1234)
        self.assertEqual(parse_number("12,345원"), 12345)

    def test_normalize_account_keeps_masking(self):
        self.assertEqual(normalize_account("123-**-4567"), "123**4567")

    def test_verify_balance_text_matches_expected_fields(self):
        result = verify_balance_text(
            "IRP 1234 ETF TIGER 미국S&P500 10주 평가금액 100,000원",
            ExpectedBalance(
                account_type="IRP",
                account_hint="1234",
                symbol_name="TIGER 미국S&P500",
                min_quantity=10,
            ),
        )

        self.assertTrue(result.passed)
        self.assertEqual(result.missing, [])

    def test_verify_order_text_reports_missing_fields(self):
        result = verify_order_text(
            "DC 9999 KODEX 미국나스닥100 매수 수량 3 금액 45000",
            ExpectedOrder(
                account_type="IRP",
                account_hint="9999",
                symbol_name="KODEX 미국나스닥100",
                side="매수",
                quantity=3,
                amount=45000,
            ),
        )

        self.assertFalse(result.passed)
        self.assertIn("account_type:IRP", result.missing)
        self.assertIn("quantity:3", result.matched)

    def test_verify_order_text_matches_market_price_type(self):
        result = verify_order_text(
            "IRP TIGER 미국S&P500 매수 시장가 수량 7",
            ExpectedOrder(
                account_type="IRP",
                symbol_name="TIGER 미국S&P500",
                side="매수",
                quantity=7,
                price_type="시장가",
            ),
        )

        self.assertTrue(result.passed)

    def test_verify_filled_result_text_matches_trade_fields(self):
        result = verify_filled_result_text(
            "체결결과 IRP 228790 TIGER 화장품 매도 시장가 체결수량 1",
            ExpectedFilledResult(
                account_type="IRP",
                symbol_code="228790",
                symbol_name="TIGER 화장품",
                side="매도",
                quantity=1,
                price_type="시장가",
            ),
        )

        self.assertTrue(result.passed)


if __name__ == "__main__":
    unittest.main()
