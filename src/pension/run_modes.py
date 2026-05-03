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
    may_tap_submit: bool
    reason: str

    def to_dict(self) -> dict:
        return {
            "mode": self.mode.value,
            "may_tap_submit": self.may_tap_submit,
            "reason": self.reason,
        }


def decide_submit_permission(mode: str, *, verified: bool, explicit_real_run: bool = False) -> RunModeDecision:
    run_mode = RunMode(mode)
    if run_mode == RunMode.INSPECT:
        return RunModeDecision(run_mode, False, "inspect mode never taps submit")
    if run_mode == RunMode.DRY_RUN:
        return RunModeDecision(run_mode, False, "dry-run stops before final submit")
    if run_mode == RunMode.CONFIRM_RUN:
        return RunModeDecision(run_mode, False, "confirm-run requires an external user approval step")
    if not verified:
        return RunModeDecision(run_mode, False, "real-run requires verified order fields")
    if not explicit_real_run:
        return RunModeDecision(run_mode, False, "real-run requires explicit_real_run")
    return RunModeDecision(run_mode, True, "real-run submit allowed")
