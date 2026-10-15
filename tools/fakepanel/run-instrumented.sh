#!/usr/bin/env bash
# Runs the M2 instrumented playback suite (docs/design/m2-playback-core.md §5.5) against the fake panel.
#
#   tools/fakepanel/run-instrumented.sh [--port 18080] [--host-url http://10.0.2.2:18080] [--synthetic 2000] [--class app.thebox.playback.SmokeAndZapTest] [gradle args…]
#
# --synthetic N starts the panel in synthetic mode (the M3 guide suite, app.thebox.guide.*; docs/design/m3-guide.md §5.6).
# Starts fakepanel.py on --port (all interfaces), waits for GET /admin/config, runs
# :app:connectedDebugAndroidTest with -Pandroid.testInstrumentationRunnerArguments.fakepanel=<host-url>, then stops the
# panel. The emulator reaches the Mac at 10.0.2.2; for a device on the LAN pass --host-url http://<mac-ip>:<port>.
# --class limits the run to one test class (or Class#method). ffmpeg media are generated on the first panel start.
# Needs ANDROID_HOME and JAVA_HOME (docs/m0-toolchain.md) and one connected device/emulator (adb devices).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PORT=18080
HOST_URL=""
CLASS=""
EXTRA=()
PANEL_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --host-url) HOST_URL="$2"; shift 2 ;;
    --class) CLASS="$2"; shift 2 ;;
    --synthetic) PANEL_ARGS+=(--synthetic "$2"); shift 2 ;;
    *) EXTRA+=("$1"); shift ;;
  esac
done
HOST_URL="${HOST_URL:-http://10.0.2.2:${PORT}}"
LOG="${TMPDIR:-/tmp}/fakepanel-${PORT}.log"

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

cd "$ROOT"
./gradlew "${ARGS[@]}" ${EXTRA[@]+"${EXTRA[@]}"}
