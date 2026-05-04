import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pension.adb_device import AdbCommandError, AdbDevice, AdbResult


class AdbDeviceTests(unittest.TestCase):
    def test_list_devices_filters_authorized_devices(self):
        device = AdbDevice(adb_path="/tmp/adb")
        output = "List of devices attached\nabc123\tdevice\nxyz\toffline\nemulator\tunauthorized\n"

        with patch("subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(["adb"], 0, output.encode(), b"")

            self.assertEqual(device.list_devices(), ["abc123"])

    def test_screen_size_parses_physical_size(self):
        device = AdbDevice(adb_path="/tmp/adb")

        with patch("subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(["adb"], 0, b"Physical size: 1080x2400\n", b"")

            self.assertEqual(device.get_screen_size(), (1080, 2400))

    def test_tap_rounds_coordinates(self):
        device = AdbDevice(serial="abc123", adb_path="/tmp/adb")

        with patch("subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(["adb"], 0, b"", b"")
            device.tap(900.4, 548.6)

            command = run.call_args.args[0]
            self.assertEqual(command, ["/tmp/adb", "-s", "abc123", "shell", "input", "tap", "900", "549"])

    def test_current_activity_parses_resumed_activity(self):
        device = AdbDevice(adb_path="/tmp/adb")
        output = (
            "    mResumedActivity: ActivityRecord{b5a5a84 u0 "
            "com.truefriend.neosmartarenewal/.ui.login.loginmain.LoginMainActivity t341}\n"
        )

        with patch("subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(["adb"], 0, output.encode(), b"")
            activity = device.get_current_activity()

            self.assertEqual(activity.package, "com.truefriend.neosmartarenewal")
            self.assertEqual(
                activity.activity,
                "com.truefriend.neosmartarenewal.ui.login.loginmain.LoginMainActivity",
            )
            self.assertEqual(
                activity.component,
                "com.truefriend.neosmartarenewal/.ui.login.loginmain.LoginMainActivity",
            )

    def test_failed_command_raises_normalized_error(self):
        device = AdbDevice(adb_path="/tmp/adb")

        with patch("subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(["adb"], 1, b"", b"no device")

            with self.assertRaises(AdbCommandError) as ctx:
                device.get_density()

            self.assertIn("exit code 1", str(ctx.exception))

    def test_screencap_writes_bytes(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "screen.png"
            device = AdbDevice(adb_path="/tmp/adb")
            device.run = lambda *args, **kwargs: AdbResult(["adb"], 0, b"png", b"")

            self.assertEqual(device.screencap(output), output)
            self.assertEqual(output.read_bytes(), b"png")

    def test_screencap_rejects_empty_output(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "screen.png"
            device = AdbDevice(adb_path="/tmp/adb")
            device.run = lambda *args, **kwargs: AdbResult(["adb"], 0, b"", b"")

            with self.assertRaises(AdbCommandError):
                device.screencap(output)
            self.assertFalse(output.exists())

    def test_screencap_falls_back_to_device_file_when_exec_out_is_empty(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "screen.png"
            device = AdbDevice(adb_path="/tmp/adb")
            calls = []

            def fake_run(args, **kwargs):
                calls.append(args)
                if args[:3] == ["exec-out", "screencap", "-p"]:
                    return AdbResult(["adb", *args], 0, b"", b"")
                if args[:2] == ["shell", "screencap"]:
                    self.assertFalse(kwargs.get("check", True))
                    return AdbResult(["adb", *args], 0, b"", b"")
                if args[0] == "pull":
                    output.write_bytes(b"png")
                    return AdbResult(["adb", *args], 0, b"", b"")
                return AdbResult(["adb", *args], 0, b"", b"")

            device.run = fake_run

            self.assertEqual(device.screencap(output), output)
            self.assertEqual(output.read_bytes(), b"png")
            self.assertEqual(calls[0], ["exec-out", "screencap", "-p"])
            self.assertEqual(calls[1][:3], ["shell", "screencap", "-p"])
            self.assertEqual(calls[2][0], "pull")
            self.assertEqual(calls[3][:3], ["shell", "rm", "-f"])


if __name__ == "__main__":
    unittest.main()
