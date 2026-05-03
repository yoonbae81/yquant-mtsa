import tempfile
import unittest
from pathlib import Path

from pension.holdings_grid import HoldingRow
from pension.holdings_service import HoldingsTickerResolver
from pension.ticker_cache import HoldingTickerCache, extract_ticker, normalize_holding_name


class HoldingTickerCacheTests(unittest.TestCase):
    def test_cache_loads_name_ticker_tsv(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "holding-tickers.tsv"
            path.write_text("TIGER 미국S&P500\t360750\nACE 미국30년\t0162Z0\n", encoding="utf-8")

            cache = HoldingTickerCache(path)

            self.assertEqual(cache.get("TIGER미국S&P500"), "360750")
            self.assertEqual(cache.get("ACE 미국30년"), "0162Z0")

    def test_cache_saves_only_name_and_ticker(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "holding-tickers.tsv"
            cache = HoldingTickerCache(path)

            cache.set("TIGER 미국S&P500", "360750")
            cache.save()

            self.assertEqual(path.read_text(encoding="utf-8"), "TIGER 미국S&P500\t360750\n")

    def test_resolver_outputs_required_holding_shape(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "holding-tickers.tsv"
            path.write_text("TIGER 미국S&P500\t360750\n", encoding="utf-8")
            cache = HoldingTickerCache(path)

            rows = [
                HoldingRow(name="TIGER미국S&P500", quantity=5, average_price=10000),
                HoldingRow(name="새종목", quantity=2, average_price=3000),
            ]
            output = HoldingsTickerResolver(cache).enrich(rows)

            self.assertEqual(
                [row.to_dict() for row in output],
                [
                    {"ticker": "360750", "name": "TIGER미국S&P500", "qty": 5, "avg_price": 10000},
                    {"ticker": None, "name": "새종목", "qty": 2, "avg_price": 3000},
                ],
            )

    def test_resolver_reports_missing_tickers(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "holding-tickers.tsv"
            path.write_text("TIGER 미국S&P500\t360750\n", encoding="utf-8")
            cache = HoldingTickerCache(path)
            rows = [
                HoldingRow(name="TIGER미국S&P500", quantity=5, average_price=10000),
                HoldingRow(name="새종목", quantity=2, average_price=3000),
            ]

            missing = HoldingsTickerResolver(cache).missing_ticker_names(rows)

            self.assertEqual(missing, ["새종목"])

    def test_extract_ticker_requires_digit(self):
        self.assertEqual(extract_ticker("ACE 미국30년 0162Z0 현재가"), "0162Z0")
        self.assertEqual(extract_ticker("TIGER 471230 ETF"), "471230")
        self.assertIsNone(extract_ticker("ETF PRICE"))

    def test_normalize_holding_name_ignores_spaces_and_case(self):
        self.assertEqual(normalize_holding_name(" TIGER S&P "), normalize_holding_name("tigers&p"))


if __name__ == "__main__":
    unittest.main()
