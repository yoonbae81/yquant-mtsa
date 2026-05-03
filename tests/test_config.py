import unittest

from pension.config import PensionConfig, parse_account_passwords


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


if __name__ == "__main__":
    unittest.main()
