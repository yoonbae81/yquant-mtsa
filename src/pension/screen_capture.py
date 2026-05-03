from __future__ import annotations

from pathlib import Path

from .adb_device import AdbDevice
from .device_profile import Region


class ScreenCapture:
    def __init__(self, device: AdbDevice):
        self.device = device

    def capture(self, output_path: str | Path) -> Path:
        return self.device.screencap(output_path)

    def crop(self, image_path: str | Path, region: Region, output_path: str | Path) -> Path:
        try:
            from PIL import Image
        except ImportError as exc:
            raise RuntimeError("Pillow is required for crop support. Install pillow.") from exc

        source = Path(image_path)
        output = Path(output_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        with Image.open(source) as image:
            cropped = image.crop((region.x, region.y, region.x + region.w, region.y + region.h))
            cropped.save(output)
        return output
