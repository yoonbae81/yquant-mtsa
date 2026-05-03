from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class PensionConfig:
    account_passwords: dict[str, str]
    default_profile: str | None = None
    allow_real_run: bool = False

    @classmethod
    def load(cls, path: str | Path) -> "PensionConfig":
        text = Path(path).read_text(encoding="utf-8")
        return cls(
            account_passwords=parse_account_passwords(text),
            default_profile=parse_default_profile(text),
            allow_real_run=parse_allow_real_run(text),
        )

    def password_for_account(self, account: str | None) -> str | None:
        if account is None:
            return None
        return self.account_passwords.get(account.strip().upper())


def parse_account_passwords(text: str) -> dict[str, str]:
    accounts: dict[str, str] = {}
    in_accounts = False
    current_account: str | None = None

    for raw_line in text.splitlines():
        line = raw_line.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        stripped = line.strip()

        if indent == 0:
            in_accounts = stripped == "accounts:"
            current_account = None
            continue
        if not in_accounts:
            continue
        if indent == 2 and stripped.endswith(":"):
            current_account = stripped[:-1].strip().upper()
            continue
        if indent >= 4 and current_account and stripped.startswith("password:"):
            value = stripped.split(":", 1)[1].strip()
            accounts[current_account] = _unquote(value)

    return accounts


def parse_default_profile(text: str) -> str | None:
    for raw_line in text.splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line.startswith("default_profile:"):
            continue
        value = line.split(":", 1)[1].strip()
        if not value:
            return None
        return _unquote(value)
    return None


def parse_allow_real_run(text: str) -> bool:
    for raw_line in text.splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line.startswith("allow_real_run:"):
            continue
        value = _unquote(line.split(":", 1)[1].strip()).strip().lower()
        return value in {"1", "true", "yes", "y", "on"}
    return False


def _unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        return value[1:-1]
    return value
