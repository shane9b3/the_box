# Fact-check of The Box research docs

Date: 2026-10-13. Scope: the four files in `docs/research/`. Method: I compared the documents with each other, then checked claims against primary sources. Those sources were Maven Central / Google Maven metadata and AAR manifests, the Media3 `RELEASENOTES.md` and source on GitHub, the pvr.iptvsimple README and C++ source on the `Omega` branch, the androidx.tv release notes, Amazon Fire TV developer docs, Android Developers pages and blog posts, the Google Play listing, and the GitHub REST API. Third-party blogs were used only where no primary source exists, and they are labelled.

Verdict key: **confirmed** (primary source agrees), **refuted** (primary source disagrees), **partly** (the core claim holds but a detail is wrong), **unresolved** (no primary source exists or none was reachable).

---

## 1. Contradictions between documents

| # | Topic | Doc A says | Doc B says | Resolved answer | Source |
|---|---|---|---|---|---|
| C1 | Time zone for the Xtream timeshift `{Y-m-d:H-M}` segment | protocols §3.3: **server-local** (`server_info.timezone`) | user-issues §7: "client sends local time instead of server/UTC", "clients use start_timestamp UTC" (ambiguous between UTC and server zone) | **Server-local.** The panel parses the segment with PHP `strtotime()` in its own zone. If the `timezone` string is unusable, derive the offset from `time_now` − `timestamp_now`. Do not send UTC or the device zone. | IPTVnator issue #1562 and fix PR #1563 (merged 2026-09-07): "read by the panel with `strtotime()` in **its own** timezone (`server_info.timezone`)". https://github.com/4gray/iptvnator/pull/1563 |
| C2 | Time zone of Kodi's `{Y}{m}{d}{H}{M}{S}` and `{utc:FORMAT}` | protocols §4.3: "Kodi uses UTC" | (implicitly) Kodi's `xc` mode is correct for server-local panels | **Refuted for both.** Kodi formats these specifiers in **device local time** (`SafeLocaltime`) after subtracting tvg-shift and catchup-correction. Kodi's `xc` URLs are therefore correct only when the device zone equals the panel zone, and users compensate with `catchup-correction`. Only the bare `{utc}`/`{lutc}` epoch values are independent of time zone. | pvr.iptvsimple `src/iptvsimple/CatchupController.cpp` `FormatDateTime()` (Omega branch), lines 363–401 |
| C3 | Buffer-size scale | protocols §6.3: None/Small 2 s/Medium 5 s/Large 10 s/Very large 20 s, "TM uses the same scale" | arch §2.3: "small/normal/large", min buffer 15 s live. user-issues §1: "15 s Ethernet, ~30 s Wi-Fi, 60 s". audit: "small/medium/large; ms in some builds" | TiviMate's labels are **None / Small / Medium / Large (+ "Very/Extra large" in some guides)**. **No source gives seconds.** The 2/5/10/20 s values belong to The Box, not TiviMate. The user-issues 15/30/60 s figures are seller-blog advice about provider buffering, not a TiviMate scale. Recommendation: keep a five-step label scale and map it to `bufferForPlaybackMs` (start threshold), keeping arch §2.3's `min/maxBufferMs` caps. | Third-party guides only: tivimateplayer.co.uk/tivimate-buffering-fix, streamvexa.com/blog/tivimate-best-settings-2026 (unresolved for exact values) |
| C4 | Xtream default output | protocols §6.1: TS default ("more widely allowed") | arch §2.2: "prefer `.m3u8` when the panel offers both" | **TS default, with HLS as an opt-in or automatic fallback when `allowed_output_formats` includes `m3u8`.** XUI enforces `allowed_output_formats`, Kodi's `xc` mode defaults to `.ts` when there is no extension, and Xtream timeshift is served per URL extension anyway. | pvr.iptvsimple `Channel.cpp` xc regex (line ~546–567). XUI player API docs (cited in protocols) |
| C5 | Auto-frame-rate default | protocols §6.3: options Off/On/On (start only), default **Off** | arch §2.7: Off/Seamless only/Always. user-issues §3: default `ONLY_IF_SEAMLESS` | **Default "Seamless only"**, which is Media3's own default (`C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS`) and cannot cause HDMI re-sync black screens. "Always" requires setting the strategy to `OFF` and calling `Surface.setFrameRate(..., CHANGE_FRAME_RATE_ALWAYS)` yourself. | Media3 `ExoPlayer.java` Javadoc of `setVideoChangeFrameRateStrategy`, `C.java` lines 826–841 |
| C6 | Fire TV picture-in-picture | arch §4: "Fire TV doesn't support PiP for third-party apps" | audit C: PiP listed as a TiviMate player option, conflicting | **Refuted.** Amazon's Fire OS 6 developer page documents in-app PiP: "pin a video to a corner of the screen while navigating to other areas in your app… PIP windows may not be used outside your app nor can they overlay native Fire OS UI". The Fire OS 7/8 pages are silent. Treat PiP as in-app-only and feature-check it at runtime. | https://developer.amazon.com/docs/fire-tv/fire-os-6.html |
| C7 | TiviMate on Google Play | user-issues §13: "TiviMate vanished from Google Play" | audit: "TiviMate IS on Google Play for Android TV" | **Both are partly right.** The listing is live today (US): `ar.tvplayer.tv`, updated **22 Jun 2026**, 5M+ installs, 4.2★ overall and 3.8★ on TV. It was **intermittently delisted in 2026** (April, restored 6 June, then reports again). | Live fetch of https://play.google.com/store/apps/details?id=ar.tvplayer.tv (2026-10-13). techkings.org thread 176379. free-codecs.com news |
| C8 | Retry delay after 403/458/509 on zap | arch §2.4(4): 300–500 ms | protocols §3.5/§5.4: 1–3 s / 1–2 s | **Harmonised to 1–2 s.** No primary source exists. Panels update `active_cons` lazily, so the longer delay is safer. | Engineering judgement, no primary source |
| C9 | How long a dropped session holds a connection slot | protocols §3.5: 20–60 s after the last segment | user-issues §6: ~2 min | **Unresolved. It is configured per panel.** Detect it empirically: on a 403/458/509 after a zap, back off and retry for up to 120 s. | Community reports only |
| C10 | EPG refresh interval default | protocols §6.2: 12 h | arch §3.5: 24 h EPG | **12 h, plus a refresh on start when the data is more than 12 h old.** Arch already does the on-start check. | Design decision, harmonised |
| C11 | Number-entry commit timeout | protocols §6.3: 2 s | arch §4 and user-issues §8: 1.5 s | **1.5 s** (2 of 3 docs), configurable | Design decision |
| C12 | Recording padding default | protocols §6.5: 2/5 min. arch §5.2: 1/5 min | user-issues §12: 1–2/5. audit: TiviMate 2/10 [S] | **2 min before / 5 min after** | Design decision |
| C13 | Past EPG days kept | protocols §6.2: 1 day, or max(1, catch-up days) | user-issues §4: 3 back / 7 forward | **Past = max(1, max catchup-days). Future = 7.** | Design decision |
| C14 | Default User-Agent | protocols §5.4: do not impersonate another app by default | user-issues §1: "default UA that mimics VLC" | **App UA by default, with VLC/browser/"Smarters" presets and a one-shot automatic retry with a preset on 403.** Impersonating by default can breach provider ToS and is unnecessary. | Design decision |
| C15 | Downloader removal year | user-issues §13: 2023 | (no doc contradicts it, but it was flagged as suspicious) | **Confirmed.** Removed 19 May 2023 (Israeli broadcasters' DMCA) and restored after about 20 days. Removed again in Nov 2023 (MarkScan for WBD). | aftvnews.com/?p=41115, ghacks.net/?p=196120, Ars/Slashdot 2023-11-28 |
| C16 | Media3 version | arch: 1.10.1 everywhere | (other docs unversioned) | **Current stable is 1.11.1 (2026-09-10).** 1.11.0 shipped 2026-08-05. | Google Maven metadata. https://github.com/androidx/media/blob/release/RELEASENOTES.md |
| C17 | WorkManager vs minSdk 23 | arch §0/§6.4: minSdk 23 | arch §3.5: WorkManager "2.10/2.11 (verify)" | **WorkManager 2.12.0 (2026-09-23) declares `minSdkVersion 24`.** With minSdk 23, pin `work-runtime` 2.11.2 (minSdk 23), or raise minSdk to 24. | AAR manifests of work-runtime 2.11.2 and 2.12.0 from dl.google.com |
| C18 | Fire OS → API mapping | arch: "Fire OS 8 is 30" and no later versions | (none) | **Fire OS 5 = 22, 6 = 25, 7 = 28, 8 = 29/30, Fire OS 14 = 31–34, Fire OS 16 = 35/36.** Fire OS 14 ships on 2026 Fire TV smart TVs (Hisense U7, TCL QM64L, Onida). | https://developer.amazon.com/docs/fire-tv/fire-os-overview.html |
| C19 | `tvg-rec` provenance | protocols §1.3: "observed (OTT Navigator)" | protocols §4.5 uses it as a fallback chain | **Kodi implements it.** It is treated exactly like the SIPTV `timeshift="days"` value when `timeshift` is absent. | pvr.iptvsimple `PlaylistLoader.cpp` lines ~445–470 |
| C20 | `catchup-correction` sign | protocols §4.4: "added to the start and end" | Kodi README: `catchup-correction="-4.0"` means time "corrected by -4.0 hours" | **Implementation detail differs from both readings.** Kodi computes `urlTime = epgDisplayTime − (tvgShift + catchupCorrection)`, and since display time = XMLTV UTC + tvgShift, `urlTime = programmeUTC − catchupCorrection`. A value of `-4.0` therefore moves the request **4 h later**. The Box should match Kodi's code, because that is what playlists are tuned against, and label the UI field "Archive time correction (h)". | `CatchupController.cpp` lines 495/538, `EpgEntry.cpp` `UpdateTo()` line 31 |

---

## 2. Verified claims

| Claim (doc) | Verdict | Evidence / source |
|---|---|---|
| Media3 latest stable is 1.10.1 (arch) | **Refuted (outdated)**: 1.11.1, 2026-09-10 | Google Maven `media3-exoplayer` metadata. RELEASENOTES.md |
| Media3 1.10.0 shipped 30 Mar 2026 (arch) | **Partly**: release notes date it 2026-03-25 | RELEASENOTES.md |
| Media3 minSdk 23 | **Confirmed**: raised in 1.9.0 (2025-12-17). The 1.11.1 AAR declares minSdk 23 | RELEASENOTES.md line ~902. AAR manifest |
| `experimentalSetDynamicSchedulingEnabled` is "new in 1.10" (arch §1.4) | **Refuted**: experimental since 1.4.0 (2024-07-24), **enabled by default in 1.11.0** | RELEASENOTES.md |
| `MediaSessionService` is a `LifecycleService` in 1.10 (arch §4) | **Confirmed** | RELEASENOTES.md 1.10.0 Session section |
| Dolby Vision Profile 10 support added in 1.10 (arch §2.7) | **Confirmed** (the "MP4 only" qualifier is not stated in the notes, so it stays unverified) | RELEASENOTES.md 1.10.0, PR #2830 |
| Media3 has no teletext support (protocols §5.3, arch §2.8) | **Confirmed**: `DefaultTsPayloadReaderFactory` has readers for DVB subtitles (`DvbSubtitleReader`, stream type 0x59 descriptor) and none for teletext. No release note mentions teletext | `libraries/extractor/.../ts/DefaultTsPayloadReaderFactory.java` (release branch) |
| Media3 supports DVB bitmap subtitles in TS | **Confirmed** | same file, `TS_STREAM_TYPE_DVBSUBS` |
| AAC-LATM in TS "needs extractor support" (protocols §5.2) | **Confirmed as already supported**: `TS_STREAM_TYPE_AAC_LATM` → `LatmReader`. The FFmpeg `aac_latm` decoder is optional, not required | same file |
| Frame-rate API: `setVideoChangeFrameRateStrategy(ONLY_IF_SEAMLESS default / OFF)`, and "Always" needs an app-side `Surface.setFrameRate` (arch §2.7) | **Confirmed** | `ExoPlayer.java` Javadoc. `C.java` IntDef has only OFF and ONLY_IF_SEAMLESS |
| FFmpeg branch for `decoder_ffmpeg` is 6.x (arch §2.5) | **Confirmed**: README clones `--branch=release/6.0`, tested on NDK r26b. The build script also takes an `ANDROID_ABI` argument, which arch's signature omits | https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg |
| `androidx.tv:tv-material` 1.1.0 and `tv-foundation` 1.0.0 stable on 6 May 2026 | **Confirmed** | androidx.tv release notes. Maven metadata |
| TV lazy layouts deprecated in tv-foundation 1.0.0-alpha11 (Jul 2024) and removed in alpha12 (Jan 2025) | **Confirmed** (alpha12 says "Cleanup lazy layouts from tv-foundation") | https://developer.android.com/jetpack/androidx/releases/tv |
| tv-material 1.0.1 fixed ExoPlayer not rendering inside tv-material `Surface` on lower APIs | **Confirmed** (16 Jul 2025: "Moved compositing strategy configuration from Surface composable to the Text composable…") | same |
| Compose minSdk 23 (arch §6.4 "verify") | **Confirmed**: the compose ui-android 1.12.1 AAR declares minSdk 23. tv-material 1.1.0 and Room 2.8.5 also declare 23 | AAR manifests |
| Compose `SurfaceView` (via `AndroidView`/`PlayerSurface`) sits behind the window and Compose draws on top through the "hole" (arch §2.6) | **Confirmed, with caveats.** SurfaceView is Z-ordered behind its window by default. Compose `clip`/`alpha`/rounded corners and `graphicsLayer` transforms do **not** apply to the video layer. Any opaque Compose background above the rect (Scaffold or tv-material `Surface` colour) hides the video. Two SurfaceViews have undefined relative order unless `setZOrderMediaOverlay` is used | Android `SurfaceView` reference. tv-material 1.0.1 note above |
| `EncryptedSharedPreferences` deprecated | **Confirmed**: `security-crypto` 1.1.0 (stable 2025-07-30) marks the classes `@Deprecated` (bytecode `Deprecated: true`) | AAR `classes.jar` inspected with `javap` |
| Google TV / Android TV 64-bit requirement "August 2026" | **Confirmed**: from **1 Aug 2026**, new apps and updates on Play that contain native code must include arm64. 32-bit should still ship alongside | https://android-developers.googleblog.com/2025/08/64-bit-app-compatibility-for-google-tv-android-tv.html |
| Play target API ≥ 34 for TV from 31 Aug 2026 | **Confirmed** (extension available to 1 Nov 2026) | https://developer.android.com/google/play/requirements/target-sdk |
| Fire OS 6 = API 25, 7 = 28, 8 = 30 | **Partly**: Fire OS 8 = API 29 **and** 30. Fire OS 14 (API 31–34) and Fire OS 16 (API 35/36) exist and are missing from the docs | Amazon Fire OS overview |
| New Fire TV Sticks (4K Select, HD 2026) run Vega OS and cannot run APKs | **Confirmed**. Vega devices show a software version beginning with "1." The 1st-gen Stick HD, 4K Plus, 4K Max, Cube and Fire TV smart TVs remain on Fire OS | aftvnews.com (Vega tag), androidauthority.com/amazon-vega-os-fire-tv-stick-3602647 |
| Fire TV lacks third-party PiP | **Refuted** (see C6) | Fire OS 6 dev doc |
| HTTP 458 = connection limit (protocols §5.4, arch §2.4) | **Unresolved.** 458 is not an IANA-registered status code, and sources disagree between "too many connections", "session/token expired" and "connection cut mid-stream". New primary evidence: a TiviMate Play review (5.3.3, 2026-08-16) reports 458 **on every fast-forward or rewind in VOD**, which points to session or range-request rejection rather than only connection limits. Treat 458 as "session rejected": re-resolve the URL once, then show the connection-limit hint | IANA HTTP status registry (no 458). tvimate.co, tivimateplayer.co.uk (S). Play review |
| HTTP 509 = bandwidth/connection limit | **Partly**: also unregistered. 509 "Bandwidth Limit Exceeded" is an Apache/cPanel convention. No primary source ties it to Xtream panels | IANA registry |
| Xtream timeshift start uses server-local time | **Confirmed** (C1) | IPTVnator PR #1563 |
| Kodi Flussonic conversions (protocols §4.2) | **Confirmed**, with an addition: `…/mpegts` → `timeshift_abs-${start}.ts`. `index.m3u8` → `timeshift_rel-{offset:1}.m3u8`. `mono/video.m3u8` → `{type}-timeshift_rel-{offset:1}.m3u8`. A second "generic" regex handles any directory name after the channel id, where `flussonic-ts`/`fs` gives `timeshift_abs-${start}.ts` and `flussonic`/`flussonic-hls` gives `timeshift_rel-{offset:1}.m3u8` | `Channel.cpp` lines 471–528 |
| Kodi `xc` derivation `{duration:60}/{Y}-{m}-{d}:{H}-{M}/id.ts` and default `.ts` | **Confirmed** | `Channel.cpp` lines 542–567 |
| Legacy `timeshift=` attribute: forces shift mode and its value is days | **Confirmed**: applied only when no other catchup tag set the mode. Kodi calls the mode obsolete and lets the "override without tags" setting replace it | `PlaylistLoader.cpp` lines ~445–470. `Channel.cpp` lines 353–356. README "Channel E" |
| `catchup-correction` semantics (hours, per channel or header) | **Partly**: the unit and scope are confirmed. The sign is the opposite of "added" (C20) | README lines 510/523. `CatchupController.cpp` |
| `default` mode without `catchup-source` falls back to append | **Confirmed** | README "Catchup mode" list |
| Flussonic `archive-FROM-DURATION.m3u8`, `timeshift_abs-FROM`, `timeshift_rel-S`, `event=true`, `ignore_gaps=true`, new token per request to avoid session reuse | **Confirmed** (FROM is UTC seconds, DURATION is seconds). `index-FROM-now` and `archive-FROM-DURATION.ts` were not found in the docs that could be reached (unresolved) | flussonic.com/doc/iptv-ott-quickstart, /doc/dvr/timezone-shift, /doc/play-protocols, /doc/api/streaming |
| OkHttp fast fallback default "since 5.x" | **Confirmed**: default since 5.0.0-alpha.11. Stable 5.5.0 | square/okhttp CHANGELOG.md |
| Room 3.0 stable since July 2026, latest 3.0.3 | **Confirmed**: 3.0.0 published 2026-07-01, 3.0.3 on 2026-09-09. 3.1.0-alpha01 exists | Google Maven |
| Bundled SQLite gives FTS5 (arch §3.1 "verify") | **Confirmed**: `sqlite-bundled-android` 2.7.1 native lib compiled with `ENABLE_FTS5`/`FTS4`. Room 2.8.5 supports `setDriver(BundledSQLiteDriver())`. Cost: about 1 MB of native code per ABI | AAR `.so` strings |
| `androidx.tvprovider` 1.1.0 | **Confirmed** (released 2025-05-07) | Google Maven |
| smbj "0.13/0.14 line" | **Refuted (outdated)**: 0.15.0 (2026-08-21) | Maven Central |
| jcifs-ng 2.1.x "less active" | **Confirmed**: last release 2.1.10 in Oct 2023 | Maven Central |
| Current stable toolchain, Oct 2026 | AGP **9.4.1**. Kotlin **2.4.21** (2.5.0-Beta1 in preview). KSP **2.3.12**. Compose BOM **2026.09.00** (Compose 1.12.1). Hilt **2.60.1**, `hilt-navigation-compose` **1.4.0**. Coroutines **1.11.0**. OkHttp **5.5.0**. Retrofit **3.0.0**. WorkManager **2.12.0** (minSdk 24) / 2.11.2. Room **2.8.5**. Coil **3.6.3**. Navigation-Compose **2.10.2**, Navigation3 **1.2.0**. Tink **1.23.0** | Maven Central / Google Maven metadata (stable versions only) |
| TiviMate minSdk | **New fact**: APKMirror lists TiviMate 5.3.2 as "Android 6.0+" (API 23), which supports minSdk 23 parity | apkmirror.com TiviMate listing |
| TiviMate 5 devices per licence | **Confirmed (S)**: Polish deal listing "maks 5 urządzeń" | pepper.pl |
| TiviMate premium price ~US$9.99/yr, lifetime $29.99–33.99 (audit) | **Unresolved**. Play reviews report an annual rise from $6.99 to $11.99 (2026-01), "£30 lifetime" (2025-03) and "$45" (2025-10). Regional pricing applies | Play reviews (see §3) |
| TiviMate SMB recording (audit "?") | **Partly confirmed**: a June 2025 Play review reports failing to record "using SMB to my QNAP NAS", which implies the option exists | Play review |
| TiviMate cross-device sync (audit: "not live sync") | **Partly refuted**: a Feb 2026 Play review says "Purchased lifetime premium for cloud sync". Another (Aug 2026) wishes favourites "synced to my account", so the scope is unclear | Play reviews |

---

## 3. Additional user-complaint evidence

**Reached:**
- **Google Play reviews** (scraped with `google-play-scraper` 1.2.7, en/US, the newest 1★ and 2★ reviews, up to 150 per star):
  - TiviMate (`ar.tvplayer.tv`): 237 reviews from 2019-03 to 2026-10, 101 of them from 2025–26.
  - Sparkle TV: 87 reviews, 33 from 2025–26.
  - OTT Navigator: 162 reviews, the newest from 2023-08.
  - IPTV Smarters: 300 reviews, the newest from 2023-01.
  - Televizo: 0 results (package id not matched).
  - Keyword classification of TiviMate's 2025–26 negatives (n=101):
    - premium, price or licence: 46
    - buffering or playback errors: 26
    - playlist loading or Xtream: 20
    - account or device limit: 15
    - "update broke it": 10
    - UI on phones or touch: 8
    - Play availability: 6
    - catch-up or recording: 6
    - EPG: 2
  - Representative TiviMate items, all on the Play listing:
    1. Annual price raised from $6.99 to $11.99, recordings freeze or loop, and scheduled recordings stop at 87 min (2026-01-15, v5.2.0, 77 helpful).
    2. Account email cannot be changed, and support is poor (2025-09-13, 63 helpful).
    3. **A changed M3U URL cannot be edited in place.** The rescan fails, so the user must add a new playlist and loses favourites (2026-01-21, 27 helpful). This validates The Box's stable-identity and URL-edit design.
    4. Error 458 on every fast-forward or rewind in VOD (2026-08-16, v5.3.3).
    5. A lifetime licence is not recognised on a second device, so cloud sync fails (2026-02-02).
    6. Playlist editing and VOD are paywalled (2025-10-28). The free tier cannot play VOD or "anything without paying" (2025-07, 2026-06).
    7. The premium purchase hand-off to the phone fails ("incompatible with my phone") on a Xiaomi box (2026-09-29).
    8. Forced updates with no downgrade path, because the server rejects old versions (2025-01-13).
    9. Stalker portal catch-up shows only 7 of 14 days and no programme names (2025-11-23).
    10. SMB recording to a QNAP fails (2025-06-01).
    11. No playback-speed control (2025-12-14).
    12. The EPG stops updating after the last two updates (2026-06-23).
  - Sparkle TV: freezes with audio continuing, Xtream entry gives a "temporary error", Fire Stick overheats, and the developer is hostile in review replies.
- **TiviMate on Google Play**: the listing is live. The "What's new" text is the only official changelog reachable: "Fixed channel sorting in 'All channels' group. Fixed missing catch-up for some m3u playlists".
- **APKMirror**: version dates 5.1.5 (2025-01-21), 5.2.0 (2025-08-30), 5.3.2 (2026-06-02, Android 6.0+). The 5.3.2 release-notes page itself did not render for the scraper.
- **GitHub issues** (REST API, sorted by reactions or comments):
  - Dispatcharr:
    - #504 multiple provider URLs with failover (27 reactions, top request)
    - #133 catch-up channels (23)
    - #407 per-channel User-Agent / stream profile (20)
    - #383 **EPG timezone offset** (20)
    - #1003 native HLS output
  - IPTVnator:
    - #125 Xtream API
    - #103 recording
    - #395 manual EPG mapping
    - #1158 Stalker portals
    - #1562/#1563 catch-up time zone (see C1)
  - pvr.iptvsimple (by comments):
    - #512 `std::bad_alloc` with big EPG (64 comments)
    - #476 compressed EPG
    - #809 restart/timeshift
    - #158 DST wrong EPG times
  - These match user-issues' top-25 ranking, especially failover (#10), EPG time zone (#4/#13) and huge-EPG memory (#5/#7).

**Could not reach:**
- Reddit (r/TiviMate, r/IPTV):
  - `www.reddit.com/.json` and `api.reddit.com` returned 403.
  - `old.reddit.com` redirected to login.
  - `.rss` returned 200 with an empty body, then 429.
  - The redlib mirror returned 429.
  - WebSearch refuses the `reddit.com` domain.
- tivimate.com is a JS-only Next.js shell with no changelog text.
- Threadfin: GitHub search returned nothing (issues probably disabled or moved).
- Televizo and OTT Navigator are closed source with no public issue tracker.
- Trustpilot was not retried.

---

## 4. Corrections to apply per research doc

### protocols-m3u-xmltv-xtream.md
- §1.3 `tvg-rec` row: change the source to "spec (Kodi: same as SIPTV `timeshift` days)".
- §2.6: fix the cross-reference §3.4 → §3.3 and add the fact-check note on the server time zone.
- §3.3 timeshift bullet: cite IPTVnator #1563 and add the `time_now`/`timestamp_now` fallback.
- §4.3 `{Y}…` row: Kodi uses **device local time**, not UTC.
- §4.4 `catchup-correction`: correct the sign (URL time = programme UTC − correction).
- §5.2: note that Media3 already extracts AAC-LATM.
- §5.4 rows 458/509: mark them unregistered with disputed meaning, and add the VOD-seek 458 evidence.
- §6.3: correct the buffer-size row (TiviMate labels only, no TiviMate seconds), change the AFR default to "Seamless only", and change the number-key delay to 1.5 s.

### android-tv-architecture.md
- Media3 1.10.1 → **1.11.1** (exec summary, §2.1, toolchain, ffmpeg module tag).
- Exec summary and §6.4: Fire OS mapping (8 = 29/30, add 14 and 16). Add the WorkManager 2.12 minSdk-24 constraint.
- §1.4: dynamic scheduling is default in 1.11, not "new in 1.10".
- §2.2: Xtream default output is TS, not HLS.
- §2.4(4): retry delay 1–2 s.
- §2.5: FFmpeg `release/6.0` confirmed, and the build script needs an ABI argument.
- §2.6: SurfaceView z-order caveats.
- §3.1: remove "(verify)" on FTS5 and update the Room versions.
- §3.5: WorkManager version, and EPG default 12 h.
- §4: Fire TV PiP claim refuted. Remove "(verify)" from tvprovider.
- §5.2: padding 2/5.
- §5.3: smbj 0.15.0 and the jcifs-ng last-release date. EncryptedSharedPreferences confirmed.
- §6.1: refresh the toolchain table.
- §6.4: 64-bit date is 1 Aug 2026 (confirmed), plus Vega identification.
- Footer: update the "known-from-experience" list.

### user-issues-and-mitigations.md
- Method note: record the Play scrape and the Reddit failure.
- §1: replace the "default UA that mimics VLC" mitigation.
- §4: change the EPG window default.
- §6: session-hold duration is panel-configured.
- §7: the time-zone cause is server zone, fixed in IPTVnator #1563.
- §8: Fire TV PiP note.
- §10: free-tier complaints are now verified.
- §13: Play status is "intermittently delisted, currently listed". Downloader 2023 confirmed, plus the second removal in Nov 2023.

### tivimate-feature-audit.md
- Caveat block: Play and APKMirror were reached, with versions and dates.
- Licensing table: price evidence from reviews, the Play status row, and the minSdk 23 fact.
- Buffer-size row: labels.
- Settings-sync row: cloud sync exists as a premium feature.
- Recording SMB row: SMB is evidenced by a review.
- PiP row: add a Fire TV note.
- H.4: add the VOD-seek 458 note and the 87-min recording cap.
- H.3: add the paywalled VOD and playlist-edit evidence.
