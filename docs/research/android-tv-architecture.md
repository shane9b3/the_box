# The Box: Android TV IPTV Player Architecture Recommendation

Research date: 2026-10-13. Version numbers were checked against developer.android.com / androidx release pages where possible. Items marked **(verify)** come from memory or from search snippets that disagreed with each other. Pin them only after checking Maven Central or the release page on the day you set up the version catalog.

---

## 0. Executive summary

| Area | Recommendation |
|---|---|
| UI shell | Jetpack Compose + `androidx.tv:tv-material:1.1.0`, and standard `androidx.compose.foundation` lazy layouts (the TV lazy layouts are removed) |
| EPG grid | **Custom-drawn, virtualised 2-axis grid.** Use a single `Layout`/`Canvas` composable, or a custom `View` hosted in `AndroidView` as the fallback. Do **not** nest `LazyColumn { LazyRow }` |
| Player | Media3 **1.11.1** (fact-checked 2026-10-13: was 1.10.1; 1.11.1 shipped 2026-09-10). One `ExoPlayer` instance, kept in a playback controller that outlives the screens. The mini preview and full screen are both `SurfaceView`, and the player is moved between them |
| Network | `media3-datasource-okhttp` on OkHttp 5.x. Per-channel headers come from a `ResolvingDataSource`. Token refresh and retries come from a custom `LoadErrorHandlingPolicy` plus a re-resolve hook |
| Audio | Prefer passthrough (AC-3/E-AC-3/DTS) through `AudioCapabilities`. Ship a self-built `decoder_ffmpeg` (ac3, eac3, dca, mp3/mp2 family, aac_latm) for software fallback, with `EXTENSION_RENDERER_MODE_PREFER` only for those MIME types |
| Data | Room on SQLite. Store EPG programmes as rows with a `(channel_id, end_time)`/`(channel_id, start_time)` covering index. Insert through streaming `XmlPullParser` with batched transactions. Use FTS4 for search. WorkManager handles refresh |
| DI | Hilt |
| SDK | `minSdk 23` (Fire OS 6 is API 25, Fire OS 7 is 28, Fire OS 8 is 29/30, Fire OS 14 is 31–34, Fire OS 16 is 35/36; fact-checked 2026-10-13). With minSdk 23, pin WorkManager 2.11.x, because 2.12.0 requires minSdk 24. `targetSdk 35` or `36`. Ship **armeabi-v7a + arm64-v8a** |
| Lifecycle | `MediaSessionService` with `foregroundServiceType="mediaPlayback"`, which is also the documented FGS type for DVR on Android TV. Re-resolve stream URLs on every resume and wake |
| DVR (phase 2) | Use a raw byte-copy recorder, not ExoPlayer: TS goes straight to a file, and HLS segments are concatenated into a `.ts`. Optional remux to MP4 or MKV later. Schedule with AlarmManager exact alarms, and write to local storage, SAF/USB or SMB (smbj) |
| Distribution | Sideloaded APK (GitHub Releases + Downloader code) with an in-app updater that uses `PackageInstaller`. Note that new 2025+ Fire TV Sticks run **Vega OS** and cannot run Android APKs |

---

## 1. UI toolkit

### 1.1 Status of the options (2026)

- **Compose for TV.** `androidx.tv:tv-material` **1.1.0** became stable on 6 May 2026, and `androidx.tv:tv-foundation` **1.0.0** became stable the same day. `TvLazyColumn`/`TvLazyRow`/`TvLazyVerticalGrid` were **deprecated in tv-foundation 1.0.0-alpha11 (July 2024)** and **removed in alpha12 (Jan 2025)**. Use the regular `androidx.compose.foundation.lazy.*` instead, which gained TV-friendly focus/bring-into-view behaviour (`BringIntoViewSpec`). Google's guidance is that Compose is the recommended toolkit for TV and Leanback is in maintenance. Google publishes a step-by-step "Migrate to Compose for TV" guide. tv-material 1.0.1 fixed a compositing bug that broke ExoPlayer rendering on a `Surface` on lower API levels. That bug shows that video inside a tv-material `Surface` was a real problem area, so keep video outside tinted or clipped tv-material surfaces.
- **Leanback** (`androidx.leanback:leanback` 1.2.0 line). It is mature, has a low memory footprint and gives you `BrowseSupportFragment` and `PlaybackSupportFragment` for free. However, it is effectively frozen, it has no grid primitive suitable for an EPG, and theming it into a TiviMate look is painful. TiviMate itself is a classic-Views app.
- **Classic Views.** These give the best raw performance on Mali-G31 class devices and the most deterministic D-pad focus (`nextFocus*`, `FocusFinder`). They mean more code, but for one screen (the EPG) a custom `View` is very reasonable.

### 1.2 Decision

**Use Compose for TV for the app shell and every screen except the EPG grid body.** That covers navigation drawer, settings, playlist management, VOD/series browsing, the player overlay/OSD, search and dialogs. The **EPG programme grid is a single custom-drawn component.** Build it first as a Compose `Layout` + `Canvas` with its own focus model. Keep the option to swap in a custom `View` (`AndroidView { EpgGridView(...) }`) if Macrobenchmark on a Fire TV Stick 4K or Onn 4K misses the targets in §1.5. Both are viable, and the arguments run as follows:

| | Compose custom-drawn grid | Custom `View` grid in `AndroidView` |
|---|---|---|
| Pros | One toolkit, state-driven, easy theming, shares focus system with the rest of the app | Most predictable frame times on low-end GPUs, direct `Canvas`/`StaticLayout` caching, no recomposition risk, mature `onKeyDown` handling, and this is how TiviMate-class apps do it |
| Cons | Recomposition/allocation discipline required, and Compose's first-frame/JIT cost on cold start is larger on 1.5 GB devices (mitigate with Baseline Profiles) | Interop boundary for focus (entering and exiting the grid must be bridged with `FocusRequester` + `requestFocus()`), plus a second rendering idiom |

The key insight is that in **both** approaches each programme cell is *not* a composable or child view. The grid is one node that draws visible cells itself and owns a single "virtual focus" (channelIndex, programmeId). This sidesteps the main D-pad pitfalls: focus jumping to the nearest-by-geometry cell in another row, and focus loss when a lazy item scrolls out and is disposed. Thousands of focusable nodes also stop being a measurement and allocation cost.

### 1.3 Why not nested lazy lists

`LazyColumn` of `LazyRow`s gives N independent horizontal scroll states. Keeping them synchronised means fanning out `scrollBy` to every visible row each frame, which causes jank. Programme cells have variable widths, so the default 2D focus search lands on the cell whose *centre* is nearest. That is wrong for an EPG, where you want "the programme airing at the same time as the focused one". Disposed rows also lose focus when scrolled out quickly with a held D-pad (key repeat about 20–30 Hz). Several open-source IPTV projects that started this way have moved to custom grids. The same applies to Leanback `HorizontalGridView` nesting.

### 1.4 EPG grid design (concrete)

**Coordinate model**
- X axis is time. `pxPerMinute` is constant, for example 30 min = 300 dp, so 10 dp/min. `x = (startEpochMin - originEpochMin) * pxPerMinute`. The origin is the start of the EPG window (now minus catch-up days).
- Y axis is channel index × `rowHeight` (fixed, for example 64 dp). Fixed row height is essential for O(1) row lookup.
- The grid holds one `scrollX` (shared by the time ruler and all rows, which gives free synchronised horizontal scroll) and one `scrollY` (shared with the channel-logo column).

**Data windowing**
- The `GuideViewModel` exposes a `GuideWindow`: channel index range `[firstVisible - 20, lastVisible + 20]` × time range `[viewStart - 3h, viewEnd + 3h]`.
- The repository fetches programmes for that window with one indexed query (see §3), into an `IntObjectMap<ProgrammeRow[]>` keyed by channel index. Each row is a sorted array of compact `ProgrammeCell(startMin:Int, endMin:Int, id:Long, titleRef)` objects. Avoid `List<data class>` with boxed fields.
- Scrolling inside the window issues no query. When the viewport comes within half a margin of the window edge, request the next window on `Dispatchers.IO`, debounced by about 100 ms, and keep drawing the old window plus "loading" placeholders. Cap memory with an LRU of row arrays (for example 300 channels × 12 h).
- Channel list (ids, names, logo URLs, numbers) for the current group is loaded fully. Even 20k channels is about 2 MB as compact arrays. Programmes are never loaded fully.

