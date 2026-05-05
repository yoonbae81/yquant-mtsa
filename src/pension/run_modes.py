from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class RunMode(str, Enum):
    INSPECT = "inspect"
    DRY_RUN = "dry-run"
    CONFIRM_RUN = "confirm-run"
    MANUAL_SUBMIT = "manual-submit"
    REAL_RUN = "real-run"


@dataclass(frozen=True)
class RunModeDecision:
    mode: RunMode
    may_open_confirmation: bool
    may_tap_final_submit: bool
    should_cancel_confirmation: bool
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
            "should_cancel_confirmation": self.should_cancel_confirmation,
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
        return RunModeDecision(run_mode, False, False, False, "inspect mode never opens confirmation")
    if run_mode == RunMode.DRY_RUN:
        return RunModeDecision(run_mode, False, False, False, "dry-run stops before order confirmation")
    if run_mode == RunMode.CONFIRM_RUN:
        if not verified:
            return RunModeDecision(run_mode, False, False, False, "confirm-run requires verified order fields")
        return RunModeDecision(run_mode, True, False, True, "confirm-run opens confirmation and cancels after verification")
    if run_mode == RunMode.MANUAL_SUBMIT:
        if not verified:
            return RunModeDecision(run_mode, False, False, False, "manual-submit requires verified order fields")
        return RunModeDecision(
            run_mode,
            True,
            False,
            False,
            "manual-submit opens verified confirmation and leaves final submit to the user",
        )
    if not verified:
        return RunModeDecision(run_mode, False, False, False, "real-run requires verified order fields")
    if not config_allows_real_run:
        return RunModeDecision(run_mode, False, False, False, "real-run requires allow_real_run in config")
    if not explicit_real_run:
        return RunModeDecision(run_mode, False, False, False, "real-run requires explicit_real_run")
    return RunModeDecision(run_mode, True, True, False, "real-run final submit allowed")
