# TiviMate feature inventory (2025-2026) and opportunities for The Box

Confidence legend: [V] = confirmed by 2+ web sources fetched in this session; [S] = single third-party source (often affiliate/IPTV-seller blog, treat with caution); [K] = from analyst prior knowledge of the app, NOT re-verified this session; [?] = uncertain/conflicting.
CAVEAT: tivimate.com returned almost no content (no official FAQ/changelog reachable; /faq = 404), Reddit and Play Store were not reachable via the tools. Nearly all web evidence is third-party SEO/affiliate pages that contradict each other on free-vs-premium splits. Verify against the in-app Premium screen and Play listing before using any row as a hard spec. Latest version seen: 5.3.x (mirror site; 5.2.0 dated 2025-08-28). (fact-checked 2026-10-13: the Google Play listing and APKMirror were reached. Play shows `ar.tvplayer.tv` updated 22 Jun 2026 (5.3.3 per reviews), 5M+ installs, 4.2★ overall and 3.8★ on TV. APKMirror lists 5.1.5 (2025-01-21), 5.2.0 (2025-08-30) and 5.3.2 (2026-06-02, "Android 6.0+", i.e. minSdk 23). Reddit is still unreachable.)
Column "Box" = priority for The Box: v1 / v2 / later / skip.

## Licensing and distribution
| Item | TiviMate | Conf. | Box |
|---|---|---|---|
| Free tier | Live playback of 1 playlist + basic guide; many QoL features locked | V | v1 (generous free tier is a differentiator) |
| Premium price | ~US$9.99/yr (EUR 9.99) or lifetime ~US$29.99-33.99; 7-day trial reported; price varies by country (fact-checked 2026-10-13: Play reviews report the annual price rising from $6.99 to $11.99 (Jan 2026), a £30 lifetime (Mar 2025) and "$45" (Oct 2025). Still unresolved) | ? | v1 decide model |
| Devices per licence | 5 devices (Premium account tied to Google/Companion login) | V (5 per [S]) | v1 |
| Platforms | Android TV/Google TV, Fire TV (sideloaded, not in Amazon Appstore), Android phones; no iOS, tvOS, Tizen, webOS | V | v1 Android TV + Fire TV |
| Fire TV purchase | Must buy via TiviMate Companion app on an Android phone, then activate | V | v1 simple licensing |
| Closed source, not on Play Store? | TiviMate IS on Google Play for Android TV (purchase via Play on Android TV); Fire TV is sideload. (fact-checked 2026-10-13: confirmed live on 2026-10-13, but it was intermittently delisted in 2026 (April, back on 6 June, more reports in July), so both earlier claims were partly right) | V | n/a |
| Support | Developer email; some reviewers claim support is poor/paid [S Trustpilot] | ? | v1 |

## A. Playlist and source management
| Feature | Free/Premium | Conf. | Box |
|---|---|---|---|
| M3U URL | Free | V | v1 |
| M3U local file | Free | S | v1 |
| Xtream Codes API login | Free | V | v1 |
| Stalker/MAG portal | Supported (one guide); quality reportedly weaker / limited | S/? | v2 (do properly) |
| Multiple playlists | Premium (free = 1) | V | v1 (consider free) |
| Playlist update interval / update on app start | Settings per playlist; auto-update | V (EPG interval "every 4h" guide) | v1 |
| Manual playlist refresh | Free | V | v1 |
| "Use EPG from Xtream" toggle | Yes | S | v1 |
| EPG source per playlist (XMLTV URL(s), Xtream built-in) | Yes; multiple EPG sources per playlist [K] | V/K | v1 |
| EPG update schedule (interval, on app start) | Auto-update is Premium; free manual [?, conflicting] | ? | v1 |
| Past days to keep EPG | Setting (1d free vs up to 7-14d premium [S]) | S | v1 |
| Store programme descriptions toggle (memory saver) | Yes | S | v2 |
| Channel group management: hide groups, reorder, rename | Premium-ish [K]; guide lists "Manage Groups" | S/K | v1 |
| Hide channels | Yes | S | v1 |
| Reorder channels / custom sort (playlist order, by name, by number) | Premium [K] | K | v1 |
| Rename channel, change logo/custom icon | Yes [K] | K | v2 |
| Channel numbering (use tvg-chno / playlist order, manual numbers) | Yes | K | v1 |
| Favourites | Reported both free and premium (conflict) | ? | v1 |
| Multiple favourite lists / custom groups | Premium [K] | K | v1 |
| Dummy channels (local video/URL as a channel) | Yes | S | later |
| Parental control with 4-digit PIN: lock groups, channels, settings menu | Premium | V | v1 |
| Search (channels, EPG programmes, VOD) | Yes | V | v1 |
| tvg-id mismatch EPG fixing | Manual EPG-channel mapping per channel [K]; users rely on external M3U editors | S/K | v1 auto-match + manual map (big win) |
| External player routing (VLC, MX) per stream | Yes | S | v2 |
| Multi-URL failover per channel | Not available (TiviMate treats duplicate channels separately; no merge) [K, unverified] | ? | v1 (win) |