**Cell measurement and drawing**
- Cell rect = `[x(start).coerceAtLeast(rowViewportLeft), x(end)]`. Clip partially visible cells so titles stay readable ("sticky title" at the left edge, as in TiviMate).
- Titles: pre-measure with `TextMeasurer` (Compose) or `StaticLayout`/`TextPaint.breakText` (View), cache by `(programmeId, cellWidthBucket)` in an `LruCache` of about 2–4k entries, and ellipsize to one or two lines. Never measure text in the draw pass for cells already cached.
- Draw order per frame: background stripes, cells (rounded rects drawn with one `Paint`, focused cell with a different `Paint` plus border), text, "now" line, time ruler, channel column. Allocate nothing in `onDraw`/`DrawScope`.
- Logos: Coil 3 (`io.coil-kt.coil3` 3.6.3, fact-checked 2026-10-13) with a hard size, for example 96×54 px, `RGB_565` where alpha is not needed, and memory cache sized for about 2 screens. In Compose, logos live in the channel column, which *can* be a plain `LazyColumn` whose `LazyListState` is driven from the grid's `scrollY`. Alternatively draw them as `ImageBitmap`s in the same Canvas.

**Focus and D-pad**
- The grid node is a single `focusable()` (Compose) or `isFocusable = true` (View). It intercepts `onPreviewKeyEvent`/`onKeyDown`:
  - **Left/Right**: move to the previous or next programme in the same row. If the new cell is off-screen, animate `scrollX` so the cell start sits at about 25% of the viewport.
  - **Up/Down**: move to the adjacent channel. Choose the programme in that row that contains the **anchor time**, not the geometric centre. The anchor time is set when the user moves left or right and is preserved across vertical moves, which is the TiviMate behaviour.
  - **Long-press / CH+ / CH- / page keys**: page by visible row count.
  - **Exit**: when on the left-most visible time and Left is pressed, release focus to the channel column or nav drawer via `FocusRequester`/`focusProperties { exit = ... }`.
- Key repeat: coalesce. If a scroll animation is running, retarget it rather than queueing, and drop events when more than about 2 are pending. That keeps held-down scrolling smooth on slow devices.
- **Focus restoration** state is `(channelId, anchorEpochMin, scrollX, scrollY)`. Save it in `SavedStateHandle` and in a process-wide `GuideStateHolder`, so that returning from full screen restores the exact cell even after the composition is disposed. On re-entry, re-request focus on the grid node in `LaunchedEffect(Unit)` after the first layout, which avoids the classic "focus goes to drawer" bug.

**"Now" line**
- A ticker `Flow` emits once per 30 s (not per frame). The line is drawn in the same canvas at `x(now)`. Cells whose `end < now` get a dimmed paint, and the airing cell gets a progress bar. Only the canvas is invalidated, with no recomposition of anything else.

**Keeping 60 fps with video in the mini window**
- The mini preview is a **`SurfaceView`**, so the video is composited by the hardware composer (HWC) in its own layer and costs the GPU nothing to draw. A `TextureView` would force every video frame through the GPU and the app's render thread, which on Mali-G31 halves available fill rate for the grid. On API 34+ `SurfaceView` also resizes and moves without black flashes (`setSurfaceLifecycle`/improved sync).
- Avoid overlapping translucent layers on top of the video. On low-end GPUs, overdraw beyond about 2.5× hurts, so draw the grid on an opaque background below the preview row.
- Do not animate the preview's size every frame. When it changes size (mini to full screen), resize once and keep the surface (see §2.6).
- In Compose, read `scrollX`/`scrollY` *in the draw phase only* (`Modifier.drawWithCache`/`graphicsLayer { translationX = ... }` lambdas), so scrolling never recomposes. This is the single most important Compose rule here.
- Generate **Baseline Profiles** (`androidx.baselineprofile` plugin) covering the guide scroll journey, and ship them. On ART devices without cloud profiles (all sideloaded installs), this is the difference between janky and smooth first use.
- Dynamic scheduling reduces playback-loop wakeups. (fact-checked 2026-10-13: it was experimental since Media3 1.4.0, not new in 1.10, and is **enabled by default from 1.11.0**. Benchmark it on low-end devices and keep the opt-out.)

### 1.5 Performance targets and device notes
- Targets: guide scroll P90 frame time under 16.7 ms and P99 under 33 ms on Fire TV Stick 4K (1st gen, MT8695, 1.5 GB) and Onn 4K (Amlogic S905Y4, Mali-G31, 2 GB). Cold start to guide visible under 2.5 s with a 20k-channel playlist.
- Many of these devices are 1080p UI on 4K panels. Keep the UI at the default density and don't render the app at 4K.
- Low-memory: `ActivityManager.isLowRamDevice()` / `memoryClass` (often 192–256 MB heap). Size Coil and text caches from it.

---

## 2. Playback (Media3)

### 2.1 Versions and modules
Media3 **1.11.1** (2026-09-10; 1.11.0 shipped 2026-08-05, 1.10.1 2026-05-12, 1.10.0 2026-03-25). (fact-checked 2026-10-13: updated from 1.10.1.) Modules: `media3-exoplayer`, `media3-exoplayer-hls`, `media3-exoplayer-dash`, `media3-extractor` (TS), `media3-datasource-okhttp`, `media3-session`, `media3-ui` (PlayerView, for the full-screen fallback) and `media3-ui-compose` / `media3-ui-compose-material3`. The latter provides `ContentFrame`/`PlayerSurface`, the new `Player` composable, `ProgressSlider` and speed controls. Subtitles in the Compose UI module are still listed as "planned", so render subtitles with `SubtitleView` via `AndroidView` for now. 1.10.1 fixes a tunneling-mode audio-session-ID race (`IllegalStateException`), which matters if you enable tunneling.

### 2.2 Sources and extractors
- Build the `MediaSource` yourself by sniffing the URL and Content-Type:
  - `.m3u8` or `application/vnd.apple.mpegurl` → `HlsMediaSource`
  - `.mpd` → `DashMediaSource`
  - Xtream `/live/u/p/id.ts` and raw HTTP TS → `ProgressiveMediaSource` with `DefaultExtractorsFactory().setTsExtractorFlags(...)`
  - Unknown types → `DefaultMediaSourceFactory`
