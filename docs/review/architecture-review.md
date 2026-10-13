# The Box: Adversarial Review of the Android TV Architecture Proposal

Reviewer stance: a senior Android TV / media engineer trying to break `docs/research/android-tv-architecture.md` (the "proposal"). I checked it against `01-design-reference.md` (the "design"), `protocols-m3u-xmltv-xtream.md` (the "protocol doc") and `user-issues-and-mitigations.md` (the "issues doc"), on the target hardware: Fire TV Stick 4K 1st gen (MT8695, 4×A53, 1.5 GB, **Fire OS 6 = API 25**), Fire TV Stick 4K Max (Fire OS 7/8, API 28/30), Onn 4K (S905Y4, Mali-G31 MP2, 2 GB, API 31–34), Chromecast with Google TV (S905D3G, 2 GB, API 31–34), with Shield as the high end.

Review date: 2026-10-13. I verified these facts during the review. Everything else marked **(verify)** still needs checking.
- **Media3 latest stable is 1.11.1 (2026-09-10).** 1.11.0 shipped 2026-08-05. The proposal pins 1.10.1, which is now one minor release behind.
- Media3 raised `minSdk` to 23 in 1.9.0.
- `ExoPlayer.Builder.experimentalSetDynamicSchedulingEnabled` dates from 1.8.0 and is not new in 1.10.
- `ContentFrame` arrived in `media3-ui-compose` 1.9.0. That release also carries an API 34 workaround for `PlayerSurface`/`ContentFrame` with `SURFACE_TYPE_SURFACE_VIEW`, and 1.11.0 fixed first-frame sizing when `ContentFrame` recomposes mid-playback.
- `ExoPlayer.setScrubbingModeEnabled` exists (since 1.8.0).
- The official `decoder_ffmpeg` module is **audio-only** (`FfmpegAudioRenderer`, FFmpeg `release/6.0`). There is no official FFmpeg video renderer.
- Compose `AndroidExternalSurface` with `AndroidExternalSurfaceZOrder.Behind`/`MediaOverlay`/`OnTop` is a separate window layer, so Compose `clip`/`graphicsLayer` cannot shape it. `AndroidEmbeddedExternalSurface` (TextureView-backed) can be clipped but costs GPU composition.

---

## (A) Decisions I agree with

