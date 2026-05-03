from __future__ import annotations

from dataclasses import dataclass

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

    def send_command(self, command: str) -> None:
        args = ["am", "broadcast", "-a", ACTION_NAVIGATE, "--es", "command", command]
        self.device.shell(*args, timeout=10, text=False)
