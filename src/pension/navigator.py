from __future__ import annotations

from dataclasses import dataclass

from .sitemap import SITEMAP, profile_screen_for_screen


@dataclass(frozen=True)
class NavigationContext:
    current_screen: str | None = None
    account: str | None = None
    tab: str | None = None
    expanded: frozenset[str] = frozenset()


@dataclass(frozen=True)
class NavigationTarget:
    screen: str
    account: str | None = None
    tab: str | None = None
    expansions: tuple[str, ...] = ()


@dataclass(frozen=True)
class NavigationStep:
    name: str
    action: str
    profile_key: str | None = None
    skipped: bool = False

    def as_sequence_item(self) -> tuple[str, str, str]:
        if self.profile_key is None:
            raise ValueError("Navigation step has no profile key")
        return (_scenario_action(self.action), _step_slug(self.name), self.profile_key)


class Navigator:
    def plan(self, target: NavigationTarget, context: NavigationContext | None = None) -> list[NavigationStep]:
        context = context or NavigationContext()
        steps: list[NavigationStep] = []
        same_screen = context.current_screen == target.screen

        if same_screen:
            steps.append(NavigationStep(name=f"{target.screen} 화면 진입", action="화면확인", skipped=True))
        else:
            steps.extend(open_screen_steps(target.screen, context=context))

        if target.account:
            if _same_account(context.account, target.account):
                steps.append(NavigationStep(name=f"{target.account} 계좌 선택", action="계좌확인", skipped=True))
            else:
                steps.extend(select_account_steps(target.screen, target.account))

        if target.tab:
            if same_screen and context.tab == target.tab:
                steps.append(NavigationStep(name=f"{target.tab} 탭 선택", action="탭확인", skipped=True))
            else:
                steps.extend(select_tab_steps(target.screen, target.tab))

        for expansion in target.expansions:
            if same_screen and expansion in context.expanded:
                steps.append(NavigationStep(name=f"{expansion} 펼침", action="펼침확인", skipped=True))
            else:
                steps.extend(expand_steps(target.screen, expansion))

        return steps


def open_screen_steps(screen: str, context: NavigationContext | None = None) -> list[NavigationStep]:
    context = context or NavigationContext()
    profile_screen = profile_screen_for_screen(screen)
    steps: list[NavigationStep] = []
    if context.current_screen != "메뉴":
        steps.append(NavigationStep(name="하단 메뉴 열기", action="탭", profile_key="home.bottom_menu"))
    steps.extend(
        [
            NavigationStep(name="연금 탭 선택", action="탭", profile_key="menu.pension_tab"),
            NavigationStep(name=f"{screen} 선택", action="탭", profile_key=f"menu.{profile_screen}"),
        ]
    )
    return steps


def select_account_steps(screen: str, account: str) -> list[NavigationStep]:
    normalized = normalize_account(account)
    if screen == "잔고":
        account_point = {
            "IRP": "balance_account_sheet.irp_account",
            "DC": "balance_account_sheet.dc_account",
        }[normalized]
        return [
            NavigationStep(name=f"{normalized} 계좌 선택", action="탭", profile_key="balance.account_select"),
            NavigationStep(name=f"{normalized} 계좌 선택", action="탭", profile_key=account_point),
        ]
    if screen == "주문":
        account_point = {
            "IRP": "account_select_sheet.irp_account",
            "DC": "account_select_sheet.dc_account",
        }[normalized]
        return [
            NavigationStep(name=f"{normalized} 계좌 선택", action="탭", profile_key="order.account_select"),
            NavigationStep(name=f"{normalized} 계좌 선택", action="탭", profile_key=account_point),
        ]
    raise ValueError(f"Unknown screen: {screen}")


def select_tab_steps(screen: str, tab: str) -> list[NavigationStep]:
    try:
        tab_entry = SITEMAP[screen]["tabs"][tab]
    except KeyError as exc:
        raise ValueError(f"Unknown tab route: {screen} / {tab}") from exc
    return [NavigationStep(name=tab_entry["step"], action="탭", profile_key=tab_entry["profile_key"])]


def expand_steps(screen: str, expansion: str) -> list[NavigationStep]:
    try:
        name, profile_key = SITEMAP[screen]["expansions"][expansion]
    except KeyError as exc:
        raise ValueError(f"Unknown expansion route: {screen} / {expansion}") from exc
    return [NavigationStep(name=name, action="탭", profile_key=profile_key)]


def sequence_for_open_screen(screen: str) -> list[tuple[str, str, str]]:
    return [step.as_sequence_item() for step in open_screen_steps(screen)]


def sequence_for_select_account(screen: str, account: str) -> list[tuple[str, str, str]]:
    return [step.as_sequence_item() for step in select_account_steps(screen, account)]


def sequence_for_select_tab(screen: str, tab: str) -> list[tuple[str, str, str]]:
    return [step.as_sequence_item() for step in select_tab_steps(screen, tab)]


def normalize_account(account: str | None) -> str | None:
    if account is None:
        return None
    normalized = account.strip().upper()
    if normalized not in {"IRP", "DC"}:
        raise ValueError("account must be IRP or DC")
    return normalized


def _same_account(current: str | None, expected: str) -> bool:
    return (current or "").strip().upper() == expected.strip().upper()


def _step_slug(name: str) -> str:
    return {
        "하단 메뉴 열기": "open_bottom_menu",
        "연금 탭 선택": "open_pension_tab",
        "잔고 선택": "open_balance",
        "주문 선택": "open_order",
        "실시간 탭 선택": "open_realtime_balance",
        "매수 탭 선택": "select_buy_tab",
        "매도 탭 선택": "select_sell_tab",
    }.get(name, name.replace(" ", "_"))


def _scenario_action(action: str) -> str:
    return {
        "탭": "tap",
        "스와이프": "swipe",
    }.get(action, action)
