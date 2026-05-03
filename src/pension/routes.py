from __future__ import annotations

from dataclasses import dataclass

from .device_profile import DeviceProfile
from .navigator import NavigationContext, NavigationStep, NavigationTarget, Navigator, normalize_account
from .sitemap import SITEMAP, screen_for_profile_screen


def route_screen_for_profile_screen(profile_screen: str | None) -> str | None:
    return screen_for_profile_screen(profile_screen)


@dataclass(frozen=True)
class InformationContext:
    current_screen: str | None = None
    account: str | None = None
    tab: str | None = None
    expanded: frozenset[str] = frozenset()
    account_password_saved_accounts: frozenset[str] = frozenset()

    @classmethod
    def from_dict(cls, data: dict) -> "InformationContext":
        return cls(
            current_screen=data.get("current_screen"),
            account=_normalize_account(data.get("account")) if data.get("account") else None,
            tab=data.get("tab"),
            expanded=frozenset(data.get("expanded", [])),
            account_password_saved_accounts=frozenset(
                _normalize_account(account)
                for account in (
                    data.get("account_password_saved_accounts")
                    or data.get("password_saved_accounts")
                    or []
                )
            ),
        )

    def to_dict(self) -> dict:
        return {
            "current_screen": self.current_screen,
            "account": self.account,
            "tab": self.tab,
            "expanded": sorted(self.expanded),
            "account_password_saved_accounts": sorted(self.account_password_saved_accounts),
        }

    def after_route(self, route: "InformationRoute", account: str | None = None) -> "InformationContext":
        normalized_account = _normalize_account(account)
        return InformationContext(
            current_screen=route.screen,
            account=normalized_account or self.account,
            tab=route.tab or self.tab,
            expanded=frozenset([*self.expanded, *route.expansions]),
            account_password_saved_accounts=self.account_password_saved_accounts,
        )

    def is_password_saved(self, account: str | None = None) -> bool:
        normalized_account = _normalize_account(account or self.account)
        return bool(normalized_account and normalized_account in self.account_password_saved_accounts)

    def with_password_saved(self, account: str | None = None) -> "InformationContext":
        normalized_account = _normalize_account(account or self.account)
        if normalized_account is None:
            return self
        return InformationContext(
            current_screen=self.current_screen,
            account=self.account,
            tab=self.tab,
            expanded=self.expanded,
            account_password_saved_accounts=frozenset([*self.account_password_saved_accounts, normalized_account]),
        )


@dataclass(frozen=True)
class RouteStep:
    name: str
    action: str
    profile_key: str | None = None
    skipped: bool = False
    event_hooks: tuple[str, ...] = ()

    def to_dict(self) -> dict:
        payload = {
            "name": self.name,
            "action": self.action,
            "skipped": self.skipped,
        }
        if self.profile_key is not None:
            payload["profile_key"] = self.profile_key
        if self.event_hooks:
            payload["event_hooks"] = list(self.event_hooks)
        return payload


@dataclass(frozen=True)
class InformationRoute:
    name: str
    screen: str
    tab: str | None
    expansions: tuple[str, ...]
    read_regions: tuple[tuple[str, str], ...]
    requires_account_password: bool = False

    def __post_init__(self) -> None:
        if self.tab is None:
            return
        allowed_tabs = SITEMAP.get(self.screen, {}).get("tabs", {})
        if self.tab not in allowed_tabs:
            raise ValueError(f"{self.tab} is not a tab under {self.screen}")

    def plan(self, account: str | None = None, context: InformationContext | None = None) -> list[RouteStep]:
        context = context or InformationContext()
        normalized_account = _normalize_account(account)
        steps: list[RouteStep] = []
        navigation_steps = Navigator().plan(
            NavigationTarget(
                screen=self.screen,
                account=normalized_account,
                tab=self.tab,
                expansions=self.expansions,
            ),
            context=NavigationContext(
                current_screen=context.current_screen,
                account=context.account,
                tab=context.tab,
                expanded=context.expanded,
            ),
        )
        steps.extend(_route_step(step) for step in navigation_steps)
        steps.extend(_account_password_gate_steps(normalized_account, context, enabled=self.requires_account_password))

        for label, profile_key in self.read_regions:
            steps.append(RouteStep(name=f"{label} 읽기", action="읽기", profile_key=profile_key))
        return steps

    def required_profile_keys(self, account: str | None = None) -> list[str]:
        keys: list[str] = []
        for step in self.plan(account=account):
            if step.profile_key is not None:
                keys.append(step.profile_key)
        return keys


