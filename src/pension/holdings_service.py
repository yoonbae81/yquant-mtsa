from __future__ import annotations

import time
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .device_profile import DeviceProfile
from .holdings_grid import HoldingRow
from .ocr import TesseractOcr
from .screen_capture import ScreenCapture
from .ticker_cache import HoldingTickerCache, extract_ticker


class HoldingDetailDevice(Protocol):
    def tap(self, x: int | float, y: int | float) -> None:
        ...


@dataclass(frozen=True)
class HoldingOutput:
    ticker: str | None
    name: str
    qty: int | None
    avg_price: int | None

    def to_dict(self) -> dict:
        return {
            "ticker": self.ticker,
            "name": self.name,
            "qty": self.qty,
            "avg_price": self.avg_price,
        }


class HoldingsTickerResolver:
    def __init__(
        self,
        cache: HoldingTickerCache,
        *,
        device: HoldingDetailDevice | None = None,
        profile: DeviceProfile | None = None,
        capture: ScreenCapture | None = None,
        ocr: TesseractOcr | None = None,
    ):
        self.cache = cache
        self.device = device
        self.profile = profile
        self.capture = capture
        self.ocr = ocr or TesseractOcr()

    def enrich(self, rows: list[HoldingRow]) -> list[HoldingOutput]:
        outputs: list[HoldingOutput] = []
        cache_updated = False
        for row in rows:
            ticker = self.cache.get(row.name)
            if ticker is None:
                ticker = self._discover_ticker(row)
                if ticker is not None:
                    self.cache.set(row.name, ticker)
                    cache_updated = True
            outputs.append(
                HoldingOutput(
            ticker=ticker,
                    name=row.name,
                    qty=row.quantity,
                    avg_price=row.average_price,
                )
            )
        if cache_updated:
            self.cache.save()
        return outputs

    def missing_ticker_names(self, rows: list[HoldingRow]) -> list[str]:
        return [row.name for row in rows if self.cache.get(row.name) is None]

    def _discover_ticker(self, row: HoldingRow) -> str | None:
        if self.device is None or self.profile is None or self.capture is None:
            return None
        if row.tap_x is None or row.tap_y is None:
            return None
        try:
            header_region = self.profile.region("balance_holding_detail.header")
            back = self.profile.tap_point("balance_holding_detail.back")
        except (KeyError, ValueError):
            return None

        self.device.tap(row.tap_x, row.tap_y)
        time.sleep(1.5)
        source = Path("runs/holding-detail-source.png")
        crop = Path("runs/holding-detail-header.png")
        self.capture.capture(source)
        self.capture.crop(source, header_region, crop)
        result = self.ocr.recognize(crop, psm=6)
        ticker = extract_ticker(result.text)
        if ticker is not None or _looks_like_holding_detail(result.text):
            self.device.tap(back.x, back.y)
            time.sleep(1.0)
        return ticker


def _looks_like_holding_detail(text: str) -> bool:
    compact = "".join(text.split()).casefold()
    return any(anchor.casefold() in compact for anchor in ("ETF", "투자한도", "현재가", "주문"))