- Xtream: default to `.ts` and use `.m3u8` only when `allowed_output_formats` lists it, or as an automatic fallback. (fact-checked 2026-10-13: harmonised with the protocol doc; Kodi's `xc` mode also defaults to `.ts`.) HLS gives proper live-window and catch-up semantics, while `.ts` gives lower latency and is what most panels serve reliably. Make it a per-playlist setting ("Stream format: TS / HLS / auto").
- TS flags to keep available via a per-channel "compatibility mode": `FLAG_ALLOW_NON_IDR_KEYFRAMES` (many IPTV encoders send no IDR frames, which leaves the player stuck in BUFFERING; it can cause brief corruption on start) and `FLAG_DETECT_ACCESS_UNITS`. Also use `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` for DTS-in-TS, and `TsExtractor.MODE_SINGLE_PMT` (default) vs `MODE_MULTI_PMT` for MPTS feeds. Don't set `FLAG_IGNORE_*` by default.
- Use `TsExtractor` timestamp-search tuning for live TS: `DefaultExtractorsFactory.setTsExtractorTimestampSearchBytes(...)`. The default is fine for live, and you can reduce it for faster start.
- **Discontinuities**: IPTV TS restreams often reset PCR/PTS when the upstream changes (ad insertion, encoder restart). `TsExtractor` handles PTS wrap via `TimestampAdjuster`, but a hard reset can stall. Detect it with `Player.Listener.onPlayerError` (`ERROR_CODE_BEHIND_LIVE_WINDOW`, `PARSING_*`) or with a stall watchdog (STATE_BUFFERING for more than N s while bytes are arriving), then `prepare()` from scratch on the same URL. HLS `#EXT-X-DISCONTINUITY` is handled natively. Catch `BehindLiveWindowException` → `seekToDefaultPosition(); prepare()`, which is the standard recipe in the Media3 docs.

### 2.3 LoadControl for live TS
Defaults (`DefaultLoadControl`: min 50 s, max 50 s, bufferForPlayback 2.5 s, afterRebuffer 5 s) are VOD-oriented and use about 30–60 MB on high-bitrate 4K TS, which is risky on 1.5 GB devices. Recommended starting point:

| Param | Live TS/HLS | VOD/catch-up |
|---|---|---|
| `minBufferMs` | 15 000 | 30 000 |
| `maxBufferMs` | 30 000 | 60 000 |
| `bufferForPlaybackMs` | 1 000–1 500 (fast zap) | 2 500 |
| `bufferForPlaybackAfterRebufferMs` | 3 000 | 5 000 |
| `backBufferDurationMs` | 0 (or 30 000 if you implement instant "rewind 10s" on live) | 15 000 |
| `targetBufferBytes` | Cap at about 32 MB on low-RAM devices (`setTargetBufferBytes`), with `prioritizeTimeOverSizeThresholds=false` |  |

- Expose "Buffer size: small/normal/large" in settings, as TiviMate does.
- For HLS live, set `MediaItem.LiveConfiguration` (`targetOffsetMs` about 3 × target duration, `min/maxPlaybackSpeed` 0.97/1.03) so the default `LivePlaybackSpeedControl` catches up smoothly instead of drifting.
- Raw HTTP TS has no live window. ExoPlayer treats it as an unseekable progressive stream, so "live edge" is just "keep reading". Time-shift on raw TS requires your own ring buffer (see §5's recorder, which doubles as a time-shift buffer).

### 2.4 Networking, headers, redirects and token expiry
- **OkHttp vs default vs Cronet**: Use `OkHttpDataSource.Factory` with OkHttp **5.5.0** (fact-checked 2026-10-13). It gives a shared connection pool with your API/EPG clients, interceptors for logging, per-host DNS (DoH via `okhttp-dnsoverhttps` for ISP-blocked hosts) and `followSslRedirects(true)`. **Cross-protocol redirects** (http→https or the reverse, common with Xtream load balancers) are *disabled* by default in `DefaultHttpDataSource` and must be enabled with `setAllowCrossProtocolRedirects(true)`. OkHttp follows them by default. Cronet (`media3-datasource-cronet`) gives QUIC/HTTP3 but needs Play Services Cronet (absent on Fire TV) or the 6–8 MB embedded Cronet. It isn't worth it for IPTV.
- **Per-channel headers**: M3U `#EXTVLCOPT:http-user-agent=`, `http-referrer=`, `#KODIPROP:` and `|User-Agent=...&Referer=...` URL suffixes are parsed at import into `channel.headers_json`. At play time, wrap the factory in `ResolvingDataSource.Factory(okHttpFactory) { spec -> spec.withAdditionalHeaders(headersFor(spec.uri)) }`. Do this for HLS segments too, since the same resolver applies to every chunk. Default UA is configurable per playlist (many panels whitelist "VLC/3.0.x" or "IPTVSmartersPlayer").
- **403/401/expired token**:
  1. A custom `LoadErrorHandlingPolicy` (extend `DefaultLoadErrorHandlingPolicy`) treats `HttpDataSource.InvalidResponseCodeException` 401/403/404/410/458/509 as **non-retryable at the loader level** (return `C.TIME_UNSET`), so the error surfaces fast. It retries 5xx/IOExceptions with jittered backoff (500 ms, 1 s, 2 s, max 4) and caps `getMinimumLoadableRetryCount` at about 3 for live.
  2. In `Player.Listener.onPlayerError`, if the cause is 401/403, call `StreamUrlResolver.resolve(channel, forceRefresh=true)`. For Xtream that rebuilds the URL; for "portal"-style playlists it re-fetches the tokenized link. Then `setMediaItem` + `prepare` at the live edge. Limit this to 2 re-resolves per minute per channel, then show an error.
  3. HLS with tokenized segment URLs: put the token refresh *inside* the `ResolvingDataSource` (rewrite the query param from a cached, refreshable token), so segments keep loading without rebuilding the source.
  4. Connection limits (`max_connections` from Xtream `player_api.php` user_info): treat 403/458/509 right after a successful zap as "too many connections". Make sure the previous connection is closed (`player.stop()` before the new `prepare`) and add a 1–2 s delay before retry (fact-checked 2026-10-13: harmonised. Panels update `active_cons` lazily, and slot release is panel-configured).

### 2.5 Audio: passthrough, FFmpeg, offload, tunneling
- `DefaultRenderersFactory` + `AudioCapabilities` (HDMI ELD via `AudioManager`) decide passthrough of AC-3/E-AC-3/DTS/TrueHD. Most Google TV/Onn devices do E-AC-3 passthrough. Fire TV does AC-3/E-AC-3 decode or passthrough. Expose a "Audio passthrough: auto/off" setting, because some soundbars misreport.
- **FFmpeg extension**: Media3 doesn't publish a prebuilt `decoder_ffmpeg` AAR (licensing), so build it from `androidx/media` `libraries/decoder_ffmpeg` with `build_ffmpeg.sh <module> <ndk> <host> <abi> <decoders...>`. Use FFmpeg `release/6.0` (fact-checked 2026-10-13: this is the README's branch, tested on NDK r26b; the script also takes an ABI argument). Media3 already extracts AAC-LATM from TS (`LatmReader`), so `aac_latm` is only a decoder fallback. and decoder names `ac3 eac3 dca mp3 mp2 aac aac_latm truehd vorbis opus flac alac pcm_mulaw pcm_alaw`. **DTS is `dca` in FFmpeg, and MP1/MP2/MP3 map to the `mp3`/`mp2` family.** Build arm64-v8a and armeabi-v7a only. Keep it LGPL (no `--enable-gpl`) so you can ship. Expect about 2–4 MB per ABI.
  - Renderer order: use `DefaultRenderersFactory.setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)` (platform first, FFmpeg fallback). The platform renderer is still chosen whenever it reports support, *including passthrough*. Use `PREFER` only behind a "Force software audio decode" toggle. MP2 (very common on DVB-sourced IPTV) has no MediaCodec decoder on many TV SoCs, and LATM-AAC (DVB-T2) is similar. Those are the main reasons to ship FFmpeg.
  - Video FFmpeg decode isn't supported by the Media3 FFmpeg module (audio only). For MPEG-2 video and HEVC without hardware support you're limited to hardware decoders. Most TV SoCs do MPEG-2 in hardware, but Fire TV Stick Lite doesn't do HEVC.
- **Audio offload** (`setAudioOffloadPreferences`) saves power for music. It is irrelevant for TV video, so leave it disabled.
- **Tunneled playback** (`DefaultTrackSelector.Parameters.setTunnelingEnabled(true)`):
  - For: A/V sync done in hardware, the best frame pacing on Amlogic/MediaTek TV SoCs, and the only judder-free path on some Sony/Philips TVs and Fire TVs.
  - Against: no software audio (FFmpeg disables it for that track selection), flaky on some Realtek/Mali boxes, problems with subtitle sync and speed changes (live catch-up speed control is ignored), and black-frame-on-pause behaviour.
  - Recommendation: **off by default, a per-device opt-in setting**, and auto-disable after a tunneling-related playback error. Media3 only enables it when the selected audio and video are both tunneling-capable.

### 2.6 Surfaces: mini preview to full screen

**Debate: one player vs two players**
- *Two players* (one for the preview, one for full screen): the switch is "instant" when the second is already prepared. However, this doubles the connection count (fatal with 1-connection IPTV accounts), doubles decoder instances (many TV SoCs have only **one** secure or 4K HEVC decoder instance, so the second fails with `MediaCodec` errors) and doubles RAM.
- *One player, moved between surfaces*: no extra connection or decoder. With `SurfaceView` the swap costs a brief re-config (`setVideoSurfaceView` → new surface → the decoder reconfigures, about 50–300 ms, sometimes a black flash).
- **Recommendation: one `ExoPlayer` and one `SurfaceView` that is *resized* rather than swapped.** Put the video `SurfaceView` in a host layer at the activity root, behind the Compose content. The guide reserves a transparent "hole" rect in the upper-left, and full screen just animates that rect to fill the screen. That gives no surface change, no decoder reconfiguration and no black frame. With Compose you can do this with a root `Box { PlayerSurface(player, surfaceType = SURFACE_TYPE_SURFACE_VIEW, modifier = Modifier.layout { place at videoRect }) ; NavHost(...) }`, where `videoRect` is state hoisted by the current screen. Animate it in one or two steps, not per frame, on low-end devices. If a screen must use a separate surface (for example a PiP-only layout), fall back to `player.setVideoSurfaceView(newView)`. ExoPlayer handles this, with a short black frame. (fact-checked 2026-10-13: z-ordering confirmed. A SurfaceView is composited behind its window, so Compose UI draws on top. Caveats: Compose `clip`/`alpha`/rounded corners/`graphicsLayer` transforms do not affect the video layer. Any opaque Compose background over the rect, such as a Scaffold or tv-material `Surface` colour, hides it. Two SurfaceViews have no defined relative order unless `setZOrderMediaOverlay` is used.)
- Keep the player in a `@Singleton` `PlaybackController` (inside the `MediaSessionService`) so it survives Activity recreation and navigation.
- **PlayerView vs raw SurfaceView in Compose**: use `media3-ui-compose` `PlayerSurface`/`ContentFrame` (surface + aspect ratio + shutter) and draw the OSD in Compose. Use `PlayerView` via `AndroidView` only if you need its `SubtitleView` integration. Even then, embed just `SubtitleView` as an `AndroidView` overlay and feed it `player.currentCues`.

### 2.7 Auto frame rate and HDR
- Android 11+ (API 30): `Surface.setFrameRate(fps, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS | CHANGE_FRAME_RATE_ALWAYS)`. Android 12+ adds the user setting "Match content frame rate" (Never/Seamless only/Always), and the system respects it. ExoPlayer calls `setFrameRate` itself. Configure it with `ExoPlayer.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)` (default) or `..._OFF` when you do your own non-seamless switching. Pass exact rates (29.97, not 30). Clear with `setFrameRate(0)` on pause or exit.
- Non-seamless switching ("Always"), which matters for 50 Hz European TV on a 60 Hz UI: on API 30+ use `CHANGE_FRAME_RATE_ALWAYS` via `SurfaceControl.Transaction.setFrameRate` or `Surface.setFrameRate`. On API under 30 (Fire OS 6/7, older boxes) use `WindowManager.LayoutParams.preferredDisplayModeId` with a `Display.Mode` whose refresh rate is an integer multiple of the content fps, then wait for `DisplayManager.DisplayListener.onDisplayChanged` before `play()`, so the HDMI re-sync (1–3 s blank) doesn't eat the first seconds. Live TV fps comes from `Format.frameRate`. For TS this is often missing until the first SPS, so wait for `onVideoSizeChanged`/`onTracksChanged`.
- Make AFR a 3-way setting: Off / Seamless only / Always. Fire OS has its own "Match original frame rate" toggle, and Fire OS 7+ honours `preferredDisplayModeId`.
- HDR: no app-side mode switch is needed on modern Google TV. The platform switches HDR output when the decoder outputs HDR to a `SurfaceView` (not `TextureView`). Check `Display.getHdrCapabilities()` to hide unsupported HDR tracks. Dolby Vision profile 5/8 TS streams need the device's DV decoder, and Media3 1.10 adds DV Profile 10 for MP4 only.

### 2.8 Subtitles and track selection
- Media3 TS supports **DVB subtitles** (bitmap, `DvbDecoder`), **CEA-608/708** (from SEI in H.264/H.265, via `DefaultTsPayloadReaderFactory` with `closedCaptionFormats` or automatic in HLS), **WebVTT/TTML** in HLS/DASH and **ID3**. **Teletext subtitles (EBU teletext, page 888) are not supported** by Media3. It's a long-standing open feature request. Options: ignore it, or write a `TsPayloadReader` for stream type 0x06 with descriptor 0x56 plus a small teletext page decoder. That is a known, bounded effort (about 1–2 kLOC; port from libzvbi logic or Kodi's `CDVDTeletextData`). Defer to a later release.
- **Text renderer gotcha (Media3 1.4+)**: subtitle parsing moved to extraction time. Custom subtitle formats need `experimentalParseSubtitlesDuringExtraction` or a `SubtitleParser.Factory`.
- Audio track selection: TS PMT language descriptors (ISO 639) map into `Format.language`. DVB "clean audio" and audio-description flags map to `roleFlags`. Use `TrackSelectionParameters.setPreferredAudioLanguages(...)`, `setPreferredAudioRoleFlags`, and remember the per-channel last choice (store it in `channel_prefs`). Expose `Tracks` grouped by type in the OSD.

