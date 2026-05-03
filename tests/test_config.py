import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from pension.cli import main
from pension.config import PensionConfig, parse_account_passwords, parse_allow_real_run, parse_default_profile
from pension.device_profile import DeviceProfile


class PensionConfigTests(unittest.TestCase):
    def test_parse_account_passwords(self):
        passwords = parse_account_passwords(
            """
accounts:
  DC:
    password: "1234"
  IRP:
    password: '5678'
"""
        )

        self.assertEqual(passwords, {"DC": "1234", "IRP": "5678"})

    def test_password_for_account_normalizes_account_type(self):
        config = PensionConfig({"IRP": "5678"})

        self.assertEqual(config.password_for_account("irp"), "5678")
        self.assertIsNone(config.password_for_account("DC"))

    def test_parse_default_profile(self):
        profile = parse_default_profile(
            """
default_profile: "src/pension/profiles/1080x2340"
accounts:
  IRP:
    password: "5678"
"""
        )

        self.assertEqual(profile, "src/pension/profiles/1080x2340")

    def test_parse_allow_real_run_defaults_to_false(self):
        self.assertFalse(parse_allow_real_run(""))
        self.assertFalse(parse_allow_real_run("allow_real_run: false\n"))
        self.assertTrue(parse_allow_real_run("allow_real_run: true\n"))

    def test_cli_uses_config_default_profile_when_profile_arg_is_missing(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            profile_path = root / "profile.json"
            config_path = root / "config.yaml"
            DeviceProfile.from_device_info(serial=None, width=1080, height=2340, density=440).save(profile_path)
            config_path.write_text(f'default_profile: "{profile_path}"\n', encoding="utf-8")

            self.assertEqual(main(["--config", str(config_path), "validate-profile"]), 0)


if __name__ == "__main__":
    unittest.main()
