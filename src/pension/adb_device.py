from __future__ import annotations

import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Sequence


DEFAULT_ADB_PATH = Path("adb")


class AdbCommandError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        command: Sequence[str],
        returncode: int | None = None,
        stdout: bytes | str = b"",
        stderr: bytes | str = b"",
    ):
        super().__init__(message)
        self.command = list(command)
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


@dataclass(frozen=True)
class DeviceInfo:
    serial: str
    width: int
    height: int
    density: int | None


@dataclass(frozen=True)
class ActivityInfo:
    package: str
    activity: str
    component: str


@dataclass(frozen=True)
class AdbResult:
    command: list[str]
    returncode: int
    stdout: bytes
    stderr: bytes

    @property
    def stdout_text(self) -> str:
        return self.stdout.decode("utf-8", errors="replace")

    @property
    def stderr_text(self) -> str:
        return self.stderr.decode("utf-8", errors="replace")


class AdbDevice:
    """Thin wrapper around adb.

    This layer intentionally knows nothing about MTS screens or order logic. It
    only exposes reliable device operations with timeouts and normalized errors.
    """

    def __init__(
        self,
        serial: str | None = None,
        adb_path: str | Path | None = None,
        *,
        default_timeout: float = 15,
        env: Mapping[str, str] | None = None,
    ):
        self.serial = serial
        self.adb_path = Path(adb_path or os.environ.get("ADB_PATH", DEFAULT_ADB_PATH))
        self.default_timeout = default_timeout
        self.env = dict(env) if env is not None else None

    def command_prefix(self) -> list[str]:
        command = [str(self.adb_path)]
        if self.serial:
            command.extend(["-s", self.serial])
        return command

    def run(
        self,
        args: Sequence[str],
        *,
        timeout: float | None = None,
        check: bool = True,
        text: bool = False,
    ) -> AdbResult | str:
        command = self.command_prefix() + list(args)
        try:
            completed = subprocess.run(
                command,
                capture_output=True,
                timeout=timeout or self.default_timeout,
                env=self.env,
            )
        except subprocess.TimeoutExpired as exc:
            raise AdbCommandError(
                f"ADB command timed out after {timeout or self.default_timeout}s",
                command=command,
                stdout=exc.stdout or b"",
                stderr=exc.stderr or b"",
            ) from exc
        except FileNotFoundError as exc:
            raise AdbCommandError(
                f"ADB executable not found: {self.adb_path}",
                command=command,
            ) from exc

        result = AdbResult(command, completed.returncode, completed.stdout, completed.stderr)
        if check and completed.returncode != 0:
            raise AdbCommandError(
                f"ADB command failed with exit code {completed.returncode}: {' '.join(command)}",
                command=command,
                returncode=completed.returncode,
                stdout=completed.stdout,
                stderr=completed.stderr,
            )
        if text:
            return result.stdout_text
        return result

    def list_devices(self) -> list[str]:
        output = self.run(["devices"], text=True)
        assert isinstance(output, str)
        devices: list[str] = []
        for line in output.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                devices.append(parts[0])
        return devices

    def shell(self, *args: str, timeout: float | None = None, text: bool = True) -> str | AdbResult:
        return self.run(["shell", *args], timeout=timeout, text=text)

    def get_screen_size(self) -> tuple[int, int]:
        output = self.shell("wm", "size")
        assert isinstance(output, str)
        match = re.search(r"Physical size:\s*(\d+)x(\d+)", output)
        if not match:
            match = re.search(r"Override size:\s*(\d+)x(\d+)", output)
        if not match:
            raise AdbCommandError(
                f"Unable to parse screen size from adb output: {output.strip()}",
                command=self.command_prefix() + ["shell", "wm", "size"],
                stdout=output,
            )
        return int(match.group(1)), int(match.group(2))

    def get_density(self) -> int | None:
        output = self.shell("wm", "density")
        assert isinstance(output, str)
        match = re.search(r"Physical density:\s*(\d+)", output)
        if not match:
            match = re.search(r"Override density:\s*(\d+)", output)
        return int(match.group(1)) if match else None

    def get_device_info(self) -> DeviceInfo:
        width, height = self.get_screen_size()
        serial = self.serial
        if not serial:
            devices = self.list_devices()
            serial = devices[0] if len(devices) == 1 else ""
        return DeviceInfo(serial=serial, width=width, height=height, density=self.get_density())

    def get_current_activity(self) -> ActivityInfo:
        output = self.shell("dumpsys", "activity", "activities")
        assert isinstance(output, str)
        match = re.search(r"mResumedActivity:.*?\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)", output)
        if not match:
            match = re.search(r"ResumedActivity:.*?\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)", output)
        if not match:
            raise AdbCommandError(
                "Unable to parse current activity from dumpsys activity output",
                command=self.command_prefix() + ["shell", "dumpsys", "activity", "activities"],
                stdout=output,
            )
        package = match.group(1)
        activity = match.group(2)
        if activity.startswith("."):
            component_activity = package + activity
        else:
            component_activity = activity
        return ActivityInfo(
            package=package,
            activity=component_activity,
            component=f"{package}/{activity}",
        )

    def screencap(self, output_path: str | Path, *, timeout: float = 15) -> Path:
        output = Path(output_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        result = self.run(["exec-out", "screencap", "-p"], timeout=timeout)
        assert isinstance(result, AdbResult)
        if not result.stdout:
            raise AdbCommandError(
                "ADB screencap returned no data. The screen may be off or the current activity may block screenshots.",
                command=result.command,
                returncode=result.returncode,
                stdout=result.stdout,
                stderr=result.stderr,
            )
        output.write_bytes(result.stdout)
        return output

    def tap(self, x: int | float, y: int | float) -> None:
        self.shell("input", "tap", str(round(x)), str(round(y)), text=False)

    def swipe(
        self,
        x1: int | float,
        y1: int | float,
        x2: int | float,
        y2: int | float,
        duration_ms: int = 300,
    ) -> None:
        self.shell(
            "input",
            "swipe",
            str(round(x1)),
            str(round(y1)),
            str(round(x2)),
            str(round(y2)),
            str(duration_ms),
            text=False,
        )

    def input_text(self, text: str) -> None:
        escaped = text.replace("%", "%s").replace(" ", "%s")
        self.shell("input", "text", escaped, text=False)

    def keyevent(self, key: str | int) -> None:
        self.shell("input", "keyevent", str(key), text=False)

    def press_back(self) -> None:
        self.keyevent("BACK")

    def start_activity(
        self,
        component: str,
        *,
        extras: Mapping[str, str] | None = None,
        action: str | None = None,
    ) -> None:
        args = ["am", "start"]
        if action:
            args.extend(["-a", action])
        args.extend(["-n", component])
        for key, value in (extras or {}).items():
            args.extend(["--es", key, value])
        self.shell(*args, timeout=20, text=False)

    def force_stop(self, package: str) -> None:
        self.shell("am", "force-stop", package, text=False)
