from __future__ import annotations

from dataclasses import dataclass

from .device_profile import Region
from .ocr import OcrResult


@dataclass(frozen=True)
class KeypadSlot:
    index: int
    row: int
    column: int
    x: int
    y: int

    def translated(self, dx: int, dy: int) -> KeypadSlot:
        return KeypadSlot(
            index=self.index,
            row=self.row,
            column=self.column,
            x=self.x + dx,
            y=self.y + dy,
        )

    def to_dict(self) -> dict:
        return {
            "index": self.index,
            "row": self.row,
            "column": self.column,
            "x": self.x,
            "y": self.y,
        }


@dataclass(frozen=True)
class KeypadMapping:
    digits: dict[str, KeypadSlot]
    empty_slots: list[KeypadSlot]

    @property
    def complete(self) -> bool:
        return set(self.digits) == set("0123456789") and len(self.empty_slots) == 2

    def to_dict(self) -> dict:
        return {
            "digits": {digit: slot.to_dict() for digit, slot in sorted(self.digits.items())},
            "empty_slots": [slot.to_dict() for slot in self.empty_slots],
            "complete": self.complete,
        }

    def translated(self, dx: int, dy: int) -> KeypadMapping:
        return KeypadMapping(
            digits={digit: slot.translated(dx, dy) for digit, slot in self.digits.items()},
            empty_slots=[slot.translated(dx, dy) for slot in self.empty_slots],
        )


class RandomNumericKeypadMapper:
    def __init__(self, region: Region, *, rows: int = 3, columns: int = 4):
        self.region = region
        self.rows = rows
        self.columns = columns
        self.slots = self._build_slots()

    def map_digits(self, ocr_result: OcrResult) -> KeypadMapping:
        digits: dict[str, KeypadSlot] = {}
        occupied: set[int] = set()
        for word in ocr_result.words:
            text = word.text.strip()
            if len(text) != 1 or not text.isdigit():
                continue
            center_x = word.left + word.width / 2
            center_y = word.top + word.height / 2
            slot = self._nearest_slot(center_x, center_y)
            if text not in digits:
                digits[text] = slot
                occupied.add(slot.index)

        empty_slots = [slot for slot in self.slots if slot.index not in occupied]
        return KeypadMapping(digits=digits, empty_slots=empty_slots)

    def _build_slots(self) -> list[KeypadSlot]:
        slot_width = self.region.w / self.columns
        slot_height = self.region.h / self.rows
        slots: list[KeypadSlot] = []
        index = 0
        for row in range(self.rows):
            for column in range(self.columns):
                slots.append(
                    KeypadSlot(
                        index=index,
                        row=row,
                        column=column,
                        x=round(self.region.x + slot_width * column + slot_width / 2),
                        y=round(self.region.y + slot_height * row + slot_height / 2),
                    )
                )
                index += 1
        return slots

    def _nearest_slot(self, x: float, y: float) -> KeypadSlot:
        return min(
            self.slots,
            key=lambda slot: ((slot.x - x) ** 2) + ((slot.y - y) ** 2),
        )
