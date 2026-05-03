from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class Point:
    x: int
    y: int
    rx: float | None = None
    ry: float | None = None


@dataclass(frozen=True)
class Region:
    x: int
    y: int
    w: int
    h: int


@dataclass(frozen=True)
class Swipe:
    x1: int
    y1: int
    x2: int
    y2: int
    duration_ms: int = 300


class DeviceProfile:
    def __init__(self, data: dict[str, Any], source_path: Path | None = None):
        self.data = data
        self.source_path = source_path

    @classmethod
    def load(cls, path: str | Path) -> "DeviceProfile":
        profile_path = Path(path)
        if profile_path.is_dir():
            data = cls._load_directory(profile_path)
            return cls(data, profile_path)
        with profile_path.open("r", encoding="utf-8") as f:
            return cls(json.load(f), profile_path)

    def save(self, path: str | Path | None = None) -> Path:
        output = Path(path) if path is not None else self.source_path
        if output is None:
            raise ValueError("No profile path provided")
        if output.suffix == "" or output.is_dir():
            self._save_directory(output)
            return output
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open("w", encoding="utf-8") as f:
            json.dump(self.data, f, indent=2, ensure_ascii=False)
            f.write("\n")
        return output

    @property
    def serial(self) -> str | None:
        return self.data.get("device", {}).get("serial")

    @property
    def width(self) -> int | None:
        value = self.data.get("device", {}).get("width")
        return int(value) if value else None

    @property
    def height(self) -> int | None:
        value = self.data.get("device", {}).get("height")
        return int(value) if value else None

    def tap_point(self, name: str) -> Point:
        screen, key = self._split_name(name)
        point = self.data["screens"][screen]["tap_points"][key]
        return Point(
            x=int(point["x"]),
            y=int(point["y"]),
            rx=float(point["rx"]) if "rx" in point else None,
            ry=float(point["ry"]) if "ry" in point else None,
        )

    def region(self, name: str) -> Region:
        screen, key = self._split_name(name)
        region = self.data["screens"][screen]["regions"][key]
        return Region(
            x=int(region["x"]),
            y=int(region["y"]),
            w=int(region["w"]),
            h=int(region["h"]),
        )

    def swipe(self, name: str) -> Swipe:
        screen, key = self._split_name(name)
        swipe = self.data["screens"][screen]["swipes"][key]
        return Swipe(
            x1=int(swipe["x1"]),
            y1=int(swipe["y1"]),
            x2=int(swipe["x2"]),
            y2=int(swipe["y2"]),
            duration_ms=int(swipe.get("duration_ms", 300)),
        )

    def post_delay_ms(self, name: str, default: int = 1000) -> int:
        screen, key = self._split_name(name)
        screen_data = self.data["screens"][screen]
        if key in screen_data.get("tap_points", {}):
            return int(screen_data["tap_points"][key].get("post_delay_ms", default))
        if key in screen_data.get("swipes", {}):
            return int(screen_data["swipes"][key].get("post_delay_ms", default))
        return default

    def anchors(self, screen: str) -> list[str] | dict[str, Any]:
        anchors = self.data["screens"][screen].get("anchors", [])
        return dict(anchors) if isinstance(anchors, dict) else list(anchors)

    def recovery(self, name: str) -> dict[str, Any]:
        return dict(self.data.get("recoveries", {})[name])

    def recovery_tap_point(self, name: str) -> Point:
        recovery, key = self._split_name(name)
        point = self.data["recoveries"][recovery]["tap_points"][key]
        return Point(
            x=int(point["x"]),
            y=int(point["y"]),
            rx=float(point["rx"]) if "rx" in point else None,
            ry=float(point["ry"]) if "ry" in point else None,
        )

    def set_tap_point(self, name: str, x: int, y: int) -> Point:
        screen, key = self._split_name(name)
        self._ensure_screen(screen)
        point = {"x": int(x), "y": int(y)}
        if self.width and self.height:
            width, height = self.width, self.height
            point["rx"] = round(x / width, 6)
            point["ry"] = round(y / height, 6)
        self.data["screens"][screen].setdefault("tap_points", {})[key] = point
        return self.tap_point(name)

    def set_region(self, name: str, x: int, y: int, w: int, h: int) -> Region:
        screen, key = self._split_name(name)
        self._ensure_screen(screen)
        region = {
            "x": int(x),
            "y": int(y),
            "w": int(w),
            "h": int(h),
        }
        if self.width and self.height:
            width, height = self.width, self.height
            region["rx"] = round(x / width, 6)
            region["ry"] = round(y / height, 6)
            region["rw"] = round(w / width, 6)
            region["rh"] = round(h / height, 6)
        self.data["screens"][screen].setdefault("regions", {})[key] = region
        return self.region(name)

    def set_swipe(self, name: str, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> Swipe:
        screen, key = self._split_name(name)
        self._ensure_screen(screen)
        swipe = {
            "x1": int(x1),
            "y1": int(y1),
            "x2": int(x2),
            "y2": int(y2),
            "duration_ms": int(duration_ms),
        }
        if self.width and self.height:
            width, height = self.width, self.height
            swipe["rx1"] = round(x1 / width, 6)
            swipe["ry1"] = round(y1 / height, 6)
            swipe["rx2"] = round(x2 / width, 6)
            swipe["ry2"] = round(y2 / height, 6)
        self.data["screens"][screen].setdefault("swipes", {})[key] = swipe
        return self.swipe(name)

    def validate(self) -> list[str]:
        errors: list[str] = []
        device = self.data.get("device", {})
        if "serial" in device:
            errors.append("device.serial must not be stored in a shared profile")
        width = self.width
        height = self.height
        if not width or not height:
            errors.append("device.width and device.height are required")

        screens = self.data.get("screens", {})
        if not isinstance(screens, dict):
            errors.append("screens must be an object")
            return errors

        for screen, screen_data in screens.items():
            if not isinstance(screen_data, dict):
                errors.append(f"{screen}: screen data must be an object")
                continue
            anchors = screen_data.get("anchors", {})
            if isinstance(anchors, dict):
                for key in ("required", "optional", "forbidden"):
                    if key in anchors and not isinstance(anchors[key], list):
                        errors.append(f"{screen}.anchors.{key} must be a list")
            elif not isinstance(anchors, list):
                errors.append(f"{screen}.anchors must be a list or object")

            for point_name, point in screen_data.get("tap_points", {}).items():
                if not isinstance(point, dict) or "x" not in point or "y" not in point:
                    errors.append(f"{screen}.tap_points.{point_name} must include x and y")
                    continue
                if width and height and not (0 <= int(point["x"]) <= width and 0 <= int(point["y"]) <= height):
                    errors.append(f"{screen}.tap_points.{point_name} is outside device bounds")
                if int(point.get("post_delay_ms", 0)) < 0:
                    errors.append(f"{screen}.tap_points.{point_name}.post_delay_ms must be non-negative")

            for region_name, region in screen_data.get("regions", {}).items():
                required = {"x", "y", "w", "h"}
                if not isinstance(region, dict) or not required.issubset(region):
                    errors.append(f"{screen}.regions.{region_name} must include x, y, w, and h")
                    continue
                x = int(region["x"])
                y = int(region["y"])
                w = int(region["w"])
                h = int(region["h"])
                if w <= 0 or h <= 0:
                    errors.append(f"{screen}.regions.{region_name} must have positive width and height")
                if width and height and (x < 0 or y < 0 or x + w > width or y + h > height):
                    errors.append(f"{screen}.regions.{region_name} is outside device bounds")

            for swipe_name, swipe in screen_data.get("swipes", {}).items():
                required = {"x1", "y1", "x2", "y2"}
                if not isinstance(swipe, dict) or not required.issubset(swipe):
                    errors.append(f"{screen}.swipes.{swipe_name} must include x1, y1, x2, and y2")
                    continue
                points = ((int(swipe["x1"]), int(swipe["y1"])), (int(swipe["x2"]), int(swipe["y2"])))
                if width and height:
                    for x, y in points:
                        if x < 0 or y < 0 or x > width or y > height:
                            errors.append(f"{screen}.swipes.{swipe_name} is outside device bounds")
                            break
                if int(swipe.get("post_delay_ms", 0)) < 0:
                    errors.append(f"{screen}.swipes.{swipe_name}.post_delay_ms must be non-negative")
        return errors

    @staticmethod
    def _split_name(name: str) -> tuple[str, str]:
        if "." not in name:
            raise ValueError(f"Expected '<screen>.<name>', got: {name}")
        return name.split(".", 1)

    def _ensure_screen(self, screen: str) -> None:
        self.data.setdefault("screens", {}).setdefault(screen, {"anchors": []})

    @classmethod
    def from_device_info(
        cls,
        *,
        serial: str,
        width: int,
        height: int,
        density: int | None,
    ) -> "DeviceProfile":
        return cls(
            {
                "device": {
                    "width": width,
                    "height": height,
                    "density": density,
                },
                "screens": {},
                "recoveries": {},
                "schema_version": 1,
            }
        )

    @staticmethod
    def _screen_file_name(screen: str) -> str:
        return f"{screen.replace('_', '-')}.json"

    @classmethod
    def _load_directory(cls, root: Path) -> dict[str, Any]:
        manifest_path = root / "manifest.json"
        if manifest_path.exists():
            with manifest_path.open("r", encoding="utf-8") as f:
                data = json.load(f)
        else:
            data = {"schema_version": 1, "device": {}, "screens": {}, "recoveries": {}}

        data.setdefault("screens", {})
        data.setdefault("recoveries", {})

        recovery_path = root / "recovery.json"
        if recovery_path.exists():
            with recovery_path.open("r", encoding="utf-8") as f:
                recovery_data = json.load(f)
            data["recoveries"] = recovery_data.get("recoveries", recovery_data)

        for file_path in sorted(root.glob("*.json")):
            if file_path.name in {"manifest.json", "recovery.json"}:
                continue
            with file_path.open("r", encoding="utf-8") as f:
                screen_data = json.load(f)
            screen_name = screen_data.get("screen") or file_path.stem.replace("-", "_")
            screen_data.pop("screen", None)
            if screen_name in data["screens"]:
                data["screens"][screen_name] = cls._merge_screen_data(data["screens"][screen_name], screen_data)
            else:
                data["screens"][screen_name] = screen_data
        return data

    @classmethod
    def _merge_screen_data(cls, current: dict[str, Any], incoming: dict[str, Any]) -> dict[str, Any]:
        merged = dict(current)
        for key, value in incoming.items():
            if isinstance(merged.get(key), dict) and isinstance(value, dict):
                merged[key] = cls._merge_screen_data(merged[key], value)
            else:
                merged[key] = value
        return merged

    def _save_directory(self, root: Path) -> None:
        root.mkdir(parents=True, exist_ok=True)
        manifest = {
            "schema_version": self.data.get("schema_version", 1),
            "device": {
                key: value
                for key, value in self.data.get("device", {}).items()
                if key != "serial"
            },
        }
        with (root / "manifest.json").open("w", encoding="utf-8") as f:
            json.dump(manifest, f, indent=2, ensure_ascii=False)
            f.write("\n")

        for screen, screen_data in sorted(self.data.get("screens", {}).items()):
            payload = {"screen": screen, **screen_data}
            with (root / self._screen_file_name(screen)).open("w", encoding="utf-8") as f:
                json.dump(payload, f, indent=2, ensure_ascii=False)
                f.write("\n")

        with (root / "recovery.json").open("w", encoding="utf-8") as f:
            json.dump({"recoveries": self.data.get("recoveries", {})}, f, indent=2, ensure_ascii=False)
            f.write("\n")
