# The Box: Product Review (adversarial)

Reviewer role: product lead / TV-UX. Date: 2026-10-13.
Inputs: `docs/01-design-reference.md`, `docs/research/tivimate-feature-audit.md`, `docs/research/user-issues-and-mitigations.md`, `docs/research/protocols-m3u-xmltv-xtream.md` (and `android-tv-architecture.md` where it constrains a decision).

## 0. Evidence caveats (read first)

These caveats affect how far the rest of this review can be trusted.

- **Most TiviMate behaviour is unverified.** The feature audit says tivimate.com, Reddit and the Play Store were unreachable. Nearly every TiviMate row is [K] (analyst memory) or [S] (single affiliate blog). Whenever this review says "TiviMate does X", it means "the research believes TiviMate does X". **Before treating any "not worse than TiviMate" claim as a spec, someone must spend 2 hours with TiviMate Premium on a Fire TV Stick 4K and an Onn 4K, recording the key map.** That session is task #1 of this plan.
- **The research contradicts itself on Play Store status.** The feature audit says TiviMate *is* on Google Play for Android TV [K/V]. The user-issues doc says it "vanished from Google Play" [S]. Both may be true at different dates. Nothing here depends on it, but do not repeat either claim publicly.
- **The free/premium split is contradictory** across sources (favourites, AFR, audio-track picker, PiP). I treat it as "TiviMate gates a lot" and do not plan against any specific split.
- **Performance and zap targets are engineering estimates [E]**, not measurements: zap under 1 s on Shield and under 2 s on Fire TV, 150k-entry import under 60 s, 1M programmes under 60 s, heap under 100 MB. They are good release gates *because* they are concrete, but the first benchmark run must re-baseline them.
- **Catch-up URL quirks are [observed], not [spec].** Examples are the TiviMate `{Y}` tokens in device zone and the Xtream timeshift date formats. The catch-up engine must therefore be probe-and-remember, not hard-coded.
- **Error 458 semantics are folklore.** We treat 458 like 403 with a connection-limit hint and do not claim to know what it means.

---

## 1. Scope verdict

### 1.1 The test for "v1"

A feature is v1 if a current TiviMate user who installs The Box would hit its absence **in the first week of daily use** and go back. Everything else waits. I also apply the reverse test: anything on the top-25 list in user-issues that *causes abandonment* stays in v1 even when the owner did not list it, because a "live guide + player" with silent failures loses to TiviMate on day 2.

The owner's stated v1 is: live guide with mini preview, full-screen player, setup via M3U+EPG or Xtream, catch-up/time-shift browsable backwards in the guide, and recording later. **That list is necessary but not sufficient.** It leaves out the plumbing that decides retention: stable favourites across refresh, group hiding, error messages, EPG mapping, onboarding without typing a 90-character URL, and an updater for a sideloaded app.

### 1.2 Release plan

**v1: must ship (the "replace TiviMate for live TV" release)**

| Area | In v1 | Why it cannot wait |
|---|---|---|
| Sources | M3U URL, M3U local file, Xtream login, XMLTV URL(s) (gz/xz/zip), several playlists, **free and ungated** | Core of the brief. Multiple playlists are cheap once the data model has `playlist_id`, and TiviMate gates them, so this is an easy win. |
| Onboarding | TV form entry with URL helpers, **phone-assisted entry via LAN QR page**, paste of a full `get.php` URL auto-converted to Xtream, auto UA probe | Top-25 #18. Typing credentials on a remote is the first-5-minutes killer. |
| Guide | Design grid, group filter, favourites filter, day navigation, jump to now, archive region, reminders (in-app only) | Owner's brief and #4/#5. |
| Player | Single Media3 player that moves between mini and full screen, info bar, channel list panel, number entry, previous channel, audio/subtitle/aspect pickers, stream stats | #1, #9, #17. |
| Catch-up | Modes `xc`, `default`, `append`, `shift`, `flussonic`, plus auto-probe. "Start over" on the live programme. Seekable archive playback with a scrub bar. Pause/rewind on **archive-capable channels** done via the archive URL | Owner's brief and #13. TiviMate charges for catch-up, so giving it away free is a headline. |
| Resilience | Retry/reconnect ladder, re-resolve tokenised URLs, server failover list, error taxonomy with reason and next step, adaptive buffer | #2, #3, #8, #12. |
| Data integrity | Stable channel identity, diff-merge on refresh, refresh report, keep last good copy | #6. The worst trust breaker. |
| Scale | Streaming M3U/XMLTV parsers, channel whitelist for EPG, Room DB, custom-drawn guide, low-RAM mode | #5, #7, #19. |
| EPG matching | Exact, case-insensitive and name matching. Fuzzy matching as *suggestions*. Unmatched-channels list with manual mapping. EPG time shift per source and per channel | #4. TiviMate's weakest area by the research. |
| Groups and parental | Hide, reorder and lock groups. Hide and lock channels. Keyword hide rules. 4-digit PIN. Adult groups auto-locked | #15. Giant lists are unusable without this, and a TV in a family room needs the PIN on day 1. |
| Favourites | Multiple lists, add from any surface, survive refresh | #6, #17. |
| Search | Channels and EPG programmes (FTS) | It is in the design's tab row, and 2,000 channels need it. |
| Account clarity | Xtream status, expiry countdown, connections used/max, allowed formats, server time skew | #14. |
| Video/audio | AFR (seamless-only default), passthrough with PCM fallback, FFmpeg audio extension (AC-3/E-AC-3/MP2/LATM), per-channel aspect and audio memory | #9, #16. |
| Lifecycle | Resume after standby/CEC with fresh URLs, keep screen on, Back-button ladder, exit confirmation | #11, #17. |
| Backup | Export/import JSON to file and through the phone page, with credentials encrypted under a passphrase. Weekly local auto-backup | #20. Reinstalls are common on sideloaded devices. |
| Diagnostics | Stats overlay, connection/DNS test, sync status screen, redacted log export | #24. It doubles as our support tool. |
| Network | DoH resolver choice and IPv4 preference | Cheap with OkHttp. Fixes a whole class of "works on phone, not on TV" in UK/IT/ES/PT. |
| Distribution | Signed per-ABI APKs, in-app updater with SHA-256 check, short Downloader code | #23. A sideloaded app without an updater is dead within 3 releases. |
| Privacy | No telemetry by default, credentials in Keystore, redacted logs | #22. It costs almost nothing if done at the start. |

**v1.1 (about 6–10 weeks after v1, the "parity plus" release)**

| Item | Reason for deferring |
|---|---|
| **Local pause/rewind buffer for non-archive channels** (disk ring buffer) | Endless-TS ring buffering with Media3 is real engineering (custom DataSource, disk management, seek mapping). v1 covers pause on archive channels through the provider archive. On a non-archive channel, Pause in v1 freezes the frame. Resume then **rejoins live** with an honest toast: "This channel has no catch-up, so you're back at live." This is not top-25 abandonment material, because TiviMate gates pause too [K]. |
| **Duplicate merge / multi-source channels** (FHD > HD > SD, cross-playlist backup) | Top-25 #10, but TiviMate lacks it, so its absence will not drive users back to TiviMate. **The schema must ship in v1** (`channel` 1..n `stream_source`) so we never migrate identity later. v1 ships server-level failover (Xtream backup base URLs). |
| Xtream **VOD and Series** browsing | Not in the owner's brief, and TiviMate's VOD is reported weak [?]. The biggest risk among these cuts: Xtream switchers will notice it. Mitigation: v1 classifies VOD/series entries at import and keeps them **out** of the live guide, and v1.1 adds the browse UI. If beta feedback says "no VOD = uninstall", pull it forward and drop reminders instead. |
| Guide "Compact" density (8–9 rows, smaller focused panel) | The design's 6 rows is lower density than TiviMate [K]. Power users with 2,000 channels will ask for more rows. Ship the design first and measure. |
| Preview-on-focus in the mini player (dwell 1.5 s), only when `max_connections >= 2` | See §3.8. |
| `catchup=vod` with `{catchup-id}`, per-playlist proxy, udpxy relay, audio delay per channel, sleep timer, accent colours, channel rename/custom logo | Niche or cosmetic. Each is under 1 week of work, so batch them. |
| Widevine via `#KODIPROP` (license URL only) | Legit FAST/OTT sources use it, but it is not core. **ClearKey keys embedded in playlists: see Risks §7.1. Do not ship.** |

**v2 (the DVR release)**

