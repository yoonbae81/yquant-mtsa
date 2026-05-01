#!/usr/bin/env bash
# macOS ADB Connect Script
# Usage:
#   ./scripts/conect_adb_macos.sh
#   ./scripts/conect_adb_macos.sh --wifi
#   ./scripts/conect_adb_macos.sh --wifi DEVICE_IP
#   ./scripts/conect_adb_macos.sh -s DEVICE_SERIAL --wifi

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_info() {
  printf "${BLUE}[INFO]${NC} %s\n" "$1"
}

print_success() {
  printf "${GREEN}[SUCCESS]${NC} %s\n" "$1"
}

print_warning() {
  printf "${YELLOW}[WARNING]${NC} %s\n" "$1"
}

print_error() {
  printf "${RED}[ERROR]${NC} %s\n" "$1" >&2
}

usage() {
  cat <<'EOF'
Usage:
  ./scripts/conect_adb_macos.sh [options]

Options:
  -s, --serial SERIAL  Use a specific adb device serial.
  --wifi [DEVICE_IP]   Enable adb-over-WiFi on port 5555 and connect.
                       If DEVICE_IP is omitted, the script tries to detect it
                       from the USB-connected device.
  --restart            Kill and restart the local adb server first.
  -h, --help           Show this help.

Examples:
  ./scripts/conect_adb_macos.sh
  ./scripts/conect_adb_macos.sh --wifi
  ./scripts/conect_adb_macos.sh --wifi 192.168.0.25
  ./scripts/conect_adb_macos.sh -s R5CT123ABCD --wifi
EOF
}

is_ipv4() {
  case "$1" in
    *.*.*.*) return 0 ;;
    *) return 1 ;;
  esac
}

find_adb() {
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi

  for candidate in \
    "${ANDROID_HOME:-}/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
    "${HOME}/Library/Android/sdk/platform-tools/adb" \
    "/opt/homebrew/bin/adb" \
    "/usr/local/bin/adb"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

adb_cmd() {
  if [ -n "$DEVICE_SERIAL" ]; then
    "$ADB" -s "$DEVICE_SERIAL" "$@"
  else
    "$ADB" "$@"
  fi
}

authorized_device_count() {
  "$ADB" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }'
}

first_authorized_serial() {
  "$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }'
}

detect_device_ip() {
  adb_cmd shell ip route 2>/dev/null \
    | tr -d '\r' \
    | awk '/src / { for (i = 1; i <= NF; i++) if ($i == "src") { print $(i + 1); exit } }'
}

DEVICE_SERIAL=""
WIFI_MODE=0
WIFI_IP=""
RESTART_SERVER=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    -s|--serial)
      if [ "$#" -lt 2 ]; then
        print_error "Missing value for $1"
        usage
        exit 1
      fi
      DEVICE_SERIAL="$2"
      shift 2
      ;;
    --wifi)
      WIFI_MODE=1
      shift
      if [ "$#" -gt 0 ] && is_ipv4 "$1"; then
        WIFI_IP="$1"
        shift
      fi
      ;;
    --restart)
      RESTART_SERVER=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      print_error "Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

echo "================================================"
echo "   macOS ADB Connect"
echo "================================================"
echo ""

if [ "$(uname -s)" != "Darwin" ]; then
  print_warning "This script is intended for macOS, but current OS is: $(uname -s)"
fi

if ! ADB="$(find_adb)"; then
  print_error "ADB not found."
  echo ""
  echo "Install Android platform-tools, then rerun:"
  echo "  brew install android-platform-tools"
  echo ""
  echo "Or install Android Studio and ensure this path exists:"
  echo "  ~/Library/Android/sdk/platform-tools/adb"
  exit 1
fi

print_info "Using ADB: $ADB"

if [ "$RESTART_SERVER" -eq 1 ]; then
  print_info "Restarting adb server..."
  "$ADB" kill-server >/dev/null 2>&1 || true
fi

print_info "Starting adb server..."
"$ADB" start-server >/dev/null

DEVICE_COUNT="$(authorized_device_count)"
if [ "$DEVICE_COUNT" -eq 0 ]; then
  print_error "No authorized Android device is connected."
  echo ""
  echo "Check these items:"
  echo "  1. Connect the phone via USB."
  echo "  2. Enable Developer options > USB debugging."
  echo "  3. Accept the USB debugging authorization prompt on the phone."
  echo ""
  print_info "Current adb devices output:"
  "$ADB" devices -l
  exit 1
fi

if [ -z "$DEVICE_SERIAL" ] && [ "$DEVICE_COUNT" -eq 1 ]; then
  DEVICE_SERIAL="$(first_authorized_serial)"
fi

echo ""
print_info "Connected devices:"
"$ADB" devices -l

if [ "$WIFI_MODE" -eq 1 ]; then
  echo ""
  print_info "Preparing adb-over-WiFi on port 5555..."

  if [ -z "$WIFI_IP" ]; then
    WIFI_IP="$(detect_device_ip || true)"
  fi

  if [ -z "$WIFI_IP" ]; then
    print_error "Could not detect the device Wi-Fi IP."
    echo ""
    echo "Run with the device IP manually:"
    echo "  ./scripts/conect_adb_macos.sh --wifi 192.168.0.25"
    echo ""
    echo "Tip: the phone and Mac must be on the same Wi-Fi network."
    exit 1
  fi

  print_info "Switching the USB-connected device to TCP mode..."
  adb_cmd tcpip 5555 >/dev/null
  sleep 2

  print_info "Connecting to ${WIFI_IP}:5555..."
  if "$ADB" connect "${WIFI_IP}:5555"; then
    print_success "Wi-Fi ADB connected: ${WIFI_IP}:5555"
  else
    print_error "Failed to connect to ${WIFI_IP}:5555"
    echo ""
    echo "Check that the Mac and phone are on the same network, then try again."
    exit 1
  fi

  echo ""
  print_info "Devices after Wi-Fi connection:"
  "$ADB" devices -l
fi

echo ""
echo "================================================"
echo "   CONNECTION READY"
echo "================================================"
echo ""
print_success "ADB is ready on macOS."
print_info "Example: $ADB shell"
print_info "Example: $ADB install app/build/outputs/apk/debug/app-debug.apk"
print_info "Example: $ADB logcat | grep MtsaAccessibility"
echo ""
