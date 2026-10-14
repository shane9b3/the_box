# The Box — Master Plan (v1 → v2)

Status: **approved baseline, 2026-10-13.** Produced by a lead-engineer synthesis of four research tracks and three adversarial reviews (see `docs/research/` and `docs/review/`). Where reviewers disagreed, the decision and the reason are recorded here. Anything marked **(verify)** must be confirmed in the spike phase before code depends on it.

Reading order: this file → `01-design-reference.md` → `review/architecture-review.md` §E → `review/product-review.md` §2–4 → `review/fact-check.md` → research as needed.

---

## 1. What we are building

An Android TV / Fire TV IPTV player that a daily TiviMate user can switch to in week one and not go back. Sources: M3U (URL or file) + XMLTV EPG, and Xtream Codes logins. Signature screen is the guide from the design: live mini player top-left, large focused-programme panel top-right, six-row 180-minute programme grid below, with the past (catch-up archive) browsable in the same grid.

**Positioning rules** (from the product review and the legal risk analysis):
- The app ships no content, no playlists, no provider list. Generic branding. Trademark-check the name "The Box" (a UK music channel uses it) before public release.
- Sideload-first distribution (GitHub Releases + Downloader code + in-app updater). Google's developer-verification rules for sideloaded apps on Android TV / Google TV are the biggest distribution risk and must be checked before v1 ships **(verify)**.
- No telemetry by default. Credentials in Keystore. Logs redacted.
- Free for v1. No licence server until the product has earned trust.

## 2. Scope decisions

### v1 — "replace TiviMate for live TV"
| Area | Commitment |
|---|---|
| Sources | M3U URL/file, Xtream login, XMLTV URL(s) incl. gz/xz/zip, multiple playlists, ungated |
| Onboarding | TV form with URL helpers, **phone-assisted setup via LAN web page + QR**, paste a full `get.php` URL → auto-convert to Xtream, UA probe |
| Guide | Design grid; group + favourites filter; day strip; jump to now; archive band; reminders (foreground-only, stated in UI); Search tab |
| Player | One Media3 player moved between mini and full screen; info bar; channel-list panel; number entry; previous channel; audio/subtitle/aspect pickers; stats overlay |
| Catch-up | Modes `xc`, `default`, `append`, `shift`, `flussonic` with auto-probe; "Start over"; seekable archive with scrub bar; pause/rewind on archive-capable channels via the archive URL |
| Resilience | Reconnect ladder, re-resolve tokenised URLs, backup server list, error taxonomy (reason + next step), adaptive buffer |
| Data integrity | Stable channel identity, diff-merge refresh, refresh report, keep last-good copy |
| Scale | Streaming M3U/XMLTV parsers, EPG channel whitelist, custom-drawn grid, low-RAM mode |
| EPG matching | Exact + name match, fuzzy as suggestions, "Fix unmatched channels" screen, per-source/per-channel time shift |
| Groups & parental | Hide/reorder/lock groups, hide rules, 4-digit PIN, adult groups auto-locked |
| Account clarity | Xtream status, expiry countdown, connections used/max, allowed formats, server skew |
| Video/audio | AFR (seamless-only default), passthrough + PCM fallback, FFmpeg audio (AC-3/E-AC-3/MP2/LATM), per-channel aspect/audio memory |
| Lifecycle | Resume after standby/CEC with fresh URLs, keep-screen-on, Back ladder, exit confirmation |
| Backup | JSON export/import (file or phone page), credentials under passphrase, weekly local auto-backup |
| Diagnostics | Stats overlay, connection/DNS test, sync status, redacted log export via LAN page |
| Network | DoH resolver choice, prefer-IPv4 auto |
| Distribution | Signed per-ABI + universal APKs, updater with signed manifest + SHA-256 + cert check |

**Design changes for v1:** the *Recordings* tab is hidden behind a feature flag (not a "coming soon" stub); the secondary button is **Remind / Reminder set** until v2 restores **Record**. Everything else in the design stays.

