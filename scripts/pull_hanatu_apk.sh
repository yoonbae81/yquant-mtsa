#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARTIFACT_ROOT="${REPO_ROOT}/captures/hanatu-apk"
RAW_DIR="${ARTIFACT_ROOT}/raw"
JADX_DIR="${ARTIFACT_ROOT}/jadx"
APKTOOL_DIR="${ARTIFACT_ROOT}/apktool"
TOOLS_DIR="${REPO_ROOT}/tools/android-tools"
JADX_BIN="${TOOLS_DIR}/jadx-1.5.5/bin/jadx"
APKTOOL_JAR="${TOOLS_DIR}/apktool_3.0.2.jar"

mkdir -p "${RAW_DIR}" "${JADX_DIR}" "${APKTOOL_DIR}"

if ! command -v adb >/dev/null 2>&1; then
  printf 'adb is not installed or not on PATH.\n' >&2
  exit 1
fi

if [[ ! -x "${JADX_BIN}" ]]; then
  printf 'jadx is missing: %s\n' "${JADX_BIN}" >&2
  exit 1
fi

if [[ ! -f "${APKTOOL_JAR}" ]]; then
  printf 'apktool jar is missing: %s\n' "${APKTOOL_JAR}" >&2
  exit 1
fi

mapfile -t DEVICES < <(adb devices | tail -n +2 | awk '$2 == "device" { print $1 }')
if [[ "${#DEVICES[@]}" -eq 0 ]]; then
  printf 'No authorized adb device is connected.\n' >&2
  exit 1
fi

if [[ -n "${ADB_SERIAL:-}" ]]; then
  ADB_ARGS=(-s "${ADB_SERIAL}")
else
  ADB_ARGS=()
fi

if [[ -n "${PACKAGE_NAME:-}" ]]; then
  PACKAGE="${PACKAGE_NAME}"
else
  mapfile -t CANDIDATES < <(
    adb "${ADB_ARGS[@]}" shell pm list packages | tr -d '\r' | grep -iE 'truefriend|koreainvest|koreainvestment|hanatu|stock' | sed 's/^package://' || true
  )

  if [[ "${#CANDIDATES[@]}" -eq 0 ]]; then
    printf 'Could not infer the 한국투자 package automatically. Set PACKAGE_NAME and rerun.\n' >&2
    exit 1
  fi

  if [[ "${#CANDIDATES[@]}" -gt 1 ]]; then
    printf 'Multiple package candidates found. Set PACKAGE_NAME to one of these values:\n' >&2
    printf '  %s\n' "${CANDIDATES[@]}" >&2
    exit 1
  fi

  PACKAGE="${CANDIDATES[0]}"
fi

printf 'Using package: %s\n' "${PACKAGE}"

mapfile -t APK_PATHS < <(
  adb "${ADB_ARGS[@]}" shell pm path "${PACKAGE}" | tr -d '\r' | sed 's/^package://' || true
)

if [[ "${#APK_PATHS[@]}" -eq 0 ]]; then
  printf 'No APK paths returned for package %s\n' "${PACKAGE}" >&2
  exit 1
fi

rm -f "${RAW_DIR}"/*.apk
rm -rf "${JADX_DIR}"/* "${APKTOOL_DIR}"/*

BASE_APK=""
for REMOTE_PATH in "${APK_PATHS[@]}"; do
  FILE_NAME="$(basename "${REMOTE_PATH}")"
  LOCAL_PATH="${RAW_DIR}/${FILE_NAME}"
  printf 'Pulling %s -> %s\n' "${REMOTE_PATH}" "${LOCAL_PATH}"
  adb "${ADB_ARGS[@]}" pull "${REMOTE_PATH}" "${LOCAL_PATH}"
  if [[ "${FILE_NAME}" == "base.apk" ]]; then
    BASE_APK="${LOCAL_PATH}"
  fi
done

if [[ -z "${BASE_APK}" ]]; then
  BASE_APK="$(ls "${RAW_DIR}"/*.apk | head -n 1)"
fi

printf 'Decompiling base APK with JADX: %s\n' "${BASE_APK}"
"${JADX_BIN}" -d "${JADX_DIR}" "${BASE_APK}"

printf 'Decompiling resources/smali with apktool: %s\n' "${BASE_APK}"
java -jar "${APKTOOL_JAR}" d -f -o "${APKTOOL_DIR}" "${BASE_APK}"

printf '\nDone. Artifacts:\n'
printf '  raw:     %s\n' "${RAW_DIR}"
printf '  jadx:    %s\n' "${JADX_DIR}"
printf '  apktool: %s\n' "${APKTOOL_DIR}"
