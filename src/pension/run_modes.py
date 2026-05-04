from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class RunMode(str, Enum):
    INSPECT = "inspect"
    DRY_RUN = "dry-run"
    CONFIRM_RUN = "confirm-run"
    REAL_RUN = "real-run"


@dataclass(frozen=True)
class RunModeDecision:
    mode: RunMode
    may_open_confirmation: bool
    may_tap_final_submit: bool
    reason: str

    @property
    def may_tap_submit(self) -> bool:
        return self.may_tap_final_submit

    def to_dict(self) -> dict:
        return {
            "mode": self.mode.value,
            "may_open_confirmation": self.may_open_confirmation,
            "may_tap_final_submit": self.may_tap_final_submit,
            "may_tap_submit": self.may_tap_final_submit,
            "reason": self.reason,
        }


def decide_submit_permission(
    mode: str,
    *,
    verified: bool,
    explicit_real_run: bool = False,
    config_allows_real_run: bool = True,
) -> RunModeDecision:
    run_mode = RunMode(mode)
    if run_mode == RunMode.INSPECT:
        return RunModeDecision(run_mode, False, False, "inspect mode never opens confirmation")
    if run_mode == RunMode.DRY_RUN:
        return RunModeDecision(run_mode, False, False, "dry-run stops before order confirmation")
    if run_mode == RunMode.CONFIRM_RUN:
        if not verified:
            return RunModeDecision(run_mode, False, False, "confirm-run requires verified order fields")
        return RunModeDecision(run_mode, True, False, "confirm-run opens confirmation and cancels after verification")
    if not verified:
        return RunModeDecision(run_mode, False, False, "real-run requires verified order fields")
    if not config_allows_real_run:
        return RunModeDecision(run_mode, False, False, "real-run requires allow_real_run in config")
    if not explicit_real_run:
        return RunModeDecision(run_mode, False, False, "real-run requires explicit_real_run")
    return RunModeDecision(run_mode, True, True, "real-run final submit allowed")