### v1.1 — "parity plus" (~6–10 weeks after v1)
Local disk ring-buffer pause/rewind for non-archive channels · duplicate-merge / multi-source channels with failover (schema ships in v1) · Xtream VOD + Series browsing (pull forward if beta says "no VOD = uninstall") · compact guide density · preview-on-focus in mini player when `max_connections ≥ 2` · `catchup=vod`/`{catchup-id}`, per-playlist proxy, udpxy, audio delay, sleep timer, rename/custom logo · Widevine licence URL via `#KODIPROP`.

### v2 — "the DVR release"
Recording (manual, from guide, scheduled, padding 2/5 min, series rules) to internal, USB (SAF) and **SMB** (smbj) · Recordings tab and Record button return · conflict handling driven by connection limits · long-recording soak test (TiviMate reviews report SMB failures and scheduled recordings stopping at ~87 min) · multi-view and PiP (Fire OS 6+ does allow third-party PiP, contrary to the original proposal) · Stalker/MAG · Watch Next · LAN sync then optional E2E cloud sync · libVLC fallback engine.

**Not planned:** dummy channels, Assistant integration, loudness normalisation, teletext decoder, TMDB enrichment, non-Android platforms. **Will not ship:** ClearKey keys embedded in playlists.

## 3. Architecture decisions (reconciled)

