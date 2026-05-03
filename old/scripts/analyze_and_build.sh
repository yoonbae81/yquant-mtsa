#!/bin/bash
# Script to analyze MTS app UI structure and generate extraction rules

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ADB_PATH="/Users/y/Downloads/platform-tools/adb"

echo "=========================================="
echo "MTS App UI Structure Analyzer"
echo "=========================================="
echo ""

# Check if device is connected
echo "Checking device connection..."
DEVICE_COUNT=$($ADB_PATH devices | grep -c "device$" || true)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "❌ No Android device connected!"
    echo "Please connect your device and enable USB debugging."
    exit 1
fi

echo "✓ Device connected"
echo ""

# Check if MTS app is running
echo "Checking MTS app status..."
MTS_RUNNING=$($ADB_PATH shell "ps | grep neosmartarenewal" || echo "")

if [ -z "$MTS_RUNNING" ]; then
    echo "⚠️  MTS app is not running"
    echo "Please start the MTS app and navigate to the screen you want to analyze."
    read -p "Press Enter when ready to continue..."
else
    echo "✓ MTS app is running"
fi

echo ""
echo "Please navigate to the screen you want to analyze (e.g., 7201 balance screen)"
read -p "Press Enter when ready to analyze..."
echo ""

# Run the Python analyzer
echo "Running UI analyzer..."
cd "$PROJECT_ROOT"
python3 scripts/analyze_ui.py --output android-prototype/app/src/main/assets/balance_extraction_rules.json

echo ""
echo "=========================================="
echo "Analysis complete!"
echo "=========================================="
echo ""
echo "Next steps:"
echo "1. Review the generated rules in android-prototype/app/src/main/assets/balance_extraction_rules.json"
echo "2. Adjust rules if necessary"
echo "3. Rebuild the APK: ./gradlew assembleDebug"
echo "4. Install the updated APK: adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo ""
