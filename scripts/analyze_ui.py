#!/usr/bin/env python3
"""
UI Structure Analyzer for MTS App
Dumps UI hierarchy and extracts patterns for balance data extraction
"""

import xml.etree.ElementTree as ET
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional
from dataclasses import dataclass, asdict


@dataclass
class NodePattern:
    text: str
    resource_id: str
    class_name: str
    content_description: str
    clickable: bool
    bounds: str


@dataclass
class ExtractionRule:
    label: str
    target_text: str
    resource_id: str
    extraction_method: str  # "text", "resource_id", "nearby_text"
    position_offset: int
    regex_pattern: Optional[str] = None


class UIAnalyzer:
    def __init__(self, device_serial: Optional[str] = None):
        self.device_serial = device_serial
        self.adb_path = "/Users/y/Downloads/platform-tools/adb"

    def run_adb_command(self, command: str) -> str:
        if self.device_serial:
            command = f"{self.adb_path} -s {self.device_serial} {command}"
        else:
            command = f"{self.adb_path} {command}"

        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            timeout=30
        )

        if result.returncode != 0:
            raise RuntimeError(f"ADB command failed: {result.stderr}")

        return result.stdout

    def dump_ui_hierarchy(self) -> str:
        print("Dumping UI hierarchy...")
        self.run_adb_command("shell uiautomator dump /sdcard/window_dump.xml")
        self.run_adb_command("pull /sdcard/window_dump.xml /tmp/current_screen.xml")

        with open("/tmp/current_screen.xml", "r", encoding="utf-8") as f:
            return f.read()

    def parse_xml(self, xml_content: str) -> ET.Element:
        return ET.fromstring(xml_content)

    def extract_nodes_with_text(self, root: ET.Element, target_text: str) -> List[ET.Element]:
        matching_nodes = []

        for node in root.iter():
            text = node.get("text", "")
            if target_text.lower() in text.lower():
                matching_nodes.append(node)

        return matching_nodes

    def find_nearby_nodes(self, node: ET.Element, max_distance: int = 5) -> List[ET.Element]:
        nearby_nodes = []
        parent = node

        for i in range(max_distance):
            if parent is None:
                break

            siblings = list(parent)
            if len(siblings) == 0:
                parent = self.get_next_sibling(parent)
                continue

            current_index = -1
            for idx, sibling in enumerate(siblings):
                if sibling == node or node in list(sibling):
                    current_index = idx
                    break

            if current_index >= 0:
                for j in range(current_index + 1, min(current_index + max_distance, len(siblings))):
                    nearby_nodes.append(siblings[j])

            parent = parent.getparent()

        return nearby_nodes

    def get_next_sibling(self, node: ET.Element) -> Optional[ET.Element]:
        parent = node.getparent()
        if parent is None:
            return None

        siblings = list(parent)
        for i, sibling in enumerate(siblings):
            if sibling == node and i + 1 < len(siblings):
                return siblings[i + 1]

        return None

    def analyze_balance_screen(self) -> List[ExtractionRule]:
        print("Analyzing balance screen...")
        xml_content = self.dump_ui_hierarchy()
        root = self.parse_xml(xml_content)

        rules = []

        target_labels = ["종목명", "매도가능", "매입단가", "잔고"]

        for label in target_labels:
            matching_nodes = self.extract_nodes_with_text(root, label)

            if not matching_nodes:
                print(f"  ⚠️  Label '{label}' not found")
                continue

            print(f"  ✓ Found {len(matching_nodes)} nodes with '{label}'")

            for node in matching_nodes:
                node_info = self.extract_node_info(node)
                print(f"    - Text: {node_info.text}")
                print(f"      Resource ID: {node_info.resource_id}")
                print(f"      Class: {node_info.class_name}")
                print(f"      Clickable: {node_info.clickable}")
                print(f"      Bounds: {node_info.bounds}")

                nearby_nodes = self.find_nearby_nodes(node)
                print(f"      Found {len(nearby_nodes)} nearby nodes")

                for i, nearby in enumerate(nearby_nodes[:3]):
                    nearby_text = nearby.get("text", "")
                    nearby_resource_id = nearby.get("resource-id", "")
                    if nearby_text and any(char.isdigit() for char in nearby_text):
                        print(f"        → Potential value: {nearby_text} (ID: {nearby_resource_id})")

                        rule = ExtractionRule(
                            label=label,
                            target_text=nearby_text,
                            resource_id=nearby_resource_id,
                            extraction_method="nearby_text",
                            position_offset=i + 1,
                            regex_pattern=self.generate_regex_pattern(nearby_text)
                        )
                        rules.append(rule)
                        break

        return rules

    def extract_node_info(self, node: ET.Element) -> NodePattern:
        return NodePattern(
            text=node.get("text", ""),
            resource_id=node.get("resource-id", ""),
            class_name=node.get("class", ""),
            content_description=node.get("content-desc", ""),
            clickable=node.get("clickable", "false") == "true",
            bounds=node.get("bounds", "")
        )

    def generate_regex_pattern(self, text: str) -> str:
        if text.isdigit():
            return r"\d+"
        elif re.match(r"[\d,]+", text.replace(",", "")):
            return r"[\d,]+"
        else:
            return re.escape(text)

    def save_rules_to_json(self, rules: List[ExtractionRule], output_path: str):
        rules_dict = [asdict(rule) for rule in rules]

        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(rules_dict, f, indent=2, ensure_ascii=False)

        print(f"\n✓ Rules saved to {output_path}")

    def generate_config_file(self, output_path: str = "balance_extraction_rules.json"):
        print("=" * 60)
        print("UI Structure Analyzer - Balance Screen")
        print("=" * 60)

        try:
            rules = self.analyze_balance_screen()

            if not rules:
                print("\n⚠️  No extraction rules generated. Make sure MTS app is on the balance screen.")

            full_config = {
                "screen_name": "7201_balance",
                "version": "1.0",
                "rules": [asdict(rule) for rule in rules],
                "generated_at": str(Path(__file__).stat().st_mtime)
            }

            config_path = Path(output_path)
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(full_config, f, indent=2, ensure_ascii=False)

            print(f"\n✓ Configuration saved to {config_path.absolute()}")
            return config_path

        except Exception as e:
            print(f"\n❌ Error: {e}")
            import traceback
            traceback.print_exc()
            sys.exit(1)


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Analyze MTS app UI structure and generate extraction rules")
    parser.add_argument("--device", help="Device serial number")
    parser.add_argument("--output", default="balance_extraction_rules.json", help="Output JSON file path")
    parser.add_argument("--screen", default="balance", help="Screen to analyze (balance, order, etc.)")

    args = parser.parse_args()

    analyzer = UIAnalyzer(device_serial=args.device)

    if args.screen == "balance":
        analyzer.generate_config_file(args.output)
    else:
        print(f"Screen '{args.screen}' analysis not yet implemented")
        sys.exit(1)


if __name__ == "__main__":
    main()