---

## 3. Data layer

### 3.1 Storage engine decision: SQLite (Room) vs custom binary/mmap
- Size estimate: 20k channels × 7 days × 24 programmes ≈ 3.4M rows. But realistically only about 20–40% of channels have EPG, so roughly 1M rows in practice. At about 120 bytes/row with indices that is about 120–400 MB on disk. That is too large for 8 GB Fire TV Stick storage with about 4 GB free. **Mitigations**: store `title` and `description` deduplicated (a separate `epg_text` table, since programmes repeat heavily), import only channels that exist in enabled playlists (map `tvg-id`, drop unmapped EPG channels at parse time), make description lazy (load on focus) and set a configurable EPG retention (past 1–7 days for catch-up, future 3–7 days).
- A **custom mmap binary store** (per-channel sorted arrays of `(start:Int32, end:Int32, textOffset:Int32)`) is about 5–10× faster for the window query and about 3× smaller. However, you would have to write search, atomic refresh and migration yourself. **Recommendation: Room/SQLite.** A properly indexed window query over about 200 channels × 12 h returns in about 5–20 ms on a Cortex-A53 with WAL. Keep the EPG in a **separate database file** (`epg.db`), so it can be rebuilt, swapped atomically (build `epg_new.db`, then rename) and excluded from backups without touching user data in `library.db`.
- Library: **Room 2.8.x** (`androidx.room`) is the safe choice. **Room 3.0** (`androidx.room3`, stable since July 2026, latest 3.0.3) is KMP-focused, drops SupportSQLite, and uses the `androidx.sqlite` driver APIs. It is fine for greenfield but has younger tooling. **Recommend Room 2.8.x now, with `BundledSQLiteDriver`** (androidx.sqlite, bundled modern SQLite with FTS5). That gives a consistent SQLite version across Fire OS 6 (system SQLite 3.9 era) through Android 15. (fact-checked 2026-10-13: confirmed. Room 2.8.5 supports `setDriver(BundledSQLiteDriver())`, and the `sqlite-bundled` 2.7.1 native library is compiled with `ENABLE_FTS5`. It costs about 1 MB of native code per ABI.) Migrate to room3 later; the annotation APIs are largely unchanged.

### 3.2 Schema (library.db)

```sql
playlist(id PK, name, type /*M3U|XTREAM|STALKER*/, url, username, password_ref /*Keystore alias*/,
         user_agent, epg_url, refresh_interval_h, last_refresh, etag, last_modified, sort_mode)

channel(id PK,                     -- internal stable rowid
        playlist_id FK, stable_key TEXT NOT NULL,   -- see 3.5
        stream_id TEXT,            -- xtream stream_id or null
        name, number INT, logo_url, group_id FK, tvg_id, tvg_name,
        url, headers_json, catchup_type, catchup_days INT, catchup_source,
        is_radio INT, hidden INT, sort_index INT, missing_since INTEGER NULL,
        UNIQUE(playlist_id, stable_key))
INDEX channel(playlist_id, group_id, sort_index)
INDEX channel(tvg_id)

channel_group(id PK, playlist_id, name, sort_index, hidden, UNIQUE(playlist_id, name))
favourite(list_id, channel_stable_key, playlist_id, sort_index, PRIMARY KEY(list_id, playlist_id, channel_stable_key))
channel_prefs(playlist_id, channel_stable_key, custom_name, custom_number, custom_logo, epg_override_tvg_id,
              audio_lang, subtitle_lang, ..., PRIMARY KEY(playlist_id, channel_stable_key))
recently_watched(...), recording_rule(...), recording(...)
```