| Topic | Decision | Why (and who disagreed) |
|---|---|---|
| UI toolkit | Jetpack Compose for TV (`tv-material` 1.1.0) for the shell; **one custom-drawn grid node** behind a toolkit-agnostic `GuideRenderer(Canvas)` so a classic-View host is a one-day swap | Both the proposal and the review agree; nested lazy lists rejected |
| Units | Root density override: 1 design px = screen width ÷ 1920 (1080p TV is 960×540 dp) | Review caught my px≈dp error; design doc corrected |
| Glass/blur | **No real-time blur.** Translucent fills; ambient blobs pre-rendered to a cached 480×270 bitmap and cross-faded per channel hue | Blur needs API 31; targets are API 25–30 Fire sticks |
| Video surface | One `ExoPlayer`, one `SurfaceView` at the root, *behind* Compose; the guide clears a rounded hole in the background layer for the mini player; mini→full is a snap behind a 120 ms scrim, not a per-frame animation; letterbox painted by the UI | Two players rejected (connections, decoders, RAM). TextureView rejected (GPU cost on Mali-G31). Review refined the proposal's animated resize |
| Player version | Media3 **1.11.1** (confirmed: released 2026-09-10; dynamic scheduling is on by default since 1.11.0) | Proposal said 1.10.1 |
| Live TS transport | `LiveTsDataSource`: never sends `Range`, transparent 3 s reconnect, byte watchdog, built as `upstream → TeeDataSource(no-op sink)` so v2 recording of the watched channel costs one connection | Release blocker from the review; also the DVR seam |
| Connections | `StreamConnectionBroker` per playlist: enforces `max_connections`, cancels the old OkHttp call before the next open, 350 ms CH± debounce, retry ladder for 403/458/509 | `player.stop()` alone does not close the socket in time |
| Error policy | 401 → account screen, no loop · 403 → retry once after 1–2 s, then reason + UA hint · 405/406 → flip TS↔HLS · 404 top-level → backup source · 404 HLS segment → retry twice · 458/509 (unregistered codes, meaning disputed) → re-fetch the stream URL once, then connection-limit hint · 5xx/IO → jittered backoff | Proposal's blanket policy would kill healthy HLS streams |
| Buffering | **Adaptive** profiles (Fast default: `bufferForPlaybackMs` ≈ 500–1500, grows after rebuffers, decays when stable); byte cap 24/48 MB by RAM class; manual override in settings: None / Small / Medium / Large / Extra large; default Auto | Protocol doc said "Small", user-issues said adaptive; adaptive serves zapping and Wi-Fi without tuning |
| Stall detection | Three detectors (no bytes; decoder stuck while bytes arrive; frozen picture with audio) + audio watchdog that drops to PCM when passthrough yields silence | Top complaints #2 and #9 |
| Decoders | HW → platform SW (`c2.android.*`) via `MediaCodecSelector`; audio platform/passthrough → FFmpeg (auto-PREFER for MP2/LATM) | Media3 FFmpeg module is audio-only; no SW video fallback for 1080p50 HEVC on sticks |
| AFR / HDR | Switch display mode only in full screen after 2 s dwell; `preferredDisplayModeId` below API 30; HDR channels show a poster in the mini player by default | Mode switches blank the TV while browsing |
| Catch-up | `TimeBase` per playlist (server zone, clock skew, correction, token zone, padding) + `CatchupUrlBuilder`; `ArchivePlayback` with a wall-clock virtual timeline (native seek where supported, else re-request), auto-continue to next programme, jump-to-live; scrub commits 600 ms after last key | Xtream timeshift `{Y-m-d:H-M}` is in the **panel's zone** (`server_info.timezone`, else derive the offset from `time_now − timestamp_now`), confirmed by IPTVnator #1562/#1563. Kodi fills `{Y}{m}{d}…` from *device local* time, so its `xc` mode only works when device and panel share a zone; we must not copy that. `catchup-correction` is **subtracted** (request = programme UTC − correction, so `-4.0` means 4 h later) |
| Databases | `library.db` on Room 2.8 + `BundledSQLiteDriver`; `epg.db` on raw `androidx.sqlite`, generation-named, built offline, swapped by atomic pointer; `programme WITHOUT ROWID PK(epg_channel_id,start_min)`; dedup'd `epg_text`; title-only FTS; nothing references EPG row ids | Review changed the proposal's close-Room-and-rename swap |
| Identity | `stable_key` priority: Xtream `stream_id` → `tvg-id|name` → host-agnostic URL path → `name|group`; user data keyed by `(playlist_id, stable_key)`; `missing_since` grace before delete | Top complaint #6 |
| Modules | 8: `:app`, `:core` (pure JVM), `:data`, `:player`, `:ui`, `:ffmpeg`, `:baselineprofile`, `:benchmark`; `:dvr` added in v2 | Proposal had 17 |
| Platform | `minSdk 25`, `targetSdk 35`, armeabi-v7a + arm64-v8a, core-library desugaring, Hilt, OkHttp 5 + DoH, Coil 3, WorkManager, Tink + Keystore, local-only ACRA + ANR watchdog. Version catalog as of 2026-10-13: AGP 9.4.1, Kotlin 2.4.21, Compose BOM 2026.09.00, tv-material 1.1.0, Media3 1.11.1, Room 2.8.5, Hilt 2.60.1, OkHttp 5.5.0, WorkManager 2.12.0 (needs minSdk ≥ 24), smbj 0.15.0 (v2) | Fire OS 6 = API 25 floor; Fire OS 7 = 28, Fire OS 8 = 29/30, Fire OS 14 = 31–34, Fire OS 16 = 35/36. Vega OS sticks (4K Select, 2026 Stick HD; software version starting "1.") cannot run APKs |
| Process | `PlaybackController` `@Singleton` owns the one `ExoPlayer` and the `MediaSession`. **Amended 2026-10-13 (M2 design review B5):** no `MediaSessionService` and no `mediaPlayback` foreground service in v1; live TV plays only while the Activity is in the foreground, and the session refuses play/next/previous from the background. Home stops playback at once; screen-off / HDMI-unplug / display-off stop after a 30 s grace (`PowerManager.isInteractive()` check, B4); resume re-resolves the URL and prepares fresh at the live edge. The service returns in v1.1/v2 for radio in the background, PiP and DVR, with `onPlaybackResumption` implemented | Black-screen-after-wake is top complaint #11. The declared service crashed on Fire OS (API < 31): a CEC PLAY after process death starts it through a `startForegroundService` media-button intent and nothing calls `startForeground` (`docs/review/m2-design-review.md` B5) |

Full detail: `review/architecture-review.md` §E (one page) and §B (each change with reasoning).

## 4. Guide behaviour (beyond the design)

