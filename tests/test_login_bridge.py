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


if __name__ == "__main__":
    unittest.main()