| Item | Notes |
|---|---|
| Recording: manual, from guide, scheduled, padding, series rules on `dd_progid`/title | Recordings tab and Record button come back. Use a foreground service, exact alarms, a boot receiver, and connection-limit-aware conflict handling (architecture §DVR). |
| Storage: internal, USB (SAF), **SMB** (smbj, Apache-2.0) | SMB is the differentiator because TiviMate's support is contested [?]. Because many users record to a NAS, SMB is in v2 itself, not later. |
| Multi-view (2 and 4) and PiP | Device-limited and connection-limited. Fire TV Stick Lite cannot do it. |
| Stalker/MAG portal | A different protocol family with MAC-lock legal and compat grey areas. Do it properly or not at all. |
| Android TV Watch Next / home channels | Nice, but not a reason anyone switches. |
| Cross-device sync (LAN pairing first, then optional E2E cloud) | v1 backup covers reinstall. Sync is a convenience. |
| libVLC fallback engine (AVI, teletext, odd codecs) | +20–30 MB per ABI and a GPL-module audit. Media3 with FFmpeg audio covers most live streams. External-player hand-off (v1, advanced) is the escape hatch meanwhile. |

**Later / maybe never**

Dummy channels, voice/Assistant search, loudness normalisation, teletext decoder, TMDB enrichment, themes beyond dark, tvOS/Tizen/webOS.

### 1.3 Explicit cuts I expect pushback on

1. **No Recordings tab in v1.** The design has one. Shipping a "Coming soon" tab on a TV app is dead focus-space that users press and resent. **Hide it** and bring it back in v2. The secondary button becomes **Remind / Reminder set** (same visual, red dot becomes accent dot).
2. **Reminders only fire while The Box is in the foreground.** Android 10+ blocks background activity launches, and Android TV notifications are nearly invisible. The reminder UI must say so ("Reminders work while The Box is open"). Background wake-and-switch is a v2 DVR-adjacent feature.
3. **No monetisation in v1.** Ship v1 fully free (or as a free beta) and decide the model before v1.1. TiviMate's gating is a top complaint (#21). Do not build a licence server before the product has earned trust.

---

## 2. Screen inventory and navigation map (v1)

### 2.1 Universal remote rules

**The 5-key rule.** Every v1 function must be reachable with **D-pad + OK + Back + long-press OK**. Chromecast/Google TV and Onn remotes have no Menu, number, colour, channel or (on several models) Play/Pause keys. Fire TV remotes have Menu (≡) and Play/Pause. TV-maker remotes (Sony, Philips, TCL) add numbers, colour keys and CH±. Extra keys are **accelerators only**, and the footer hint row shows only keys the device has actually reported (track `KeyEvent` codes seen at runtime, and use `KeyCharacterMap.deviceHasKey` as a hint).

| Key | Global meaning |
|---|---|
| Back | Closes the top-most layer. Never exits without confirmation (configurable). |
| Long-press OK (≥ 500 ms) | Context menu for the focused thing. **Menu key is an alias.** |
| Play/Pause | Player: pause/resume (with the archive caveat in §3). Guide: go full screen on the tuned channel. |
| 0–9 | Number entry overlay, anywhere a channel is relevant. |
| CH+ / CH− | Player: zap. Guide: page 6 rows. |
| FF / RW | Player: seek ±30 s (archive). Guide: ±3 h. |
| Colour keys | Red = Start over, Green = Favourites list, Yellow = Audio and subtitles, Blue = Previous channel. Remappable in v1.1. |
| Home | OS-owned. Playback stops (no background audio for live TV). On return we restore the tuned channel. |

"TiviMate parity" notes below are from the research and are marked as such.

### 2.2 Navigation map

```
                       [First run]
 Splash ──► Welcome ──► Add source ─┬─► Xtream login ─┐
   │                                ├─► M3U + EPG ────┼─► Import progress ─► Groups review (+PIN offer) ─┐
   │                                ├─► Phone setup (QR) ┘                                                │
   │                                └─► Restore backup ────────────────────────────────────────────────── ┤
   │                                                                                                       ▼
   └──(returning user)──────────────────────────────────────────────────────────────────────────────► GUIDE (home)
                                                                                                          │
   GUIDE tabs:  [Guide] [Search] [gear Settings]      (Recordings hidden until v2)                        │
     ├─ header row: Group chip ─► Groups panel (Favourites lists, Recent, All, provider groups)            │
     │              Day chip ─► Day strip            Now chip (when scrolled away)                         │
     ├─ OK on row ─► tunes mini ─► OK again ─► FULL-SCREEN PLAYER                                          │
     ├─ OK on past archived cell ─► FULL-SCREEN (catch-up)                                                 │
     ├─ OK on future cell ─► Programme sheet (Remind / Watch channel now)                                  │
     └─ Long-press OK ─► Channel/programme menu (Start over, Remind, Favourite, Hide, Lock, EPG mapping…)  │
                                                                                                          │
   FULL-SCREEN PLAYER (overlays stack, one at a time + toasts)                                            │
     ├─ Info bar (Right / any key) ── Up/Down within info bar = now/next programme                        │
     ├─ Channel list panel (OK) ── Left ─► Group column                                                   │
     ├─ Quick menu (long-press OK / Menu): Audio, Subtitles, Aspect, Start over, Favourite, Sources,      │
     │                                     Stream info, Decoder, Open in external player                  │
     ├─ Number entry (0–9)                                                                                │
     ├─ Scrub bar (archive/time-shift: Play/Pause, FF/RW, Left/Right in seek mode)                         │
     ├─ Error card (reason + actions)                                                                      │
     └─ Back ─► GUIDE (player keeps playing in mini) ─► Back ─► "Press Back again to exit"                │
                                                                                                          │
   SETTINGS ─► Playlists ─► Playlist detail/account ─► Edit source / Refresh report                       │
            ─► TV guide ─► EPG sources ─► Matching (unmatched list ─► mapping picker)                     │
            ─► Channels & groups ─► Group manager / Hide rules / Favourites lists                         │
            ─► Playback / Interface / Parental / Backup / About & diagnostics / [Network, Remote: adv.]   │
   Global: Sync-status chip (header, while syncing) ─► Sync status screen; PIN dialog (modal, any layer).
```

### 2.3 Screen-by-screen

Each screen follows the design language: dark canvas `#0A0D13`, glass panels (`.045` bg, `.09` border, blur 30, radius 24), white solid focus (`#EEF1F6` with accent ring), Instrument Sans with JetBrains Mono for numbers and times.

#### S1 Welcome / Add source

The layout reuses the guide composition: an ambient blob in the top-left mini-player slot shows the logo animation, and the title sits in the 64 px focused-panel type. There are four large choice cards in a row: **Xtream login**, **M3U playlist**, **Set up from phone**, and **Restore backup**. Default focus is on "Set up from phone" if the device has no physical keyboard (almost always), otherwise on Xtream.

| Key | Behaviour |
|---|---|
| ←/→ | Move between cards. |
| OK | Open the card. |
| Back | Exit app (first-run only, no confirm). |

TiviMate parity [K]: it offers a plain list (M3U / Xtream / Stalker) and has **no phone-assisted entry**. That is our clearest first-5-minutes win.

#### S2 Set up from phone (LAN QR)

- The TV starts an embedded HTTP server, bound to the LAN IP, with a random port and a 6-digit one-time code. It shows a **QR code** (`http://192.168.x.y:port/?c=XXXXXX`), the plain URL, and the code.
- The phone page offers the same three forms (Xtream / M3U+EPG / restore backup) with a big paste-friendly input. On submit the TV shows "Received from phone. Checking…" and jumps to the import screen.
- Security: the code is single-use, the server shuts down after 10 min or on success, the page binds only to private address ranges, and credentials never leave the LAN. **Caveat:** it is HTTP on the LAN, so a hostile LAN can sniff it. That is acceptable for a home network, but say so in the page footer.

| Key | Behaviour |
|---|---|
| OK | "I can't scan": shows the URL larger. |
| Back | Stops the server and returns to S1. |

TiviMate [K]: none. TiviMate Companion handles licences, not setup.

#### S3 Xtream login (TV form)

Fields: Name (optional, autofilled from host), **Server** (with chips: `http://`, `https://`, `:8080`, `:80`, `.com`, `.net`, `.tv`), **Username**, **Password** (with show toggle), and *Advanced* (collapsed: User-Agent preset, output TS/HLS/Auto, backup servers). Primary button: **Connect**.

- **Normalise input:** trim invisible and trailing whitespace, add a missing scheme, and if the user types a full `get.php?username=…&password=…` URL into Server, split it into the three fields automatically and toast "Filled from link".
- On Connect, call `player_api.php` and show a result card: **Active · expires 14 Mar 2027 (152 days) · 1 of 2 connections in use · formats TS, HLS · server time in sync**. Failure states are specific: wrong credentials, Expired (with date), Banned/Disabled, unreachable (DNS / TCP / TLS, each named), "server returned a web page instead of data", and "max connections reached".
- **Auto UA probe:** after login, test-open one live stream for 3 s. On 403 with the default UA, retry with the VLC preset. If that works, keep it and explain why in the account screen. Always close the probe stream (it counts against max connections).

Text entry: use the system IME (Gboard TV / Fire keyboard). Each field shows an IME action "Next", and the last field submits. Do not build a custom keyboard in v1. A custom keyboard is a maintenance sink, and the phone path exists.

| Key | Behaviour |
|---|---|
| ↑/↓ | Move fields. |
| OK | Open IME / toggle. |
| Back | Close IME. A second press leaves the form, and the draft is kept for the session. |

