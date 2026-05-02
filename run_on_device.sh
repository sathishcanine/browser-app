#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
echo "Device(s):"
adb devices -l
# Avoid "adb: more than one device/emulator" when multiple are connected.
ADB_SERIAL="${ANDROID_SERIAL:-$(adb devices | awk '/\tdevice$/{print $1; exit}')}"
if [[ -z "${ADB_SERIAL}" ]]; then
  echo "No device in 'device' state; connect one or set ANDROID_SERIAL." >&2
  exit 1
fi
export ANDROID_SERIAL="${ADB_SERIAL}"
echo "Using ANDROID_SERIAL=${ANDROID_SERIAL}"
echo "Installing Lightning Plus (debug)..."
./gradlew :app:installLightningPlusDebug
echo "Launching app..."
adb shell am start -n com.browser.minnal/.DefaultBrowserActivity
echo "Done."
