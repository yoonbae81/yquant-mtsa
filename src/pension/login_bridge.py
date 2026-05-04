from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass
from typing import Any

from .adb_device import AdbDevice


ACTION_NAVIGATE = "com.yquant.mtsa.ACTION_NAVIGATE"
ACCESSIBILITY_HELPER_PACKAGE = "com.yquant.mtsa"
ACCESSIBILITY_HELPER_SERVICE = "com.yquant.mtsa/com.yquant.mtsa.NeoSmartAccessibilityService"


@dataclass(frozen=True)
class LoginBridgeStatus:
    package_installed: bool
    service_enabled: bool
    process_running: bool

    @property
    def ready(self) -> bool:
        return self.package_installed and self.service_enabled and self.process_running

    def to_dict(self) -> dict:
        return {
            "package_installed": self.package_installed,
            "service_enabled": self.service_enabled,
            "process_running": self.process_running,
            "ready": self.ready,
        }


@dataclass(frozen=True)
class LoginCommandResult:
    request_id: str
    command: str
    finished: bool
    success: bool
    state: str
    message: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "request_id": self.request_id,
            "command": self.command,
            "finished": self.finished,
            "success": self.success,
            "state": self.state,
            "message": self.message,
        }


class LoginBridge:
    """Bridge to the Android login helper.

    The pension subsystem owns ADB orchestration and artifacts. The login helper
    owns certificate login and secure keyboard input where ADB text/screencap is
    not accepted by the MTS app.
    """

    def __init__(self, device: AdbDevice):
        self.device = device

    def status(self) -> LoginBridgeStatus:
        packages = self.device.shell("pm", "list", "packages", ACCESSIBILITY_HELPER_PACKAGE)
        assert isinstance(packages, str)
        enabled_services = self.device.shell("settings", "get", "secure", "enabled_accessibility_services")
        assert isinstance(enabled_services, str)
        processes = self.device.shell("ps", "-A")
        assert isinstance(processes, str)
        return LoginBridgeStatus(
            package_installed=f"package:{ACCESSIBILITY_HELPER_PACKAGE}" in packages.splitlines(),
            service_enabled=ACCESSIBILITY_HELPER_SERVICE in enabled_services,
            process_running=ACCESSIBILITY_HELPER_PACKAGE in processes,
        )

    def send_command(self, command: str, *, request_id: str | None = None) -> str | None:
        args = ["am", "broadcast", "-a", ACTION_NAVIGATE, "--es", "command", command]
        if request_id:
            args.extend(["--es", "request_id", request_id])
        self.device.shell(*args, timeout=10, text=False)
        return request_id

    def send_command_and_wait(self, command: str, *, timeout: float = 90) -> LoginCommandResult:
        request_id = f"pension-{uuid.uuid4().hex[:12]}"
        self.device.run(["logcat", "-c"], timeout=10, text=False)
        self.send_command(command, request_id=request_id)
        deadline = time.monotonic() + timeout
        last_result: LoginCommandResult | None = None
        while time.monotonic() < deadline:
            result = self._latest_command_result(request_id)
            if result is not None:
                last_result = result
                if result.finished:
                    return result
            time.sleep(1)
        if last_result is not None:
            return last_result
        return LoginCommandResult(
            request_id=request_id,
            command=command,
            finished=False,
            success=False,
            state="TIMEOUT",
            message=f"Timed out waiting for {command} result",
        )

    def _latest_command_result(self, request_id: str) -> LoginCommandResult | None:
        output = self.device.run(["logcat", "-d", "-s", "MtsaCommandResult"], timeout=10, text=True)
        assert isinstance(output, str)
        result: LoginCommandResult | None = None
        for line in output.splitlines():
            payload_start = line.find("{")
            if payload_start < 0:
                continue
            try:
                payload = json.loads(line[payload_start:])
            except json.JSONDecodeError:
                continue
            if payload.get("request_id") != request_id:
                continue
            result = LoginCommandResult(
                request_id=str(payload.get("request_id", "")),
                command=str(payload.get("command", "")),
                finished=bool(payload.get("finished")),
                success=bool(payload.get("success")),
                state=str(payload.get("state", "UNKNOWN")),
                message=str(payload.get("message", "")),
            )
        return result
