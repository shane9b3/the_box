# IPTV Player Pain Points: Research for "The Box"

Method note: WebSearch returned mostly vendor/troubleshooting blogs and GitHub issues; it surfaced almost no Reddit threads, and I did not fetch Reddit/Play reviews directly. Items marked [S] are backed by a cited source below. Items marked [E] are engineering analysis from domain knowledge (ExoPlayer/Media3, Android TV platform, Xtream API behaviour) and should be validated in testing. Competitor behaviour (TiviMate/OTT Navigator/Smarters) is stated only where known; "unverified" otherwise. (fact-checked 2026-10-13: a follow-up pass scraped Google Play 1★/2★ reviews for TiviMate, Sparkle, OTT Navigator and Smarters, and they corroborate the themes below. Reddit stayed unreachable (403/429/login wall). See docs/review/fact-check.md §3.)

---
## 1. Buffering / stutter / freeze / stops after N minutes
**Symptoms:** buffering every ~30s; freeze at ad breaks; "stream stops after 15-20s / 3 min / 1 hr"; works in VLC but not in app.
**Causes:** 
- Provider overload/peering (most common, evenings/sports) [S]. Guides say the app buffer setting does not fix an overloaded provider.
- Buffer too small (Wi-Fi jitter) or too large (slow zaps). Guides: 15s on Ethernet, ~30s on Wi-Fi, 60s hides ISP blips but slows channel change [S].
- Low-RAM devices (Fire Stick) fragmenting memory; other apps competing [S].
- Decoder: hardware vs software choice; some channels desync on HW [S].
- Session/connection issues: provider drops connection and the player resumes with wrong Range offset (IPTVnator catch-up reconnect returns wrong bytes) [S]; SSAI/ad-insertion discontinuities in FAST streams freeze players but not VLC [S]. [E] HLS discontinuity tags, PTS wraps, changing codec params mid-stream, and MPEG-TS PCR jumps break strict ExoPlayer extractors.
- "Works in VLC not app": user-agent rejection (Kodi PVR IPTV Simple needed UA changes; default null UA gets rejected silently) [S]; redirects (302 chains, http->https), required Referer/Origin headers, cookies, non-standard MIME types, TS served with wrong content-type so ExoPlayer picks wrong extractor [E].
**Competitors:** TiviMate exposes buffer size, decoder (HW/HW+/SW), and user-agent per playlist; OTT Navigator exposes multiple players (ExoPlayer, VLC, libVLC, external) and per-channel overrides (unverified detail). Smarters: minimal tuning.
**Mitigation for The Box:**
1. Adaptive buffering: start small (fast zap), grow buffer automatically after rebuffer events (up to a cap), shrink on stable Ethernet. Expose manual override.
2. Per-playlist and per-channel user-agent/header overrides; the app's own UA by default, with VLC/browser presets and a one-shot automatic retry using a preset on 403 (fact-checked 2026-10-13: harmonised with the protocol doc's no-impersonation default); send Referer if specified via M3U `#EXTVLCOPT` and `#EXTHTTP`/KODIPROP tags (parse them).
3. Format sniffing: ignore server MIME, sniff content (TS sync bytes 0x47, #EXTM3U) and select MediaSource accordingly; follow redirects including cross-protocol.
4. Auto-recovery: on error, retry with backoff (e.g. 0.5s, 2s, 5s), then fall back to alternate decoder, then alternate URL (see section 11). Never show bare "Playback error": show the reason (HTTP 403/404/458, codec unsupported, timeout) plus a suggested action.
5. Built-in stats overlay (bitrate, buffer ms, dropped frames, decoder, HTTP status) for self-diagnosis; "Test stream" tool that reports whether it is provider vs device (e.g. measured throughput).
6. Optional "second player fallback" (libVLC/FFmpeg software) for streams ExoPlayer cannot parse.

## 2. Audio
**Symptoms:** picture but no sound; sound on some channels only; lip-sync drift; wrong default language; loud/quiet channels.
**Causes:** AC-3/E-AC-3 not decoded or passthrough sent to a sink that cannot take it; hardware decoder lacks AC3/EAC3/DTS; wrong track selected; optical/SPDIF cannot carry E-AC-3 or HD formats [S]. [E] Android TV devices differ: Shield supports passthrough broadly; Fire TV has Dolby toggles in Display & Sound; Onn/Google TV often needs "Auto" format, and some report AudioTrack init failure when capabilities are mis-reported. Desync: audio-only DTS discontinuities, AAC-LATM, HW decoder latency, Bluetooth/ARC latency. Loudness: no normalisation across sources (LUFS differences, 5.1 downmix lower).
**Competitors:** TiviMate: audio track picker and per-channel remembered audio language, HW/SW decoder toggle; no loudness normalisation (unverified). OTT Navigator: multiple audio options, audio delay (unverified).
**Mitigation:**
- Query AudioCapabilities at runtime; enable passthrough only for formats the sink reports; otherwise decode to PCM (bundle FFmpeg audio decoder extension for AC3/EAC3/DTS/TrueHD fallback).
- Auto fallback: if no audio output within ~3s of first video frame, switch to software-decode/PCM stereo and toast it.
- Audio track picker with remembered preferred language per playlist, and manual audio delay (+/- ms) per channel/device.
- Optional loudness normalisation / night mode (dynamic range compression) for stereo downmix; allow per-channel volume boost stored with the channel.

## 3. Video
**Symptoms:** stretched/wrong aspect ratio; judder on 24p film and 50Hz channels on 60Hz TV; washed-out HDR; combing on interlaced; HEVC black screen on cheap boxes; dropped frames.
**Causes:** AR from wrong SAR/DAR metadata; no display-mode switching (Android 11+ `Surface.setFrameRate`, Android 12 "match content frame rate") so 24/25/50 fps content gets 3:2 pulldown or 50->60 frame dup [S: judder explanations; Android 12 setting]. Frequent mode switches on zapping cause black screens/delays [S]. HEVC/10-bit support absent or software-only on cheap SoCs, causing drops [S]. [E] Interlaced 1080i needs deinterlacing; MediaCodec does it on some SoCs only; HDR->SDR tone-mapping depends on display; Dolby Vision profile 5 fails on non-DV displays.
**Competitors:** TiviMate has aspect ratio options, AFR (frame rate matching) in premium (reported; unverified detail), HW/SW decoders. OTT Navigator has AFR, resolution switching.
**Mitigation:**
- Frame-rate matching using `Surface.setFrameRate` with `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS` default, option for "always" (with seamless-switch warning). Debounce: don't switch during rapid zapping (wait ~1s dwell); cache per-channel detected fps.
- Aspect modes: Auto, 16:9, 4:3, Fill/zoom, and per-channel memory.
- Detect decoder capability via MediaCodecList at startup; if HEVC/10-bit has only SW decoder, warn and offer to prefer H.264 variant (SD/HD/4K tag in group) automatically.
- Surface decoder name and dropped-frame stats in debug overlay; allow forcing SW decoder per channel.
- HDR: pass through HDR10/HLG to capable displays; offer "force SDR" option for broken tone mapping.

## 4. EPG
**Symptoms:** no guide data; only some channels have data; times off by N hours; EPG stops updating; wrong channel mapped; slow load; storage/RAM bloat; laggy guide on Fire Stick/Onn/CCwGTV; 2 vs 7 days.
**Causes:** missing/rotated XMLTV URL; malformed or huge XML failing silently in the parser/out of memory [S]; channel-id (tvg-id) mismatch between playlist and XMLTV [S]; timezone offset/DST handling (wrong offset or device clock) [S]; provider simply has no EPG [S]; EPG with entire global content parsed even for channels the user hides [S: OTT Navigator advice to hide groups]; [E] DOM parsing of 100-500MB XML, no streaming parser, storing every programme incl. descriptions/icons, no index on (channel,start), recomposition of huge grids in Compose.
**Competitors:** TiviMate: multiple EPG sources, manual per-channel EPG mapping with search of loaded sources, EPG offset, update interval, EPG days setting, "update EPG on start" [S partial]. OTT Navigator: group hide reduces EPG processing [S]. Televizo: reports of stale EPG until data deleted [S].
**Mitigation:**
- Streaming SAX/StAX (or gzip-streaming) parser; only store programmes for channels actually in the (unhidden) playlist and within a configurable window (default back = max(1 day, max catchup-days), forward = 7 days; fact-checked 2026-10-13: harmonised; trim descriptions beyond N days). Insert in batched SQLite/Room transactions with indices; WAL mode.
- Background sync via WorkManager with conditional GET (ETag/If-Modified-Since), checksum skip, retry with visible status ("EPG updated 14:02, 8,410 programmes, 312 channels matched, 40 unmatched") and a failure notification instead of silence.
- Smart auto-matching: tvg-id exact, then normalized name (strip HD/FHD/country prefixes), then fuzzy with confidence; show "unmatched channels" list with one-tap manual mapping; persist mapping across refresh.
- Time handling: parse XMLTV `+HHMM` offsets properly (store UTC), per-source offset override, DST-safe; detect probable constant offset (compare "now playing" vs. broadcast boundaries) and suggest fix.
- Lazy-loaded paged guide: only query visible window (virtualised lazy grid), pre-cache adjacent 2 hours; low-RAM mode that disables images and animations. Benchmark on Fire Stick Lite/Onn 4K as release gate.
- Support multiple EPG sources with priority, Xtream `get_short_epg` as fallback when no XMLTV, and `.gz`/`.xz` URLs.

## 5. Playlists
**Symptoms:** 100k+ entries crash/slow; refresh fails silently; provider URL changed; duplicates; order changes after refresh; favourites/channel numbers lost; VOD/series in live list; adult groups.
**Causes:** [S] Smarters crashes on very large M3U on low RAM; using Xtream login (category-based) works better; hiding groups helps. [E] Whole-file in-memory parse; identity keyed by index or list position rather than stable key (tvg-id/stream URL hash/Xtream stream_id) so refresh breaks favourites; providers regenerate IDs or tokens in URLs; `group-title` containing `VOD`/`Series` prefixes.
**Mitigation:**
- Streaming M3U parser into DB; never hold entire list in memory; paging in UI. Target: 150k entries imports < 60s on Fire Stick without OOM.
- Stable identity: composite key (source id + Xtream stream_id, else tvg-id+name, else normalized URL path without token/host). On refresh, diff and merge (keep favourites, order, hidden state, custom names, EPG map, channel numbers); mark vanished channels as "missing" for N days rather than deleting; show a refresh report ("+12 new, -3 removed, 2 URL changed").
- Failure handling: keep last good copy; surface error ("HTTP 403: URL may have changed or account expired") and offer "edit URL". Detect HTML or error page returned in place of M3U.
- Classification: detect VOD/series by extension (.mkv/.mp4), Xtream endpoint (movie/series), group keywords; separate "Live / Movies / Series" tabs automatically.
- Group management: bulk hide, regex/keyword rules ("hide all groups matching XXX|adult"), PIN-protected adult filter default-on for detected adult groups, country-based quick pick at onboarding.
- Dedupe: group variants (FHD/HD/SD/4K, backup sources) into one channel with multiple streams (see section 11).
- Custom channel numbers stable and user-editable; "keep provider numbers" vs "my numbers" modes.

## 6. Xtream Codes
**Symptoms:** "authentication failed"; expired account unclear; max connections; slow VOD/series; missing categories; times off.
**Causes:** [S] expired → `auth:0`/"user not found"; connection limit holds session ~2 min after dropped client (fact-checked 2026-10-13: panel-configured; other reports say 20–60 s, so back off for up to 120 s); invisible characters/trailing spaces in credentials; wrong host/port/scheme. [E] `get_vod_streams`/`get_series` without category_id returns tens of MB; per-series `get_series_info` needed; `user_info.exp_date` and `server_info.timestamp_now/timezone` are available but unused by most apps.
**Mitigation:**
- Differentiate error states from `player_api.php` response: network, HTTP code, `auth:0`, `status:Expired/Banned/Disabled`, `max_connections`/`active_cons`. Show expiry date and countdown ("expires in 5 days") on account screen; warn proactively.
- Trim/normalise credentials; try http/https and alternate ports automatically; accept pasted `get.php?...` URLs and auto-convert to Xtream mode.
- Show connections used vs max; on "max connections", explain and offer "stop other session" guidance; never silently spawn extra connections (e.g. prefetching, preview thumbnails, or EPG-fetch with stream URL) which consume the allowance. Limit to one open stream per device.
- Lazy category-by-category load for VOD/series with local cache and background prefetch; local search index.
- Use server timezone/timestamp from `server_info` to compute catch-up times (section 7).

## 7. Catch-up / time-shift / start over
**Symptoms:** catch-up not working; wrong start time; archive flagged but 404; no seeking; no "play from beginning"; pause live behaviour.
**Causes:** [S] timeshift URL format and time format wrong (YYYY-MM-DD:HH-MM; also `timeshift.php` vs `streaming/timeshift.php`) ; provider flags tv_archive=1 without actual archive [S]; client sends the viewer's local time instead of the panel's `server_info.timezone` (IPTVnator bug #1562, fixed in PR #1563: the panel reads the segment with `strtotime()` in its own zone) [S, fact-checked 2026-10-13]; catch-up needs EPG entries so channels with bad EPG show no catch-up [S]; Kodi IPTV Simple catch-up types (default, append, shift, flussonic, xc, vod) and duration/end-time placeholders cause streams to stop after the listed duration when starting from live [S]; flussonic HLS lacks short timeshift [S]; clients start from `start_timestamp` (UTC epoch) and must then format it in the server zone for the URL [S]; IPTVnator Range-resume mismatch after reconnect [S].
**Competitors:** TiviMate supports catch-up for Xtream and M3U with `catchup-source` tags and has "start over/play from beginning" and time-shift (pause live) buffering locally; OTT Navigator similar (unverified). Smarters supports catch-up on Xtream.
**Mitigation:**
- Support all catch-up modes (`xc`, `default`, `append`, `shift`, `flussonic`, `vod`, `catchup-days`, `catchup-source` placeholders `{utc}`, `{utcend}`, `{duration}`, `{Y}{m}{d}`...); per-playlist override of mode and timezone offset; "auto-detect" probe: try request, if 404 try alternates and remember what worked.
- Compute times in UTC and convert with server tz from Xtream `server_info`; per-source EPG-to-archive offset adjust (+/- minutes) and padding (start 2 min early, end +5 min since EPG is inaccurate).
- Mark programmes playable only if within `tv_archive_duration` and channel flagged; on 404, show reason and mark programme/channel as failing (cache for session) instead of loop.
- UI: "Start over" on live info bar when archive exists; seekable catch-up with scrubber using programme duration; resume after reconnect using timestamp-based re-request rather than byte Range.
- Local pause/rewind of live via in-memory/disk ring buffer (e.g. 30-60 min configurable; disk on devices with space, otherwise RAM small) distinct from provider archive.

## 8. Remote / UX
**Symptoms:** slow zapping; number entry doesn't work; no last-channel; hard to reach favourites; info bar clutter; screen dims/sleeps; back button exits app; black screen after wake; lost playback after CEC/standby; no PiP.
**Causes:** [E] zap time = network connect + manifest + keyframe wait + decoder init; releasing and re-creating the player each zap; `Activity` onStop/onStart not restoring surface after standby (SurfaceView destroyed); keep-screen-on flag unset; HDMI-CEC power events trigger lifecycle; auth tokens expire in URL during sleep; back key semantic not stack-based; Compose focus restoration bugs.
**Mitigation:**
- Zap speed target < 1.0s on Shield, < 2s on Fire Stick: reuse a single player instance, `setMediaItem` with fast-start flags, small initial buffer, predictive pre-connect of next/previous channel manifest (only if connection limit > 1; otherwise skip), keep last frame on screen (no black flash), optional channel-logo overlay during tune.
- Number entry with 1.5s timeout and on-screen digits, OK to commit instantly; channel number + name search; Last-channel (e.g. Back-double-tap or dedicated key); CH+/- mapping; long-press OK for quick menu (favourite, hide, audio/sub, aspect); long-press numeric to add to favourites.
- Favourites: multiple lists, quick-jump with colour keys; Remember last group and channel; Resume last channel on launch (toggle).
- Lifecycle: on resume re-acquire surface and restart playback at live edge; re-fetch tokenised URLs; auto-resume after standby with timeout rule (e.g. >15 min asleep → go to channel list or resume per setting). Test CEC power on/off, HDMI switching.
- Keep screen awake during playback; handle screensaver/dim (Daydream) as pause-safe; don't stop playback when screensaver starts unless user wants.
- Back button: first press closes overlay/menu, second returns to channel list, third asks to exit (configurable "double back to exit").
- PiP (Android 8+ on Android TV where supported; Fire TV documents in-app-only PiP since Fire OS 6, fact-checked 2026-10-13) and multi-view (2-4 streams) later; gate by connection count.

## 9. Reliability
**Symptoms:** crashes; memory leaks after hours; token expiry after sleep; generic "playback error"; no failover.
**Causes:** [E] bitmap caches for logos unbounded; EPG held in memory; player listeners leak Activity; Compose state hoisting retaining big lists; URLs with expiring tokens; no retry strategy; single URL per channel. Streams stopping when navigating between sections because player is tied to a screen (IPTVnator #1687) [S].
**Mitigation:**
- Decouple player from UI (foreground service/singleton PlaybackManager) so navigation does not kill playback.
- Memory discipline: Coil/Glide with size-limited cache and downsampled logos; LeakCanary in debug; soak test (12h playback, zapping bot every 20s) in CI on Fire Stick Lite emulation/hardware; track heap trend.
- Crash reporting that is opt-in and privacy-preserving (see section 13).
- Error taxonomy with user-facing text and "Try again / Switch source / Open diagnostics".
- Failover: per-channel ordered list of URLs; on stall > N s or error, switch to next and (optionally) return when primary recovers; keep a "health score" per stream.
- Periodic keepalive/re-resolution of tokenised URLs; on resume from sleep, refresh playlist/tokens before starting playback.

## 10. Multi-device / account / licence
**Symptoms:** can't sync favourites between devices; lose setup on reinstall (favourites lost after reinstall) [S]; premium licence tied to one account/device; device limits; subscription annoyances.
**Mitigation:**
- Export/import backup (JSON) to local storage, USB, and optionally cloud (Google Drive/WebDAV/ user-provided server) including playlists (credentials encrypted), favourites, groups, EPG maps, settings.
- Optional account-less sync via QR/short code pairing between devices on the same LAN; later optional cloud sync with end-to-end encryption.
- Licensing: generous device count, offline grace period, clear one-time vs subscription pricing, transfer without support ticket, don't paywall basics (EPG, favourites, buffer settings); TiviMate's free tier limits are a source of complaints (fact-checked 2026-10-13: confirmed. 46 of 101 TiviMate 1★/2★ Play reviews from 2025–26 cite premium, price or licence issues, including the paywalled VOD and playlist editing, the annual price rising from $6.99 to $11.99, and the phone-only purchase hand-off failing).
- Remote setup: QR code / phone web UI to enter long URLs/credentials (typing on a remote is a top onboarding pain).

## 11. Multi-URL failover, duplicates, quality variants
Covered in sections 1/5/9: group duplicates by normalised name/tvg-id into one channel with ordered sources ("FHD > HD > SD", plus backup providers); user can pin a preferred source; automatic failover and "report bad source" flag. This is a major differentiator since TiviMate/Smarters largely treat each entry as separate channel (unverified for latest versions).

## 12. Recording (later)
**Symptoms/causes:** [E] failures from provider connection limits (recording uses a second connection), app killed in background on Fire OS/Android TV doze, SAF/scoped storage permission on Android 11+, USB drives unmounted or exFAT/NTFS handling, SMB credentials, scheduled start missed due to sleep (alarm needs `SCHEDULE_EXACT_ALARM`/wakelock), no padding so partial recordings, conflicts when max connections exceeded, unstable stream causing truncated TS, EPG changes shifting schedule.
**Mitigation:** foreground service with partial wakelock; exact alarms and boot receiver; default pre/post padding (1-2 / 5 min); connection-limit-aware conflict dialog before scheduling; reconnect and append on drop with gap marker; write TS directly (no remux until done); verify free space and writable storage at schedule time and again at start; SAF folder picker + internal storage fallback; EPG-linked series recordings that follow time changes; recordings list showing status and failure reason.

## 13. Privacy / security / distribution
**Symptoms/facts:** TiviMate was intermittently delisted from Google Play in 2026 (cause unconfirmed; installed copies continue to work) and users sideload via Downloader/APK [S]. (fact-checked 2026-10-13: the listing `ar.tvplayer.tv` is live again, updated 22 Jun 2026 with 5M+ installs.) Downloader itself was suspended from Google Play in May 2023 following a DMCA complaint (restored after about 20 days, then removed again briefly in Nov 2023) and remains available via Amazon Appstore/website [S, fact-checked 2026-10-13: confirmed via aftvnews/ghacks]. Playlists contain credentials in URLs; cleartext HTTP is standard for providers; ISP blocking of provider domains/ports occurs [E].
**Mitigation:**
- Distribution: ship signed APK with in-app updater (verify signature + hash, HTTPS only) and a simple short URL for Downloader; also publish to Amazon Appstore/Aptoide-style stores where policy allows; reproducible builds; clear "no content provided" positioning to stay store-compliant.
- No analytics by default; opt-in crash reports with URL/credential scrubbing (strip query strings, user/pass). Never send playlist URLs to our servers; any metadata lookups (logos/EPG) proxied or anonymised.
- Store credentials in Android Keystore-backed encrypted storage; mask in UI and logs; do not leak in screenshots (FLAG_SECURE optional on setup screens); warn when using cleartext HTTP but allow it (network security config `cleartextTrafficPermitted` needed since providers use HTTP).
- ISP blocking: support DNS-over-HTTPS option, custom DNS, alternative host/port list per account, and optional proxy/VPN-friendly behaviour (do not block when VPN active); show diagnostic "DNS resolves? TCP connects? HTTP status?" test.

---
# Top 25 things that make or break an IPTV player (ranked)
1. Channel zap speed (<1-2s) with no black flash — the most-used action; every session is dozens of zaps.
2. Stream resilience: automatic retry, reconnect and recovery without user action — provider instability is a given; the player must absorb it.
3. Actionable error messages (reason + next step) — "Playback error" drives support load and churn.
4. EPG correctness and freshness (mapping, timezone, auto-update) — the guide is the home screen for most users.
5. EPG performance on low-end devices (Fire Stick/Onn) — half the install base; laggy guide = uninstall.
6. Stable identity across playlist refresh (favourites, order, numbers, hidden groups preserved) — losing favourites is the worst trust breaker.
7. Handling huge playlists without crashes (streaming parse, DB-backed, paging) — 100k+ entries are normal.
8. Compatibility "works in VLC" parity: UA/headers/redirects/format sniffing/fallback player — top cause of "app is broken" reports.
9. Audio codec reliability (AC3/EAC3 passthrough and PCM fallback, track picker) — silent video is an instant fail.
10. Multi-URL source failover and duplicate merging — turns provider flakiness into a non-event; major differentiator.
11. Lifecycle robustness: resume after sleep/CEC/standby with fresh tokens and no black screen — daily-use experience on TV hardware.
12. Smart, adaptive buffering with sane defaults and manual overrides — fixes most "buffering" without user tuning.
13. Catch-up/start-over reliability with auto-probing of URL formats and timezone handling — premium feature users pay for, rife with provider quirks.
14. Xtream account clarity (expiry, connection count, error states) — separates provider problems from app problems.
15. Group management and filtering (hide adult/foreign/VOD, bulk rules, PIN) — makes giant lists usable.
16. Frame-rate matching and correct aspect/decoder handling — quality perception for sports/film.
17. Remote ergonomics: number entry, last channel, long-press menus, favourites, back-button logic — the whole interface is a remote.
18. Quick, easy onboarding (QR/phone entry, import via Xtream or M3U, auto EPG) — first 5 minutes decide retention.
19. Memory/leak hygiene for 12h+ playback; decoupled playback from UI — prevents crashes on cheap devices.
20. Backup/restore and cross-device sync — reinstall and multi-TV households are common.
21. Fair licensing and transparent pricing — premium annoyance is a recurring complaint category.
22. Privacy-respecting design (no telemetry by default, encrypted credentials, scrubbed logs) — user base is sensitive and trust is scarce.
23. Distribution resilience (signed sideload APK, in-app update, short Downloader code) given Play Store removal.
24. Built-in diagnostics (stats overlay, connection test, DNS/ISP-block checks) — reduces support and builds credibility.
25. Recording/time-shift robust to connection limits, sleep and storage permissions — "later" feature but a frequent source of data-loss complaints.

---
# Sources
- TiviMate buffering (vivimate.io): https://vivimate.io/blog/tivimate-buffering-every-30-seconds
- TiviMate buffering Firestick fixes: https://globall-tv.com/tivimate-buffering-fix-firestick/
- Best TiviMate settings Fire Stick: https://itvstrong.pro/blog/best-tivimate-settings-firestick
- TiviMate Firestick 4K guide: https://www.streamelite.app/blog/fix-tivimate-buffering-firestick-4k-guide
- TROYPOINT forum, Tivimate buffering: https://troypointinsider.com/t/tivimate-buffering/87118
- EPG not working guide: https://iptvlime.com/epg-not-working-iptv-complete-fix-guide/
- EPG fix / catch-up guide: https://www.flash4kiptv.vip/blog/fix-epg-catchup-iptv-2026
- EPG setup guides: https://iptvsociety.com/blog/epg-setup-complete-guide/ , https://mtivi.app/iptv-epg-setup/
- Kodi forum E-AC3 passthrough: https://forum.kodi.tv/showthread.php?tid=341407
- IPTV no sound guides: https://www.playlistm3u.com/iptv-fix/iptv-no-sound/ , https://nviewx.com/iptv-no-sound/ , https://ottmega.com/blog/play-dts-ac3-eac3-audio-android-player
- TiviMate Play Store removal: https://www.free-codecs.com/news/tivimate-has-vanished-from-the-play-store-again.htm , https://tv-shadow.com/en/tivimate-not-on-google-play/
- Downloader Play Store suspension: https://alternativeto.net/news/2023/5/popular-android-tv-app-downloader-suspended-from-google-play-store--because-it-s-a-web-browser-and-can-load-a-pirate-website
- Smarters crash/large playlist: https://www.varodatic.vip/blog/iptv-smarters-crashing-firestick-fixes , https://lumiptv.com/blog/iptv-smarters-pro-not-working-fixes , https://www.techkings.org/threads/iptv-smarters-crashes.174540/
- OTT Navigator review (group management): https://smartifliix.pro/blog/ott-navigator-iptv-premium-review
- M3U not loading: https://www.ottv.org/docs/fix-m3u-not-loading
- IPTVnator issues: https://github.com/4gray/iptvnator/issues/1682 , /1687 , /1794 , /1562 , /658
- Dispatcharr catch-up Range issue: https://github.com/Dispatcharr/Dispatcharr/issues/1764 ; timeshift plugin: https://github.com/cedric-marcoux/dispatcharr_timeshift
- Kodi PVR IPTV Simple issues: https://github.com/kodi-pvr/pvr.iptvsimple/issues/333 , /337 , /376 , /392 , /388 , /809 ; https://forum.kodi.tv/showthread.php?tid=359945
- Televizo XDA thread: https://xdaforums.com/t/app-5-0-televizo-iptv-player.3956790/page-37
- Frame rate / AFR: https://www.nvidia.com/en-us/geforce/forums/shield-tv/9/216437/resolution-frame-rate-question-can-someone-explai/ , https://www.formuler-support.tv/forum/thread/5589-question-on-afr/
- Xtream errors: https://lumiptv.com/blog/xtream-codes-connection-failed-fix , https://iptvforum.net/troubleshoot/xtream-codes-auth-failed/ , https://4gray.github.io/iptvnator/blog/xtream-codes-setup-guide/
- Xtream catch-up: https://github.com/MuxTV/Muxtv/issues/315 , https://github.com/namillis/LiveWireTV/pull/36
Caveat: many sources are IPTV-seller blogs with commercial bias. Channels DVR, Jellyfin Live TV and the TiviMate support forum were not directly retrieved. (fact-checked 2026-10-13: Google Play reviews have since been retrieved; Reddit is still blocked.) Recommend a follow-up pass fetching those directly.