## B. Guide / EPG UI
| Feature | Free/Premium | Conf. | Box |
|---|---|---|---|
| Grid guide (channels x time) | Free | V | v1 |
| Mini preview video window in guide | Yes | K | v1 |
| Programme info panel/details (title, time, description, poster/art if available) | Yes | K | v1 |
| Reminders for programmes | Premium [K] | K | v2 |
| Categories (groups) panel/view | Yes | K | v1 |
| Now/next in channel list, progress bars | Yes | K | v1 |
| Day navigation / jump to day, time jump | Yes | K | v1 |
| Catch-up indicator in guide (archive icon) | Premium (catch-up) | K | v1 |
| Navigate into past within guide for archive | Premium | K | v1 |
| Channel list shown on left during playback (OK/Up) | Yes; layout options | K | v1 |
| Guide zoom / row-height and time scale | Setting [K] | K | v2 |
| Font/text size, theme/colours, accent, transparency | Themes/colours/transparency Premium (one source: background colour needs Premium) | S/? | v1 (give free) |
| Clock format 12/24h | Yes | K | v1 |
| Modern vs classic guide layout | Layout choices exist (e.g. channel list style options) [K] | ? | v2 |
| Guide EPG days (free 1 day; premium 7-14) | Gated | S | v1 (don't gate) |

## C. Player
| Feature | Free/Premium | Conf. | Box |
|---|---|---|---|
| Engine | ExoPlayer/Media3 based; HW decoder, SW decoder (FFmpeg) options; "Auto" | V | v1 |
| Hardware/software video decoder choice | Yes | V | v1 |
| Audio decoder (auto/HW/SW), passthrough option | Yes [K] | S/K | v1 |
| Tunneled playback | Option on some devices | S | v2 |
| Buffer size (None/Small/Medium/Large, plus "Very/Extra large" in some guides; no source gives seconds) | Yes | V (fact-checked 2026-10-13: labels from third-party guides only) | v1 |
| Auto frame rate (AFR) matching, resolution switch | Yes | S | v1 |
| Aspect ratio modes (fit, fill, zoom, 16:9, 4:3...) | Yes | K | v1 |
| Audio and subtitle track pick; default language preferences | Yes (one source says premium) | ?/K | v1 |
| Auto-play last channel on start; start on boot | Yes | K | v1 |
| Number-key channel entry | Yes | K | v1 |
| Previous/last channel (back-and-forth) | Yes | K | v1 |
| Channel info bar duration setting | Yes | K | v1 |
| Stream info overlay (resolution/codec/bitrate) | Long-press OK | S | v1 |
| Picture-in-picture | Listed Premium by one source; other lists it as player option (fact-checked 2026-10-13: Fire TV supports in-app PiP per the Fire OS 6 developer docs, so the platform allows it) | ? | v2 |
| Multi-view (2/4, up to 9 claimed, device-dependent; long-press OK to add; tap audio focus; dbl-tap fullscreen) | Premium | V | v2 |
| Zoom | Aspect modes | K | v1 |
| Sleep timer | Yes [K] | K | v2 |
| Pause/restart live buffer | Premium time-shift (see D) | K | v1 |

## D. Catch-up and time-shift
| Feature | Free/Premium | Conf. | Box |
|---|---|---|---|
| Catch-up/archive (provider-dependent: Xtream timeshift, tvg-rec/catchup attributes: default, append, shift, flussonic, xc) | Premium | V (premium) / K (types) | v1 |
| Pause live TV | Free? local buffer limited; [?] | ? | v1 |
| Start over / restart current programme | Yes when catch-up available | K | v1 |
| Archive nav in guide | Premium | K | v1 |
| Catch-up days setting | via EPG days to keep (7 for catch-up) | S | v1 |

## E. Recording (DVR)
| Feature | Free/Premium | Conf. | Box |
|---|---|---|---|
| Manual, scheduled, series ("record series") | Premium | V | v1 manual+schedule, v2 series |
| Storage: internal, USB; SMB/NAS claimed by one source, historically not supported [?] (fact-checked 2026-10-13: a June 2025 Play review reports failing to record "using SMB to my QNAP NAS", so the option exists but is unreliable. Another reports scheduled recordings stopping at 87 min) | Premium | S | v1 USB/internal, v2 SMB (a reliability win rather than a feature gap) |
| Padding (reported default 2 min before / 10 after; configurable) | Yes | S | v1 |
| Recording quality | Records raw stream (no transcoding) | K | v1 |
| Recordings list + playback, resume | Yes | V | v1 |
| Conflict handling | Limited; provider connection limits make concurrent recordings fail; recording while app in foreground issues reported by users | S/? | v2 |
| Depends on stream permitting (some Xtream streams disallow) | | S | note |

## F. VOD and series
| Feature | Free/Premium | Conf. | Box |
|---|---|---|---|
| Xtream VOD/Series browsing (movies, series, seasons/episodes) | Added in 4.x; free-tier VOD availability disputed | ? | v1 |
| Posters/cover art, plot, ratings; TMDB enrichment | Premium (per one 2026 comparison) | S | v1 (TMDB key optional) |
| Continue watching / resume | Yes | K | v1 |
| M3U-based VOD detection | Limited [K] | K | v2 |

## G. System integration
| Feature | Free/Premium | Conf. | Box |
|---|---|---|---|
| Android TV home "Watch next"/channels | Limited/absent [K, unverified] | ? | v2 |
| Google Assistant / voice search | Limited/absent [K] | ? | later |
| Backup/restore (Google Drive; weekly auto-backup; local file) | Premium | V | v1 file + cloud |
| Settings sync across devices via account | Via Companion account: licence & backup (fact-checked 2026-10-13: a Feb 2026 Play review says "Purchased lifetime premium for cloud sync", which failed on a second device. A premium cloud-sync feature exists, but its scope is unclear) | S/? | v2 (win) |
| Companion app (phone) | Handles licence, device management (5 devices), backup | V | v2 |
| Remote button mapping (long-press OK menu, number keys, colour keys, channel +/-) | Fixed mapping, some remappable [K] | K | v1 |
| Boot on start | Yes | K | v1 |

## Settings menu tree (reconstructed; names approximate, check in app)
Sources are guides that disagree on grouping. Reconstructed tree:
- Playlists: Add playlist (M3U URL / M3U file / Xtream / Stalker); per-playlist: Name, Update playlist, Auto-update interval, Update on start, EPG source(s), Use Xtream EPG, User-agent/headers [K], Manage groups (show/hide/reorder/rename), Channel sorting, Remove playlist, Catch-up type override [K]
- EPG (TV guide): EPG sources, Update EPG, Update interval (e.g. 4h), Update on app start, Store programme descriptions, Past days to keep EPG, Match channels / EPG mapping [K], Timezone offset/shift [K]
- Appearance: Language, Theme / background colour, Text colour, Font size, UI transparency, Logos (show/size/style), Clock format, Channel list layout, TV guide layout/zoom
- Playback / Player: Video decoder (auto/HW/SW), Audio decoder, Tunneled playback, Buffer size, Auto frame rate, Aspect ratio default, Audio language, Subtitle language, Start on last channel, Start on boot, Channel info bar timeout, PiP, Multi-view, Preferred external player
- Recording (Premium): Storage location (internal/USB/[SMB]), Padding before/after, Default recording behaviour, Delete rules [K]
- Parental control (Premium): Enable, PIN, locked groups/channels, lock Settings
- General: Backup & restore (Drive/file), Auto backup weekly, Premium/licence status & Companion login, About/version, Reset
- Main menu (left rail): Live TV/channels, Guide, Movies, Series, Catch-up, Recordings, Search, Settings.

## H. Known limitations / complaints (verified status)
1. Android TV app IS on Google Play; Fire TV needs sideload + Companion purchase - partial; friction is real [V].
2. Closed source, solo-developer, slow roadmap; changelog 5.0.3 -> 5.2.0 over 2024-2025 mostly "fixes and improvements" [S mirror].
3. Per-device/account cap (5) and annual price; free tier is crippled (1 playlist, short EPG, no catch-up/DVR) [V]. (fact-checked 2026-10-13: Play reviews add that VOD and playlist editing are premium, a changed M3U URL cannot be edited in place (you must re-add the playlist and lose favourites), the server forces updates with no downgrade, and premium purchase requires a phone hand-off that fails on some phones.)
4. Buffering/error 458 and playback failure reports after 5.2 update [S Trustpilot, anecdotal; likely provider-side, uncertain]. (fact-checked 2026-10-13: a Play review on 5.3.3 (Aug 2026) reports 458 on every fast-forward or rewind in VOD, which suggests session or range rejection. 458 is not a registered HTTP code.)
5. EPG: mapping depends on tvg-id; free EPG window short; complaints "no EPG" mostly misconfiguration [S].
6. Multi-view capped (4-9) and device-limited; mid/low-end Fire sticks struggle [S].
7. SMB/NAS recording: conflicting (one source claims supported) [?]. Recording conflicts and "app must be running" complaints [S].
8. No iOS/tvOS/Tizen/webOS [V].
9. No Dolby Vision / advanced HDR tone-mapping controls (depends on ExoPlayer/device) [K, unverified].
10. Stalker support quality weaker than dedicated apps [? unverified].
11. No multi-URL failover / duplicate-channel merging [K, unverified].
12. Home-screen integration and voice search weak [K, unverified].
13. Grid performance on low-end devices with 10k+ channels/large EPG (memory) [K, user reports commonly].

## Competitors (brief, from search summaries [S])
- OTT Navigator: most customisable (custom categories, filters, PiP, multiple playlists), steep learning curve; free with premium option.
- Televizo: lightweight, fast; premium one-time lifetime (~5 devices reported); fewer advanced features.
- Sparkle TV: TiviMate-like UI, DVR reported reliable, PiP, Chromecast; free tier lacks VOD/series; smaller community.
- Kodi PVR IPTV Simple: not researched in depth this session.

## Top 10 opportunities for The Box
1. Generous free tier: multiple playlists, full EPG days, favourites, themes ungated; charge only for DVR/cloud sync.
2. Multi-URL failover and duplicate-channel merge (quality ranking) - TiviMate lacks.
3. Smart EPG auto-matching with fuzzy name matching + visual mapping UI + multiple EPG sources merged.
4. First-class Stalker/MAG portal support.
5. SMB/NFS/NAS recording, series rules, conflict resolution and connection-limit awareness.
6. Cross-device settings sync (favourites, order, resume position, playlists) free via account/QR, no phone-purchase dance for Fire TV.
7. Android TV Watch Next/home channels, Assistant voice search.
8. Performance on low-end devices: virtualised grid, DB-backed EPG, lazy loading of 50k channels.
9. Pricing: one-time or per-household rather than per-device; clear licence terms.
10. Modern player extras: Dolby Vision/HDR passthrough, per-channel decoder/buffer profiles, AFR, multiview with layout presets, built-in stream health diagnostics.

## Sources
- https://tivimate.me/tivimate-premium (premium table, purchase flow) [S]
- https://cloudje.eu/en/tivimate-guide/ (features, DVR/multiview settings, EPG days, hardware) [S, affiliate]
- https://tivimateiptvplayer.net/mastering-tivimate/ (settings tree) [S]
- https://shop.plume.com/blogs/news/tivimate ; https://vivimate.io/blog/tivimate-premium ; https://tvimate4k.com/tivimate-premium-vs-free/ ; https://freeiptvcheck.com/blog/tivimate-premium ; https://iptvranking.com/tivimate-free-vs-premium/ (conflicting free/premium tables) [S]
- https://www.ottv.org/blog/tivimate-setup-guide ; https://settingsguide.com/tivimate-playlist-settings/ ; https://docs.uplay.se/en/guide-tivimate (menu paths) [S]
- https://ca.trustpilot.com/review/tivimate.org (complaints) [S]
- https://tivimate-iptv-player.soft112.com/ (version history 5.0.3/5.2.0/5.3.3) [S]
- https://tivimatecompanion.com/ (Companion app) [S]
- https://www.indiehackers.com/post/best-iptv-player-in-2026-top-apps-compared-for-smooth-streaming-2oOJLIME93c4vi5IrGGp (competitor comparison) [S]
- Not reachable/not verified: tivimate.com (empty), tivimate.com/faq (404), Reddit r/TiviMate, YouTube. (fact-checked 2026-10-13: the Play Store listing and reviews (https://play.google.com/store/apps/details?id=ar.tvplayer.tv) and APKMirror have since been reached.)
