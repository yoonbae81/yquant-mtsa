PYTHON ?= python3
VENV ?= .venv
VENV_PYTHON := $(VENV)/bin/python
VENV_PIP := $(VENV)/bin/pip
PYTHONPATH := src
ADB ?= adb
TESSERACT ?= tesseract
LOGIN_DIR := src/login
LOGIN_APK := $(LOGIN_DIR)/app/build/outputs/apk/debug/app-debug.apk

.PHONY: setup check-env test compile cli-help login-build login-install clean-venv

setup:
	$(PYTHON) -m venv $(VENV)
	$(VENV_PYTHON) -m pip install --upgrade pip
	$(VENV_PIP) install -r requirements.txt
	@test -x "$(ADB)" || (echo "ADB executable not found: $(ADB)"; echo "Expected local path: adb"; exit 1)
	@command -v "$(TESSERACT)" >/dev/null || (echo "tesseract executable not found."; echo "Install it with: brew install tesseract tesseract-lang"; exit 1)

check-env:
	@test -x "$(ADB)" || (echo "ADB executable not found: $(ADB)"; echo "Expected local path: adb"; exit 1)
	@command -v "$(TESSERACT)" >/dev/null || (echo "tesseract executable not found."; echo "Install it with: brew install tesseract tesseract-lang"; exit 1)
	@$(ADB) version | head -n 2
	@$(TESSERACT) --version | head -n 1
	@$(ADB) devices

test:
	PYTHONPATH=$(PYTHONPATH) $(VENV_PYTHON) -m unittest discover -s tests

compile:
	PYTHONPATH=$(PYTHONPATH) $(VENV_PYTHON) -m compileall src/pension tests

cli-help:
	PYTHONPATH=$(PYTHONPATH) $(VENV_PYTHON) -m pension.cli --help
	./scripts/debug --help
	./scripts/profile --help
	./scripts/run --help

login-build:
	cd $(LOGIN_DIR) && ./gradlew assembleDebug

login-install: login-build
	$(ADB) install -r $(LOGIN_APK)

clean-venv:
	rm -rf $(VENV)
