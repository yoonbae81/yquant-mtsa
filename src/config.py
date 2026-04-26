import os
from pathlib import Path

# Project paths
PROJECT_ROOT = Path(__file__).parent
SRC_DIR = PROJECT_ROOT / "src"
ADB_PATH = os.getenv("ADB_PATH", "/Users/y/Downloads/platform-tools/adb")

# App package
NEOSMART_PACKAGE = "com.truefriend.neosmartarenewal"

# API config
API_HOST = "0.0.0.0"
API_PORT = 8080

# Timeouts
SCREENSHOT_TIMEOUT = 10  # seconds
CLICK_TIMEOUT = 5
WAIT_TIMEOUT = 30