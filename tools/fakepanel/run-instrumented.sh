#!/usr/bin/env bash
# Runs the M2 instrumented playback suite (docs/design/m2-playback-core.md §5.5) against the fake panel.
#
#   tools/fakepanel/run-instrumented.sh [--port 18080] [--host-url http://10.0.2.2:18080] [--synthetic 2000] [--set KEY=JSON]… [--class app.thebox.playback.SmokeAndZapTest] [gradle args…]
#
# --set KEY=JSON passes a config key to the panel (repeatable; M4 player suite: --set player_fixtures=true --set timeshift_mode='"duration"').
#
# --synthetic N starts the panel in synthetic mode (the M3 guide suite, app.thebox.guide.*; docs/design/m3-guide.md §5.6).
# Starts fakepanel.py on --port (all interfaces), waits for GET /admin/config, runs
# :app:connectedDebugAndroidTest with -Pandroid.testInstrumentationRunnerArguments.fakepanel=<host-url>, then stops the
# panel. The emulator reaches the Mac at 10.0.2.2; for a device on the LAN pass --host-url http://<mac-ip>:<port>.
# --class limits the run to one test class (or Class#method). ffmpeg media are generated on the first panel start.
# --update-fixtures (M5 I-M5-19, app.thebox.settings.UpdaterTest): builds the debug APK, derives versionCode-2 copies
# signed with the debug key and with a throwaway key (make_update_apk.py), loads them into the panel by name
# (--update-apk-dir: installed, v2, other) and pushes v2/other to /data/local/tmp as the upload fallback.
# Before each run the Leanback IME is selected and Gboard force-stopped when present (U-S13 / D-L16).
# Needs ANDROID_HOME and JAVA_HOME (docs/m0-toolchain.md) and one connected device/emulator (adb devices).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PORT=18080
HOST_URL=""
CLASS=""
UPDATE_FIXTURES=0
EXTRA=()
PANEL_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --host-url) HOST_URL="$2"; shift 2 ;;
    --class) CLASS="$2"; shift 2 ;;
    --update-fixtures) UPDATE_FIXTURES=1; shift ;;
    --synthetic) PANEL_ARGS+=(--synthetic "$2"); shift 2 ;;
    --set) PANEL_ARGS+=(--set "$2"); shift 2 ;;
    *) EXTRA+=("$1"); shift ;;
  esac
done
HOST_URL="${HOST_URL:-http://10.0.2.2:${PORT}}"
LOG="${TMPDIR:-/tmp}/fakepanel-${PORT}.log"
cd "$ROOT"

# U-S13 / D-L16 (code review 2026-10-16): the TV keyboard, not a stale Gboard input view, before each class. The Leanback
# IME (`com.android.inputmethod.leanback/.service.LeanbackImeService` on the API 30 TV image) is selected when the image
# has one; Gboard is force-stopped when installed. Both are guarded (other images).
LEANBACK_IME="$(adb shell ime list -s 2>/dev/null | tr -d '\r' | grep -i 'leanback' | head -n 1 || true)"
if [[ -n "$LEANBACK_IME" ]]; then
  adb shell ime set "$LEANBACK_IME" >/dev/null 2>&1 || true
fi
if adb shell pm path com.google.android.inputmethod.latin >/dev/null 2>&1; then
  adb shell am force-stop com.google.android.inputmethod.latin >/dev/null 2>&1 || true
fi

if [[ "$UPDATE_FIXTURES" == 1 ]]; then
  ./gradlew :app:assembleDebug
  FIX="${TMPDIR:-/tmp}/thebox-update-fixtures"
  rm -rf "$FIX"; mkdir -p "$FIX"
  APK="$ROOT/app/build/outputs/apk/debug/app-universal-debug.apk"
  python3 "$ROOT/tools/fakepanel/make_update_apk.py" "$APK" "$FIX/thebox-update-v2.apk" --version-code 2
  python3 "$ROOT/tools/fakepanel/make_update_apk.py" "$APK" "$FIX/thebox-update-other.apk" --version-code 2 --new-key
  # U-S14 (code review 2026-10-16): the panel loads the fixtures on the host (--update-apk-dir) and the suite selects
  # them by name; nothing moves 36 MB through the emulator per case. The pushed copies stay as the upload fallback.
  cp "$APK" "$FIX/thebox-update-installed.apk"
  PANEL_ARGS+=(--update-apk-dir "$FIX")
  for f in thebox-update-v2.apk thebox-update-other.apk; do
    adb push "$FIX/$f" "/data/local/tmp/$f" >/dev/null
    adb shell chmod 644 "/data/local/tmp/$f"
  done
  echo "update fixtures in $FIX (panel) and /data/local/tmp (fallback)"
  # "Install unknown apps" must be granted before the app process starts: the platform kills the app when the
  # REQUEST_INSTALL_PACKAGES app-op changes. Install now and grant; Gradle's `install -r` keeps the app-op.
  adb install -r -t "$APK" >/dev/null
  adb shell appops set app.thebox.debug REQUEST_INSTALL_PACKAGES allow
  echo "REQUEST_INSTALL_PACKAGES granted to app.thebox.debug"
fi

python3 "$ROOT/tools/fakepanel/fakepanel.py" --host 0.0.0.0 --port "$PORT" ${PANEL_ARGS[@]+"${PANEL_ARGS[@]}"} >"$LOG" 2>&1 &
PANEL_PID=$!
cleanup() { kill "$PANEL_PID" 2>/dev/null || true; wait "$PANEL_PID" 2>/dev/null || true; }
trap cleanup EXIT

for _ in $(seq 1 120); do
  if curl -fsS "http://127.0.0.1:${PORT}/admin/config" >/dev/null 2>&1; then break; fi
  if ! kill -0 "$PANEL_PID" 2>/dev/null; then echo "fake panel exited; see $LOG" >&2; exit 1; fi
  sleep 0.5
done
curl -fsS "http://127.0.0.1:${PORT}/admin/config" >/dev/null || { echo "fake panel did not come up; see $LOG" >&2; exit 1; }
echo "fake panel up on :$PORT (log $LOG); device URL $HOST_URL"

ARGS=(":app:connectedDebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.fakepanel=${HOST_URL}")
if [[ -n "$CLASS" ]]; then ARGS+=("-Pandroid.testInstrumentationRunnerArguments.class=${CLASS}"); fi

./gradlew "${ARGS[@]}" ${EXTRA[@]+"${EXTRA[@]}"}
