from __future__ import annotations

import re
from pathlib import Path


DEFAULT_TICKER_CACHE_PATH = Path("state/holding-tickers.tsv")


class HoldingTickerCache:
    def __init__(self, path: str | Path = DEFAULT_TICKER_CACHE_PATH):
        self.path = Path(path)
        self._tickers: dict[str, tuple[str, str]] = {}
        self.load()

    def get(self, name: str) -> str | None:
        item = self._tickers.get(normalize_holding_name(name))
        return item[1] if item else None

    def set(self, name: str, ticker: str) -> None:
        normalized_ticker = normalize_ticker(ticker)
        self._tickers[normalize_holding_name(name)] = (name, normalized_ticker)

    def enrich(self, name: str, *, qty: int | None, avg_price: int | None) -> dict:
        return {
            "ticker": self.get(name),
            "name": name,
            "qty": qty,
            "avg_price": avg_price,
        }

    def load(self) -> None:
        self._tickers = {}
        if not self.path.exists():
            return
        with self.path.open("r", encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) < 2:
                    continue
                name, ticker = parts[0].strip(), parts[1].strip()
                if not name or not ticker:
                    continue
                try:
                    self.set(name, ticker)
                except ValueError:
                    continue

    def save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        rows = sorted(self._tickers.values(), key=lambda item: normalize_holding_name(item[0]))
        with self.path.open("w", encoding="utf-8") as f:
            for name, ticker in rows:
                f.write(f"{name}\t{ticker}\n")


def normalize_holding_name(name: str) -> str:
    return re.sub(r"\s+", "", name).casefold()


def normalize_ticker(ticker: str) -> str:
    normalized = ticker.strip().upper()
    if not re.fullmatch(r"[0-9A-Z]{5,6}", normalized):
        raise ValueError(f"Invalid ticker: {ticker}")
    return normalized


def extract_ticker(text: str) -> str | None:
    candidates = re.findall(r"\b[0-9A-Z]{5,6}\b", text.upper())
    for candidate in candidates:
        if re.search(r"\d", candidate):
            return candidate
    return None
