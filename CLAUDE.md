# The Box — working conventions

Android TV / Fire TV IPTV player (M3U + XMLTV + Xtream Codes). Competes with TiviMate.

## Read first
- `docs/00-master-plan.md` is the approved baseline. Decisions there win over anything in `docs/research/`.
- `docs/01-design-reference.md` holds the UI measurements and tokens. **1 design px = screen width ÷ 1920**, never dp or sp directly in guide/player screens.
- `docs/review/architecture-review.md` §E is the one-page architecture. §D lists the spike experiments.
- `docs/m0-toolchain.md` holds the pinned toolchain and the build command.

## Process
- Senior-dev / delegation model: non-trivial designs get a proposer and at least one reviewer agent before implementation. Record the reconciled decision in `docs/00-master-plan.md` (or a `docs/decisions/` note) with the reason.
- Model choice: Opus for protocol, playback, architecture and review work; Sonnet for breadth research and mechanical implementation under a spec; Haiku for trivial edits.
- Every milestone ends with a code review pass and the relevant release gates from the plan §6.

## Engineering rules (from the plan; do not re-litigate without a review)
- One `ExoPlayer`, one `SurfaceView` at the root behind Compose. No `graphicsLayer`, clip or blur on any ancestor of the surface. No real-time blur anywhere.
- The guide grid is one custom-drawn node behind a toolkit-agnostic `GuideRenderer(Canvas)`. No nested lazy lists for programme cells.
- Live TS goes through `LiveTsDataSource`: never send a `Range` header on live streams; reconnect transparently; built as `upstream → TeeDataSource(sink)` for the v2 recorder.
- All upstream stream connections go through `StreamConnectionBroker` (per playlist, enforces `max_connections`, cancels the old call before opening the next).
- Xtream timeshift times are in the **panel's** zone (`server_info.timezone`). `catchup-correction` is subtracted. Never use device-local time for catch-up tokens unless the per-playlist setting says so.
- User data (favourites, prefs, reminders, recordings) is keyed by `(playlist_id, stable_key)`, never by channel or EPG row ids. EPG row ids change on every rebuild.
- `library.db` is Room; `epg.db` is raw `androidx.sqlite`, generation-named, swapped atomically.
- Streaming parsers only (M3U, XMLTV, Xtream JSON). Never load a playlist or EPG into memory as a whole.
- Errors shown to users carry a reason and a next step. No bare "Playback error".
- No telemetry by default. Credentials via Tink + Keystore. Logs redacted (strip user/pass/tokens/query strings).
- Cleartext HTTP is allowed app-wide (providers are plain HTTP).
- `minSdk 25`, `targetSdk 35`, ABIs armeabi-v7a + arm64-v8a, core-library desugaring on.

## Build
- `export ANDROID_HOME=~/Library/Android/sdk`; `JAVA_HOME` per `docs/m0-toolchain.md`.
- `./gradlew :core:test :app:assembleDebug` is the smoke build. Universal debug APK lands under `app/build/outputs/apk/`.
- Fake Xtream panel for local testing: `python3 tools/fakepanel/fakepanel.py --port 8080` (see `tools/fakepanel/README.md`).
- FFmpeg audio decoder AAR: `tools/ffmpeg/build-ffmpeg-aar.sh` (CI: `.github/workflows/ffmpeg.yml`). Never pass `--enable-gpl`.

## Git
- Commit messages: imperative subject, short body explaining why. The lead commits; agents do not.
- Never commit `local.properties`, keystores, `tools/fakepanel/media/`, `tools/ffmpeg/work/`.
