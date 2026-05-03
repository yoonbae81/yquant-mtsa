import subprocess
import unittest
from unittest.mock import patch

from pension.ocr import OcrCommandError, TesseractOcr, TesseractRuntime


class TesseractRuntimeTests(unittest.TestCase):
    def test_version_returns_first_line(self):
        runtime = TesseractRuntime(executable="/tmp/tesseract")

        with patch("subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(
                ["tesseract"],
                0,
                "tesseract 5.5.1\n leptonica\n",
                "",
            )

            self.assertEqual(runtime.version(), "tesseract 5.5.1")

    def test_failed_command_raises_normalized_error(self):
        runtime = TesseractRuntime(executable="/tmp/tesseract")

        with patch("subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess(["tesseract"], 1, "", "missing language")

            with self.assertRaises(OcrCommandError) as ctx:
                runtime.run(["image.png", "stdout"])

            self.assertIn("exit code 1", str(ctx.exception))
            self.assertEqual(ctx.exception.stderr, "missing language")


class TesseractOcrTests(unittest.TestCase):
    def test_build_args_includes_config_variables(self):
        ocr = TesseractOcr(language="eng")

        args = ocr._build_args(
            "digits.png",
            psm=6,
            config_vars={"tessedit_char_whitelist": "0123456789"},
        )

        self.assertEqual(
            args,
            [
                "digits.png",
                "stdout",
                "-l",
                "eng",
                "--psm",
                "6",
                "-c",
                "tessedit_char_whitelist=0123456789",
            ],
        )


if __name__ == "__main__":
    unittest.main()
