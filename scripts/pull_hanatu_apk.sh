#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARTIFACT_ROOT="${REPO_ROOT}/captures/hanatu-apk"
RAW_DIR="${ARTIFACT_ROOT}/raw"
JADX_DIR="${ARTIFACT_ROOT}/jadx"
APKTOOL_DIR="${ARTIFACT_ROOT}/apktool"
mkdir -p "${RAW_DIR}" "${JADX_DIR}" "${APKTOOL_DIR}"

ADB=""
for candidate in \
  "${ADB_PATH:-}" \
  "$(command -v adb 2>/dev/null || true)" \
  "${REPO_ROOT}/tools/android-tools/adb" \
  "${ANDROID_HOME:-}/platform-tools/adb" \
  "${ANDROID_SDK_ROOT:-}/platform-tools/adb" \
  "${HOME}/Library/Android/sdk/platform-tools/adb" \
  "/opt/homebrew/bin/adb" \
  "/usr/local/bin/adb"; do
  if [[ -n "${candidate}" && -x "${candidate}" ]]; then
    ADB="${candidate}"
    break
  fi
done

if [[ -z "${ADB}" ]]; then
  printf 'adb is not installed or not reachable.\n' >&2
  printf 'Tried PATH, %s, and common Android SDK locations.\n' "${REPO_ROOT}/tools/android-tools/adb" >&2
  exit 1
fi

ADB_ARGS=()

if ! command -v jadx >/dev/null 2>&1; then
  printf 'jadx is missing from PATH.\n' >&2
  exit 1
fi

if ! command -v apktool >/dev/null 2>&1; then
  printf 'apktool is missing from PATH.\n' >&2
  exit 1
fi

run_adb() {
  if [[ "${#ADB_ARGS[@]}" -eq 0 ]]; then
    "${ADB}" "$@"
  else
    "${ADB}" "${ADB_ARGS[@]}" "$@"
  fi
}

DEVICES=()
while IFS= read -r device; do
  [[ -n "${device}" ]] && DEVICES+=("${device}")
done < <("${ADB}" devices | tail -n +2 | awk '$2 == "device" { print $1 }')

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
  CANDIDATES=()
  while IFS= read -r candidate; do
    [[ -n "${candidate}" ]] && CANDIDATES+=("${candidate}")
  done < <(
    run_adb shell pm list packages | tr -d '\r' | grep -iE 'truefriend|koreainvest|koreainvestment|hanatu|stock' | sed 's/^package://' || true
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

APK_PATHS=()
while IFS= read -r apk_path; do
  [[ -n "${apk_path}" ]] && APK_PATHS+=("${apk_path}")
done < <(
  run_adb shell pm path "${PACKAGE}" | tr -d '\r' | sed 's/^package://' || true
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
  run_adb pull "${REMOTE_PATH}" "${LOCAL_PATH}"
  if [[ "${FILE_NAME}" == "base.apk" ]]; then
    BASE_APK="${LOCAL_PATH}"
  fi
done

if [[ -z "${BASE_APK}" ]]; then
  BASE_APK="$(ls "${RAW_DIR}"/*.apk | head -n 1)"
fi

printf 'Decompiling base APK with JADX: %s\n' "${BASE_APK}"
if ! jadx -d "${JADX_DIR}" "${BASE_APK}"; then
  printf 'jadx finished with warnings or errors, but output was still written to %s\n' "${JADX_DIR}" >&2
fi

printf 'Decompiling resources/smali with apktool: %s\n' "${BASE_APK}"
if ! apktool d -f -o "${APKTOOL_DIR}" "${BASE_APK}"; then
  printf 'apktool finished with warnings or errors, but partial output may exist at %s\n' "${APKTOOL_DIR}" >&2
fi

printf '\nDone. Artifacts:\n'
printf '  raw:     %s\n' "${RAW_DIR}"
printf '  jadx:    %s\n' "${JADX_DIR}"
printf '  apktool: %s\n' "${APKTOOL_DIR}"
