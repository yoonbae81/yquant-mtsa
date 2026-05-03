#!/bin/bash
# WSL ADB Connect Script
# Run this script in WSL to connect to Windows ADB server
# Usage: ./scripts/connect_adb_wsl.sh [WINDOWS_IP]
#   - If WINDOWS_IP is not provided, it will try to auto-detect

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

is_ipv4() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

is_172_ip() {
    [[ "$1" =~ ^172\.[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

get_windows_172_ip() {
    if command -v powershell.exe &> /dev/null; then
        powershell.exe -Command "Get-NetIPAddress -AddressFamily IPv4 | Select-Object -ExpandProperty IPAddress" 2>/dev/null \
            | tr -d '\r' \
            | while IFS= read -r ip; do
                if is_172_ip "$ip"; then
                    echo "$ip"
                    break
                fi
            done
    fi
}

get_windows_wsl_vethernet_ip() {
    if command -v powershell.exe &> /dev/null; then
        powershell.exe -Command "Get-NetIPAddress -AddressFamily IPv4 | Where-Object { \$_.InterfaceAlias -like 'vEthernet (WSL*' -and \$_.IPAddress -notlike '169.254.*' } | Select-Object -First 1 -ExpandProperty IPAddress" 2>/dev/null | tr -d '\r'
    fi
}

echo "================================================"
echo "   WSL ADB Connect to Windows"
echo "================================================"
echo ""

# Auto-detect Windows IP if not provided
if [ -z "$1" ]; then
    print_info "No Windows IP provided, attempting auto-detection..."

    # Method 1: Get WSL vEthernet adapter IP from Windows via PowerShell (most reliable)
    if command -v powershell.exe &> /dev/null; then
        print_info "Trying to get Windows IP via PowerShell (vEthernet WSL adapter)..."
        # Get the first non-link-local IPv4 address from vEthernet (WSL*)
        WIN_IP_PS=$(get_windows_wsl_vethernet_ip)
        if [ -n "$WIN_IP_PS" ] && is_ipv4 "$WIN_IP_PS"; then
            WINDOWS_IP="$WIN_IP_PS"
            print_info "Auto-detected Windows IP (vEthernet WSL): $WINDOWS_IP"
        fi
    fi

    # Method 2: Prefer any detected Windows-side 172.* IP before Linux-side fallbacks
    if [ -z "$WINDOWS_IP" ]; then
        print_info "Trying to detect a Windows 172.* IP..."
        WIN_IP_172=$(get_windows_172_ip)
        if [ -n "$WIN_IP_172" ] && is_172_ip "$WIN_IP_172"; then
            WINDOWS_IP="$WIN_IP_172"
            print_info "Auto-detected Windows IP (172.*): $WINDOWS_IP"
        fi
    fi

    # Method 3: Check /etc/resolv.conf (fallback, with reachability check)
    if [ -z "$WINDOWS_IP" ] && [ -f /etc/resolv.conf ]; then
        RESOLV_IP=$(grep nameserver /etc/resolv.conf | awk '{print $2}' | head -n1)
        if [ -n "$RESOLV_IP" ] && is_ipv4 "$RESOLV_IP"; then
            # Verify the IP is reachable (avoid wrong nameserver IPs like 10.255.255.254)
            if ping -c 1 -W 1 "$RESOLV_IP" &> /dev/null; then
                WINDOWS_IP="$RESOLV_IP"
                print_info "Auto-detected Windows IP (from /etc/resolv.conf): $WINDOWS_IP"
            else
                print_warning "IP from /etc/resolv.conf ($RESOLV_IP) is not reachable, skipping"
            fi
        fi
    fi

    # Method 4: Try ip route (fallback, with reachability check)
    if [ -z "$WINDOWS_IP" ]; then
        ROUTE_IP=$(ip route | grep default | awk '{print $3}' | head -n1)
        if [ -n "$ROUTE_IP" ] && is_ipv4 "$ROUTE_IP"; then
            if ping -c 1 -W 1 "$ROUTE_IP" &> /dev/null; then
                WINDOWS_IP="$ROUTE_IP"
                print_info "Auto-detected Windows IP (from ip route): $WINDOWS_IP"
            else
                print_warning "IP from ip route ($ROUTE_IP) is not reachable, skipping"
            fi
        fi
    fi

    if [ -z "$WINDOWS_IP" ]; then
        print_error "Could not auto-detect Windows IP"
        echo ""
        echo "Please run with Windows IP as argument:"
        echo "  $0 WINDOWS_IP"
        echo ""
        echo "Your Windows IPs (from Windows script):"
        echo "  172.25.176.1"
        echo "  192.168.1.235"
        echo ""
        echo "To find Windows IP manually, run this on Windows:"
        echo "  ipconfig | findstr IPv4"
        exit 1
    fi
else
    WINDOWS_IP="$1"
fi

print_info "Using Windows IP: $WINDOWS_IP"
echo ""

# Check if ADB is available in WSL
if ! command -v adb &> /dev/null; then
    print_warning "ADB not found in WSL PATH"
    print_info "Trying to use Windows ADB via /mnt/c..."

    # Try to find Windows ADB
    WIN_ADB_PATHS=(
        "/mnt/c/Users/$USER/AppData/Local/Android/Sdk/platform-tools/adb.exe"
        "/mnt/c/Android/Sdk/platform-tools/adb.exe"
    )

    ADB_CMD=""
    for path in "${WIN_ADB_PATHS[@]}"; do
        if [ -f "$path" ]; then
            ADB_CMD="$path"
            print_info "Found Windows ADB at: $path"
            break
        fi
    done

    if [ -z "$ADB_CMD" ]; then
        print_error "ADB not found. Please install Android SDK in WSL or ensure Windows ADB is accessible."
        exit 1
    fi

    # Create alias for ADB
    alias adb="$ADB_CMD"
else
    ADB_CMD="adb"
fi

# Connect to Windows ADB server
print_info "Connecting to Windows ADB server at $WINDOWS_IP:5037..."
ADB_SERVER_SOCKET_VALUE="tcp:$WINDOWS_IP:5037"
export ADB_SERVER_SOCKET="$ADB_SERVER_SOCKET_VALUE"
ADB_CONNECT_TIMEOUT_SECONDS=5

# Test connection
print_info "Testing connection (timeout: ${ADB_CONNECT_TIMEOUT_SECONDS}s)..."
if command -v timeout &> /dev/null; then
    timeout "${ADB_CONNECT_TIMEOUT_SECONDS}s" $ADB_CMD devices 2>&1
else
    $ADB_CMD devices 2>&1
fi || {
    print_error "Failed to connect to Windows ADB server"
    echo ""
    echo "Troubleshooting:"
    echo "1. Make sure Windows script (connect_adb_windows.bat) is running"
    echo "2. Check Windows firewall - allow the Windows ADB server on port 5037"
    echo "3. Verify Windows IP is correct: $WINDOWS_IP"
    echo "4. This flow uses the Windows ADB server on port 5037, not adb tcpip 5555"
    exit 1
}

echo ""
print_success "Connected to Windows ADB server!"
echo ""

# Show connected devices
print_info "Connected devices:"
$ADB_CMD devices -l

echo ""
echo "================================================"
echo "   CONNECTION READY"
echo "================================================"
echo ""
print_info "You can now use ADB commands in WSL."
print_info "Example: $ADB_CMD shell"
print_info "Example: $ADB_CMD install app/build/outputs/apk/debug/app-debug.apk"
echo ""

# Optional: Set up persistent environment variable
print_info "This script exported ADB_SERVER_SOCKET as: $ADB_SERVER_SOCKET_VALUE"
print_info "To use the same value in your current shell or another shell, run:"
echo "  export ADB_SERVER_SOCKET=$ADB_SERVER_SOCKET_VALUE"
echo ""
print_info "To make this persistent, add this to your ~/.bashrc or ~/.zshrc:"
echo "  export ADB_SERVER_SOCKET=$ADB_SERVER_SOCKET_VALUE"
echo ""