User data (favourites, prefs, custom ordering) is keyed by **`(playlist_id, stable_key)`, not by `channel.id`**, so refreshes can delete and recreate channel rows freely.

### 3.3 Schema (epg.db)

```sql
epg_channel(id INTEGER PK, xmltv_id TEXT UNIQUE, display_name, icon)
epg_text(id INTEGER PK, title TEXT, subtitle TEXT, description TEXT, category TEXT, hash INTEGER UNIQUE)
programme(epg_channel_id INTEGER, start_min INTEGER, end_min INTEGER,  -- epoch minutes (Int fits)
          text_id INTEGER, flags INTEGER /*new, live, premiere, catchup-able*/, season INT, episode INT,
          PRIMARY KEY(epg_channel_id, start_min)) WITHOUT ROWID
INDEX programme(epg_channel_id, end_min)   -- optional; the PK alone usually suffices (see query)
VIRTUAL TABLE epg_fts USING fts5(title, description, content='epg_text', content_rowid='id')  -- or FTS4
```

`WITHOUT ROWID` with PK `(epg_channel_id, start_min)` clusters each channel's programmes contiguously on disk. That is the main trick for fast windowed reads.

**Guide window query** (one query per window):

```sql
SELECT p.epg_channel_id, p.start_min, p.end_min, p.text_id, t.title, p.flags
FROM programme p JOIN epg_text t ON t.id = p.text_id
WHERE p.epg_channel_id IN (:visibleEpgChannelIds)      -- ~60–200 ids
  AND p.start_min < :windowEnd
  AND p.start_min >= :windowStart - :maxProgrammeLen    -- e.g. 12h; lets the PK range-scan
  AND p.end_min   > :windowStart
ORDER BY p.epg_channel_id, p.start_min
```

The extra lower bound on `start_min` turns this into a bounded PK range scan per channel. Without it, `end_min > x` can't use the PK. Map `channel.tvg_id` to `epg_channel_id` once and keep it in memory. Keep host parameter count under 999 on old SQLite (chunk the IN list), or use a temp table. Use `PRAGMA journal_mode=WAL`, `synchronous=NORMAL`, `temp_store=MEMORY` and `cache_size=-8000`.

### 3.4 XMLTV ingestion
- Use **Android `XmlPullParser`** (`Xml.newPullParser()`, the KXmlParser native-backed implementation) in streaming mode. It is the fastest option on-device without native code: about 50–150k programmes/s on A53. kotlinx-serialization XML (xmlutil) and DOM-style parsers are slower and allocate more. SAX via `javax.xml.parsers` is similar in speed but clunkier. Disable namespace processing. Don't call `getText()` on ignorable whitespace.
- Streaming pipeline: `OkHttp Response.body.source()` → (`GZIPInputStream`, or `XZInputStream` from **org.tukaani:xz** for `.xz`; detect by magic bytes `1F 8B` and `FD 37 7A 58 5A`, not by extension) → `BufferedInputStream(64 KB)` → `XmlPullParser`. Never download to a String. Optionally tee to a cache file for retry.
- Parse `start="20261013190000 +0100"` with a hand-written parser to epoch minutes. Avoid `SimpleDateFormat`/`DateTimeFormatter` per row, which is very slow.
- Insert through a raw `SupportSQLiteStatement` (compiled once) in transactions of about 5–10k rows. Dedupe text via an in-memory `HashMap<Long(hash), Int(id)>`. Skip `<programme>` whose `channel` isn't in the mapped set, which is decided before parsing the text.
- Expect a 7-day, 20k-channel XMLTV of 300 MB to 1 GB uncompressed. Prefer per-playlist Xtream `xmltv.php` with only the mapped channels, or the Xtream `get_short_epg`/`get_simple_data_table` APIs for on-demand detail.

### 3.5 Refresh with WorkManager (`androidx.work:work-runtime-ktx` 2.11.2 for minSdk 23, or 2.12.0 which requires minSdk 24; fact-checked 2026-10-13)
- `PeriodicWorkRequest` per playlist (default 12 h EPG, harmonised with the protocol doc (fact-checked 2026-10-13); 12–24 h playlist) with constraints `NetworkType.CONNECTED`, `setRequiresStorageNotLow(true)`. On TV, `requiresDeviceIdle`/`requiresCharging` are meaningless. Use `ExistingPeriodicWorkPolicy.UPDATE` and `setForeground(...)` (FGS type `dataSync`) for long EPG imports, so they aren't killed after 10 min.
- Also refresh on app start if stale (more than 12 h), since TV boxes are often unplugged and periodic work drifts. Use `If-None-Match`/`If-Modified-Since` (store ETag/Last-Modified) to skip unchanged EPGs.
- **Incremental EPG**: rebuild into `epg_new.db`, then swap atomically (close the Room instance, rename, reopen, invalidate Flows). That avoids an hours-long read lock and fragmentation. Alternatively do per-channel `DELETE WHERE epg_channel_id=? AND start_min>=:from` + insert in one transaction per channel batch. The rebuild-and-swap approach is simpler and avoids `VACUUM`.
- Purge programmes older than `max(catchup_days)` days.

### 3.6 Playlist refresh diffing: stable channel identity
Priority order for `stable_key`:
1. Xtream: `stream_id` (stable per panel).
2. M3U with `tvg-id` + name: `"tvg:" + tvg_id + "|" + normalizedName`. Many playlists reuse one `tvg-id` for SD/HD/FHD variants, so include the name.
3. M3U with only URL: `"url:" + url with the credentials/token path stripped`. Xtream-style M3U URLs `/user/pass/12345` collapse to stream id `12345`.
4. Fallback: `"name:" + normalizedName + "|" + groupName`, plus an occurrence index for duplicates.

Diff algorithm: load the existing `(stable_key → id)` map, upsert matching rows (update URL, logo, name and group but keep `sort_index` if the user reordered), insert new ones, and mark missing ones with `missing_since` rather than deleting them immediately. Delete after N consecutive refreshes or 7 days, so a provider hiccup doesn't wipe favourites. Favourites and prefs reference `stable_key`, so they survive regardless. Run the whole diff in one transaction. Parse M3U streaming (line reader). 20k entries should finish in under 3 s.

### 3.7 Search
FTS5 if using the bundled SQLite (supports `bm25`, prefix `title*`), otherwise FTS4 (`@Fts4` in Room, available on all platform SQLite versions). Use external-content tables over `epg_text` and over channel names (a `channel_fts` in library.db). Use the `unicode61 remove_diacritics 2` tokenizer. Search is "type-ahead" from the on-screen keyboard and voice. Debounce 250 ms and use `LIMIT 50`.

---

## 4. Process and lifecycle

