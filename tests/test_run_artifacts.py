import tempfile
import unittest
from pathlib import Path

from pension.run_artifacts import RunArtifacts


class RunArtifactsTests(unittest.TestCase):
    def test_writes_manifest_log_and_numbered_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            artifacts = RunArtifacts(root=temp_dir, run_id="run-1")
            manifest = artifacts.write_manifest({"mode": "dry-run"})
            log = artifacts.append_log("hello")
            step = artifacts.record_step("capture.current", {"state": "ok"})

            self.assertEqual(manifest, Path(temp_dir) / "run-1" / "run.json")
            self.assertEqual(log.read_text(encoding="utf-8"), "hello\n")
            self.assertEqual(step.name, "001-capture-current.json")
            self.assertIn('"state": "ok"', step.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
