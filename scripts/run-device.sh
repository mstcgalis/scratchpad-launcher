#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

APP_ID="app.scratchpad.launcher.debug"
ACTIVITY="app.olauncher.MainActivity"

./gradlew installDebug
adb shell am start -n "$APP_ID/$ACTIVITY"