- **Foreground service**: run playback in a `MediaSessionService` (Media3 1.10: now a `LifecycleService`), declared `android:foregroundServiceType="mediaPlayback"` with the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission. While the Activity is visible a FGS isn't strictly needed. It is needed for **audio-only (radio) in background**, for **PiP** survival under memory pressure and for **DVR**. The Android docs list mediaPlayback as the type for "Digital Video Recording (DVR) functionality on Android TV". By default, stop playback in `onStop` when not in PiP (TV norm: pressing Home stops the stream, which also frees the provider connection).
- **Standby / HDMI-CEC resume ("black screen after wake")**: on `ACTION_SCREEN_OFF`/`onStop`, call `player.stop()` (release decoders and the network connection) but keep the `MediaItem`. On `onStart`/`ACTION_SCREEN_ON`, **re-resolve the URL** (tokens expire overnight; Xtream re-auth via `player_api.php`) and `prepare()` fresh at the live edge. Never try to resume a stale TS connection. Also handle the `SurfaceView` surface being destroyed and recreated across standby: rely on `PlayerSurface` re-attaching, not on a cached `Surface`. Some Amlogic boxes report HDMI hot-plug on wake. Listen to `AudioManager.ACTION_HDMI_AUDIO_PLUG` to rebuild audio capabilities (passthrough may change) via `player.setAudioAttributes`, or rely on Media3's `AudioCapabilitiesReceiver`, which is built in.
- **PiP**: supported on Android TV 8.0+ but disabled or unreliable on many devices. Fire TV documents in-app PiP for third-party apps since Fire OS 6 ("PIP windows may not be used outside your app nor can they overlay native Fire OS UI"; fact-checked 2026-10-13: the earlier "no PiP on Fire TV" claim was refuted, and per-model reliability is untested). Chromecast with Google TV supports it. Declare `supportsPictureInPicture`, use `setAutoEnterEnabled` (API 31+) and feature-check `PackageManager.FEATURE_PICTURE_IN_PICTURE`. Treat it as an optional feature.
- **Audio focus**: `ExoPlayer.setAudioAttributes(AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MOVIE), handleAudioFocus = true)`, so assistant and voice search duck or pause correctly.
- **Screensaver / screen-on**: `window.addFlags(FLAG_KEEP_SCREEN_ON)` while playing, or `PlayerView.keepScreenOn`/`Modifier.keepScreenOn()` equivalent. Clear it when paused or stopped, so Daydream/Ambient Mode can kick in. Google TV's "ambient mode" and Fire TV's screensaver both respect this flag.
- **Media session and remote buttons**: the `MediaSession` maps play/pause/FF/RW, and CEC remote keys arrive as `KEYCODE_MEDIA_*`. Handle **`KEYCODE_CHANNEL_UP/DOWN`**, **`KEYCODE_0..9`** (number entry with a 1.5 s commit timer and on-screen digits; also `KEYCODE_NUMPAD_*`), `KEYCODE_LAST_CHANNEL`, `KEYCODE_GUIDE` (open EPG), `KEYCODE_INFO`, `KEYCODE_CAPTIONS`, `KEYCODE_TV_*`, `KEYCODE_PROG_RED/GREEN/YELLOW/BLUE` (quick actions) and `KEYCODE_BOOKMARK`. Handle them in `Activity.dispatchKeyEvent` before Compose. Fire TV remotes lack number keys, so offer an on-screen numpad.
- **Home screen integration**:
  - **Watch Next** (`androidx.tvprovider:tvprovider` 1.1.0, fact-checked 2026-10-13: confirmed, released 2025-05-07, `PreviewChannelHelper` / `WatchNextProgram`): useful for VOD/series "continue watching", not for live channels. Supported on Google TV (Google TV's surfacing of third-party Watch Next is now limited to approved partners; sideloaded apps may not appear), and ignored on Fire TV.
  - **Preview channels** (Android TV 8+ Leanback launcher): de-emphasised on the Google TV launcher. Low priority.
- **TIF (TV Input Framework)** (`TvInputService` + populating `TvContract.Channels/Programs`):
  - For: channels appear in the system Live TV/"Live" tab and Google TV's live guide (on supported devices), plus remote CH+/− integration from the system.
  - Against: large surface area, heavy EPG duplication into the system content provider (which is slow with 20k channels), device fragmentation (Fire TV has its own "Live" integration that requires Amazon partner approval), and session/surface handling different from your own player.
  - **Recommendation: not in v1.** Revisit as an optional "Expose favourites as a TV input" feature limited to the favourites list (under 500 channels).
- **Launch intents and deep links**: `<intent-filter>` for `android.intent.action.VIEW` with `thebox://channel/{playlistId}/{stableKey}`, `thebox://play?url=` and an M3U file/URL import (`*.m3u`, `*.m3u8`, `application/x-mpegurl`). Google Assistant "open The Box" works via `LEANBACK_LAUNCHER`. Deeper "play channel X on The Box" requires App Actions/Media Actions partner integration, which isn't available for sideloaded apps. Support `android.media.action.MEDIA_PLAY_FROM_SEARCH` via MediaSession `onPlayFromSearch`, which Assistant can use on Google TV.

---

## 5. Recording (phase 2)

### 5.1 Capture approach
- **Raw byte copy (recommended)**:
  - TS: open an independent OkHttp connection to the same URL with the same headers and stream bytes to `*.ts` via a 1–4 MB buffered sink, which is near-zero CPU. Make it resilient: on disconnect, reconnect and keep appending. TS is self-synchronising, so a gap is just a discontinuity, and you write a marker into a sidecar `.json` with gap times.
  - HLS: write a mini HLS downloader. Poll the media playlist at target-duration/2, download new segments in order (respecting `#EXT-X-KEY` AES-128 by decrypting with `javax.crypto` and caching key URIs) and **concatenate TS segments into one `.ts`**. fMP4/CMAF segments need the init segment written once, then concatenation works for fMP4 as well. Pick the variant by bandwidth policy.
  - Playback of recordings: ExoPlayer plays the growing `.ts` file (`ProgressiveMediaSource` + `FileDataSource`, using `TsExtractor` seeking via binary search). Raw TS seeking is approximate. Generate a lightweight **PTS index sidecar** during recording (every N seconds, map byte offset → PTS) for accurate seeking and for **time-shift**. The same recorder writing to a ring file gives live pause/rewind for raw TS channels.
- **Media3 recording via Transformer / `MediaMuxer` (alternative)**: Media3 Transformer can remux (`setVideoMimeType` unchanged → transmux, no re-encode) to MP4. It is good as an **optional post-process** ("Convert to MP4" for portability), but not as the live capture path: it is designed for finite input, has limited MPEG-2/AC-3/MP2/DVB-subtitle-in-MP4 support and costs CPU and RAM on a 1.5 GB device that is simultaneously playing. Keep recordings as `.ts` by default. That is what Kodi PVR, TVHeadend and Channels DVR do, and it preserves all audio and subtitle tracks.
- **ExoPlayer `CacheDataSource`/`DownloadManager`**: meant for VOD downloads (fixed-length HLS/DASH). It doesn't fit live recording. Don't use it.

### 5.2 Scheduling
- `AlarmManager.setExactAndAllowWhileIdle` (or `setAlarmClock` for ultimate reliability) fires about 60 s before start → `BroadcastReceiver` → `startForegroundService(RecordingService)` with type `mediaPlayback`. That is the documented TV DVR type and is not subject to the 6 h/24 h `mediaProcessing`/`dataSync` timeouts on Android 15+.
- Exact alarms: on Android 12+ `SCHEDULE_EXACT_ALARM` needs the user grant (denied by default for new installs targeting 33+ on Android 14+). `USE_EXACT_ALARM` is auto-granted but Play-restricted to alarm/calendar apps. For a sideloaded app, declaring `USE_EXACT_ALARM` is acceptable, but if you ever ship on Play, use `SCHEDULE_EXACT_ALARM` + `canScheduleExactAlarms()` + a settings deep link. Most TV devices are API 28–34. Fire OS 7/8 respects exact alarms.
- Re-arm all alarms on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET` and `TIMEZONE_CHANGED`. Persist schedules in Room, never only in AlarmManager.
- **Rules**: one-off (programme id), series (title match + optional channel + "new only" via `flags`/`<episode-num>` / `<new/>`), and time-slot (manual). A `RuleEngine` runs after each EPG refresh, resolves rules into concrete `recording` rows (dedupe by `title+episode` or `title+subtitle+description hash`) and schedules alarms. Pre/post padding defaults are 2 min / 5 min (fact-checked 2026-10-13: harmonised across docs).
- **Conflicts / connection limits**: model each playlist's `max_connections` (Xtream `user_info.max_connections`, user-overridable for M3U). Sum active playback + recordings per playlist. On conflict: (1) a higher-priority rule wins, (2) prefer an alternate source of the same `tvg_id` on another playlist (common with multiple providers), (3) otherwise mark "conflict" in the UI ahead of time. While recording on a 1-connection account, **live viewing of another channel must be blocked**, or the player must offer "watch the recording in progress" instead. That is the honest UX for most IPTV subscriptions.

### 5.3 Storage targets
- **Internal app storage** (`context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)` or `filesDir`): no permission, deleted on uninstall, and small on sticks (about 4–5 GB free).
- **USB drives**: on Android TV, adoptable storage is often disabled. Use **SAF** `ACTION_OPEN_DOCUMENT_TREE` on the USB volume (`StorageManager.getStorageVolumes()` + `StorageVolume.createOpenDocumentTreeIntent()` on API 29+) and `takePersistableUriPermission`. Writes go through `ContentResolver.openOutputStream`/`ParcelFileDescriptor`, which is slower than direct `File` (about 10–30% overhead) but fine for 5–20 Mbit/s streams. **Caveat**: some TV builds (notably Fire OS and certain Google TV builds) ship without a DocumentsUI tree picker, so test it and fall back to `getExternalFilesDirs()` (the app-specific dir on the USB volume, no permission, writable by direct `File` API). That fallback is the most reliable path on Fire TV.
- **MANAGE_EXTERNAL_STORAGE**: works for sideloaded APKs (the user toggles "All files access"; on many TV builds the settings screen doesn't exist, so provide `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` instructions). Play policy forbids it for this use case. Make it an optional "advanced" path, not a requirement.
- **SMB shares**:
  - **smbj** (`com.hierynomus:smbj` 0.15.0, released 2026-08-21; fact-checked 2026-10-13) supports SMB2/SMB3 (dialects 2.0.2–3.1.1, signing, encryption). It is actively maintained and used by SambaLite and others, and has good streaming write throughput with `File.getOutputStream()` and large `writeBufferSize`. Note anonymous sessions need SMB2 dialects only (smbj#792). Pull `bcprov` carefully (size; use `bcprov-jdk18on`), and use `smbj-rpc` only if you need share enumeration.
  - **jcifs-ng** (`eu.agno3.jcifs:jcifs-ng` 2.1.10, the last release, Oct 2023; fact-checked 2026-10-13) also supports SMB2 (SMB3 partially), is less active and slower for large sequential writes in community reports.
  - **Recommendation: smbj.** Write with a 1–4 MB buffer on a dedicated IO thread with reconnect-and-append on failure, which works because TS append is safe. Also expose the SMB share as a recordings *source* for playback via a custom `DataSource` (smbj `File.read` with offset), so recordings stored on a NAS can be played and seeked.
  - Credentials: **Jetpack Security `EncryptedSharedPreferences` is deprecated** (androidx.security:security-crypto 1.1.0 deprecated all APIs in 2025; fact-checked 2026-10-13: confirmed, the 1.1.0 stable classes carry `@Deprecated`). Store secrets encrypted with an AES-GCM key held in **Android Keystore** (`KeyGenParameterSpec`, non-exportable) in DataStore/Room yourself, or use Tink (`com.google.crypto.tink:tink-android`). Same for playlist passwords.

---

## 6. Project skeleton

### 6.1 Toolchain (pin in `gradle/libs.versions.toml`)
| Item | Version (Oct 2026) |
|---|---|
| AGP | 9.4.1 (built-in Kotlin; no `kotlin-android` plugin needed) (fact-checked 2026-10-13: latest stable) |
| Kotlin | 2.4.21, KSP 2.3.12 (fact-checked 2026-10-13) |
| Compose BOM | 2026.09.00 (Compose 1.12.1; fact-checked 2026-10-13) |
| tv-material / tv-foundation | 1.1.0 / 1.0.0 |
| Media3 | 1.11.1 (fact-checked 2026-10-13) |
| Room | 2.8.5 (or room3 3.0.3) (fact-checked 2026-10-13) |
| Hilt | Dagger/Hilt 2.60.1 + `androidx.hilt:hilt-navigation-compose` 1.4.0 (fact-checked 2026-10-13) |
| Coroutines | kotlinx-coroutines 1.11.0 (fact-checked 2026-10-13) |
| OkHttp | 5.5.0; Retrofit 3.0.0 or Ktor client for Xtream JSON (fact-checked 2026-10-13) |
| Serialization | kotlinx-serialization-json for Xtream API |
| WorkManager | 2.11.2 (minSdk 23) or 2.12.0 (minSdk 24) (fact-checked 2026-10-13) |
| Coil | 3.6.3 (fact-checked 2026-10-13) |
| Navigation | Navigation-Compose 2.10.2 with type-safe routes, or Navigation 3 1.2.0 stable (fact-checked 2026-10-13) |

### 6.2 Modules
```
:app                     – Application, MainActivity (single-activity), DI graph, manifest, update checker
:core:model              – pure Kotlin: Playlist, Channel, Programme, StableKey, time utils (no Android)
:core:common             – dispatchers, Result types, logging (Timber), crypto helpers (Keystore)
:core:database           – Room DBs (library.db, epg.db), DAOs, migrations, FTS
:core:network            – OkHttp client factory, header/UA policy, DoH, interceptors
:core:designsystem       – Compose TV theme, focus modifiers, common components
:data:playlist           – M3U parser (streaming), diff/merge, PlaylistRepository
:data:epg                – XMLTV streaming parser, gz/xz, importer, EpgRepository (window queries)
:data:xtream             – Xtream Codes API (player_api.php, get_live_streams, catchup/timeshift URLs)
:player                  – PlaybackController, ExoPlayer factory, LoadControl, DataSource/LoadErrorPolicy,
                           StreamUrlResolver, AFR, track prefs, MediaSessionService, ffmpeg AAR dependency
