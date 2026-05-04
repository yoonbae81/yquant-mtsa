import unittest

from pension.login_bridge import LoginBridge


class FakeDevice:
    def __init__(self):
        self.commands = []

    def shell(self, *args, **kwargs):
        self.commands.append((args, kwargs))
        if args[:3] == ("pm", "list", "packages"):
            return "package:com.yquant.mtsa\n"
        if args[:4] == ("settings", "get", "secure", "enabled_accessibility_services"):
            return "com.yquant.mtsa/com.yquant.mtsa.NeoSmartAccessibilityService"
        if args[:1] == ("ps",):
            return "u0_a225 123 1 S com.yquant.mtsa\n"
        return ""

    def run(self, args, **kwargs):
        self.commands.append((tuple(args), kwargs))
        if args[:4] == ["logcat", "-d", "-s", "MtsaCommandResult"]:
            return (
                '05-04 I/MtsaCommandResult: {"request_id":"pension-abc",'
                '"command":"LOGIN","finished":true,"success":true,'
                '"state":"LOGGED_IN","message":"공동인증서 로그인이 완료되었습니다."}\n'
            )
        return ""


class LoginBridgeTests(unittest.TestCase):
    def test_status_reports_ready(self):
        bridge = LoginBridge(FakeDevice())

        status = bridge.status()

        self.assertTrue(status.ready)
        self.assertTrue(status.package_installed)
        self.assertTrue(status.service_enabled)
        self.assertTrue(status.process_running)

    def test_send_command_broadcasts_login_helper_action(self):
        device = FakeDevice()
        bridge = LoginBridge(device)

        bridge.send_command("LOGIN")

        self.assertIn(
            (
                (
                    "am",
                    "broadcast",
                    "-a",
                    "com.yquant.mtsa.ACTION_NAVIGATE",
                    "--es",
                    "command",
                    "LOGIN",
                ),
                {"timeout": 10, "text": False},
            ),
            device.commands,
        )

    def test_send_command_includes_optional_request_id(self):
        device = FakeDevice()
        bridge = LoginBridge(device)

        bridge.send_command("LOGIN", request_id="pension-abc")

        self.assertIn(
            (
                (
                    "am",
                    "broadcast",
                    "-a",
                    "com.yquant.mtsa.ACTION_NAVIGATE",
                    "--es",
                    "command",
                    "LOGIN",
                    "--es",
                    "request_id",
                    "pension-abc",
                ),
                {"timeout": 10, "text": False},
            ),
            device.commands,
        )

    def test_reads_command_result_from_logcat(self):
        result = LoginBridge(FakeDevice())._latest_command_result("pension-abc")

        self.assertIsNotNone(result)
        self.assertTrue(result.success)
        self.assertEqual(result.state, "LOGGED_IN")


if __name__ == "__main__":
    unittest.main()
