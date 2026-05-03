from __future__ import annotations

import json
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Sequence


@dataclass(frozen=True)
class OcrWord:
    text: str
    left: int
    top: int
    width: int
    height: int
    confidence: float


@dataclass(frozen=True)
class OcrResult:
    image_path: str
    language: str
    text: str
    words: list[OcrWord]

    def to_dict(self) -> dict:
        return {
            "image_path": self.image_path,
            "language": self.language,
            "text": self.text,
            "words": [asdict(word) for word in self.words],
        }

    def save_json(self, output_path: str | Path) -> Path:
        output = Path(output_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, indent=2, ensure_ascii=False)
            f.write("\n")
        return output


class OcrCommandError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        command: Sequence[str],
        returncode: int | None = None,
        stdout: str = "",
        stderr: str = "",
    ):
        super().__init__(message)
        self.command = list(command)
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


@dataclass(frozen=True)
class OcrRuntimeResult:
    command: list[str]
    returncode: int
    stdout: str
    stderr: str


class TesseractRuntime:
    """Thin execution layer for the tesseract binary."""

    def __init__(self, executable: str = "tesseract", *, default_timeout: float = 30):
        self.executable = executable
        self.default_timeout = default_timeout

    def run(
        self,
        args: Sequence[str],
        *,
        timeout: float | None = None,
        check: bool = True,
    ) -> OcrRuntimeResult:
        command = [self.executable, *args]
        try:
            completed = subprocess.run(
                command,
                capture_output=True,
                text=True,
                timeout=timeout or self.default_timeout,
            )
        except subprocess.TimeoutExpired as exc:
            raise OcrCommandError(
                f"tesseract command timed out after {timeout or self.default_timeout}s",
                command=command,
                stdout=exc.stdout or "",
                stderr=exc.stderr or "",
            ) from exc
        except FileNotFoundError as exc:
            raise OcrCommandError(
                f"tesseract executable not found: {self.executable}",
                command=command,
            ) from exc

        result = OcrRuntimeResult(
            command=command,
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )
        if check and completed.returncode != 0:
            raise OcrCommandError(
                f"tesseract command failed with exit code {completed.returncode}: {' '.join(command)}",
                command=command,
                returncode=completed.returncode,
                stdout=completed.stdout,
                stderr=completed.stderr,
            )
        return result

    def version(self) -> str:
        result = self.run(["--version"])
        return result.stdout.splitlines()[0] if result.stdout else ""


class TesseractOcr:
    def __init__(
        self,
        executable: str = "tesseract",
        language: str = "kor+eng",
        *,
        runtime: TesseractRuntime | None = None,
    ):
        self.runtime = runtime or TesseractRuntime(executable)
        self.language = language

    def recognize(
        self,
        image_path: str | Path,
        *,
        psm: int = 6,
        config_vars: dict[str, str] | None = None,
    ) -> OcrResult:
        image = Path(image_path)
        text = self._run_text(image, psm=psm, config_vars=config_vars)
        words = self._run_tsv(image, psm=psm, config_vars=config_vars)
        return OcrResult(
            image_path=str(image),
            language=self.language,
            text=text,
            words=words,
        )

    def recognize_words(
        self,
        image_path: str | Path,
        *,
        psm: int = 6,
        config_vars: dict[str, str] | None = None,
    ) -> OcrResult:
        image = Path(image_path)
        return OcrResult(
            image_path=str(image),
            language=self.language,
            text="",
            words=self._run_tsv(image, psm=psm, config_vars=config_vars),
        )

    def _run_text(self, image_path: Path, *, psm: int, config_vars: dict[str, str] | None) -> str:
        result = self.runtime.run(
            self._build_args(
                str(image_path),
                psm=psm,
                config_vars=config_vars,
            )
        )
        return result.stdout.strip()

    def _run_tsv(self, image_path: Path, *, psm: int, config_vars: dict[str, str] | None) -> list[OcrWord]:
        result = self.runtime.run(
            [
                *self._build_args(
                    str(image_path),
                    psm=psm,
                    config_vars=config_vars,
                ),
                "tsv",
            ]
        )

        words: list[OcrWord] = []
        lines = result.stdout.splitlines()
        if not lines:
            return words
        header = lines[0].split("\t")
        index = {name: i for i, name in enumerate(header)}
        for line in lines[1:]:
            cells = line.split("\t")
            if len(cells) < len(header):
                continue
            text = cells[index["text"]].strip()
            if not text:
                continue
            try:
                confidence = float(cells[index["conf"]])
            except ValueError:
                confidence = -1
            words.append(
                OcrWord(
                    text=text,
                    left=int(cells[index["left"]]),
                    top=int(cells[index["top"]]),
                    width=int(cells[index["width"]]),
                    height=int(cells[index["height"]]),
                    confidence=confidence,
                )
            )
        return words

    def _build_args(
        self,
        image_path: str,
        *,
        psm: int,
        config_vars: dict[str, str] | None,
    ) -> list[str]:
        args = [
            str(image_path),
            "stdout",
            "-l",
            self.language,
            "--psm",
            str(psm),
        ]
        for key, value in (config_vars or {}).items():
            args.extend(["-c", f"{key}={value}"])
        return args
