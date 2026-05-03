from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class ArtifactStep:
    index: int
    name: str
    payload: dict[str, Any]


class RunArtifacts:
    def __init__(self, root: str | Path = "runs", run_id: str | None = None):
        timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        self.run_id = run_id or timestamp
        self.root = Path(root) / self.run_id
        self.root.mkdir(parents=True, exist_ok=True)
        self._step_index = 0

    def next_path(self, suffix: str, *, ext: str) -> Path:
        self._step_index += 1
        safe_suffix = suffix.replace("/", "-").replace(".", "-")
        return self.root / f"{self._step_index:03d}-{safe_suffix}.{ext.lstrip('.')}"

    def write_json(self, name: str, payload: dict[str, Any]) -> Path:
        output = self.next_path(name, ext="json")
        with output.open("w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)
            f.write("\n")
        return output

    def append_log(self, message: str) -> Path:
        output = self.root / "run.log"
        with output.open("a", encoding="utf-8") as f:
            f.write(message.rstrip() + "\n")
        return output

    def write_manifest(self, payload: dict[str, Any]) -> Path:
        output = self.root / "run.json"
        with output.open("w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)
            f.write("\n")
        return output

    def record_step(self, name: str, payload: dict[str, Any]) -> Path:
        step = ArtifactStep(self._step_index + 1, name, payload)
        return self.write_json(name, asdict(step))