:feature:guide (ui-guide)    – EPG grid (custom draw), mini preview host, guide VM
:feature:player (ui-player)  – full-screen OSD, zapping, number entry, track dialogs
:feature:settings, :feature:search, :feature:vod (later)
:dvr                     – recorder engine (TS/HLS), scheduler, rules engine, storage (SAF/SMB) backends
:libs:ffmpeg-decoder     – prebuilt decoder_ffmpeg AAR (built in CI from androidx/media tag 1.11.1)
:benchmark               – Macrobenchmark + Baseline Profile generator (com.android.test)
```
Keep `:core:model`, `:data:*` parsers and the DVR rules engine as pure-JVM or Android-light modules so they're unit-testable without Robolectric.

### 6.3 DI: Hilt vs Koin
**Hilt**: compile-time graph validation, first-class `@HiltViewModel`, WorkManager (`@HiltWorker`) and Service injection. Build cost is mitigated by KSP. **Koin**: no codegen, faster builds, runtime errors, slightly slower startup resolution (negligible). For a multi-module app with services, workers and a long-lived player singleton, **Hilt** is the better fit. Koin is acceptable if the team prefers it.

### 6.4 SDK levels and devices
- **minSdk 23** is a pragmatic floor. It drops ancient Android TV 5.x boxes and the Fire TV Stick 1st/2nd gen on Fire OS 5 (API 22), and Compose/Media3 have been dropping API 21 support (fact-checked 2026-10-13: confirmed. Media3 moved to minSdk 23 in 1.9.0, and the Compose UI 1.12.1, tv-material 1.1.0 and Room 2.8.5 AARs all declare minSdk 23. WorkManager 2.12.0 declares minSdk **24**. TiviMate itself is "Android 6.0+".) Fire OS 6 = API 25, Fire OS 7 = API 28, Fire OS 8 = API 29/30, Fire OS 14 = API 31–34, Fire OS 16 = API 35/36. Consider **minSdk 25** if the market data shows no API 23/24 boxes.
- **targetSdk 35** (or 36). Play would require ≥34 for TV from 31 Aug 2026. Sideloaded apps aren't bound, but targeting <24 triggers "built for an older version" warnings, and recent targets give the right FGS/exact-alarm behaviour.
- **Vega OS**: Amazon's 2025+ Fire TV Stick 4K Select / HD run Vega OS (Linux + React Native), which **cannot run Android APKs or sideloads**. State this in the README/FAQ. Supported Fire devices are the Android-based Fire TV Stick 4K (Max), 4K Plus, Cube, Stick (3rd gen), 1st-gen Stick HD, Lite and Fire TV smart TVs on Fire OS 7/8/14. (fact-checked 2026-10-13: confirmed. Vega devices show a software version starting with "1." under Settings > My Fire TV > About. Beware that the 2026 Wi-Fi 6 "Fire TV Stick HD" shares its name with the Fire OS model.)
- **ABIs**: ship **armeabi-v7a and arm64-v8a**. Many TV devices (Fire TV Stick 4K, Onn, CCwGTV) run a **32-bit userspace on 64-bit SoCs**. Google TV requires 64-bit native code *in addition* from August 2026, while still supporting 32-bit devices (fact-checked 2026-10-13: confirmed. From 1 Aug 2026, Play submissions with native code must include arm64). The only native code is FFmpeg (and possibly a bundled SQLite), so build both and use `splits { abi { enable true; universalApk true } }` for sideload APKs (smaller per-ABI downloads + a universal fallback). x86_64 is only for the emulator; include it in debug builds.

### 6.5 Manifest essentials
```xml
<uses-feature android:name="android.software.leanback" android:required="false"/>   <!-- true if TV-only -->
<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
<uses-feature android:name="android.hardware.microphone" android:required="false"/>   <!-- voice search -->
<application android:banner="@drawable/banner_320x180" android:icon=... android:usesCleartextTraffic="true"
             android:networkSecurityConfig="@xml/network_security_config" ...>
  <activity android:name=".MainActivity" android:screenOrientation="landscape"
            android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize|uiMode|density"
            android:supportsPictureInPicture="true" android:launchMode="singleTask">
    <intent-filter>
      <action android:name="android.intent.action.MAIN"/>
      <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
      <category android:name="android.intent.category.LAUNCHER"/>  <!-- Fire TV / phones -->
    </intent-filter>
  </activity>
  <service android:name=".player.PlaybackService" android:foregroundServiceType="mediaPlayback" android:exported="true">
    <intent-filter><action android:name="androidx.media3.session.MediaSessionService"/></intent-filter>
  </service>
