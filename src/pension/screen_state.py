from __future__ import annotations

from dataclasses import dataclass

from .device_profile import DeviceProfile
from .ocr import OcrResult
from .sitemap import SITEMAP, screen_for_profile_screen


@dataclass(frozen=True)
class AnchorCheck:
    screen: str
    anchors: list[str]
    matched: list[str]
    missing: list[str]
    optional: list[str] | None = None
    optional_matched: list[str] | None = None
    forbidden: list[str] | None = None
    forbidden_matched: list[str] | None = None
    score: float = 0.0
    min_score: float = 1.0

    @property
    def passed(self) -> bool:
        return len(self.missing) == 0 and not self.forbidden_matched and self.score >= self.min_score

    def to_dict(self) -> dict:
        return {
            "screen": self.screen,
            "anchors": self.anchors,
            "matched": self.matched,
            "missing": self.missing,
            "optional": self.optional or [],
            "optional_matched": self.optional_matched or [],
            "forbidden": self.forbidden or [],
            "forbidden_matched": self.forbidden_matched or [],
            "score": self.score,
            "min_score": self.min_score,
            "passed": self.passed,
        }


class ScreenStateChecker:
    def check_anchors(self, screen: str, anchors: list[str] | dict, ocr_result: OcrResult) -> AnchorCheck:
        text = normalize_text(ocr_result.text)
        if isinstance(anchors, dict):
            required = list(anchors.get("required", []))
            optional = list(anchors.get("optional", []))
            forbidden = list(anchors.get("forbidden", []))
            min_score = float(anchors.get("min_score", 1.0 if not optional else 0.75))
        else:
            required = list(anchors)
            optional = []
            forbidden = []
            min_score = 1.0

        matched: list[str] = []
        missing: list[str] = []
        for anchor in required:
            if normalize_text(anchor) in text:
                matched.append(anchor)
            else:
                missing.append(anchor)

        optional_matched = [anchor for anchor in optional if normalize_text(anchor) in text]
        forbidden_matched = [anchor for anchor in forbidden if normalize_text(anchor) in text]
        total_weight = len(required) + (0.5 * len(optional))
        matched_weight = len(matched) + (0.5 * len(optional_matched))
        score = 1.0 if total_weight == 0 else round(matched_weight / total_weight, 4)
        return AnchorCheck(
            screen=screen,
            anchors=required,
            matched=matched,
            missing=missing,
            optional=optional,
            optional_matched=optional_matched,
            forbidden=forbidden,
            forbidden_matched=forbidden_matched,
            score=score,
            min_score=min_score,
        )

    def inspect(self, profile: DeviceProfile, ocr_result: OcrResult) -> "ScreenStateSnapshot":
        checks: list[AnchorCheck] = []
        for screen in profile.data.get("screens", {}):
            anchors = profile.anchors(screen)
            if anchors:
                checks.append(self.check_anchors(screen, anchors, ocr_result))
        for sitemap_entry in SITEMAP.values():
            checks.append(
                self.check_anchors(
                    sitemap_entry["profile_screen"],
                    sitemap_entry["recognition"],
                    ocr_result,
                )
            )
        checks.sort(
            key=lambda check: (
                check.passed,
                check.score,
                len(check.matched) + len(check.optional_matched or []),
            ),
            reverse=True,
        )
        best = next((check for check in checks if check.passed), None)
        if best is None:
            best = next(
                (
                    check
                    for check in checks
                    if not check.forbidden_matched and check.score >= check.min_score
                ),
                None,
            )
        text = normalize_text(ocr_result.text)
        current_screen = screen_for_profile_screen(best.screen if best else None)
        return ScreenStateSnapshot(
            screen_key=best.screen if best else None,
            current_screen=current_screen or (display_screen_name(best.screen) if best else None),
            account=detect_account(text),
            tab=detect_tab(current_screen, text),
            expanded=detect_expanded(text),
            checks=checks,
        )


@dataclass(frozen=True)
class ScreenStateSnapshot:
    screen_key: str | None
    current_screen: str | None
    account: str | None
    tab: str | None
    expanded: frozenset[str]
    checks: list[AnchorCheck]

    def to_dict(self) -> dict:
        return {
            "screen_key": self.screen_key,
            "current_screen": self.current_screen,
            "account": self.account,
            "tab": self.tab,
            "expanded": sorted(self.expanded),
            "checks": [check.to_dict() for check in self.checks],
        }


def normalize_text(value: str) -> str:
    return "".join(value.split()).casefold()


def display_screen_name(screen_key: str) -> str:
    return {
        "balance": "잔고",
        "order": "주문",
        "menu": "메뉴",
        "home": "홈",
        "order_search": "종목검색",
        "account_select_sheet": "주문 계좌 선택",
        "balance_account_sheet": "잔고 계좌 선택",
        "account_password": "계좌 비밀번호",
        "account_password_popup": "계좌 비밀번호",
        "secure_number_keypad": "보안 숫자 키패드",
    }.get(screen_key, screen_key)


def detect_account(normalized_text: str) -> str | None:
    has_irp = "irp" in normalized_text or "개인형" in normalized_text
    has_dc = "dc" in normalized_text
    if has_irp and not has_dc:
        return "IRP"
    if has_dc and not has_irp:
        return "DC"
    return None


def detect_tab(screen: str | None, normalized_text: str) -> str | None:
    if screen is None:
        return None
    tabs = SITEMAP.get(screen, {}).get("tabs", {})
    scored_tabs: list[tuple[int, str]] = []
    for tab, tab_route in tabs.items():
        anchors = tab_route.get("selected_anchors", ())
        score = sum(1 for anchor in anchors if normalize_text(anchor) in normalized_text)
        if score:
            scored_tabs.append((score, tab))
    if scored_tabs:
        scored_tabs.sort(reverse=True)
        return scored_tabs[0][1]
    return None


def detect_expanded(normalized_text: str) -> frozenset[str]:
    if "예수금" in normalized_text:
        return frozenset({"평가손익 상세"})
    return frozenset()
