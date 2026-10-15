# The Box

Android TV / Fire TV IPTV player (M3U + XMLTV EPG + Xtream Codes) built to match and beat TiviMate: a live guide with an in-guide mini player, full-screen playback, catch-up browsable backwards in the guide, and (v2) DVR to local storage and SMB shares.

## Documents

| Path | What |
|---|---|
| `docs/00-master-plan.md` | **Start here.** Scope (v1 / v1.1 / v2), reconciled architecture decisions, release gates, spike experiments, milestones |
| `docs/01-design-reference.md` | Measurements, tokens and interaction model extracted from the Claude Design prototype in `design/` |
| `docs/review/architecture-review.md` | Adversarial review of the architecture; §E is the one-page recommended v1 architecture, §D the benchmark experiments |
| `docs/review/product-review.md` | Scope verdict, screen inventory with D-pad key maps, guide UX details, full settings tree, top-25 issue-prevention table, risks |
| `docs/review/fact-check.md` | Contradictions between research reports and which claims were verified |
| `docs/research/protocols-m3u-xmltv-xtream.md` | M3U attributes, XMLTV, Xtream API, catch-up URL vocabulary, HTTP behaviours, settings inventory |
| `docs/research/android-tv-architecture.md` | Original architecture proposal (Compose TV, Media3, Room, lifecycle, DVR, project skeleton) |
| `docs/research/user-issues-and-mitigations.md` | What users complain about in IPTV apps and how The Box avoids each; ranked top 25 |
| `docs/research/tivimate-feature-audit.md` | TiviMate feature inventory (free/premium, confidence-tagged) and opportunities |
| `design/` | Exported prototype (`TV Guide.dc.html`, `support.js`, `thumbnail.webp`) |

## Status

- **M0 foundations** done: Gradle skeleton, design density rule and tokens, fake Xtream panel (`tools/fakepanel/`), FFmpeg audio decoder pipeline (`tools/ffmpeg/`), TV emulator smoke test.
- **M1 data layer** done: streaming M3U/XMLTV/Xtream parsers, stable channel identity and diff-merge, generation-swapped `epg.db`, Room `library.db`, EPG matcher, WorkManager refresh, Tink credentials. Design: `docs/design/m1-data-layer.md`.
- **M2 playback core** done: one ExoPlayer behind the root `SurfaceView`, `LiveTsDataSource` with in-source reconnect and no `Range` on live, connection broker, 44-row error ladder, adaptive buffer with a None…Extra large override, stall detectors, HW→SW and FFmpeg audio fallback, AFR/HDR policy, lifecycle rules, debug harness (`thebox-debug://play`), instrumented suite against the fake panel. Design `docs/design/m2-playback-core.md`; reviews `docs/review/m2-design-review.md`, `docs/review/m2-code-review.md`. Spike experiments D1–D5 and the I-3 reconnect test wait for low-end hardware.
- **M3 guide** done: custom-drawn programme grid behind a toolkit-agnostic renderer, virtual focus with one key sink, hole-punched mini player over the single `SurfaceView`, Groups panel, day strip, archive band, catch-up, search, reminders, number entry, channel-name prefix stripping, the owner's logo as banner, icon and wordmark. Design `docs/design/m3-guide.md`; reviews `docs/review/m3-design-review.md`, `docs/review/m3-code-review.md`. Frame-time and cold-start gates wait for hardware.
- **M4 full-screen player UX** next.

## Build

```sh
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :core:test :data:testDebugUnitTest :app:assembleDebug
```
See `docs/m0-toolchain.md` for the pinned toolchain.
