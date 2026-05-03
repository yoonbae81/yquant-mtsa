from __future__ import annotations

import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from .ocr import OcrResult, OcrWord


@dataclass(frozen=True)
class HoldingRow:
    name: str
    quantity: int | None
    average_price: int | None
    tap_x: int | None = None
    tap_y: int | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def to_output_dict(self, ticker: str | None = None) -> dict[str, Any]:
        return {
            "ticker": ticker,
            "name": self.name,
            "qty": self.quantity,
            "avg_price": self.average_price,
        }


def load_ocr_json(path: str | Path) -> OcrResult:
    with Path(path).open("r", encoding="utf-8") as f:
        data = json.load(f)
    return OcrResult(
        image_path=data.get("image_path", str(path)),
        language=data.get("language", "kor+eng"),
        text=data.get("text", ""),
        words=[OcrWord(**word) for word in data.get("words", [])],
    )


class BalanceHoldingsGridParser:
    """Parse the portrait balance grid from paired horizontal scroll captures."""

    def parse(self, left: OcrResult, right: OcrResult) -> list[HoldingRow]:
        left_rows = self._parse_left_rows(left.words)
        right_rows = self._parse_right_rows(right.words)
        rows: list[HoldingRow] = []
        for index, left_row in enumerate(left_rows):
            rows.append(
                HoldingRow(
                    name=left_row["name"],
                    quantity=left_row["quantity"],
                    average_price=right_rows[index]["average_price"] if index < len(right_rows) else None,
                    tap_x=left_row["tap_x"],
                    tap_y=left_row["tap_y"],
                )
            )
        return rows

    def _parse_left_rows(self, words: list[OcrWord]) -> list[dict[str, Any]]:
        row_bands = self._row_bands(words)
        rows: list[dict[str, Any]] = []
        for top, bottom in row_bands:
            row_words = [word for word in words if top <= self._center_y(word) <= bottom]
            name = self._join_name(word for word in row_words if word.left < 290)
            quantity = self._first_number(word for word in row_words if 610 <= word.left <= 850)
            if name:
                rows.append(
                    {
                        "name": name,
                        "quantity": quantity,
                        "tap_x": 145,
                        "tap_y": (top + bottom) // 2,
                    }
                )
        return rows

    def _parse_right_rows(self, words: list[OcrWord]) -> list[dict[str, Any]]:
        row_bands = self._row_bands(words)
        rows: list[dict[str, Any]] = []
        for top, bottom in row_bands:
            row_words = [word for word in words if top <= self._center_y(word) <= bottom]
            average_price = self._first_number(word for word in row_words if 650 <= word.left <= 930)
            if average_price is not None:
                rows.append({"average_price": average_price})
        return rows

    def _row_bands(self, words: list[OcrWord]) -> list[tuple[int, int]]:
        anchors = sorted(
            word.top
            for word in words
            if word.left < 290 and 1240 <= word.top <= 2050 and self._looks_like_holding_name(word.text)
        )
        bands: list[tuple[int, int]] = []
        for top in anchors:
            if bands and top <= bands[-1][1]:
                bands[-1] = (bands[-1][0], max(bands[-1][1], top + 95))
            else:
                bands.append((top - 20, top + 115))
        return bands

    def _join_name(self, words: Any) -> str:
        ordered = sorted(words, key=lambda word: (word.top, word.left))
        lines: list[list[OcrWord]] = []
        for word in ordered:
            if not lines or abs(word.top - min(item.top for item in lines[-1])) > 24:
                lines.append([word])
            else:
                lines[-1].append(word)
        parts: list[str] = []
        for line in lines:
            for word in sorted(line, key=lambda item: item.left):
                part = self._clean_name_token(word.text)
                if part and not self._is_ignored_name_token(part, reject_single_korean=False):
                    parts.append(part)
        return self._normalize_name("".join(parts))

    def _first_number(self, words: Any) -> int | None:
        candidates: list[tuple[int, int, int]] = []
        for word in words:
            value = re.sub(r"[^0-9]", "", word.text)
            if value:
                candidates.append((word.top, word.left, int(value)))
        return sorted(candidates)[0][2] if candidates else None

    def _looks_like_holding_name(self, text: str) -> bool:
        cleaned = self._clean_name_token(text)
        if not cleaned:
            return False
        if self._is_ignored_name_token(cleaned, reject_single_korean=True):
            return False
        if re.fullmatch(r"[0-9,.\-+%원주]+", cleaned):
            return False
        return any(ch.isalpha() or "\uac00" <= ch <= "\ud7a3" for ch in cleaned)

    def _is_ignored_name_token(self, cleaned: str, *, reject_single_korean: bool) -> bool:
        if cleaned.casefold() in {"san"}:
            return True
        if cleaned in {
            "초기화",
            "초기화1",
            "잔고구분",
            "수익률순",
            "종목명",
            "평가손익",
            "수익률",
            "평가금액",
            "보유수량",
            "매도가",
            "비중",
            "종",
            "목",
            "명",
        }:
            return True
        if len(cleaned) == 1 and "\uac00" <= cleaned <= "\ud7a3":
            return reject_single_korean
        return False

    def _clean_name_token(self, text: str) -> str:
        return re.sub(r"[^0-9A-Za-z가-힣]", "", text)

    def _normalize_name(self, name: str) -> str:
        replacements = {
            "삼성전자5하이닉스": "삼성전자SK하이닉스",
            "RISEKOFR2리액티브합성": "RISEKOFR금리액티브(합성)",
            "KODEX14410년액티브": "KODEX국고채10년액티브",
        }
        for source, target in replacements.items():
            name = name.replace(source, target)
        return name

    def _center_y(self, word: OcrWord) -> int:
        return word.top + (word.height // 2)