class InformationRouteRegistry:
    def __init__(self, routes: dict[str, InformationRoute] | None = None):
        self.routes = routes or DEFAULT_ROUTES

    def names(self) -> list[str]:
        return sorted(self.routes)

    def get(self, name: str) -> InformationRoute:
        try:
            return self.routes[name]
        except KeyError as exc:
            raise ValueError(f"Unknown information route: {name}") from exc

    def plan(self, name: str, account: str | None = None, context: InformationContext | None = None) -> list[RouteStep]:
        return self.get(name).plan(account=account, context=context)

    def context_after(self, name: str, account: str | None = None, context: InformationContext | None = None) -> InformationContext:
        base_context = context or InformationContext()
        return base_context.after_route(self.get(name), account=account)

    def validate(self, name: str, profile: DeviceProfile, account: str | None = None) -> list[str]:
        missing: list[str] = []
        for step in self.get(name).plan(account=account):
            if step.profile_key is not None:
                try:
                    if step.action == "읽기":
                        profile.region(step.profile_key)
                    elif step.action == "스와이프":
                        profile.swipe(step.profile_key)
                    else:
                        profile.tap_point(step.profile_key)
                except (KeyError, ValueError):
                    missing.append(step.profile_key)
            for event_name in step.event_hooks:
                missing.extend(_missing_event_profile_keys(profile, event_name))
        return missing


def _route_step(step: NavigationStep) -> RouteStep:
    return RouteStep(
        name=step.name,
        action=step.action,
        profile_key=step.profile_key,
        skipped=step.skipped,
    )


ACCOUNT_PASSWORD_EVENT = "계좌 비밀번호 입력"

EVENT_PROFILE_REQUIREMENTS = {
    ACCOUNT_PASSWORD_EVENT: {
        "tap_points": (
            "account_password_popup.auto_save_toggle",
            "secure_number_keypad.complete",
        ),
        "regions": (
            "account_password_popup.password_dots",
            "account_password_popup.auto_save_toggle",
            "secure_number_keypad.digit_grid",
        ),
    }
}


def _account_password_gate_steps(
    account: str | None,
    context: InformationContext,
    *,
    enabled: bool,
) -> list[RouteStep]:
    if not enabled:
        return []
    if account is None:
        return [
            RouteStep(
                name="계좌 비밀번호 입력",
                action="이벤트",
                event_hooks=(ACCOUNT_PASSWORD_EVENT,),
            )
        ]
    if context.is_password_saved(account):
        return [
            RouteStep(
                name=f"{account} 계좌 비밀번호 입력",
                action="이벤트확인",
                skipped=True,
                event_hooks=(ACCOUNT_PASSWORD_EVENT,),
            )
        ]
    return [
        RouteStep(name=f"{account} 계좌 비밀번호 팝업 열기", action="탭", profile_key="order.account_password"),
        RouteStep(
            name=f"{account} 계좌 비밀번호 입력",
            action="이벤트",
            event_hooks=(ACCOUNT_PASSWORD_EVENT,),
        ),
    ]


def _missing_event_profile_keys(profile: DeviceProfile, event_name: str) -> list[str]:
    requirements = EVENT_PROFILE_REQUIREMENTS.get(event_name, {})
    missing: list[str] = []
    for key in requirements.get("tap_points", ()):
        try:
            profile.tap_point(key)
        except (KeyError, ValueError):
            missing.append(key)
    for key in requirements.get("regions", ()):
        try:
            profile.region(key)
        except (KeyError, ValueError):
            missing.append(key)
    return missing


def _normalize_account(account: str | None) -> str | None:
    return normalize_account(account)


DEFAULT_ROUTES: dict[str, InformationRoute] = {
    "예수금": InformationRoute(
        name="예수금",
        screen="잔고",
        tab="실시간",
        expansions=("평가손익 상세",),
        read_regions=(("예수금", "balance.cash_asset"),),
    ),
    "보유종목": InformationRoute(
        name="보유종목",
        screen="잔고",
        tab="실시간",
        expansions=(),
        read_regions=(("보유종목 그리드", "balance.holdings_grid"),),
    ),
    "매수": InformationRoute(
        name="매수",
        screen="주문",
        tab="매수",
        expansions=(),
        read_regions=(),
        requires_account_password=True,
    ),
    "매도": InformationRoute(
        name="매도",
        screen="주문",
        tab="매도",
        expansions=(),
        read_regions=(),
        requires_account_password=True,
    ),
}
