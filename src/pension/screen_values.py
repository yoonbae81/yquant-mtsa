from __future__ import annotations

import re
from dataclasses import dataclass

from .screen_state import normalize_text


def parse_number(value: str) -> int:
    digits = re.sub(r"[^0-9-]", "", value)
    if not digits or digits == "-":
        raise ValueError(f"No number found in: {value!r}")
    return int(digits)


def normalize_account(value: str) -> str:
    return re.sub(r"[^0-9*]", "", value)


def normalize_code(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z]", "", value).upper()


@dataclass(frozen=True)
class ExpectedBalance:
    account_type: str | None = None
    account_hint: str | None = None
    symbol_code: str | None = None
    symbol_name: str | None = None
    min_quantity: int | None = None


@dataclass(frozen=True)
class ExpectedOrder:
    account_type: str | None = None
    account_hint: str | None = None
    symbol_code: str | None = None
    symbol_name: str | None = None
    side: str | None = None
    quantity: int | None = None
    amount: int | None = None
    price_type: str | None = None


@dataclass(frozen=True)
class VerificationResult:
    target: str
    passed: bool
    matched: list[str]
    missing: list[str]

    def to_dict(self) -> dict:
        return {
            "target": self.target,
            "passed": self.passed,
            "matched": self.matched,
            "missing": self.missing,
        }


def verify_balance_text(text: str, expected: ExpectedBalance) -> VerificationResult:
    checks: list[tuple[str, bool]] = []
    normalized = normalize_text(text)
    if expected.account_type:
        checks.append((f"account_type:{expected.account_type.upper()}", expected.account_type.upper() in text.upper()))
    if expected.account_hint:
        checks.append((f"account_hint:{expected.account_hint}", normalize_account(expected.account_hint) in normalize_account(text)))
    if expected.symbol_code:
        checks.append((f"symbol_code:{expected.symbol_code}", normalize_code(expected.symbol_code) in normalize_code(text)))
    if expected.symbol_name:
        checks.append((f"symbol_name:{expected.symbol_name}", normalize_text(expected.symbol_name) in normalized))
    if expected.min_quantity is not None:
        numbers = [int(match) for match in re.findall(r"\d[\d,]*", text.replace(",", ""))]
        checks.append((f"min_quantity:{expected.min_quantity}", any(number >= expected.min_quantity for number in numbers)))
    return _result("balance", checks)


def verify_order_text(text: str, expected: ExpectedOrder) -> VerificationResult:
    checks: list[tuple[str, bool]] = []
    normalized = normalize_text(text)
    if expected.account_type:
        checks.append((f"account_type:{expected.account_type.upper()}", expected.account_type.upper() in text.upper()))
    if expected.account_hint:
        checks.append((f"account_hint:{expected.account_hint}", normalize_account(expected.account_hint) in normalize_account(text)))
    if expected.symbol_code:
        checks.append((f"symbol_code:{expected.symbol_code}", normalize_code(expected.symbol_code) in normalize_code(text)))
    if expected.symbol_name:
        checks.append((f"symbol_name:{expected.symbol_name}", normalize_text(expected.symbol_name) in normalized))
    if expected.side:
        checks.append((f"side:{expected.side}", normalize_text(expected.side) in normalized))
    if expected.quantity is not None:
        checks.append((f"quantity:{expected.quantity}", str(expected.quantity) in re.sub(r"[^0-9]", " ", text).split()))
    if expected.amount is not None:
        amount = str(expected.amount)
        checks.append((f"amount:{expected.amount}", amount in re.sub(r"[^0-9]", "", text)))
    if expected.price_type:
        checks.append((f"price_type:{expected.price_type}", normalize_text(expected.price_type) in normalized))
    return _result("order", checks)


def _result(target: str, checks: list[tuple[str, bool]]) -> VerificationResult:
    matched = [name for name, passed in checks if passed]
    missing = [name for name, passed in checks if not passed]
    return VerificationResult(target=target, passed=not missing, matched=matched, missing=missing)
