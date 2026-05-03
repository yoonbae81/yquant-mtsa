#!/usr/bin/env python3
"""
UI Structure Analyzer for MTS App (uiautomator2 version with auto-click)
Dumps UI hierarchy and extracts patterns for balance data extraction
"""

import uiautomator2 as u2
import json
import re
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional
from dataclasses import dataclass, asdict


@dataclass
class ExtractionRule:
    label: str
    target_text: str
    resource_id: str
    extraction_method: str
    position_offset: int
    regex_pattern: Optional[str] = None


class UIAnalyzerUiautomator2:
    def __init__(self, device_serial: Optional[str] = None):
        if device_serial:
            self.d = u2.connect(device_serial)
        else:
            self.d = u2.connect()
        print(f"✓ Connected to device: {self.d.serial}")

    def analyze_balance_screen(self) -> List[ExtractionRule]:
        print("=" * 60)
        print("UI Structure Analyzer - Balance Screen (uiautomator2)")
        print("=" * 60)
        print("\nStep 1: Checking for '보유잔고 조회' button...")

        # Check if we need to click "보유잔고 조회"
        xml_content = self.d.dump_hierarchy()
        if "보유잔고 조회" in xml_content:
            print("✓ Found '보유잔고 조회' button")
            print("Step 2: Clicking '보유잔고 조회' button...")

            # Find and click the button
            if self.d(text="보유잔고 조회").exists:
                self.d(text="보유잔고 조회").click()
                print("✓ Clicked '보유잔고 조회' button")
                time.sleep(2)  # Wait for content to load
            else:
                print("⚠️  Could not find clickable '보유잔고 조회' button")
        else:
            print("⚠️  '보유잔고 조회' button not found, assuming balance data is already visible")

        print("\nStep 3: Analyzing balance screen...")
        xml_content = self.d.dump_hierarchy()
        print(f"✓ UI hierarchy dumped ({len(xml_content)} bytes)")

        rules = []
        target_labels = ["종목명", "매도가능", "매입단가", "잔고", "보유수량", "평가금액"]

        for label in target_labels:
            matching_nodes = self.find_nodes_by_text(xml_content, label)

            if not matching_nodes:
                print(f"  ⚠️  Label '{label}' not found")
                continue

            print(f"  ✓ Found {len(matching_nodes)} nodes with '{label}'")

            for node_info in matching_nodes[:1]:  # Only process first match
                print(f"    - Text: {node_info.get('text', '')}")
                print(f"      Resource ID: {node_info.get('resource-id', '')}")
                print(f"      Class: {node_info.get('class', '')}")

                nearby_values = self.find_nearby_values(xml_content, node_info, max_distance=5)
                print(f"      Found {len(nearby_values)} nearby values")

                for value in nearby_values[:1]:  # Only take first match
                    print(f"        → Potential value: {value}")

                    rule = ExtractionRule(
                        label=label,
                        target_text=value,
                        resource_id=node_info.get('resource-id', ''),
                        extraction_method="nearby_text",
                        position_offset=1,
                        regex_pattern=self.generate_regex_pattern(value)
                    )
                    rules.append(rule)
                    break

        return rules

    def find_nodes_by_text(self, xml_content: str, target_text: str) -> List[Dict]:
        matching_nodes = []

        import xml.etree.ElementTree as ET
        root = ET.fromstring(xml_content)

        for node in root.iter():
            text = node.get("text", "")
            if target_text.lower() in text.lower():
                node_info = {
                    'text': text,
                    'resource-id': node.get('resource-id', ''),
                    'class': node.get('class', ''),
                    'content-desc': node.get('content-desc', ''),
                    'clickable': node.get('clickable', 'false') == 'true',
                    'bounds': node.get('bounds', '')
                }
                matching_nodes.append(node_info)

        return matching_nodes

    def find_nearby_values(self, xml_content: str, target_node: Dict, max_distance: int = 3) -> List[str]:
        import xml.etree.ElementTree as ET
        root = ET.fromstring(xml_content)

        values = []
        target_resource_id = target_node.get('resource-id', '')
        target_text = target_node.get('text', '')

        # Find the target node in XML
        target_element = None
        for node in root.iter():
            if node.get('resource-id') == target_resource_id and node.get('text') == target_text:
                target_element = node
                break

        if target_element is None:
            return values

        # Get all nodes with text
        all_nodes = []
        for node in root.iter():
            text = node.get('text', '').strip()
            if text:
                all_nodes.append((node, text))

        # Find target node index
        target_index = -1
        for i, (node, text) in enumerate(all_nodes):
            if node.get('resource-id') == target_resource_id and text == target_text:
                target_index = i
                break

        if target_index == -1:
            return values

        # Look at next nodes
        for j in range(target_index + 1, min(target_index + max_distance + 1, len(all_nodes))):
            node, text = all_nodes[j]
            if any(char.isdigit() for char in text):
                values.append(text)

        return values

    def generate_regex_pattern(self, text: str) -> str:
        if text.isdigit():
            return r"\d+"
        elif re.match(r"[\d,]+", text.replace(",", "")):
            return r"[\d,]+"
        else:
            return re.escape(text)

    def save_rules_to_json(self, rules: List[ExtractionRule], output_path: str):
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


def main():
    import argparse

    parser = argparse.ArgumentParser(description="Analyze MTS app UI structure using uiautomator2")
    parser.add_argument("--device", help="Device serial number")
    parser.add_argument("--output", default="app/src/main/assets/balance_extraction_rules.json", help="Output JSON file path")

    args = parser.parse_args()

    try:
        analyzer = UIAnalyzerUiautomator2(device_serial=args.device)
        rules = analyzer.analyze_balance_screen()

        if rules:
            analyzer.save_rules_to_json(rules, args.output)
            print("\n✓ Analysis complete!")
            print(f"  Generated {len(rules)} extraction rules")
        else:
            print("\n⚠️  No rules generated. Make sure MTS app is on the 7201 screen.")
            print("  Try manually navigating to the balance screen and running again.")
    except Exception as e:
        print(f"\n❌ Error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