TiviMate [K]: similar form, but no URL splitting and no structured error states. Users report generic failures (research #6).

#### S4 M3U + EPG

Fields: Name, **Playlist URL or file** (file picker for USB/Downloads), **EPG URL(s)** (optional, add more), and *Advanced* (UA, headers, catch-up mode, EPG shift). The URL chips are the same as S3.

- If the playlist has `x-tvg-url`/`url-tvg`/`tvg-url`, prefill the EPG field after the first parse and tell the user.
- If the URL is an Xtream `get.php`, offer "Use Xtream login instead (unlocks catch-up, account info, faster loading)". This is the default choice.
- If the downloaded file is an HLS manifest (`#EXT-X-*`), offer "This is a single stream, not a playlist. Add it as one channel?"

#### S5 Import progress

This is a full-screen glass panel with stage rows, each with a mono count:

1. Downloading playlist (MB)
2. Reading channels: **18,402 entries → 2,140 live, 16,262 movies/series (kept for later)**
3. Groups: 64
4. Downloading guide (MB)
5. Matching guide: **1,912 of 2,140 matched (89 %)** with "Fix later" link

The **Start watching** button becomes active as soon as stage 3 finishes, and EPG continues in the background with the header sync chip. Never block the user on a 300 MB XMLTV.

| Key | Behaviour |
|---|---|
| OK | Start watching (when enabled). |
| Back | Confirm "Cancel import?" (keeps the already-imported channels). |

#### S6 Groups review (first run only, skippable)

This is a two-column checklist: **Show** (country quick-picks from `tvg-country` / group prefixes such as `UK |`, `US:`) and **Hidden**. Adult-looking groups (`XXX|Adult|18+`, `is_adult`) are **pre-locked** with a lock glyph. Below the list is the offer: "Set a 4-digit PIN to protect locked groups" [Set PIN] [Not now]. Every hidden group cuts EPG import work (whitelist), so we tell the user: "Hiding groups makes the guide faster."

Keys: ↑/↓ move, OK toggles show/hide, → moves to the lock column, OK toggles lock, Back/Done → Guide.

TiviMate [S/K]: group management exists but is buried in settings. Surfacing it at onboarding is where the "2,000 channels feels usable" experience is won.

#### S7 Guide (home)

The full design applies, with the additions in §3. Focus zones are: **tab row** (Guide / Search / gear) → **guide header row** (Group chip, Day chip, Now chip) → **grid** (virtual focus `(channel, programme)`).

| Key | Behaviour |
|---|---|
| ← / → | Previous/next programme on the row. The window scrolls so that the focused cell's start sits at about 25 % of the viewport, with the ruler snapped to :00/:30 (§3.1). |
| ↑ / ↓ | Previous/next channel, keeping the sticky anchor time (§3.1). Up from the **first channel of the group** goes to the header row. Holding the key accelerates after 600 ms and shows a big channel-number bubble at the left. |
| OK | On-now or no-archive past cell: tune the row in the mini player. If the row is already tuned: **go full screen**. Past cell with archive: **play catch-up full screen**. Future cell: Programme sheet. |
| Long-press OK / Menu | Context menu: Watch from start, Remind me, Add to favourites, Hide channel, Lock channel, Fix guide data (EPG mapping), Channel info. |
| Back | (1) If focus is not on the tuned channel at now, snap there (animated). (2) Otherwise, if something is playing, go full screen. (3) Otherwise, "Press Back again to exit". |
| Play/Pause | Full screen on the tuned channel. |
| CH+ / CH− | Page up/down 6 rows. |
| FF / RW | ±3 h (one window). |
| 0–9 | Number entry → focuses that channel row at now. With a second OK it tunes. |
| Green | Toggle Favourites filter. |
| Red | Watch from start (if possible) or Remind (future). |

TiviMate [K]: Left at the channel column opens categories, and OK opens programme details or tunes. Our Group chip is one Up press from the top row, plus Green. Verify that this is not slower than TiviMate's Left-to-categories in the parity session. If it is, also map "Left on the first programme at the archive limit" to open the Groups panel.

> **Correction 2026-10-19** (survey for `docs/decisions/2026-10-19-guide-left-and-groups.md`): the "Left at the channel column opens categories" claim is not supported. TiviMate's documented guide behaviour (third-party help pages; no official key table) is **Left = back in time on catch-up channels, Back = Groups**. The Box now follows that: Back from home opens Groups; Left opens it only where no earlier watchable programme exists. The text above is kept as the original review wrote it.

#### S7a Groups panel

A left-side glass panel (360 wide, full grid height) that overlays the grid's label column. Sections: **Favourites** (each list), **Recently watched**, **All channels**, then provider groups in the user's order with counts and lock glyphs. Locked groups ask for the PIN on OK.

Keys: ↑/↓ move, OK selects (the grid reloads, focus goes to the first channel at now), → or Back closes, long-press OK opens group options (Hide, Lock, Move up/down).

#### S7b Programme sheet (future programme)

A small glass card anchored to the focused cell, with **[Remind me]** (default) and **[Watch channel now]**. It shows "Reminder set" when the reminder already exists. OK activates, Back closes.

#### S8 Full-screen player

The player is the same `SurfaceView`-hosted Media3 instance, moved from the mini slot (architecture §2.6). With no overlay showing:

| Key | Behaviour |
|---|---|
| ↑ / ↓ | Zap ±1 within the current group. A logo and name card shows instantly, and the last frame stays on screen (no black flash). Setting: "Up/Down opens channel list". |
| ← | Channel list panel with the **group column** focused. |
| → | Info bar. |
| OK | Channel list panel, focused on the current channel. |
| Long-press OK / Menu | Quick menu. |
| Back | Back to the guide (the stream continues in the mini player). |
| Play/Pause | Archive channel: pause. Resume continues from the paused point via the archive URL. Non-archive channel: freeze, and resume rejoins live with a toast (v1). |
| FF / RW | ±30 s on archive (RW on live archive enters time-shift mode). Non-archive: toast "No catch-up on this channel". |
| CH± | Zap. |
| 0–9 | Number entry. |
| Colour keys | As in §2.1. |

TiviMate parity [K, from protocol §6.3]: OK opens the channel list and Up/Down zap, with an invert option. We match that default deliberately, so muscle memory transfers.

**Player overlays** (all glass, bottom- or left-anchored, auto-hide):

| Overlay | Content | Keys | Notes |
|---|---|---|---|
| **Info bar** (bottom, 1792×220) | Channel number chip, logo, name. Now title 30/600 with time row and progress (the mini-player style, enlarged). Next title. State pill: LIVE / CATCH-UP / PAUSED / "−12:30 behind live". Glyphs: catch-up available, resolution (1080p50), audio (5.1), subtitles. | ↑/↓ browse now→next→later on this channel (no zap). OK on a past programme plays catch-up. OK on next sets a reminder. ← / → move between channels' now (browse without tuning, and OK tunes). Back hides. | Timeout setting (default 5 s). Also shown automatically for 4 s after every zap. |
| **Channel list panel** (left, 620 wide) | Two columns. Groups (collapsible, 240) and channels (number, logo, name, now title, progress). Pinned top row: **Previous: {channel}**. | ↑/↓ move (hold accelerates). → / OK tunes and keeps the panel open (TiviMate behaviour [K]). A second OK closes. ← goes to groups. Long-press OK opens the channel menu. Back closes. CH± pages. | Keep the panel's video unobstructed: the channel list covers about one third, not a full dim. |
| **Quick menu** (right sheet) | Audio (track list with language and codec, e.g. "English · E-AC-3 5.1"), Subtitles, Aspect (Fit/Fill/Zoom/16:9/4:3, "Remember for this channel" checked by default), **Watch from start**, Add to favourites, Sources (v1: server list; v1.1: stream variants), Stream info, Video decoder (Auto/HW/SW for this channel), Open in external player. | ↑/↓, OK, ← / Back closes or goes up a level. | |
| **Number entry** | Large mono digits (top-right), with a live preview of the matching channel name and logo below. | Digits append. OK commits now. Auto-commit after the timeout (1.5 s default). Back clears. A 4th digit with no match shows "No channel 4021". | |
| **Scrub bar** (archive / time-shift) | Programme-scoped bar from programme start to end, with the live-edge marker and the archive limit shaded. Thumbnail-free (thumbnails need a second connection). Time label mono 18. | ← / → seek ±10 s (hold: 30 s, then 2 min steps). FF/RW ±30 s. OK plays from there. Up is "Jump to live". Down shows previous/next programme chips. Back hides the bar (keeps position). | Crossing into the previous programme loads the archive URL for that programme seamlessly. |
| **Error card** (centre, 720 wide) | Headline with the plain reason, a one-line detail with the code, and actions. See §2.4. | ←/→ actions, OK, Back goes to the guide. | Never a bare "Playback error". |
| **Toasts** | Bottom-left, 4 s: "Reconnecting… (2/5)", "Switched to backup server", "Audio decoder switched to software". | n/a | Never steal focus. |
| **Stats overlay** (toggle) | Codec, resolution, fps (and AFR state), bitrate, buffer ms, dropped frames, decoder name, HTTP status, server host. | Toggled from the quick menu "Stream info". Back closes. | TiviMate shows stream info on long-press OK [S]. Ours is one level deeper but richer. |

#### S9 Search tab

A query field at the top (system IME, or voice input via the IME mic if the device has one). Results come in two horizontal rows: **Channels** (logo cards) and **Programmes** (title, channel, time, with a catch-up glyph if past and in the archive). Search starts after 2 characters and uses FTS on normalised text.

Keys: ↓ moves from field to results, ←/→ within a row, OK plays (catch-up for past programmes, remind for future ones), long-press OK opens the context menu, Back goes to the field and then to the Guide tab.

TiviMate [V]: searches channels, EPG and VOD. VOD is in v1.1 for us.

#### S10 Favourites

Favourites is **not a separate screen.** It is a filter (Groups panel / Green key) plus "Add to favourites" in every context menu. A favourites list is reordered in Settings → Channels & groups → Favourites with a "move mode" (OK picks up a row, ↑/↓ moves it, OK drops it). Multiple lists are free.

#### S11 Settings

The same glass language. Two-pane layout: a section list on the left (420), detail on the right. Every row shows the current value on the right in `.65` alpha. A footer row reads **Show advanced settings** (toggle). Locked when "Lock settings" is on: a PIN is needed on entry.

Keys: ↑/↓, OK/→ enters, ← / Back goes up. Choice settings open an inline radio list, not a modal.

#### S12 Playlist detail / account

Header: name, type, host (credentials masked). Account card (Xtream): Status, expiry with countdown (amber under 7 days, red when expired), connections `1 / 2`, formats, server timezone and skew. Last refresh: "Today 14:02 · 2,140 channels · +12 new · −3 removed · 2 URLs changed · favourites kept". Actions: Refresh now, Edit source, Groups, Catch-up settings, Remove.

#### S13 Guide data (EPG) and matching

Shows the source list with status lines ("epg.example.com · updated 06:00 · 412k programmes · 304 MB → 18 MB stored"). The **Unmatched channels** list sorts the most-watched first. OK on a channel opens a picker with a search field over all loaded EPG channels and suggested fuzzy candidates with a confidence label ("Suggested · 94 %"). Choosing one shows a "Now: …" preview so the user can see the match is right before confirming.

#### S14 Parental PIN

A modal 4-dot PIN pad (0–9 on-screen grid, so it works with D-pad-only remotes, and number keys also work). Three wrong attempts give a 30 s cooldown. On a locked channel the guide shows the title as "Locked" with a lock glyph, the mini player shows a still, and OK prompts for the PIN. Unlock lasts until the app closes (setting).

#### S15 Sync status

A header chip ("Updating guide · 62 %") appears during background sync in the guide's top-right, left of the date. OK on it opens the screen: one row per playlist and EPG source with state (Queued / Downloading 120 MB / Parsing / Done / **Failed: HTTP 403, URL may have changed or the account expired** [Edit] [Retry]). Failures **persist** as an amber chip until acknowledged. This is the opposite of silent failure.

#### S16 Diagnostics

Device panel (model, Android version, RAM, low-RAM mode state, HDMI audio caps, supported HDR, available decoders for H.264/HEVC/MPEG-2). **Network test** (for the selected playlist): DNS resolves (system vs DoH), TCP connect ms, HTTP status, API latency, throughput test on the current stream for 10 s against the stream's bitrate, with a verdict: "Your connection: 38 Mbit/s. Stream needs 8 Mbit/s. Buffering is likely on the provider side." **Logs**: export with credentials and query strings redacted, to file or to the phone page.

#### S17 Update prompt

A glass card at app start when a new version exists ("Version 1.0.3 · Fixes catch-up on Flussonic servers · [Update] [Later]"). It never interrupts playback; it shows on the guide only.

### 2.4 Error states catalogue (player and guide)

| Condition (detected how) | Headline | Actions |
|---|---|---|
| Auth failure (401, `auth:0`) | "Your provider rejected the login" | Account details · Edit login |
| Expired (`status:Expired`) | "Your subscription expired on 3 Oct" | Account details |
| Connection limit (403/458/509 right after zap, or `active_cons >= max`) | "Your account allows 1 stream and another device is using it" | Retry · Stop and wait |
| Offline channel (404) | "This channel isn't broadcasting right now" | Try backup source · Previous channel |
| Format refused (405/406) | Retry silently with TS↔HLS first. Shown only if both fail: "The server refused this stream format" | Diagnostics |
| HTML or tiny body instead of a stream | "The server sent a web page instead of video (often an expiry notice)" | Account details |
| Timeout / no data in 15 s | "No video received from the server" | Retry · Diagnostics |
| Codec unsupported (MediaCodec) | "This TV box can't decode HEVC 10-bit" | Try software decoder · Open in external player |
| No audio after 3 s of video | Auto-switch to software/PCM first, then toast. Card only if that also fails | Audio options |
| Catch-up 404 | "This programme isn't in the provider's archive" | Watch live · Try other catch-up mode (advanced) |
| DNS failure | "Can't find the server. Your ISP may be blocking it" | Use secure DNS (one tap) · Diagnostics |

The mini player shows the same states as a compact still: logo, headline, and an amber dot.

---

## 3. Guide UX details the design does not cover

### 3.1 Time scrolling: focus-driven, snapped, never free-scrolling

- **There is no free horizontal scrolling.** The window moves only because focus moves. When focus moves right onto a cell whose start is outside the visible 180 min, the window animates (200 ms, ease-out) so the cell start sits about 25 % in. Snap the window start to the nearest :00/:30 so the ruler always shows clean ticks, which the design's 30-min ticks depend on.
- **Sticky anchor.** The design anchors Up/Down on "focused start + 1 min". That drifts badly: moving down through a 3-hour film moves the anchor 2 h back. Instead, keep `anchorTime = max(focused.start, windowStart)` and **update it only on Left/Right and day/now jumps**. Up/Down pick the cell on the next row that contains `anchorTime`. This matches what users expect: "what else is on at 21:00".
- **Gaps and no-EPG rows.** Fill gaps with "No information" pseudo-cells, cut into **60-min blocks aligned to the hour**. A channel without EPG then behaves like a time ruler. Otherwise Right on an EPG-less row is one endless cell, and Up/Down anchoring breaks. Label: "No guide data · Fix" (long-press OK leads to matching).
- **Hold-to-scroll.** Key repeat is coalesced (architecture §2.3). After 1 s of holding Right, step by 30 min of window regardless of cell boundaries, then land focus on the cell under the anchor when released.

### 3.2 Day navigation

- **Day chip** in the header row, styled as a pill. It reads "Today" / "Yesterday" / "Wed 15 Oct". OK opens a horizontal **day strip** from the archive limit to +6 days. Days beyond any archive or EPG are dimmed and unfocusable. Selecting a day keeps the **current clock time** (anchor time + N days), because "what's on tomorrow at this time" is the common intent. A second item "Tonight 20:00" jumps to prime time.
- FF/RW jump ±3 h. Moving past midnight with Left/Right just continues. The date label in the top-right updates, and the grid header label ("TONIGHT" in the design) becomes contextual: EARLIER TODAY / THIS AFTERNOON / TONIGHT / TOMORROW / YESTERDAY / {WEEKDAY}.

### 3.3 Jump to now

Three routes: the **Back key** (first press when not at now and the tuned channel), a **Now chip** that appears in the header when the now line is out of view (with an arrow showing direction), and a Play/Pause press while in the header row. A "Now ›" edge marker sits at the ruler edge when now is off-screen, so the user always knows which way to go.

### 3.4 6 rows × 2,000 channels

- **Edge scrolling with one-row lookahead:** focus moves freely through rows 1–5, and on row 6 pressing Down scrolls the list by one, so the next channel is always visible. Centre-locked scrolling looks smart but makes the focused row jump around on a 6-row grid.
- CH± and long-press of the Groups chip page by 6. Number keys jump. **Hold Down** accelerates to about 15 rows/s with the channel-number bubble.
- The real answer to 2,000 channels is **fewer channels**: onboarding group hiding, Favourites as the default filter once a list has 5 or more channels (ask once: "Open the guide on Favourites?"), and Search.
- The design's 6 rows are a deliberate density choice (big focused-programme panel). Users with big lists will compare it to TiviMate's denser grid [K]. Plan **Compact density** for v1.1 (smaller panel, 9 rows of 48 px), and gate it on whether beta users ask.
- Data: the grid queries `(channel window ± 20 rows) × (time window ± 3 h)` from Room. Never load 2,000 rows of programmes.

### 3.5 Past / archive region

- **Channel column:** a small ↺ glyph after the channel name for archive-capable channels. The focused-programme meta row adds "Catch-up · 7 days".
- **Cells in the past:** if the channel has archive and the programme is within `archiveDays`, keep the cell text at `.85` alpha (not the design's `.45`) with a ↺ before the time. Otherwise use `.45` (design "past"). The difference between "watchable" and "gone" is the main affordance, so it must be visible at 3 m.
- **Region beyond the archive depth:** cells older than a channel's archive window look like no-archive past cells. A subtle vertical boundary tick on each row marks that channel's archive start when it is on screen.
- **How far back the guide scrolls:** `max(archiveDays over channels in the current group, pastDaysToKeep)`, floored to midnight. If the current group has no archive channels, the limit is `pastDaysToKeep` (default auto = 1 day).
- **Trust:** `has_archive` from Xtream `get_simple_data_table` is authoritative when fetched (only fetch it for the focused channel, debounced). A catch-up 404 marks *that channel's* archive as suspect for the session, and the ↺ turns hollow. Providers that flag `tv_archive=1` without real archive are a known [S] problem.

### 3.6 Future programmes: Remind (v1) vs Record (v2)

- The v1 secondary button is **Remind / Reminder set**. The footer hint row changes from "R Record" to "Hold OK  Options" (and "Red  Remind" if colour keys exist).
- Reminder behaviour: at T−1 min, a card slides up bottom-right over the guide or player: "{Title} starts in 1 min on {Channel}" with [Switch now] (focused) and [Dismiss], plus a 60 s countdown. With "Auto-switch" on, it switches at T−0. **Foreground only in v1.** The confirmation toast says so the first time.
- v2 brings back **Record** as the secondary button. Remind moves into the long-press menu.

### 3.7 "Watch from start" (programme in progress)

| Channel state | Primary button | OK on cell | "Watch from start" does |
|---|---|---|---|
| Archive available (resolves to a mode, programme start within archive) | **Watch now**, with **From start** shown as a second button (Red key / long-press menu) | Tune live | Opens full screen on the archive URL at `programme.start − 1 min` (padding), with the scrub bar visible for 4 s. The bar runs from programme start to now and grows. ↑ in the bar jumps to live. At programme end it continues into the next programme from archive. Xtream timeshift needs a fixed `duration`, so request `(now − start) + 2 h` and re-request seamlessly near the end. |
| Archive flagged but probe fails (404) | Watch now | Tune live | Error card "isn't in the archive", then falls back to live. The channel's ↺ goes hollow for the session. |
| No archive, v1 | Watch now (no From start) | Tune live | Not offered. Long-press menu shows it disabled with the reason "No catch-up on this channel". |
| No archive, v1.1 with local buffer | Watch now | Tune live | Offered only if the local buffer already covers `programme.start` (you have been tuned since before it started). Otherwise disabled with the same reason. |

The design's primary label logic ("Watch from start" for past programmes) is right for **finished** programmes. Rename it **"Watch"** for finished archive programmes and keep **"Watch from start"** for in-progress ones, so the label always tells the truth.

### 3.8 Mini player and connection limits

- In this design the mini player plays the **tuned** channel, not the focused one. It is the same Media3 instance and the same single connection as full screen. **On a 1-connection account the design costs nothing extra.** That is a point in its favour; keep it.
- Never auto-preview the focused channel in v1. Preview-on-focus (1.5 s dwell) arrives in v1.1, **only** when `max_connections >= 2`, and it releases the tuned stream first if the limit would be exceeded.
- The mini player shows a **still** (channel logo centred on the ambient hue, the now title, and "Press OK to watch") when nothing is tuned (autoplay off, or after an error), when the channel is locked, when the "Mini player: Still image" setting is chosen, or automatically in low-RAM mode on devices under 1.5 GB (decoding plus guide rendering together is what makes Fire TV Stick Lite stutter [E]).
- The LIVE pill needs states the design lacks: **LIVE** (red pulsing), **CATCH-UP** (accent), **PAUSED** (white 12 %), and **−MM:SS** (behind live).

### 3.9 Back from the guide

The order is: close any overlay → snap to now on the tuned row → go full screen if something is playing → "Press Back again to exit" (2 s). The exit behaviour is configurable (double-back / dialog / none). This avoids two classic complaints: "Back exits the app" and "I can never leave the app".

### 3.10 Guide header additions

The header gains: the **sync chip** (only while syncing or failed), a **gear** after the tabs, and the **Group** and **Day** chips in the grid header (the Group chip sits in the 200-wide label column in place of the static "TONIGHT" label; the contextual time-of-day label moves to the ruler's left edge).

---

## 4. Settings specification (v1)

Legend:
- **Scope**: G = global, P = per playlist, C = per channel (set from the channel long-press menu, also listed here). Settings marked "G+C" have a global default with a per-channel override.
- **Vis**: S = simple (always shown), A = advanced (shown when "Show advanced settings" is on).
- **#** is the top-25 complaint the setting exists to fix.

Target: the simple view has **at most 35 rows** across all sections, and no section is deeper than two levels.

### 4.1 Settings root (simple view)

Playlists · TV guide · Playback · Channels & groups · Interface · Parental controls · Backup & restore · About & diagnostics. The advanced view adds Network and Remote control.

### 4.2 Playlists (one page per playlist)

| Setting | Type | Default | Scope | Vis | Why / # |
|---|---|---|---|---|---|
| Name | text | host name | P | S | |
| Source (server/user/pass or URL/file) | form | – | P | S | Providers rotate URLs. Editing in place must keep favourites (#6). |
| Account status | read-only card | – | P | S | #14 |
| Refresh now / last refresh report | action + info | – | P | S | #6 (show what changed) |
| Update on app start | toggle | On if last update is over 12 h old | P | S | Freshness without hammering |
| Update interval | 6 h / 12 h / 24 h / Manual | 24 h | P | A | Large lists are expensive |
| Channel numbers | Provider / Playlist order / Start at N | Provider (`tvg-chno`/`num`), else playlist order | P | S | #6, #17 (number entry must stay stable) |
| Channel order | Provider / Number / Name | Provider | P | S | |
| Use guide data from playlist | toggle | On | P | A | `x-tvg-url`, `xmltv.php` |
| User-Agent | The Box / VLC / Browser / Custom | The Box (auto-switch to VLC on a 403 probe, then explained) | P | A | #8. Do not impersonate another IPTV app by default. |
| Extra headers / Referer | key-value list | empty | P | A | #8 |
| Stream format (Xtream) | Auto / TS / HLS | Auto (TS, switch on 405/406) | P | A | #8 |
| Backup servers | URL list | empty | P | A | #2 |
| Catch-up mode | Auto / Xtream / Default / Append / Shift / Flussonic / Off | Auto (probe and remember) | P | A | #13 |
| Catch-up days | number | from source | P | A | Providers mis-flag archives |
| Catch-up time zone | Auto / UTC / Device / Server / pick zone | Auto (Xtream: server zone, M3U `{Y}`: UTC) | P | A | #13. The number-one catch-up bug. |
| Catch-up padding | before/after minutes | 1 / 5 | P | A | EPG times are inaccurate |
| Max connections | number | Xtream: from account (read-only). M3U: 1 | P | A | Drives preview, failover and v2 recording |
| Ignore TLS errors | toggle with warning | Off | P | A | Self-signed panels |
| Remove playlist | action | – | P | S | |

### 4.3 TV guide (EPG)

| Setting | Type | Default | Scope | Vis | Why / # |
|---|---|---|---|---|---|
| Guide sources | ordered list (enable, add URL/file, assign to playlists) | playlist-provided | G | S | #4 |
| Update now / status | action + info (last update, programme count, match %) | – | G | S | #3, #4 (no silent failure) |
| Fix unmatched channels | screen | – | G | S | #4. The flagship fix. |
| Update interval | 6 h / 12 h / 24 h / daily at a set time | 12 h | G | A | |
| Automatic matching | Exact only / Exact + names / + fuzzy suggestions / + fuzzy auto (≥ 0.92) | Exact + names, fuzzy as suggestions | G | A | Wrong mappings are worse than none |
| Guide time shift | ±12 h in 0.5 h steps | 0 | G+P+C (per source and per channel) | A | #4 |
| Time zone when the source has no offset | zone | UTC | per source | A | DST breakage |
| Past days to keep | Auto (max catch-up days, minimum 1) / 1 / 2 / 3 / 7 | Auto | G | A | Catch-up needs past data. #5 memory. |
| Future days to keep | 3 / 7 / 14 | 7 | G | A | #5 |
| Store programme descriptions | toggle | On (Off in low-RAM mode) | G | A | #5 |
| Logos from | Playlist / Guide | Playlist | G | A | |

### 4.4 Playback

| Setting | Type | Default | Scope | Vis | Why / # |
|---|---|---|---|---|---|
| Buffer | **Auto (adaptive)** / Small / Medium / Large / Extra large | Auto: starts at about 1.5 s for zapping, grows to 5→10→20 s after rebuffers, decays after 10 min stable | G+C | S | #1, #12. The research disagrees on the default: protocol §6.3 says Small, user-issues says adaptive. **Adaptive wins** because it serves zapping and Wi-Fi without user tuning. |
| Frame-rate matching | Off / Seamless only / Always | Seamless only (API 30+, hidden below that) | G | S | #16. "Seamless only" avoids black-screen mode switches, the reason the protocol doc defaulted to Off. |
| Audio output | Auto / Always decode (PCM) / Stereo | Auto (passthrough only for formats the HDMI sink reports) | G | S | #9 |
| Preferred audio language | ordered list (up to 3) | device language, then original | G (remembered per channel) | S | #9 |
| Subtitles | Off / On (preferred language) / Forced only | Off | G (remembered per channel) | S | |
| Aspect ratio | Fit / Fill / Zoom / 16:9 / 4:3 | Fit | G+C (remembered) | S | #16 |
| On launch | Guide with last channel / Full screen last channel / Guide, nothing playing | Guide with last channel | G | S | #17 |
| Video decoder | Auto (hardware, platform software fallback) / Hardware only / Software preferred | Auto | G+C | A | #3, #16. "Software" means platform `c2.android.*` decoders. Media3's FFmpeg extension is audio-only. |
| Software audio fallback (FFmpeg) | toggle | On | G | A | #9 |
| Tunneled playback | toggle | Off | G | A | Known black screens on some chipsets |
| Reconnect attempts | 3 / 5 / 10 / Unlimited | 5 (about 15 s total) | G | A | #2 |
| Fail over to backup server/source | toggle | On | G | A | #2, #10 |
| After standby over 15 min | Resume / Open guide, paused | Resume (with fresh URL) | G | A | #11 |
| Info bar duration | 3 / 5 / 8 s | 5 s | G | A | |
| Number entry wait | 1 / 1.5 / 2.5 s | 1.5 s | G | A | #17 |
| External player | None / pick an installed app | None | G (+ "Open in…" per channel) | A | Escape hatch until v2 (libVLC) |

### 4.5 Channels and groups (management, not toggles)

| Item | Type | Default | Scope | Vis | Why / # |
|---|---|---|---|---|---|
| Groups: show/hide, lock, reorder | list editor | all shown, adult locked | P | S | #15 |
| Hide rules | keyword/regex list with live preview count | empty | P | A | #15 (bulk) |
| Hidden channels | list (unhide) | – | P | S | Undo path for hide |
| Favourites lists | create, rename, reorder, delete | one list, "Favourites" | G | S | #6, #17 |
| Open guide on | All channels / Favourites / Last group | Last group | G | S | |

### 4.6 Interface

| Setting | Type | Default | Scope | Vis | Why / # |
|---|---|---|---|---|---|
| Language | list | system | G | S | |
| Clock | 12 h / 24 h | system | G | S | |
| Channel logos in guide | toggle | On | G | S | |
| Mini player | Video / Still image | Video (Still in low-RAM mode) | G | S | #5 |
| Performance mode | Auto / High quality / Low-end device | Auto (`isLowRamDevice` or RAM under 2 GB → Low-end: no blur, no ambient blobs, logos capped) | G | S | #5. One switch instead of five. |
| Ambient background | toggle | On (Off in Low-end) | G | A | |
| Show channel numbers | toggle | On | G | A | |

### 4.7 Remote control (advanced section)

| Setting | Type | Default | Scope | Why / # |
|---|---|---|---|---|
| Up/Down in player | Change channel / Open channel list | Change channel | G | #17 (TiviMate muscle memory [K]) |
| Invert channel direction | toggle | Off | G | |
| Back in player | Go to guide / Open channel list | Go to guide | G | |
| Exit confirmation | Press Back twice / Ask / None | Press Back twice | G | #17 (accidental exits) |
| Colour keys | read-only map in v1, editable in v1.1 | as §2.1 | G | |

### 4.8 Parental controls

| Setting | Type | Default | Scope | Vis | Why / # |
|---|---|---|---|---|---|
| PIN | 4 digits | unset | G | S | #15 |
| Lock adult content automatically | toggle | On | G (applies per playlist) | S | #15 |
| Locked channels in guide | Show with lock / Hide | Show with lock | G | S | |
| Lock settings and playlist editing | toggle | Off | G | S | |
| Stay unlocked | Until app closes / 1 h / Ask every time | Until app closes | G | S | |
| Forgotten PIN | "Clear app data" only, stated plainly | – | – | S | Honest. No backdoor. |

### 4.9 Network (advanced section)

| Setting | Type | Default | Scope | Why / # |
|---|---|---|---|---|
| DNS | System / Cloudflare (DoH) / Google (DoH) / Quad9 (DoH) / Custom DoH | System (and the DNS error card offers a one-tap switch) | G | #3, #24 ISP blocks |
| Prefer IPv4 | Auto / On / Off | Auto (switches on after AAAA connect failures) | G | 20–75 s connect stalls |
| Connect / read timeout | seconds | 10 / 20 | G | |

### 4.10 Backup and restore

| Setting | Type | Default | Scope | Vis | Why / # |
|---|---|---|---|---|---|
| Back up now (to file / to phone) | action | – | G | S | #20 |
| Restore (from file / from phone) | action | – | G | S | #20 |
| Include logins | toggle with passphrase | On (passphrase required) | G | S | #22 |
| Weekly automatic backup (local) | toggle | On | G | A | #20 (reinstall) |

### 4.11 About and diagnostics

| Setting | Type | Default | Scope | Vis | Why / # |
|---|---|---|---|---|---|
| Version / check for updates | action | – | G | S | #23 |
| Check automatically | toggle | On | G | S | #23 |
| Diagnostics | screen | – | G | S | #24 |
| Export logs (redacted) | action | – | G | S | #24 |
| Send crash reports | toggle | **Off** | G | S | #22 |
| Open-source licences | screen | – | G | S | Legal (LGPL/OFL) |
| Reset app | action (PIN if set) | – | G | S | |

### 4.12 Per-channel (channel long-press menu, not in Settings)

Favourite (choose list) · Hide · Lock · Fix guide data (source + EPG id) · Guide time shift · Aspect · Audio language (remembered automatically) · Video decoder · Buffer · Open in external player · Channel info (stream URL masked, archive mode and days, match type). v1.1 adds Sources/order and Rename.

**Settings that exist because of a known complaint**: Fix unmatched channels, guide time shift and missing-offset zone (#4); adaptive buffer (#1/#12); User-Agent and extra headers (#8); backup servers and failover (#2/#10); catch-up mode, zone and padding (#13); audio output and software audio fallback (#9); frame-rate matching and decoder (#16); performance mode and mini-player still (#5); hide rules and auto adult lock (#15); exit confirmation and Up/Down behaviour (#17); after-standby (#11); DNS (#24); backup with logins (#20); crash reports off (#22).

---

## 5. Issue-prevention plan (top 25)

Verification notation: **Unit** = JVM test, **Inst** = instrumented, **Bench** = Macrobenchmark on reference hardware (Fire TV Stick 4K 1st gen, Onn 4K, CCwGTV HD, Shield as high end), **Manual** = scripted manual check on reference hardware, **Soak** = long-run rig.

| # | Complaint | Decision in The Box | Verification | Phase |
|---|---|---|---|---|
| 1 | Slow zapping / black flash | One player instance that persists across screens. `stop()` the old stream before `prepare` (connection limit). Small initial buffer (adaptive). Keep the last frame. Logo card on zap. No pre-connect on 1-connection accounts. | Bench: `zap` trace section, P50/P90 over 100 zaps on a local TS/HLS loop server. Gates (to re-baseline [E]): P90 under 1.0 s on Shield, under 2.0 s on Fire TV Stick 4K. Manual: no black frame on 20 real-provider zaps (screen recording). | v1 |
| 2 | No automatic recovery | Retry ladder (silent reconnect on EOF up to 3 s, then backoff 0.5/1/2/4 s), **re-resolve from the original URL** each retry, backup-server rotation, and toasts showing progress. | Inst: MockWebServer fixtures for EOF mid-TS, 5xx bursts, 302 to an expired-token URL, and HLS media-sequence reset. Expect recovery under 5 s without user input. Soak: a 12 h proxy fault injector (random drops every 2–10 min). | v1 |
| 3 | "Playback error" | Error taxonomy (§2.4): every failure maps to a headline, detail code and actions. A lint rule bans showing raw exception text. | Unit: a table test mapping each HTTP/codec/auth condition to its card. Manual: an error-state gallery screen in debug builds, reviewed at 3 m viewing distance. | v1 |
| 4 | Wrong / missing / stale EPG | Streaming parser, UTC storage, lenient time parsing, ordered sources resolved per channel, matching ladder with fuzzy-as-suggestion, unmatched list, per-source and per-channel shift, conditional GET, failure chip that persists. | Unit: XMLTV fixtures (no offset, `+00:00`, missing stop, overlaps, gz with Content-Encoding twice, base64 Xtream EPG). A matching corpus of 2k real channel names with expected matches, gate ≥ 95 % precision on auto. Manual: the DST weekend (last Sunday of October is 2026-10-25) on a European source. | v1 |
| 5 | Laggy guide on low-end devices | Custom-drawn grid with a single virtual focus, draw-phase scroll reads, Room window queries, a performance mode that drops blur/ambient/logos, Baseline Profiles. | Bench: FrameTimingMetric, 100 × D-pad down and 100 × right with the mini player playing. Gate P90 under 16.7 ms and P99 under 33 ms on Onn 4K and Fire TV Stick 4K. Release blocker. | v1 |
| 6 | Favourites/order lost on refresh | Stable identity (Xtream `stream_id`; else `tvg-id`+name; else normalised URL path without host/creds/token). Diff-merge, "missing" state for 7 days instead of delete, refresh report, last good copy kept. | Unit: refresh fixtures (renamed channel, rotated token, new host, reordered list, removed then re-added). Assert favourites, numbers, hidden flags and EPG maps survive. Manual: change provider DNS in Edit source and confirm favourites are intact. | v1 |
| 7 | Crash on huge playlists | Okio line streaming, 64 KB line cap, 5k-row transactions, VOD/series classified and stored but not loaded in v1. | Bench: 150k-entry and 250k-entry M3U import on Fire TV Stick 4K. Gate: no OOM, under 60 s [E], heap under 100 MB (via `dumpsys meminfo` sampling). | v1 |
| 8 | "Works in VLC, not here" | Header precedence (pipe > EXTVLCOPT/EXTHTTP > EXTINF > playlist > default), UA probe at onboarding, content sniffing (0x47 / `#EXTM3U`), cross-protocol redirects, TS↔HLS switch, external-player escape hatch. | Unit: parser fixtures for each header syntax. Inst: MockWebServer that 403s unless the UA or Referer matches, serves TS with `text/html`, and redirects http→https→http. Manual: a compat matrix of at least 10 real providers/panels tracked in a sheet. | v1 (libVLC v2) |
| 9 | No sound / wrong track | Query `AudioCapabilities`, passthrough only for supported formats, FFmpeg audio (ac3, eac3, mp2, aac_latm, dca), auto-fallback after 3 s with no audio, per-channel language memory. | Inst: fixtures for AC-3, E-AC-3, MP2 and LATM against a PCM-only sink. Manual: Fire TV via TV speakers, via ARC soundbar, and via optical (E-AC-3 must fall back). | v1 |
| 10 | No failover / duplicates | v1: server failover list plus a schema with channel 1..n sources. v1.1: duplicate merge and quality ranking. | Unit (v1): schema and migration test that one channel holds several sources. Inst (v1.1): primary 404 → secondary in under 3 s. | v1 schema + server failover, v1.1 merge |
| 11 | Black screen after standby/CEC | On `onStop`, release the stream. On `onStart`, re-acquire the surface, re-resolve the URL, and resume at the live edge. Keep-screen-on during playback. | Manual script: TV off via CEC for 1 min, 20 min and 8 h; HDMI input switch; Fire TV sleep. Pass = playback within 3 s of return, no black screen. Run on each reference device every release. | v1 |
| 12 | Buffering that settings could fix | Adaptive buffer default, manual override per channel, stats overlay showing buffer ms. | Inst: throttled-proxy test (Wi-Fi jitter profile). Rebuffer count must fall under adaptive vs fixed Small. Manual: Wi-Fi 2.4 GHz at evening peak. | v1 |
| 13 | Catch-up broken / wrong times | All modes with auto-probe-and-remember, UTC internally, server zone for Xtream, separate display shift vs `catchup-correction`, padding, timestamp-based re-request (never byte Range). | Unit: URL-builder golden tests per mode and zone (including DST boundaries). Manual: at least 3 Xtream panels and 1 Flussonic source; a programme from 2 days ago starts within ±2 min of the EPG start. | v1 |
| 14 | Unclear account state | Account card, expiry countdown with amber/red, connections `x/y`, structured auth errors. | Unit: `user_info` fixtures (string numbers, `null` exp, `auth:0` with 200, HTML body). Manual: an expired trial account. | v1 |
| 15 | Unusable giant lists / adult content | Onboarding group review, hide rules, auto adult lock, PIN. | Unit: adult detection on group-name fixtures. Manual: a 2,000-channel list; time to "my 20 channels" under 2 min for a first-time tester. | v1 |
| 16 | Judder / wrong aspect / decoder issues | `Surface.setFrameRate` seamless-only with 1 s dwell debounce while zapping, per-channel aspect memory, decoder capability check at startup with a warning for SW-only HEVC. | Manual: 24p, 25p, 50i and 59.94 test streams on Shield and Onn. Confirm the mode-switch log and absence of judder (filmed pan test). | v1 |
| 17 | Remote ergonomics | The 5-key rule, number entry, Previous channel pinned row and Blue key, long-press menus, Back ladder, TiviMate-compatible defaults. | Manual: task-based test with 5 TiviMate users (find channel 501, go back to the previous channel, change audio, start over) on a CCwGTV remote (no number keys). Compare time per task with TiviMate on the same device. | v1 |
| 18 | Painful onboarding | Phone QR setup, `get.php` splitting, UA probe, start watching before EPG finishes. | Manual: 5 first-time users. Gate: median time from install to first live picture under 3 min with the phone path. | v1 |
| 19 | Memory leaks / crashes over long use | Player in a process-scoped controller, bounded Coil caches, LeakCanary in debug, StrictMode. | Soak: 12 h zap bot (every 20 s) on Fire TV Stick 4K. Gate: heap trend under +10 % after hour 1, zero ANRs and crashes. | v1 |
| 20 | Lost setup on reinstall | JSON backup (passphrase-encrypted logins), phone-page download/upload, weekly local auto-backup. | Inst: round-trip test (export → wipe → import → identical DB hash of user tables). Manual: reinstall a sideloaded APK. | v1 (cloud sync v2) |
| 21 | Unfair licensing | v1 free. Model decided before v1.1. Never gate EPG days, favourites, multiple playlists or buffer settings. | Product review sign-off before v1.1. | v1.1 decision |
| 22 | Privacy | No telemetry by default, Keystore-encrypted credentials, redaction in logs and crash reports, masked fields. | Unit: redaction tests (URLs with `username=`, path creds `/live/u/p/`, pipe headers). Manual: grep the exported log for test credentials. | v1 |
| 23 | Distribution fragility | Per-ABI and universal signed APKs, in-app updater with SHA-256 and `PackageInstaller`, Downloader short code, version manifest on our own domain. | Inst: updater test with tampered hash (must refuse). Manual: update from v1.0.0 → 1.0.1 on Fire TV with "unknown apps" enabled. | v1 |
| 24 | No diagnostics | Stats overlay, network test with verdict, sync status, log export. | Manual: support-scenario drills ("buffering", "no EPG") that a tester resolves using only the in-app diagnostics. | v1 |
| 25 | Recording/time-shift data loss | v1: honest Pause behaviour on non-archive channels (no fake time-shift). v1.1: disk ring buffer with a free-space check. v2: DVR with foreground service, exact alarms, conflict checks against connection limits, SAF/SMB checks at schedule and start time. **Amended 2026-10-19:** the disk ring buffer moved into v1 (owner request; `design/m6-timeshift.md`): every live TS channel pauses and rewinds from a local buffer (Auto 30 min on ≤ 16 GB storage, 1 h above), sized against free space with a reserve, discarded on zap, Home and standby with a toast on return; catch-up channels use the buffer first and the provider archive beyond it and for gaps; HLS still freezes with an honest reason toast (HLS DVR window is a v1.1 item); flash wear is capped by an internal 12 GB/day rule on small devices. | v1: Inst I-TS-1…30 (`TimeShiftBufferTest`; wrap, seek, full ring, gaps, slot loss, eviction, Home, zap confirm) plus the JVM ring suites; manual flash-wear, screensaver and process-death steps (design §17.4). v2: Soak with an overnight scheduled recording, a mid-recording network drop, and USB removal. | v1 (honest pause on HLS; ring buffer on TS), v1.1 (HLS DVR window), v2 |

---

## 6. Differentiators vs TiviMate

### 6.1 Commit for v1 (high payoff, bounded effort)

| Differentiator | Effort | Payoff | Reasoning |
|---|---|---|---|
| **Catch-up, multiple playlists, full EPG days, favourites and themes free** | ~0 (a pricing decision) | Very high | TiviMate's gating is the top licensing complaint [V]. This is the cheapest headline we will ever get. |
| **Phone QR setup** | ~1 week (embedded server plus 1 static page) | Very high | The first 5 minutes decide retention. TiviMate has none [K]. |
| **EPG matching UI with suggestions and an unmatched list** | 2–3 weeks | High | "No EPG" is the most common confusion. Users currently use external M3U editors [S]. |
| **Actionable errors and built-in diagnostics with a verdict** | 2 weeks | High | Turns "the app is broken" into "my provider is broken". Reduces our support load. |
| **Account clarity (expiry, connections)** | days | Medium-high | Trivial with the data already in `user_info`. |
| **Refresh report and identity that survives refresh** | part of the core (2 weeks) | High | Trust. We must build it anyway, and surfacing it is free. |
| **Secure DNS toggle with the one-tap fix from the error card** | days | Medium (huge in UK/IT/ES/PT) | OkHttp has it built in. |
| **The design itself**: ambient, big focused panel, mini player | already designed | Medium | It looks better than TiviMate. Not a retention driver on its own, but it is the screenshot that sells the sideload. |

### 6.2 Defer

| Differentiator | Phase | Why not v1 |
|---|---|---|
| Duplicate merge / multi-source | v1.1 | 2–3 weeks plus UX for the source picker. Schema in v1. |
| SMB recording, series rules, conflict-aware DVR | v2 | The DVR is a release of its own. Doing half of it creates data-loss complaints (#25). |
| Cross-device sync | v2 | Backup covers reinstall. Sync needs pairing and conflict resolution. |
| Stalker portal | v2 | Separate protocol, MAC-lock grey area, smaller audience. |
| Watch Next / home-screen channels | v2 | Low switching value. Google TV policies change often. |
| Multi-view, PiP | v2 | Hardware- and connection-limited. Expensive QA matrix. |
| Per-channel AFR/decoder profiles beyond overrides | v1.1 | Overrides ship in v1. "Profiles" are polish. |

**Not a differentiator, just table stakes:** zap speed, AFR, passthrough, number entry. Ship them at least as well as TiviMate, but do not market them.

---

## 7. Risks

### 7.1 Legal and app stores

- **Positioning:** "a player for playlists you already have". **No bundled content, no sample playlists (not even iptv-org), no provider links, no "free IPTV" wording, no screenshots showing real paid channels with provider branding.** Screenshots use our own test channels and EPG (generate a fake XMLTV with invented channels).
- **Google Play (Android TV):** IPTV players are allowed in principle, but Google removes them reactively, and removal is reported for TiviMate [S, unverified cause]. Treat Play as a bonus channel, not the plan. Keep a store-safe build flavour: no Downloader-code marketing, no DoH preset descriptions framed as "bypass blocking" (describe it as "secure DNS").
- **Sideload distribution:**
  - **Android developer verification:** Google announced in 2025 that apps installed on certified Android devices, *including sideloaded ones*, must come from registered developers, with rollout from 2026 starting in some countries [K, verify the current timeline and whether Google TV / Android TV devices are in scope]. If it applies, register an identity before v1 public release, or sideload installs may be blocked. **This is the single largest distribution risk.**
  - **Fire TV on Vega OS:** Amazon's newer Fire TV devices on Vega OS (Linux-based, from late 2025) reportedly cannot run Android APKs at all [K, verify]. Our Fire TV addressable base will shrink to existing Fire OS devices. Track it, and do not promise "all Fire TV".
  - Amazon Appstore listing for Fire OS: possible, with the same no-content positioning. Policies on "IPTV" are strict and reviewer-dependent.
- **ClearKey and DRM:** playlists that carry `kid:key` ClearKey pairs are overwhelmingly ripped premium streams. Supporting them invites "facilitating circumvention" arguments (DMCA §1201 / EU InfoSoc Art. 6) and app-store takedown. **Recommendation:** do not ship ClearKey-from-playlist. Widevine with a provider licence URL (legitimate FAST/OTT) can come in v1.1.
- **Naming and branding:**
  - **"The Box" is a trademark risk.** It is the name of a long-running UK music TV channel brand (The Box), which could literally appear in a user's guide. Run a trademark search (UK IPO, EUIPO, USPTO, classes 9/38/41) before public release and consider a distinctive name.
  - Refer to "Xtream Codes" only descriptively ("Xtream Codes API compatible"), with no logos. The original Xtream Codes service was shut down in a 2019 anti-piracy operation, so lean on "XC-compatible login" wording.
  - Name TiviMate only in factual comparisons, if at all.
- **Privacy law:** with no telemetry by default and no server receiving playlist URLs, GDPR exposure is minimal. Crash reporting (opt-in) needs a privacy notice and a processor agreement with whichever crash service is chosen.

### 7.2 Licensing

| Component | Licence | Obligations / risk |
|---|---|---|
| FFmpeg (audio decoders via Media3 `decoder_ffmpeg`) | LGPL 2.1+ (if built **without** `--enable-gpl` / `--enable-nonfree`) | Ship as a shared library (it is, inside the AAR). Include licence text, state the FFmpeg version, publish the exact source and build script used (the CI script and tag), and allow replacement (dynamic linking satisfies this in practice). Add a CI check that fails if the FFmpeg configure line contains `--enable-gpl`. |
| Codec patents (AC-3, E-AC-3, DTS, AAC, MP2) | Not copyright | AC-3 core patents are widely reported expired, and MP2/MP3 are expired. **E-AC-3 and DTS patent status is unverified here.** Prefer platform decoders and passthrough (OEM-licensed) and use FFmpeg only as a fallback. **Get a one-hour legal opinion before monetising.** This is the reason Media3 does not ship a prebuilt FFmpeg AAR. |
| Instrument Sans, JetBrains Mono | SIL OFL 1.1 | Bundle the licence text in "Open-source licences". Do not sell the fonts standalone. Fine to embed. |
| Roboto / Roboto Mono (fallback) | Apache 2.0 / OFL | Notices. |
| Media3, OkHttp, Room, Coil, Kotlin libraries | Apache 2.0 | NOTICE aggregation (use the OSS-licences Gradle plugin). |
| QR generation (e.g. ZXing core) | Apache 2.0 | Notice. |
| Embedded HTTP server (NanoHTTPD BSD-3 or Ktor Apache 2.0) | permissive | Notice. |
| v2: smbj (Apache 2.0) preferred over jcifs-ng (LGPL 2.1) | | Keeps the licence surface small. |
| v2: libVLC | LGPL 2.1 core, but **some modules are GPL** | Needs a module audit. This is one more reason it is deferred. |
| Xtream API | de-facto, reverse-engineered | No copyright issue for interoperability. Trademark only (above). |

### 7.3 Provider compatibility

| Risk | Likelihood | Mitigation | Evidence level |
|---|---|---|---|
| UA/Referer gating; some panels whitelist other apps' UAs | High | Probe at setup, presets, per-playlist override. **Do not default to impersonating TiviMate/Smarters** (ethical and ToS risk); let the user choose. | [observed] |
| Connection-limit rejections during fast zaps (`active_cons` lags) | High | Close before open, 300–500 ms retry, a clear message, no second stream on 1-connection accounts. | [observed] / [E] |
| Catch-up URL and zone variance across panel forks | High | Probe-and-remember per playlist, advanced overrides, padding. Build a **provider compat matrix** in beta (at least 10 panels: XC 2.9, XUI, NXT, Flussonic, plain M3U/HLS, iptv-org-style). | [observed] |
| API flood bans when fetching per category / big lists | Medium | Max 3 parallel API requests, backoff on 429, cache, no VOD fetch in v1. | [observed] |
| `xmltv.php` huge or slow, or mislabelled time offset | High | Conditional GET, whitelist parse, offset detection with a suggestion ("guide looks 1 h early, fix?"). | [observed] |
| DNS/IP/SNI blocking on match days | Medium (region-specific) | DoH helps DNS only. Be honest that IP/SNI blocks need a VPN, and don't break when a VPN is active. | [observed] |
| Providers that lock to their own app or MAC (Stalker) | Medium | Out of scope for v1. Show an explicit "this looks like a Stalker portal, not supported yet" message. | [K] |
| Self-signed/expired TLS, broken IPv6 | Medium | Per-playlist TLS ignore (warned), Happy Eyeballs, IPv4 preference. | [observed] |
| Device fragmentation (Fire OS 6–8, Amlogic/MediaTek/Realtek decoders, HDMI audio caps misreported) | High | Reference-device lab of at least 4 devices as a release gate. Audio auto-fallback. Tunneling off by default. | [E] |
| Beta support load shifts to "my provider is down" | High | Diagnostics verdict and wording that attributes the cause correctly ("provider side"). | [E] |

---

## 8. Changes requested to the design reference

1. Hide the **Recordings** tab and add a **gear** for Settings. Swap **Record** for **Remind** in v1, and change the footer hint from "R Record" to "Hold OK  Options".
2. Make the Group chip and Day chip part of the grid header, add a Now chip, and make the section label contextual.
3. Add mini-player pill states (LIVE / CATCH-UP / PAUSED / −MM:SS) and a still-image mode.
4. Define past-cell styling for archive (`.85` with ↺) vs no-archive (`.45`), and a ↺ glyph in the channel column.
5. Change the anchor rule from "focused start + 1 min" to a sticky anchor (§3.1), and add "No information" 60-min pseudo-cells.
6. Primary button labels: **Watch** (finished archive programme), **Watch from start** (in progress, archive), **Watch now / Watching** (live), **Remind** (future).
7. Design the error card, the import-progress screen, the PIN pad, the channel list panel and the info bar in the same glass language (specs in §2.3).