```
Cleartext is required: most IPTV is plain HTTP. Use a `network_security_config` with `cleartextTrafficPermitted="true"` and allow user CAs for self-signed panels. Use a 320×180 xhdpi banner (required for the Android TV launcher). Add `configChanges` to avoid Activity recreation on HDMI mode/density changes during AFR switches, since recreation would kill the surface.

### 6.6 Release and distribution
- Signing: one upload key in a hardware-backed store or CI secret (GitHub Actions encrypted secrets + `signingConfigs` from env). **Never rotate it.** Sideloaded updates require the same signature. Enable APK Signature Scheme v2+v3 (v1 too only if supporting API <24).
- Distribution: GitHub Releases (per-ABI + universal APK) + a short URL/Downloader code (AFTVnews Downloader is the standard sideload path on Fire TV). The in-app updater checks a JSON manifest (`version.json` on GitHub Pages: versionCode, URL, sha256, changelog), downloads via OkHttp to `cacheDir`, verifies SHA-256 and installs with **`PackageInstaller` session API** (`REQUEST_INSTALL_PACKAGES` permission; the user must enable "Install unknown apps" for The Box once). Android 12+ `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` allows silent updates for self-updates when the app is the installer of record.
- Crash reporting without Play Services: ACRA or Sentry (works on Fire TV); Firebase Crashlytics needs Play Services on some features **(verify)**.

### 6.7 Testing strategy
- **JVM unit tests**: M3U parser, XMLTV parser (fixture files including malformed/huge cases), stable-key diffing, rule engine, time-window math, the URL resolver and header parsing.
- **Robolectric**: Room DAOs (or use the in-memory `androidx.sqlite` bundled driver on JVM), WorkManager workers (`work-testing`), and key-handling logic in ViewModels.
- **Media3 test utilities**: `media3-test-utils` and `media3-test-utils-robolectric` (`TestPlayerRunHelper`, `FakeDataSource`) to test the 403 → re-resolve → retry flow, LoadErrorHandlingPolicy and BehindLiveWindow recovery deterministically. Run a local MockWebServer serving looping TS/HLS fixtures (generated with ffmpeg) for integration tests.
- **Instrumented**: Android TV emulator image (API 34 Google TV, x86_64/arm64 on Apple Silicon, which also has a 64-bit userspace) with Compose UI tests using `performKeyInput { pressKey(Key.DirectionDown) }` to assert focus traversal in the guide.
- **Macrobenchmark** (`androidx.benchmark:benchmark-macro-junit4`) on **real low-end hardware** (Onn 4K, Fire TV Stick 4K, CCwGTV HD): `FrameTimingMetric` for guide scroll (UiAutomator `device.pressDPadDown()` × 100 with a mini preview playing a local TS loop), `StartupTimingMetric` cold/warm, and zap time (custom trace section `Trace.beginSection("zap")` measured with `TraceSectionMetric`). Generate the **Baseline Profile** from the same journeys. Track results in CI per release.
- Manual matrix: AFR on 50/60 Hz TVs, passthrough with an AVR/soundbar, CEC standby/resume, 1-connection accounts.

---

## Sources

- Compose for TV release notes (tv-material 1.1.0, tv-foundation 1.0.0; TV lazy layouts deprecated in alpha11 and removed in alpha12): https://developer.android.com/jetpack/androidx/releases/tv
- Migrate from Leanback to Compose for TV: https://developer.android.com/training/tv/playback/leanback/migrate-to-compose
- Use Jetpack Compose on Android TV: https://developer.android.com/training/tv/playback/compose
- AndroidX stable channel: https://developer.android.com/jetpack/androidx/versions/stable-channel
- Media3 1.10 announcement (Mar 2026): https://developer.android.com/blog/posts/media3-1-10-is-out and https://android-developers.googleblog.com/2026/03/media3-110-is-out.html
- Media3 1.10.0 release notes: https://github.com/androidx/media/releases/tag/1.10.0
- Media3 1.9.0 what's new (Compose Material3 UI, ContentFrame): https://android-developers.googleblog.com/2025/12/media3-190-whats-new.html
- Media3 1.10.1 changelog excerpt (tunneling audio-session fix): https://dependabot.ecosyste.ms/hosts/GitHub/repositories/Writeopia%2FWriteopia/issues/620
- ExoPlayer troubleshooting (TS flags, cleartext, BehindLiveWindow): https://developer.android.com/media/media3/exoplayer/troubleshooting
- DefaultTsPayloadReaderFactory reference: https://developer.android.com/reference/androidx/media3/extractor/ts/DefaultTsPayloadReaderFactory
- ExoPlayer issue #7560 (non-IDR keyframes in TS, MP2 audio): https://github.com/google/ExoPlayer/issues/7560
- ExoPlayer FFmpeg extension README (build_ffmpeg.sh, ENABLED_DECODERS): https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg
- Match content frame rate (TV): https://developer.android.com/training/tv/playback/framerate
- Frame rate API guide: https://developer.android.com/media/optimize/performance/frame-rate
- Foreground service types (mediaPlayback incl. DVR on Android TV; mediaProcessing 6h limit): https://developer.android.com/develop/background-work/services/fgs/service-types
- Room 3 release notes: https://developer.android.com/jetpack/androidx/releases/room3
- AGP 9.0 release notes (built-in Kotlin): https://developer.android.com/build/releases/agp-9-0-0-release-notes
- Google Play target API requirements (TV ≥ API 34 from 31 Aug 2026): https://developer.android.com/google/play/requirements/target-sdk
- 64-bit compatibility for Google TV / Android TV (Aug 2026): https://android-developers.googleblog.com/2025/08/64-bit-app-compatibility-for-google-tv-android-tv.html
- Fire OS overview / Fire OS 8 (API levels): https://developer.amazon.com/docs/fire-tv/fire-os-overview.html and https://www.developer.amazon.com/docs/fire-tv/fire-os-8.html
- Vega OS Fire TV Stick (not Android): https://www.androidauthority.com/amazon-vega-os-fire-tv-stick-3602647/
- SambaLite (smbj usage, SMB2/3, smbj#792): https://f-droid.org/packages/de.schliweb.sambalite/
- Jellyfin Android TV playback rewrite discussion: https://forum.jellyfin.org/t-when-will-the-androidtv-playback-rewrite-be-finished

Known-from-experience (fact-checked 2026-10-13 status in brackets): Media3 has no teletext subtitle support [confirmed]; `DefaultHttpDataSource` blocks cross-protocol redirects by default [not re-checked]; EncryptedSharedPreferences/security-crypto deprecation [confirmed]; Fire TV lacks third-party PiP [refuted: Fire OS 6 documents in-app PiP]; exact-alarm permission behaviour on Android 12–14; OkHttp 5 stable [confirmed, 5.5.0; fast fallback is the default]; smbj/jcifs-ng relative performance [unverified]. See docs/review/fact-check.md. Validate these during spike work.