1. **One `ExoPlayer` that lives longer than the screens and sits in a `MediaSessionService`.** A second player would take a second provider connection and a second decoder instance, which is fatal on 1-connection accounts and on SoCs with a single HEVC/secure decoder. This is correct.
2. **Video in a `SurfaceView` placed *behind* the UI, with a transparent "hole" in the guide.** On Amlogic and MediaTek the video goes to a dedicated hardware video plane that is *always* below the OSD plane. "Video below, UI above with a hole" is the only layering these SoCs compose for free, so the proposal matches the hardware. (B1 covers the details it got wrong.)
3. **EPG grid as one node with virtual focus over programmes, not composables per cell.** Nested `LazyColumn{LazyRow}` is a known dead end. Focus movement anchored on time (programme start + 1 min) is exactly the design's interaction model.
4. **Scroll offsets are read only in the draw phase.** This is the most important Compose rule for this screen.
5. **A separate `epg.db` that can be rebuilt and is excluded from backup**, with programme times as epoch minutes and deduplicated text. The PK `WITHOUT ROWID (channel, start)` clusters each channel's rows together. This is the right physical layout.
6. **User data is keyed by `(playlist_id, stable_key)`, and missing channels get a `missing_since` grace period.** This directly prevents the "lost favourites after refresh" trust-breaker (#6 in the issues doc).
7. **Streaming parsers (`XmlPullParser`, Okio line reader) with gzip/xz detected by magic bytes**, a hand-written XMLTV time parser, and inserts through compiled statements.
8. **`OkHttpDataSource` + `ResolvingDataSource` for per-channel headers**, re-resolving from the *original* URL on retry, with DoH through `okhttp-dnsoverhttps`.
9. **Tunneling off by default.** Build FFmpeg *audio* for MP2/LATM/AC-3/E-AC-3/DTS with `EXTENSION_RENDERER_MODE_ON`.
10. **DVR as a raw TS byte-copy** rather than Transformer or `DownloadManager`. Use `mediaPlayback` FGS, exact alarms re-armed on boot, smbj, and SAF with a fallback to `getExternalFilesDirs()`.
11. **TIF, Watch Next and preview channels are out of v1.** This is correct.
12. **armeabi-v7a + arm64-v8a.** Fire TV and Onn run 32-bit userspace.

---

## (B) Decisions I would change

### B1. The mini player and full-screen surface (single SurfaceView "hole")

**B1.1 Rounded corners (radius 20) and the 1 px ring cannot be done by clipping the SurfaceView.**
- *Problem:* The proposal says nothing about this. A `SurfaceView` / `AndroidExternalSurface` is its own layer, so `Modifier.clip(RoundedCornerShape(20))` has no effect on it. On API < 31 there is no supported way to clip a SurfaceView at all.
- *Alternative:* **Draw the corners in the UI layer.**
  - The ambient background composable draws everything *except* a rounded-rect hole. Use `Path` with `PathFillType.EvenOdd`, or draw the background and then `drawRoundRect(..., blendMode = BlendMode.Clear)` inside a `graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)` that holds **only the background**.
  - The SurfaceView rect is placed 0.5 px inside the hole. The anti-aliased corners then blend background over video.
  - Draw the ring (`.14` white) and the shadow (`0 30 80 rgba(0,0,0,.55)`) around the hole in the same layer.
  - This works on API 25 through 35 and costs one extra full-screen layer only when the background changes. Cache it (B1.3).

**B1.2 Never put the SurfaceView under an ancestor with `graphicsLayer` alpha, clip, offscreen compositing or `RenderEffect`.**
- *Problem:* Hole-punching becomes unreliable when an ancestor composites offscreen, and tv-material 1.0.1 already fixed one such bug. The proposal mentions the bug but not the rule.
- *Alternative:* Use this exact tree:
  ```
  Box(Modifier.fillMaxSize()) {
      AmbientBackground(holeRect)                       // draws bg, clears rounded hole
      PlayerSurface(player, SURFACE_TYPE_SURFACE_VIEW,  // sized to the video's aspect inside holeRect
                    Modifier.layout { placeAt(videoRect) })
      AppContent()                                      // must be transparent over holeRect; no ancestor graphicsLayer
  }
  ```
  `AppContent` screen transitions must fade their *own* content, not a parent layer that contains the surface.

**B1.3 Glass panels and backdrop blur: drop real-time blur entirely.**
- *Problem:* Backdrop blur needs `RenderEffect` (API 31+), so it doesn't exist on Fire OS 6/7/8, which are API 25/28/30. Libraries such as Haze fall back to a scrim below API 31 **(verify)**. Even on API 31+, a blur of radius 30 over a 1792×460 region every frame on a Mali-G31 MP2 is very expensive while a decoder is running. And the video is in another layer, so you *cannot* blur the video at all.
- *Evidence:* Look at the design geometry. The mini player spans y = 56…461 and the glass guide panel starts at y = 500, so **nothing glassy overlaps the video**. The only thing "behind" the glass is the ambient background, which is already a blur of 140–160 px. Blurring an already-blurred, low-frequency field is visually a no-op.
- *Alternative:* `glass.panel` becomes a flat `Color(0x0BFFFFFF)` fill + `.09` border. The Record button's `blur 20` becomes a flat `0x1AFFFFFF` fill. The result is pixel-indistinguishable over the ambient field, with zero blur cost. Write it into the design tokens as "glass = translucent fill on all devices", so nobody adds Haze later.

**B1.4 The ambient colour blobs should come from a cached, low-resolution bitmap, not live blur.**
- *Problem:* Two ellipses (1300×1000 and 1100×900) blurred at 140/160 px, plus a gradient overlay and a 1.2 s colour transition, mean three full-screen translucent layers redrawn for 72 frames whenever you tune. At 1080p that is about 6 Mpx per frame of blended fill, on top of the guide's own overdraw. That is about half of a G31's realistic fill budget during the exact moment the decoder is spinning up.
- *Alternative:* Approximate a Gaussian with `Brush.radialGradient` (6–8 stops along an exp(-r²) falloff).
  - Render the background **once per hue** into a 480×270 `ImageBitmap` (an offscreen `Canvas`), then `drawImage(..., filterQuality = FilterQuality.Low)` scaled 4×. Bilinear upscaling of a blurred field is invisible.
  - A hue change crossfades two cached bitmaps by alpha in the draw phase, so nothing recomposes.
  - In low-RAM mode (`ActivityManager.isLowRamDevice()` or `memoryClass ≤ 192`), cut the transition to 300 ms or drop it.
  - Take the hue from `androidx.palette` on the downsampled channel logo. Compute it once at logo load and persist it in `channel_prefs.hue`, falling back to a hash of the stable key.
  - **Never** sample the video frame. `PixelCopy` on a SurfaceView costs a GPU readback, returns nothing useful for DRM/secure surfaces, and gives the wrong colours for HDR.

**B1.5 Resizing between the mini player and full screen.**
- *Problem:*
  - Animating `videoRect` frame-by-frame produces a SurfaceControl transaction every frame. On API < 29 the hole and the video layer can drift apart by a frame (a visible "tear" at the edges), and some Amlogic video planes re-scale with a one-frame glitch.
  - The proposal also leaves aspect handling unspecified. With `MediaCodec`'s default `VIDEO_SCALING_MODE_SCALE_TO_FIT`, video *stretches* to the surface. So the SurfaceView must be sized to the video aspect inside the hole, and the letterbox bars must be drawn by the UI as opaque black. A transparent bar shows the black of "nothing behind the window", which is fine on some devices and garbage on others.
- *Alternative:*
  - (a) Mini to full: a **two-step change with a cover**. Fade a 120 ms black scrim over the hole, snap `videoRect` to full screen in one layout pass, then fade the scrim out after `Player.Listener.onRenderedFirstFrame` or after 2 frames. Make the animated version a Shield-only "fancy transitions" flag.
  - (b) Compute `videoRect` from `VideoSize.pixelWidthHeightRatio`/width/height and the user aspect mode (Fit/Fill/Zoom/16:9/4:3). Clear only `videoRect` in the background layer and paint the remaining part of the hole `#000`.
  - (c) For Zoom/Fill, set `ExoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)` and size the surface to the hole.
  - (d) Use `PlayerSurface` (plain, sized by us) rather than `ContentFrame`, because we need control of the rect. Pin Media3 **1.11.1**, which includes the `ContentFrame`/`PlayerSurface` SurfaceView sizing fixes.

**B1.6 HDR and Dolby Vision inside the mini window are a real problem the proposal treats as a non-issue.**
- *Problem:*
  - On Shield, Fire TV and most Google TV boxes, an HDR10/HLG/DV stream decoding to a visible SurfaceView makes the display **switch to HDR output**. That is a 1–3 s HDMI blank on many TVs, *while the user is browsing the guide*. The SDR guide UI is then composited into an HDR signal, so it looks dim or washed out.
  - Pressing OK on a 4K HDR channel in the guide therefore blanks the TV. Dolby Vision profile 5 on a non-DV sink produces green/purple video.
- *Alternative:* A `HdrPolicy` in `PlaybackController`.
  1. If `Format.colorInfo` is HDR and the UI mode is GUIDE, either (a) show the mini window with a "HDR · press OK for full screen" poster and keep the stream *audio-only* by disabling the video track (`TrackSelectionParameters.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)`, which keeps the connection and avoids the HDMI switch), or (b) pick an SDR variant if the channel has one (variant grouping from issues-doc §11). It becomes a setting: "HDR in preview: Poster / Allow".
  2. In full screen, allow HDR and DV only if `Display.getHdrCapabilities().supportedHdrTypes` contains the type. Otherwise prefer a non-DV track or fall back to the HEVC base layer. This needs a spike (D6).
  3. Never TextureView for HDR. A TextureView composites as SDR 8-bit through the GPU, so HDR becomes clipped SDR.

**B1.7 TextureView for the mini window only: reject as the default, keep it as a probe.**
- *Problem:*
  - Switching surface type between mini and full means `setVideoSurface` swaps, which can reconfigure the decoder, flash black, or fail on some Amlogic codecs that don't support `setOutputSurface`.
  - TextureView puts every 50/60 fps frame through the app's render thread and GPU. That is exactly the fill rate the guide needs on a G31.
  - It also kills HDR, kills tunneling, and loses the hardware video plane's scaler and deinterlacer on some SoCs.
- *Its single advantage:* real clipping, alpha and blur. B1.1 to B1.4 make that unnecessary.
- *Alternative:* SurfaceView everywhere. Keep `AndroidEmbeddedExternalSurface` only behind a hidden debug flag for the D1 benchmark.

**B1.8 Frame-rate switching must be gated by UI mode.**
- *Problem:* `ExoPlayer` calls `Surface.setFrameRate` whenever the format changes. Seamless switches are harmless, but the proposal's "Always" path (`preferredDisplayModeId`) would trigger an HDMI re-sync every time the user tunes a 50 Hz channel *from the guide*.
- *Problem (target hardware):* `Surface.setFrameRate` is API 30+. The primary low-end target, Fire TV Stick 4K 1st gen on API 25, *only* has `WindowManager.LayoutParams.preferredDisplayModeId` (API 23+), plus Fire OS's own "Match original frame rate" toggle. The proposal treats the API < 30 path as a footnote, but it is the main path for half the install base.
- *Alternative:* Write a `DisplayModeController`.
  - Apply non-seamless switches only in FULLSCREEN, after a **2 s dwell** with no zap.
  - Never apply them in GUIDE mode. Keep the content mode when returning to the guide (don't ping-pong), and revert to the UI default mode after 30 s idle in the guide or on exit.
  - Get fps from `Format.frameRate`, and if it's missing, estimate from `VideoFrameMetadataListener` presentation-time deltas over 30 frames.
  - Cache per-channel fps in `channel_prefs` so the next tune switches immediately.
  - Call `play()` only after `DisplayManager.DisplayListener.onDisplayChanged` or a 3 s timeout.

### B2. The EPG grid

**B2.1 The proposal's grid geometry does not match the design.**
- *Problem:* The proposal assumes 10 dp/min and 64 dp rows.
- *Design numbers:*
  - Grid inner width is 1792 − 2×16 padding − 200 label column = **1560 px for 180 min = 8.67 px/min**, so a 30-min tick is 260 px.
  - Row pitch is 60 + 8 = **68 px**.
  - Body height is 460 − 48 header − 12 padding = 400 px, so **5.9 rows are visible**, not 7 to 10.
- *Consequences:*
  - The data window can be far smaller than ±20 channels.
  - Vertical paging with CH+/CH− moves only 5 rows, so add "page = 5 rows" and a fast-scroll mode.
  - A 30-min programme is 260 px wide, so 18 px titles fit about 18 characters. Short 5–10 min programmes (news bulletins, idents) are 43–87 px wide, so the cell needs a "narrow" rendering: no time line, title only, and a 1-char ellipsis below 40 px.

**B2.2 Units: "px at 1080p = dp" is wrong on Android TV.**
- *Problem:* 1080p Android TV is xhdpi (density 2.0), so the screen is **960×540 dp**. The design doc's instruction to use px as dp would render the 720 px mini player at 1440 px. 720p devices (Fire TV Stick Lite, older boxes) are 1280×720 at about tvdpi and also 960×540 dp. The proposal doesn't catch this.
- *Alternative:* Provide a root `CompositionLocalProvider(LocalDensity provides Density(density = windowWidthPx / 1920f, fontScale = 1f))`. Every value in the design doc is then literally `x.dp`, and it is resolution-independent. Do the same `pxPerDesignUnit` math inside the custom grid. Convert the oklch tokens to sRGB constants at design-token generation time. Bundle Instrument Sans and JetBrains Mono, and use `fontFeatureSettings = "tnum"` for the clock and time rows.

**B2.3 A single custom Compose node is realistic. Specify it as `Layout` + `drawWithCache`, not `Canvas`.**
- One composable owns `GuideState`: `scrollXMin: Float`, `scrollYRow: Float`, `focus: (row, programmeIdx)`, `anchorMin: Int`, the window data, and `nowMin`.
- `GuideState` holds `Animatable`s. Key events mutate them, and only `DrawScope` reads them.
- The left channel column is drawn in the *same* node, including logos as `ImageBitmap`s fetched by Coil `ImageLoader.execute` into a small `LruCache<Long, ImageBitmap>`. Driving a separate `LazyColumn` from `scrollY` (the proposal's option) reintroduces composition per scroll step and a focus surface. Avoid it.
- The time ruler, past shading, "now" line and cells all share one translation, so they stay in sync for free.

**B2.4 Text measurement caching: the proposal's `(programmeId, widthBucket)` key churns with sticky titles.**
- *Problem:* The left-edge sticky title changes the visible width of the first cell on every scroll frame. That forces a new bucket every 8 px and allocates a `TextLayoutResult` in the draw pass, which is exactly what we must avoid.
- *Alternative:*
  - Measure each title **once, unconstrained, single line** (`TextMeasurer.measure(text, style, maxLines = 1, softWrap = false)`) and cache it by `textId`. A `TextLayoutResult` holds a `MultiParagraph`, so budget about 1–2 KB each and cap at 1,500 entries, sized by `memoryClass`.
  - At draw time, if `intrinsicWidth ≤ cellInnerWidth`, draw it. Otherwise draw it inside `clipRect` with a 24 px right-edge fade (a `drawRect` with a horizontal gradient in the cell's bg colour). That is cheaper and nicer than "…" and needs no re-measure.
  - Keep a real ellipsized measure only for the *focused* cell, because its solid `#EEF1F6` background makes the fade colour trivial anyway.
  - Time strings (`19:30`) come from a 1,440-entry pre-measured table, one per minute of day.
  - Pre-warm the cache for the next window on `Dispatchers.Default`. `TextMeasurer` is not thread-safe, so use a dedicated instance off-main **(verify)**, or fall back to `StaticLayout` off-thread.

**B2.5 Time navigation while keeping the 180-min density.**
- Keep `pxPerMin` fixed at 8.67 (density is part of the design). Navigation keys:
  - **Left/Right:** move to the previous/next programme. If the target start is out of view, animate `scrollX` so the focused cell starts at the first tick (`floor30(start)`). This keeps tick labels aligned, unlike the proposal's "25 % of viewport". With key repeat, *snap* instead of animating after the 3rd repeat.
  - **`KEYCODE_MEDIA_FAST_FORWARD`/`REWIND`:** ±3 h, i.e. one page.
  - **`KEYCODE_PAGE_UP/DOWN`, `CHANNEL_UP/DOWN`:** ±5 rows.
  - **Long-press Left at the earliest archive or Right:** open a **day strip** (Today, Tomorrow, …, plus past days only when any channel has archive). Choosing a day sets `scrollX` to 18:00 that day (prime time), or to now for Today.
  - **"Jump to now"** is on `KEYCODE_BACK` when the guide isn't scrolled to now (first Back = jump to now, second Back = leave). This is TiviMate's behaviour and what users expect.
- The header label "TONIGHT" becomes dynamic ("Now", "Tonight", "Tomorrow 14:00", "Yesterday"), derived from `scrollX`.

**B2.6 The past, archive and "now" line.**
- Draw the per-row archive band as one rect from `now − archiveDays(channel)` to `now`, at `.03` white, behind the cells. Rows without archive get no band, and their past cells use the `.45` text alpha from the design.
- Past cells inside a channel's archive keep full-opacity text plus a 12 px ↺ glyph drawn from a pre-rasterised `ImageBitmap`. This implements the design's own "slightly more opaque when archive exists".
- Programmes with Xtream `has_archive=0` (from `get_simple_data_table` when fetched) override this.
- The "now" line is drawn last over cells. Its 12 px glow is a pre-rendered 24×N gradient bitmap, not `BlurMaskFilter` (that is slow and software-only on some paths).
- Use a 30 s ticker as proposed, plus a ticker aligned to the next minute boundary so progress bars don't lag up to 30 s at programme changes.

**B2.7 Focus, accessibility and TalkBack: the proposal ignores these, and a single node is invisible to TalkBack and Fire TV VoiceView.**
- *Alternative:*
  - The grid node gets `Modifier.semantics { contentDescription = focusedCellDescription; liveRegion = LiveRegionMode.Polite; role = Role.Button; customActions = listOf(Record, Watch from start) }` and updates it when virtual focus moves, debounced to 300 ms so held keys don't spam.
  - Text: "BBC One, channel 101, Six O'Clock News, 18:00 to 18:30, on now, catch-up available".
  - This is enough for D-pad TalkBack/VoiceView, which follow focus rather than touch exploration.
  - If the View fallback is used, implement `ExploreByTouchHelper` with virtual views for the visible cells. That is one point *in favour* of the View fallback.
- Focus entry and exit:
  - Use `focusProperties { enter = { focusRequester } }` on the guide region, and `onPreviewKeyEvent` for Left at the first column, which releases to the group rail.
  - Up from row 0 goes to the action buttons in the header panel ("Watch now / Record"). The design puts them above the grid, so *Up* must reach them. TiviMate gets there with a different key, so add a focus route.

**B2.8 The focused-programme panel will be the actual jank source, not the grid.**
- *Problem:* Every focus move recomposes the header with a 64 px title using balanced wrapping (`LineBreak.Heading`; balanced breaking is API 33+, and older APIs silently fall back), a 21 px multi-line description and chips. Text layout of the description is 3–8 ms on an A53. Under 20 Hz key repeat that alone blows the frame budget.
- *Alternative:* Update the panel immediately with title and time only (cheap). Load the description from `epg_text` lazily and render it **only after 150 ms without a focus change**. During repeat, freeze the panel. Use `Text(..., maxLines = 3)` and `remember(textId)` for layout. Cache the last 32 panel states.

**B2.9 The fallback plan when Compose can't hold 60 fps while decoding must be concrete, with a decision gate.**
- *Gate:* Benchmark D2 on Fire TV Stick 4K (API 25) and Onn 4K. The target is P90 < 16.7 ms and P99 < 33 ms with 1080i50 H.264 TS decoding in the mini window.
- *Ordered fallbacks:*
  1. Reduce work in Compose. Use snap scrolling (no animation) during key repeat. Drop shadows and focus-ring glow to pre-rendered 9-patch-like bitmaps. Turn the 150 ms cell transition off on low-RAM devices.
  2. Cache each *row* into an `android.graphics.RenderNode` or `Picture` keyed by `(row, windowGeneration)`. `RenderNode` is public only on API 29+, so use `Picture` below that. Scrolling then becomes translate-and-replay.
  3. Move the grid to a custom `View` inside `AndroidView` with the same `GuideState` and draw code. Keep the draw code toolkit-agnostic by writing it against `android.graphics.Canvas` from day one: Compose's `drawIntoCanvas { it.nativeCanvas }` lets one `GuideRenderer` serve both hosts. **This is the important change.** It makes the fallback a 1-day swap instead of a rewrite.
  4. Use a 30 fps "lite" scroll mode on devices failing the gate.

### B3. The data layer

**B3.1 Room for `epg.db` plus "close Room, rename, reopen" is the fragile part.**
- *Problems:*
  - (a) Renaming a WAL-mode database while `-wal`/`-shm` files exist risks corruption or a silently stale read.
  - (b) Room's `InvalidationTracker`, open `Flow`s and the connection pool don't support swapping the file underneath them. You'd have to close the database while guide queries are in flight.
  - (c) Room entity mapping allocates a `List<Entity>` for a query whose consumer wants primitive arrays.
- *Alternative:*
  - **Room (2.8.x with `BundledSQLiteDriver`) for `library.db` only.** **Raw `androidx.sqlite` (`SQLiteDriver`/`SQLiteConnection.prepare`/`SQLiteStatement.step`) for `epg.db`**, behind an `EpgStore` interface.
  - Use **generation-named files**: `epg-<gen>.db`. To build:
    1. Open `epg-<gen+1>.db` with `journal_mode=OFF`, `synchronous=OFF`, `locking_mode=EXCLUSIVE`, and create the PK and indexes *after* the bulk insert.
    2. `ANALYZE`, then close.
    3. Publish `gen+1` atomically to `AtomicReference<EpgReader>` in a small `epg-current` file (written via temp + rename).
    4. Close the old reader when its ref-count drops to zero, then delete it.
  - No rename of a live database, and no WAL on a file that is read-only after build. Open readers with `SQLITE_OPEN_READONLY` and `query_only`.
  - The guide's `EpgStore.window(...)` fills a reusable `IntArray`/`LongArray` buffer directly from `SQLiteStatement`, with zero entity allocation.
- *SQLDelight?* No. It adds a second ORM and Gradle plugin for no gain over one raw-SQL class.

**B3.2 The schema needs these fixes.**
- *Overlapping starts:* `PRIMARY KEY(epg_channel_id, start_min)` rejects duplicate starts, which do happen in bad XMLTV. Use `INSERT OR REPLACE` *after* overlap clipping in the importer (a per-channel sort in a 1-channel buffer). Programmes in XMLTV are usually grouped by channel but not guaranteed to be, so handle the out-of-order case with an `ORDER BY` repair pass. Alternatively, buffer per channel in an external sort keyed by `(channel, start)` in a temp table and copy it in PK order.
- *Programme length bound:* `maxProgrammeLen = 12 h` is wrong for real data. 24 h "No information"/"Off air" placeholders and multi-day "loop" programmes exist. Clip stored programmes at 6 h segments (split long ones) so the PK range scan stays bounded, or store `max(end-start)` per channel in `epg_channel`.
- *Text dedup:* Use `epg_text.hash` as a 64-bit hash of `title|subtitle|desc`, and use `androidx.collection.MutableLongIntMap` in the importer, **not** `HashMap<Long, Int>`. At 300k unique texts that's about 5 MB versus about 25 MB of boxed objects.
- *FTS:* Index **title and subtitle only**. FTS over descriptions roughly doubles `epg.db` size, and users search titles. `ENABLE_FTS5` in the bundled SQLite is not confirmed, so check with `PRAGMA compile_options` and fall back to FTS4 otherwise **(verify)**.
- *Stable references:* No other DB may reference `programme` or `epg_text` row ids, because they're regenerated on every build. Reminders, recordings and "watched" markers reference `(stable_key, start_min, titleHash)`.

**B3.3 The import whitelist makes manual EPG mapping require a re-import.**
- *Problem:* The proposal (rightly) drops programmes for unmapped channels. But when the user maps a channel manually (TiviMate's *Channel → EPG*), there is no data for it until the next 12–24 h refresh. The mapping UI also needs to list *all* EPG channels of all sources.
- *Alternative:*
  - Always store `epg_channel` for every `<channel>` element in every source. It's small: 50k rows of id + display names.
  - Keep the last downloaded compressed XMLTV in `cacheDir/epg/<source>.xml.gz`, which is 30–80 MB compressed. On Fire TV with about 4 GB free, make that a setting and keep it by default only if free space is over 1 GB.
  - A new mapping triggers a *targeted* re-parse of the cached file for just those channels. That is one streaming pass with a tiny whitelist, about 20–40 s on an A53, run in the background.

**B3.4 Disk and memory budget on 1.5 GB devices.**
- *Disk peak:* A build-and-swap needs **2× `epg.db` during the build**, plus the cached `.gz`. Check `StatFs` before the build. If free space is below 2.5× the current DB, fall back to the in-place per-channel `DELETE`+`INSERT` path, which the proposal mentions as an alternative. Keep both.
- *Heap:* Fire TV Stick 4K has a heap limit of about 192–256 MB **(verify per device)**. Proposed steady-state budget:

| Consumer | Budget |
|---|---|
| ExoPlayer `DefaultAllocator` (Java heap!) | ≤ 24 MB low-RAM / 48 MB normal via `setTargetBufferBytes` + `prioritizeTimeOverSizeThresholds(false)` |
| Compose runtime and slot tables (whole app) | ~25–35 MB |
| Guide window (6 visible rows ±15 rows × ±6 h) | < 1 MB of primitive arrays |
| Text layout cache | ≤ 4 MB (1,500 entries) |
| Logos (Coil memory, `RGB_565` where opaque; bitmaps are native on API 26+ but **Java heap on API 25**) | 12 MB low-RAM / 32 MB normal |
| Channel list of current group (20k worst case) | ~4 MB as SoA arrays; names interned |
| Importer (same process, background thread) | ≤ 20 MB (64 KB buffers, `MutableLongIntMap`, 5k-row transactions) |

- *Logos on API 25:* Bitmaps live on the Java heap on the API 25 Fire TV Stick 4K. The proposal's "size Coil from memoryClass" is necessary but not sufficient, because logos must also be decoded at **exact draw size** (`Size(96, 54)`).
- *Process:* Run the importer in the main process at `THREAD_PRIORITY_BACKGROUND`, and **pause it during a zap** (a `Mutex` that the zap holds for 3 s), because eMMC and CPU contention measurably slows zaps on 4×A53. A separate `:sync` process adds 30+ MB RSS and invites the LMK. Don't.

**B3.5 Playlist diff and stable keys need small but important fixes.**
- *The `url:` key:* Strip *both* credentials and host/port, because providers rotate DNS/LB hosts (protocol doc §3.4). Use `"url:" + lastPathSegments(2..)` with the token query removed.
- *Xtream across a re-created line:* `stream_id` is stable per panel, but when a provider migrates panels the ids change wholesale. Detect a mass mismatch (more than 70 % of keys missing *and* more than 70 % new) and offer "Remap favourites by name". The protocol doc asks for this, and the proposal omits it.
- *Normalised name in the `tvg:` key:* A provider renaming "BBC One HD" to "BBC One FHD" breaks the key. Normalise by stripping quality tokens, using the same function as EPG fuzzy matching (protocol doc §2.5).

### B4. Live playback tuning

**B4.1 LoadControl: the numbers are tuned for the wrong variable.**
- *Problem:* For endless live TS, the server delivers at roughly real-time rate after an initial burst (Xtream/XUI typically bursts 1–5 s of its own buffer). So `minBufferMs 15 s` / `maxBufferMs 30 s` are rarely reached and mostly don't matter. What determines zap time is `bufferForPlaybackMs` plus keyframe wait plus decoder init. What determines resilience is how much the initial burst gives you and how fast you reconnect.
- *Alternative:* Use three profiles, selectable and adaptive:

| | Fast (default, Ethernet/good Wi-Fi) | Balanced | Robust |
|---|---|---|---|
| `bufferForPlaybackMs` | **500** | 1 000 | 2 500 |
| `bufferForPlaybackAfterRebufferMs` | 2 000 | 3 000 | 5 000 |
| `minBufferMs` / `maxBufferMs` | 10 000 / 20 000 | 15 000 / 30 000 | 30 000 / 60 000 |
| `targetBufferBytes` | 24 MB low-RAM, 48 MB otherwise | same | same |
| HLS `LiveConfiguration.targetOffsetMs` | 3×target duration, min 6 s | 4× | 5× |

  - Adapt automatically: 2 rebuffers within 10 min on a channel moves up one profile *for that playlist*, and 24 h without rebuffer moves back down.
  - For catch-up and VOD use the proposal's VOD column.
  - Keep `backBufferDurationMs = 0` for live TS. The v1 pause/rewind story is B5.4, not back buffer.

**B4.2 Live TS EOF is handled as "ended". This is a P0 omission.**
- *Problem:* Endless TS over `ProgressiveMediaSource`: when the server closes the socket (routine, per the protocol doc's "Reconnect on EOF"), ExoPlayer reaches `STATE_ENDED`. The proposal has no handler for it. The loader's default retry on `IOException` also resumes with a **Range request** from the byte offset, which live TS endpoints either ignore (sending fresh live bytes, so PTS jumps) or reject with 416. This is exactly the IPTVnator bug from the issues doc.
- *Alternative:* Write a `LiveTsDataSource` wrapper over OkHttp.
  - It always opens *without* `Range`, ignores `DataSpec.position`, and reports `C.LENGTH_UNSET`.
  - On a read `IOException` or EOF it **transparently reconnects up to 3 times within 3 s**, re-resolving the URL through `StreamUrlResolver` for a fresh token, and keeps feeding bytes. `TsExtractor` resyncs on 0x47.
  - If the PTS gap after a reconnect exceeds 2 s, it throws a typed `LiveDiscontinuityException`. The `PlaybackController` catches it, along with `STATE_ENDED` on a live item, and does a full `prepare()` at live.
  - Prototype it in D4, because the PTS behaviour across a reconnect must be measured, not assumed.

**B4.3 The stall watchdog needs three concrete detectors.** The proposal has only "BUFFERING > N s while bytes arriving".
1. **Network stall:** no bytes for 4 s, measured by a counting wrapper in `LiveTsDataSource` (or `TransferListener.onBytesTransferred`). Cancel the OkHttp `Call` (`call.cancel()` unblocks the read immediately, so don't wait for the 20 s read timeout) and reconnect. Also set the live-stream read timeout to 8 s, not 15–20 s.
2. **Decoder stall:** `STATE_BUFFERING` > 6 s while bytes *are* arriving (PCR/PTS reset, no keyframe) means a full re-prepare. On the second occurrence for the same channel within 5 min, retry with `FLAG_ALLOW_NON_IDR_KEYFRAMES` (compat mode) and remember it per channel.
3. **Frozen picture:** `STATE_READY` and playing, but `DecoderCounters.renderedOutputBufferCount` hasn't advanced for 3 s while audio position advances (polled from `ExoPlayer.getVideoDecoderCounters()` once a second). Re-prepare. This catches the "audio plays, video frozen" class that never errors.
- Equivalent **audio watchdog:** video renders but `AnalyticsListener.onAudioUnderrun` storms or audio position is stuck for 3 s after the first frame. Disable passthrough for this sink (`DefaultAudioSink` built with `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES`), rebuild the renderers and toast "Switched to PCM audio". Persist the result per (device route, codec).

**B4.4 Error handling per HTTP status is incomplete.**
- *Problem:* The proposal makes 404 non-retryable everywhere and handles 401/403 alike.
- *Alternative:* Map statuses as follows (this follows protocol-doc §5.4):

| Signal | Action |
|---|---|
| 401, or `user_info.auth=0`/status ≠ Active | Re-query `player_api.php`. If the account is expired, banned or disabled, show the account screen with `exp_date`. **No retry loop.** |
| 403 / 458 / 509 / 503 *within 5 s after a zap* | Treat as a connection limit. Wait for the previous lease to close (B4.5), then retry at 1 s and 3 s. After that show "Provider connection limit (x/y in use)". |
| 403 mid-playback | Re-resolve once (token expiry), then show a UA/Referer hint. |
| 405/406 (XC wrong output) | Flip `.ts`↔`.m3u8` and persist it per playlist. |
| 404 top-level | Try the next source in the channel's failover list, then show "Channel offline". |
| 404 on an **HLS segment** | Retry ×2 at 500 ms. This is normal CDN lag, and the proposal's policy would kill healthy streams. |
| 200 with HTML/tiny body | Sniff it and surface "provider returned a web page (expired?)". |

**B4.5 Max-connections semantics: `player.stop()` does not guarantee the socket is closed before the next open.**
- *Problem:* `stop()` is asynchronous on the playback thread. The loader thread may be blocked in `read()` until the read timeout and only then call `DataSource.close()`. Meanwhile the new `prepare()` opens a second connection, and the server sees 2/1.
- *Alternative:* Write a `StreamConnectionBroker` per playlist, owned by `:player` and later shared with `:dvr`. It has `maxConnections` (from `user_info.max_connections`, user-overridable) and a registry of live OkHttp `Call`s, tagged by playlist via `Request.tag()` in `LiveTsDataSource`.
  - **Zap:** (1) `broker.preempt(playlistId)` calls `Call.cancel()` on the active playback call, which is synchronous socket close. (2) Wait for the lease release, with a 300 ms timeout. (3) Open the new call.
  - **Lingering server sessions:** Some panels count a closed session for 2–60 s. If the first open returns a limit-type error, retry after 1 s and 3 s, as above.
  - **Mini player on 1-connection accounts:** In this design the mini player *is* the current stream, so it costs no extra connection. The proposal and protocol doc both worry about this needlessly. What *must* be forbidden on `max==1` is any **second** consumer: focus-to-preview (not in the design, so don't add it), next/previous-channel pre-connect (the issues doc suggests it), multi-view, recording while watching a different channel, and EPG/logo fetches through stream endpoints.
  - **Held CH+/CH− keys:** With CH+/CH− held, **debounce the actual tune by 350 ms** and show only the banner while repeating. Otherwise 5 quick zaps make 5 connections, and the panel bans you.

**B4.6 Zap latency under 1.5 s needs a budget and specific techniques.**
- *Budget on Fire TV Stick 4K, TS, warm DNS:*

| Step | Time |
|---|---|
| Close previous | ≤ 50 ms (B4.5) |
| HTTP request plus 302 to the LB | 150–400 ms. Keep an OkHttp connection to the *login host* warm via a 25 s keep-alive ping on `player_api.php`. Never cache the redirect target. |
| First bytes to PAT/PMT | 50–150 ms |
| Keyframe | 0–2 000 ms. Most panels start at a GOP boundary. Otherwise use compat mode. |
| `bufferForPlaybackMs` | 500 |
| Codec init | 100–300 ms |

- *Target:* P50 ≤ 1.2 s and P90 ≤ 2.0 s on Fire TV Stick 4K. P50 ≤ 0.8 s on Shield.
- *Techniques:*
  - Reuse the player and the surface. Never show a black shutter: keep the last frame, which SurfaceView keeps by default when the codec flushes or reconfigures. Measure it in D3 because some SoCs blank.
  - Overlay the channel logo and a spinner after 400 ms.
  - Prefer TS over HLS for live (HLS needs playlist + segment = 2 RTTs + segment download).
  - Measure each phase with `Trace.beginAsyncSection("zap", id)` from key-down to `onRenderedFirstFrame`, and keep the last 50 zaps in diagnostics.

**B4.7 Decoder fallback ordering: there is no FFmpeg video, so the "SW" video fallback is the platform's software MediaCodec.**
- *Video:* `DefaultRenderersFactory.setEnableDecoderFallback(true)`, plus a custom `MediaCodecSelector` that lists hardware decoders first (`MediaCodecInfo.hardwareAccelerated`, API 29+, or a name heuristic `OMX.google.`/`c2.android.` = software below that), then `c2.android.*`/`OMX.google.*`.
  - Warn that the software path loses deinterlacing and HDR, and can't do 1080p50 HEVC on an A53.
  - "Force software video" is a per-channel debug option, not a user-facing "HW/HW+/SW" triad that pretends SW is a real option for 1080i.
  - If a true SW video path is ever required (e.g. MPEG-2 on a SoC without HW MPEG-2), evaluate a **libVLC engine fallback** or the Jellyfin `media3-ffmpeg-decoder` fork **(verify video support)**. That is phase 2, behind an "Alternative player engine" setting.
- *Audio:* Platform MediaCodec/passthrough first (`EXTENSION_RENDERER_MODE_ON`), then FFmpeg audio. Force `PREFER` automatically for MP2 and LATM on devices where `MediaCodecList` has no decoder, which you can detect at startup.

**B4.8 Audio passthrough detection on the actual target APIs.**
- On API 25/28 (Fire OS 6/7), capabilities come from the sticky `AudioManager.ACTION_HDMI_AUDIO_PLUG` intent (`EXTRA_ENCODINGS`), which Media3's `AudioCapabilitiesReceiver` reads. On API 29+ it's `AudioTrack.isDirectPlaybackSupported`, and on 33+ `AudioManager.getDirectProfilesForAttributes`.
- Fire OS also has a user "Dolby Digital Plus: Auto/Off" setting that changes the reported capabilities.
- Re-evaluate on `ACTION_HDMI_AUDIO_PLUG` and on `AudioDeviceCallback` route changes (BT headphones!). Offer "Passthrough: Auto / Off / Force AC-3 only".
- The B4.3 audio watchdog is the safety net for sinks that misreport.

**B4.9 Standby, HDMI-CEC and idle need more than `onStop`.**
- *Problem:* When the *TV* is turned off via its own remote, many Fire TV/Onn devices **don't sleep for minutes or ever**. The app keeps streaming to a dark TV, holding the provider's only connection.
- *Alternative:*
  - Listen for `ACTION_HDMI_AUDIO_PLUG` with state 0 and `DisplayManager` display state OFF, and stop playback after a 30 s grace.
  - Add an **inactivity timer**: no key for 4 h shows "Still watching?", and after 60 s more it stops. This is configurable.
  - On wake or resume, re-auth Xtream (`player_api.php`) before re-preparing, as the proposal says.
  - `FLAG_KEEP_SCREEN_ON` only while `isPlaying && uiMode==FULLSCREEN or GUIDE`.

### B5. Catch-up and time-shift

**B5.1 Introduce one `ArchiveTimeline` abstraction. Catch-up must be a *virtual wall-clock timeline*, not ExoPlayer positions.**
- *Problem:* The proposal doesn't model catch-up playback at all. The catch-up modes differ in seekability.

| Mode | Container | Native seek? | Seek implementation |
|---|---|---|---|
| `xc` path/php `.ts` | progressive TS, finite | Usually **no** (no Range/length) | Re-request with `start = target`, `duration = remaining` |
| `xc` `.m3u8` | HLS VOD/EVENT | yes, within the request | Native seek within; re-request outside |
| `flussonic` fixed range `index-FROM-DUR.m3u8` / `archive-FROM-DUR.ts` | HLS VOD / TS | HLS yes; TS with Range (supported) | Native |
| `flussonic` `timeshift_abs-FROM` | live-like | no | Re-request |
| `shift`/`append`/`default` (`?utc={utc}&lutc=`) | whatever the live URL is | no (TS) / limited (HLS) | Re-request with new `{utc}` |
| `vod` (`{catchup-id}`) | VOD | yes | Native |

- *Alternative:*
  - `CatchupUrlBuilder.build(channel, fromUtc, toUtc): ArchiveRequest(url, headers, nativeSeekRange: LongRange?)`.
  - `ArchivePlayback` keeps `requestStartUtc`. Then `wallClockPosition = requestStartUtc + player.currentPosition`.
  - A seek target inside `nativeSeekRange` becomes `player.seekTo`. Otherwise it's a *new request* at the target, going through the connection broker.
  - At the programme end, continue seamlessly into the next programme with a new request. Kodi's "stops after listed duration" bug shows why. For `xc` TS, request `duration = min(remaining archive to now, 180 min)` so programme boundaries don't force reconnects.
  - When `wallClockPosition` reaches `now − 30 s`, offer "Jump to live", and auto-switch at the end of the archive.

**B5.2 Time zones and corrections need one place and clear rules.**
- `TimeBase` per playlist: `serverZone: ZoneId` (from Xtream `server_info.timezone`, user-overridable), `serverSkewSec = timestamp_now − deviceUtc`, `catchupCorrectionH`, `tokenZone` for `{Y}{m}{d}{H}{M}` (UTC | device | server; default UTC, XC = server), and padding `before 2 min / after 5 min`.
- `xc` formats `fromUtc + correction − padding` in `serverZone` as `yyyy-MM-dd:HH-mm`. `flussonic`/`shift`/`{utc}` use unix UTC seconds.
- `tvg-shift`/EPG shift affects display only and never request times (protocol doc §2.6). The proposal never mentions this distinction.
- **minSdk 23/25 with `java.time` needs core library desugaring** (`coreLibraryDesugaring` `desugar_jdk_libs`). The proposal omits this. Old Fire OS tzdata can also be stale, so prefer the server's own `time_now` against `timestamp_now` to derive the *current* offset when the zone's DST rules look inconsistent. Log a diagnostic when they disagree.
- **Auto-probe:** On the first archive play per playlist, if the request returns 404/empty, try `xc path` → `xc php` → the alternate time format `yyyy-MM-dd:HH-mm-ss`, then remember the winner (protocol doc and issues doc §7). Cache "archive failed" per channel for the session.

**B5.3 The time-shift scrub bar UI should be designed in the design's language.**
- The bar spans the *programme* (start→end) with ticks at programme boundaries ±1. The archive-available range is drawn at `.11` white and the not-yet-aired future is dimmed. The live edge is a red `#FF4D5E` marker.
- Show the playhead's wall clock (`19:42:10`) in mono, plus a "−00:17:50 behind live" label.
- **D-pad scrubbing:** Left/Right steps of 10 s, accelerating to 30 s, 1 min and 5 min as the key repeats. The *visual* playhead moves immediately, and the **commit happens 600 ms after the last key**. One re-request per scrub gesture matters because each non-native seek is a new connection. Use `ExoPlayer.setScrubbingModeEnabled(true)` only for native-seek sources.
- Up/Down on the bar jumps to the previous/next programme start. OK means play, and Back means cancel the scrub and return to the original position.
- Thumbnails: none, since IPTV archives don't provide them. Show programme title tooltips at boundaries.

**B5.4 Pause on live TS in v1: decide it explicitly.** The proposal defers everything to the phase-2 ring buffer.
- *Alternative for v1:*
  - On pause, if the channel has archive, `stop()` (releasing the connection) and remember `pausedAtUtc`. On resume, play the archive from `pausedAtUtc`, which gives TiviMate-like behaviour with no disk buffer.
  - Without archive, pause = stop, and resume = live with a toast "Live resumed (no catch-up on this channel)".
  - HLS live: allow pausing within the playlist window by setting `backBufferDurationMs` to the window for HLS only.

### B6. Modules, DI, SDK, ABI, updater

**B6.1 Seventeen modules is over-engineered for a 1–3 person team. Gradle configuration time and Hilt/KSP per module will dominate the inner loop.**
- *Alternative:* 8 modules:
  - `:app` (Activity, DI root, navigation)
  - `:core` (pure Kotlin: model, time, stable keys, catch-up URL builder, parsers for M3U/XMLTV/Xtream JSON, rules engine, all JVM-tested)
  - `:data` (Room library.db, raw EpgStore, OkHttp, repositories, workers)
  - `:player` (PlaybackController, LiveTsDataSource, broker, watchdogs, AFR, MediaSessionService)
  - `:ui` (design system, guide renderer, screens)
  - `:ffmpeg` (prebuilt AAR)
  - `:baselineprofile`
  - `:benchmark`
- Split `:ui` per feature later if build times hurt. Add `:dvr` in phase 2.

**B6.2 DI: Hilt is fine, but `@Singleton PlaybackController` inside the `MediaSessionService` is a lifecycle contradiction.**
- *Problem:* A Hilt singleton outlives the service, while a service-owned player dies with the service.
- *Alternative:* `PlaybackController` is an `@Singleton` that *owns* the `ExoPlayer` (created lazily, released on `onTrimMemory(TRIM_MEMORY_COMPLETE)` or when app is backgrounded > 5 min). The `MediaSessionService` only *binds* to it to expose a session. Calling `startForeground` is needed only for background audio or recording.

**B6.3 SDK levels: say out loud that the low-end target is API 25.**
- `minSdk 25` (Fire OS 6), not 23. No API 23/24 TV device worth supporting runs Compose well.
- `targetSdk 35`.
- What API 25 means:
  - No `RenderEffect`, no `Surface.setFrameRate`, no public `RenderNode`.
  - `java.time` needs desugaring.
  - Bitmaps are on the Java heap.
  - `PackageInstaller.setRequireUserAction` (API 31) is unavailable on *every* Fire OS device, so updates always require a user tap.
  - Signature scheme v1+v2+v3 (v1 is harmless).
- Pin **Media3 1.11.1**, not 1.10.1.

**B6.4 Harden the in-app updater.**
- *Problem:* A SHA-256 in `version.json` served from the same GitHub Pages as the APK proves nothing if the account or DNS is compromised.
- *Alternative:*
  - Sign `version.json` with an Ed25519 key whose public key is embedded in the app (Tink `Ed25519Verify` or `java.security` on API 33+; Tink works on 25).
  - Before install, `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES or GET_SIGNATURES)` and compare the signing cert digest with our own.
  - Refuse downgrades. Download to `filesDir` (not `cacheDir`, which can be purged mid-install on low-storage sticks) and resume with Range.
  - Ship to Amazon Appstore too only if policy allows. The updater must disable itself when `getInstallSourceInfo`/`getInstallerPackageName` says a store installed it.

### B7. Testing strategy is missing three things

1. **A fake Xtream panel** for integration tests and manual QA. Build it as a MockWebServer/Ktor module that:
   - Enforces `max_connections` with configurable lingering (0–60 s).
   - Serves looping TS fixtures from ffmpeg: H.264 1080i50 + MP2, HEVC Main10 HDR10 + E-AC-3, AAC-LATM, PCR/PTS reset mid-stream, non-IDR start.
   - Supports 302 chains with expiring tokens, 458/509, an HTML "expired" page, `server_info.timezone = America/New_York`, and timeshift endpoints that require server-local time.
   - Also usable from a real device over LAN.
2. **Soak automation on real hardware:** a 12 h run with a zap every 20 s via `adb shell input keyevent` plus a heap/PSS sampler (`dumpsys meminfo`), on Fire TV Stick 4K and Onn 4K, as a release gate. The issues doc asks for this, and the proposal's testing section doesn't include it.
3. **Time-zone and DST tests:** catch-up URL golden tests for every mode × {UTC, Europe/London across a DST boundary, America/New_York, Asia/Kolkata (half-hour)} × `catchup-correction`.

---

## (C) Gaps the proposal must address

1. **Diagnostics screen (v1, not later).**
   - Live stats overlay: decoder name (`onVideoDecoderInitialized`), format, fps, bitrate (`onBandwidthEstimate`), buffer ms, dropped frames, HTTP status chain with redirect hosts (redacted), connection broker leases x/y.
   - Device capabilities dump: `MediaCodecList`, HDR caps, display modes, audio encodings.
   - Last 50 zaps with per-phase timings, EPG import reports, match rate, and a network test (DNS → TCP → HTTP → throughput, with and without DoH).
2. **Log export without telemetry.**
   - A Timber tree writing to an in-memory ring (2 MB) plus a rotating file (5 × 1 MB), with **mandatory redaction** of `username`, `password`, `/live/<u>/<p>/`, `token=`, and `Authorization`.
   - Export via (a) a **LAN web server** (embedded `NanoHTTPD` or a Ktor CIO server on a random port, gated by a 6-digit code shown on the TV and a QR code), or (b) SAF to USB.
   - The same LAN server doubles as **phone-based onboarding** (paste M3U/Xtream credentials from a phone). Issues doc #18 ranks this, and the proposal doesn't mention it.
3. **Crash reporting without telemetry.** ACRA (`ch.acra:acra-core`) with `ReportingInteractionMode` = dialog/notification. Store reports locally, and send only on explicit user action via the LAN export or a user-configured HTTP endpoint. Run the same redaction. Add an ANR watchdog (the main-thread ping thread) in debug *and* release, because Fire OS ANRs are otherwise invisible.
4. **Credential storage.** The proposal says "Keystore alias" but doesn't specify. Use a Tink `Aead` (AES256-GCM) with keys wrapped by an Android Keystore master key, and store the ciphertext in Room. Keystore on some Fire OS 6 builds is flaky after OS updates, so handle `KeyPermanentlyInvalidatedException` with a "re-enter password" path rather than a crash.
5. **Xtream API client specifics.**
   - Lenient kotlinx-serialization `JsonTransformingSerializer`s for string/int/bool/null/`[]`-as-`{}`.
   - **Streaming decode** of `get_live_streams`/`get_vod_streams` (`Json.decodeToSequence` over `body.byteStream()`), never `String`.
   - Bounded concurrency of 2–3 requests and a 429 backoff. Multiple base URLs with rotation.
6. **EPG via Xtream when no XMLTV is available.** Proposal §3.4 mentions `get_short_epg` only in passing. The guide needs a `ShortEpgFetcher` for the *visible rows only*: ≤ 6 channels, cached 30 min, ≤ 2 concurrent requests, and never for the 20k list.
7. **Parental control.** A PIN-gated group filter must be applied **in SQL** (a `hidden`/`locked` predicate in every channel query, including search and the FTS join), not in the UI. Otherwise locked channels leak through search and number entry.
8. **Number entry, last channel and favourites.** Hidden or locked channels must be excluded from CH+/CH− order. Keep a "current zap list" (group or favourites list) in `PlaybackController`.
9. **Startup path.** "Auto-play last channel": start `StreamUrlResolver` and `player.prepare()` in parallel with the first composition, triggered from `Application.onCreate` after a quick check of `library.db`. Without this, cold start to video is cold start + zap, which is 4–5 s on Fire TV.
10. **Backup/restore format** (issues doc #20): versioned JSON covering playlists (credentials re-encrypted with a user passphrase), favourites, prefs, EPG mappings and settings, keyed by stable keys.
11. **Key mapping.** The design's `R` key doesn't exist. Map Record to `KEYCODE_PROG_RED`, `KEYCODE_MEDIA_RECORD`, and long-press OK (`KeyEvent.isLongPress` / `repeatCount > 0` on `DPAD_CENTER`). The footer hint row must be generated from a `KeyCapabilities` probe (`KeyCharacterMap.deviceHasKey`).
12. **Group/category selector and logos** (design gap): a left rail that opens on Left from the first column. Logos are drawn inside the grid node (B2.3).
13. **Loading and empty states for the grid:** skeleton cells drawn by the renderer itself (no composables), "No information" gap cells, and an "EPG updating 43 %" chip.
14. **Low-RAM mode as a first-class switch** (`isLowRamDevice || memoryClass ≤ 192 || totalMem ≤ 2 GB`). It turns off the ambient transition, logos in the grid (optional), cell transitions and the description prefetch, and sets the smaller buffer and cache profiles.

---

## (D) Open questions that need a prototype or benchmark before committing

| # | Question | Exact experiment | Pass criterion |
|---|---|---|---|
| D1 | Does the hole-punch layout (B1.1/B1.2) render correctly with rounded corners, overlays and transitions on all targets? | APK with `Box{ AmbientBackground(hole) ; PlayerSurface(SURFACE_VIEW) ; GuideMock }` playing a local 1080i50 TS. Toggle: (a) static, (b) hue crossfade, (c) mini→full snap-with-scrim, (d) animated resize. Run on Fire TV Stick 4K (API 25), 4K Max (API 28/30), Onn 4K, CCwGTV and Shield. Capture with HDMI capture plus `dumpsys SurfaceFlinger --latency`. Repeat (a)–(d) with `AndroidEmbeddedExternalSurface` for comparison. | No corner artifacts, no 1-frame hole/video misalignment in (c), and (d) acceptable on API 30+ only. GPU frame P90 < 12 ms in (b). |
| D2 | Can the Compose single-node guide hold 60 fps while decoding? | Macrobenchmark `FrameTimingMetric`. 2,000 channels × 7 days of synthetic EPG (with 5-min and 6-h programmes, long titles). Journey: 100× DPAD_DOWN at 25 Hz repeat, 100× DPAD_RIGHT, 20× page. Mini window decodes 1080i50 H.264 + MP2 (FFmpeg) from local MockWebServer. Variants: Compose renderer vs the same `GuideRenderer` in a custom `View`; with/without logos; with/without the B2.8 panel debounce. | P90 < 16.7 ms, P99 < 33 ms on Fire TV Stick 4K and Onn 4K. If Compose misses and View passes by > 20 %, ship the View host. |
| D3 | Zap time and black flash per device. | Fake panel on LAN with a 150 ms RTT shaper. 200 zaps between 4 channels (H.264 TS, HEVC TS, HLS, MP2/AC-3 audio). Trace key-down → `onRenderedFirstFrame`. Record whether the last frame persists (HDMI capture) and codec re-init vs reuse (`onVideoDecoderInitialized` count). | Fire TV Stick 4K P50 ≤ 1.2 s and P90 ≤ 2.0 s. No black frame on ≥ 3 of 5 devices. Otherwise add a logo cover. |
| D4 | Transparent TS reconnect (B4.2) vs full re-prepare. | Fake panel closes the socket every 90 s and on reconnect serves (a) a continuous PTS, (b) a PTS jump of +5 s, (c) a PTS reset to 0. Measure the visible interruption and A/V sync after 1 h. | (a) under 300 ms glitch. (b)/(c) detected and recovered by re-prepare in under 2 s, with no A/V drift. |
| D5 | Max-connection behaviour. | Fake panel with `max=1` and linger values 0/2/10/30 s. 50 rapid zaps with and without `StreamConnectionBroker` preempt, plus 350 ms CH+ debounce. | Zero limit errors with linger ≤ 2 s. With longer linger, the retry ladder succeeds within 3 s in ≥ 90 % of zaps. |
| D6 | HDR and DV in the mini window. | HDR10, HLG and DV P5/P8 TS samples. Tune from the guide on Shield, CCwGTV, 4K Max and Onn with HDR and SDR TVs. Record HDMI blanking, UI appearance, and the effect of `setTrackTypeDisabled(VIDEO)` while in the guide. | Pick the default `HdrPolicy` per device class. Never a green/purple picture. |
| D7 | AFR on API 25/28 via `preferredDisplayModeId`. | 25/50 fps channel on Fire TV Stick 4K and 4K Max, with the 2 s dwell rule. Measure the HDMI re-sync time and whether `onDisplayChanged` fires. Check interaction with the Fire OS "Match original frame rate" setting. | Switch completes before `play()`, and no switch happens in guide mode. |
| D8 | EPG import throughput and disk peak. | Real-world 7-day XMLTV (epg.best-size, ~400 MB uncompressed, 1M programmes after whitelist) on Fire TV Stick 4K, while video plays. Measure time, peak heap (`Debug.getNativeHeapAllocatedSize` + `Runtime`), disk peak and zap-time degradation during import. Compare FTS on title vs title+desc. | ≤ 90 s with video playing, ≤ 20 MB importer heap, epg.db ≤ 150 MB, zap P50 degradation ≤ 15 %. |
| D9 | Window query latency on the generation-swapped raw `epg.db`. | 20k channels × 7 days synthetic (3.4M rows). Query 21 channels × 12 h, cold and warm cache, on Fire TV Stick 4K eMMC. Also check `PRAGMA compile_options` for FTS5 in `sqlite-bundled`. | Warm ≤ 5 ms, cold ≤ 30 ms. FTS5 present, otherwise FTS4 fallback confirmed. |
| D10 | Archive seek semantics per real panel. | Against 3 real providers (XUI, XC 2.9, Flussonic): test whether the `xc` timeshift TS returns `Content-Length`/`Accept-Ranges` and whether `.m3u8` timeshift is a VOD playlist. Test the `{utc}` shift on a Flussonic URL. | Fill in the `nativeSeek` column of B5.1 with facts. Default to re-request where unknown. |

---

## (E) Recommended v1 architecture (one page)

**Platform.** `minSdk 25`, `targetSdk 35`, ABIs armeabi-v7a + arm64-v8a (per-ABI and universal APKs), core library desugaring on. AGP 9.x, Kotlin 2.2+, Compose BOM 2026.06+, `androidx.tv:tv-material 1.1.0` (shell only), **Media3 1.11.1**, OkHttp 5 + DoH, Room 2.8 + `BundledSQLiteDriver` (library.db), raw `androidx.sqlite` (epg.db), Coil 3, Hilt, WorkManager, Tink, ACRA (local-only).

**Modules (8).** `:app`, `:core` (pure JVM: models, parsers, stable keys, `TimeBase`, `CatchupUrlBuilder`, rules), `:data`, `:player`, `:ui`, `:ffmpeg` (audio-only AAR built in CI from the Media3 1.11.1 tag, FFmpeg `release/6.0`, LGPL), `:baselineprofile`, `:benchmark`. `:dvr` is added in phase 2.

**UI composition.** A root `LocalDensity` maps 1 design px to `windowWidthPx/1920`. The root tree is `Box{ AmbientBackground(holeRect) ; PlayerSurface(SURFACE_VIEW, videoRect) ; AppContent }`.
- No `graphicsLayer`, clip or `RenderEffect` on any ancestor of the surface.
- Rounded corners, ring and shadow come from a cleared rounded hole in the background layer.
- The ambient field is a cached 480×270 radial-gradient bitmap, crossfaded per hue. Hue comes from logo Palette and is persisted.
- "Glass" is a translucent fill. **No real-time blur anywhere.**
- Mini to full is a snap behind a 120 ms scrim. The aspect is computed by us, and letterbox bars are painted black by the UI.

**Guide.** One `Layout`+`drawWithCache` node driven by `GuideState` (`Animatable` scroll, virtual focus, time anchor).
- All drawing goes through a toolkit-agnostic `GuideRenderer(Canvas)`, so the `AndroidView` fallback is a host swap.
- Fixed 8.67 px/min (180 min) and a 68 px row pitch. Sticky titles use a measure-once and clip-and-fade approach. Time labels come from a pre-measured table.
- Per-row archive bands, ↺ glyphs, a pre-rendered now-line glow, a day strip, Back = jump to now, page keys.
- Accessibility through the `semantics` live region. The header panel is debounced to 150 ms.

**Playback.** A `PlaybackController` `@Singleton` owns one `ExoPlayer`, and the `MediaSessionService` binds to it. The media source pipeline is `StreamUrlResolver` → `LiveTsDataSource` (no Range, transparent 3 s reconnect, byte watchdog, tagged OkHttp `Call`s) → `ProgressiveMediaSource`/`HlsMediaSource`, chosen by sniffing.
- `StreamConnectionBroker` per playlist enforces `max_connections`, pre-empts by `Call.cancel()`, debounces held CH keys by 350 ms and runs a retry ladder for 403/458/509.
- Three detectors: network stall, decoder stall and frozen picture, plus the audio watchdog for PCM fallback.
- Three adaptive LoadControl profiles, with Fast as the default (`bufferForPlaybackMs` 500) and a 24/48 MB byte cap.
- `MediaCodecSelector` orders HW then platform SW video. Audio goes platform/passthrough first, then FFmpeg (auto-PREFER for MP2/LATM where no MediaCodec exists).
- `DisplayModeController` switches only in full screen after a 2 s dwell, using `preferredDisplayModeId` below API 30.
- `HdrPolicy` defaults to Poster in the guide.
- Stop on TV-off or HDMI-unplug after 30 s, plus an inactivity timer. Re-auth and re-resolve on resume.

**Catch-up.** `TimeBase` per playlist (server zone, skew, correction, token zone, padding) and `CatchupUrlBuilder` for xc/default/append/shift/flussonic/vod with auto-probe.
- `ArchivePlayback` with a virtual wall-clock timeline: native seek where the source allows it, re-request otherwise, auto-continue across programmes, and jump-to-live.
- Scrub bar with accelerating steps and a commit after 600 ms. v1 "pause live" works by switching to the archive.

**Data.** `library.db` (Room) holds playlists, channels with `stable_key`, groups, favourites/prefs/reminders keyed by `(playlist_id, stable_key)`, and title-only `channel_fts`.
- `epg.db` is generation-named, built offline with no journal, published by atomic pointer, read-only for readers. Tables are `epg_channel` (all source channels), `epg_text` (deduped, title+subtitle FTS) and `programme WITHOUT ROWID PK(epg_channel_id,start_min)`, split at 6 h.
- The importer is streaming, whitelisted, uses `MutableLongIntMap` dedupe and 5k-row transactions, pauses during zaps, keeps the cached `.gz` for targeted re-imports, and falls back to in-place updates when free disk is under 2.5× the DB size.
- Playlist diff: host-agnostic URL keys, quality-normalised names, `missing_since` grace and a mass-remap prompt.
- `ShortEpgFetcher` handles visible rows only.

**Ops (sideload).** A Diagnostics screen (stats, caps, zap timings, network test). A redacted ring-buffer log, exported through a LAN web server with code and QR, which is also used for phone onboarding. Local ACRA and an ANR watchdog. An updater with an Ed25519-signed manifest, signing-cert check, no downgrades, and download to `filesDir`. Tink+Keystore for credentials.

**Phase-2 seams built in v1.**
- (1) `StreamConnectionBroker` is the single gate for *all* upstream connections. The recorder takes leases from it, and scheduling asks it for conflicts.
- (2) `LiveTsDataSource` is written as `upstream → TeeDataSource(DataSink)` (Media3 `TeeDataSource`) with a no-op sink in v1. Phase 2 plugs in a `RecordingSink`/`RingBufferSink`, so watching and recording the *same* channel costs **one** connection. That's the only honest UX on 1-connection accounts.
- (3) `StreamUrlResolver` and the header policy are player-agnostic, so the recorder reuses them.
- (4) Recordings and reminders reference programmes by `(stable_key, start_min, titleHash)`, never EPG row ids.
- (5) A `GrowingFileDataSource` interface (blocks at EOF until the writer advances) is reserved in `:player`, ready for playing in-progress recordings and the disk time-shift buffer with a PTS index sidecar.
- (6) `ArchivePlayback`'s virtual timeline is the same abstraction the local time-shift buffer will plug into: one scrub bar for provider archive and local buffer.

---

### Sources consulted during review
- Media3 release notes (1.8.0–1.11.1; minSdk 23 in 1.9.0; `ContentFrame`/`PlayerSurface` SurfaceView fixes; `setScrubbingModeEnabled`; dynamic scheduling 1.8.0): https://github.com/androidx/media/blob/release/RELEASENOTES.md
- Media3 FFmpeg extension (audio-only `FfmpegAudioRenderer`, FFmpeg release/6.0): https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg
- Compose `AndroidExternalSurface` / z-order (`Behind`, `MediaOverlay`, `OnTop`): https://developer.android.com/reference/kotlin/androidx/compose/foundation/AndroidExternalSurfaceZOrder and `AndroidEmbeddedExternalSurface`: https://developer.android.com/reference/kotlin/androidx/compose/foundation/AndroidEmbeddedExternalSurface.composable
- `BundledSQLiteDriver` (multi-thread mode; compile options unconfirmed): https://developer.android.com/reference/kotlin/androidx/sqlite/driver/bundled/BundledSQLiteDriver
- Fire OS device API levels: https://developer.amazon.com/docs/fire-tv/fire-os-overview.html ; Fire TV Stick 4K (2018) specs incl. Fire OS 6 / API 25, 1.5 GB: https://forum.kodi.tv/showthread.php?tid=336151
- Jellyfin Media3 FFmpeg fork (possible SW video path, unverified): https://central.sonatype.com/artifact/org.jellyfin.media3/media3-ffmpeg-decoder/