- Focus is a (channel, programme) pair. Left/Right move between programmes and set the **anchor time**; Up/Down keep the anchor and pick the programme airing at it (the design's "start + 1 min" rule is replaced because it drifts).
- Time scrolls by moving focus, snapped to :00/:30; the 180-minute window pages. Day strip in the header; Back = jump to now, then full screen, then exit confirmation.
- Channels without EPG get 60-minute "No information" cells so the row is still navigable and tunable.
- Archive band: past programmes inside the channel's `archiveDays` stay readable with a ↺ glyph; outside it they dim. "Watch from start" appears only when the archive actually covers the programme; otherwise the primary button is "Watch now".
- Future programmes offer **Remind** (v1) / **Record** (v2).
- The mini player always shows the **tuned** channel (as in the design), so it never consumes a second connection. Preview-on-focus is v1.1 and gated by `max_connections ≥ 2`.
- Low-end mode: mini player can show a still/logo, logos capped, no ambient blobs.

## 5. Settings surface

The full tree (section → setting → type → default → scope → simple/advanced → which complaint it fixes) is in `review/product-review.md` §4. Rules: simple view ≤ 35 rows, never deeper than two levels, every per-playlist and per-channel override listed. Protocol-level inventory with rationale is in `research/protocols-m3u-xmltv-xtream.md` §6.

Owner additions 2026-10-14 (`decisions/2026-10-14-settings-and-branding.md`): "Always passthrough" audio option, per-playlist channel-name prefix stripping (display name only), Groups-panel accelerators (Left at the column edge, CH± in the panel, long-press ←/→ in the player), and a full button-mapping screen pulled forward from v1.1 to v1. Tunneling, stream format and hide/show already exist in the tree.

## 6. Issue-prevention plan

The ranked top-25 "make or break" list and the complaint → decision → verification → phase table are in `research/user-issues-and-mitigations.md` (list) and `review/product-review.md` §5 (table). Release gates taken from it:

| Gate | Target | Device |
|---|---|---|
| Guide scroll | P90 < 16.7 ms, P99 < 33 ms with 1080i50 decoding in the mini window | Fire TV Stick 4K (2018), Onn 4K |
| Zap time | P50 ≤ 1.2 s, P90 ≤ 2.0 s, no black frame | Fire TV Stick 4K |
| EPG import | ≤ 90 s for ~1M programmes while playing, importer heap ≤ 20 MB, zap degradation ≤ 15 % | Fire TV Stick 4K |
| Window query | warm ≤ 5 ms, cold ≤ 30 ms, 20k channels × 7 days | Fire TV Stick 4K |
| Soak | 12 h playback with a zapping bot, no heap growth | all |

## 7. Spike phase (before feature work)

Run the ten experiments in `review/architecture-review.md` §D. The ones that can change the architecture: **D1** hole-punch surface rendering on Fire OS 6/7, **D2** Compose grid vs View grid at 60 fps, **D3** zap latency, **D4** transparent TS reconnect, **D5** max-connection behaviour. Build a **fake Xtream panel** (LAN server with configurable linger, 302 tokens, 403/458/509 behaviours, PTS resets) first; most experiments and later tests depend on it.

**Hardware status (2026-10-13):** no Fire TV Stick or Onn 4K is available. D1–D5 run first on the Android TV API 30 emulator (`TheBox_TV_API30`) for *correctness only*; the frame-time and zap-time gates in §6 stay open until a low-end device is on hand. Recommended reference device: Onn 4K (Google TV, ~US$20) plus a Fire TV Stick 4K (Fire OS 6, API 25) for the oldest supported path. M1 proceeds in parallel because it does not depend on these spikes.

Also in the spike phase:
- Version catalog is pinned in §3 (fact-checked). Bundled SQLite FTS5 support is confirmed; keep the FTS4 fallback path only if a device test contradicts it.
- 2-hour hands-on check of TiviMate's actual key map and settings labels (most "TiviMate does X" claims in the research are single-source).
- Trademark search and sideload-policy check.

## 8. Milestones

| # | Milestone | Contents |
|---|---|---|
| M0 | Foundations — **done 2026-10-13** (spikes D1–D5 deferred: no hardware) | Repo, version catalog, modules, CI building the FFmpeg AAR, fake panel, benchmark harness, design tokens + density rule |
| M1 | Data in — **done 2026-10-13** (`docs/design/m1-data-layer.md`, reviews in `docs/review/m1-*.md`) | M3U/XMLTV/Xtream parsers with fixtures, `library.db`/`epg.db`, stable keys, diff-merge, refresh report, EPG matcher (the unmatched-channels *screen* moves to M5), WorkManager refresh, Tink credential store. JVM perf: 200k-entry M3U import 1.9 s; 980k-programme EPG rebuild 20 s with 6.5 MB retained heap; warm window query < 1 ms; matcher 20k×50k 2.3 s. Device numbers still open (no low-end hardware) |
| M2 | Playback core — **done 2026-10-14** (design `docs/design/m2-playback-core.md`, reviews `docs/review/m2-design-review.md`, `docs/review/m2-code-review.md`, Media3 facts `docs/design/m2-media3-api-notes.md`) | One `ExoPlayer` behind the root `SurfaceView`, `PlaybackController`/`TuneSession`, `LiveTsDataSource` (reconnect in-source, no `Range` on live, PCR verdict), `StreamConnectionBroker`, two-layer error policy with 44-row ladder, adaptive buffer (Auto + None/Small/Medium/Large/Extra large), five stall detectors incl. readiness stall, HW→SW codec fallback + FFmpeg audio, passthrough gate, AFR/HDR policy, lifecycle (no service in v1), debug harness, fake panel v1.1. JVM: core 1,318 / data 124 / player 302 tests. Device (API 30 TV emulator): 34 instrumented pass; I-3 ignored (emulator decoder wedge after splices, §5.10); zap P50 ≈ 0.4–0.6 s on the emulator. Open: hardware D1–D5, I-3 on hardware, I-21 flake, 35 review 'later' items |
| M3 | Guide — **in progress** (design `docs/design/m3-guide.md`, review `docs/review/m3-design-review.md`, API facts `docs/design/m3-compose-tv-api-notes.md`) | Custom grid, hole-punch mini player, focused panel, group/favourites filter, Groups panel with the accelerators of the 2026-10-14 decision, day strip, archive band, reminders, search, channel-name prefix stripping (`channel.display_name`), logo-derived banner/icon/wordmark assets |
| M4 | Full-screen player UX | Info bar, channel list, number entry, pickers, stats overlay, error cards, catch-up scrub bar and `ArchivePlayback` |
| M5 | Setup, settings, parental, backup | Onboarding + phone page/QR, settings tree (incl. Always passthrough, stream format label, prefix rule, button-mapping screen), PIN, backup/restore, diagnostics, updater |
| M6 | Hardening | Benchmarks against the gates, soak, device matrix (Fire OS 6/7/8, Onn, CCwGTV, Shield), beta |
| v1.1 | see §2 | |
| v2 | DVR + SMB | `:dvr` module on the broker/Tee seams, scheduler, storage backends, Recordings tab |

## 9. Working agreement

Every milestone's design is proposed by one agent and challenged by at least one reviewer before implementation; the lead reconciles and records the decision here. Models: Opus for protocol/architecture/review work, Sonnet for breadth research and mechanical implementation under a spec, Haiku for trivial edits.

## 10. Evidence caveats

Reddit and TiviMate's own site were not reachable; Google Play reviews were scraped in the fact-check pass (TiviMate 2025–26 one- and two-star reviews: 46 of 101 are about pricing or licensing; a changed playlist URL cannot be edited in place and loses favourites; SMB recording failures). Competitor behaviour claims are tagged [K]/[S]/[?] in the research files and must not be used as marketing claims. `review/fact-check.md` lists every contradiction resolved, every claim confirmed or refuted, and what is still open (TiviMate buffer seconds and price, the meaning of HTTP 458, per-panel connection linger time).
