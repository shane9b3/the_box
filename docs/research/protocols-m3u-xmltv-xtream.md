# The Box: IPTV Protocol & Format Reference

Scope: M3U/M3U8 playlists, XMLTV EPG, Xtream Codes API, catch-up/time-shift, stream delivery, and the settings surface of a TiviMate-class Android TV player.

Confidence levels used below:
- **[spec]** comes from a primary spec or source code (xmltv.dtd, pvr.iptvsimple source, Flussonic docs, inputstream.adaptive wiki).
- **[de-facto]** is widely implemented but has no formal spec (most of M3U-IPTV and all of Xtream Codes).
- **[observed]** comes from field reports or community docs. Verify it against real panels.

---

## 1. M3U / M3U8 extended playlists

### 1.1 Grammar (de-facto)

```
#EXTM3U [header-attrs...]
[#EXTGRP:Group]                      (optional, begin-directive in Kodi)
#EXTINF:<duration> [attr="value" ...],<Display Name>
[#EXTVLCOPT:key=value]               (0..n, between EXTINF and URL)
[#KODIPROP:key=value]                (0..n)
[#EXTHTTP:{"Header":"value"}]        (0..n, OTT Navigator style)
<stream URL>[|Header=Value&Header2=Value2]
```

- `<duration>` is `-1` for live. `0` is tolerated, and VOD entries may carry real seconds.
- The display name is everything after the **first comma that is outside quotes**. Commas inside quoted attribute values are common (`group-title="News, UK"`), so a naive `split(',')` breaks. The parser must be quote-aware.
- Attributes are `key="value"`. In the wild you also see `key='value'`, unquoted `key=value`, and empty `key=""`. Keys should be matched case-insensitively (`tvg-ID`, `Group-Title` both occur).
- Auxiliary lines can sit between `#EXTINF` and the URL, so the URL is the first line that is neither blank nor `#`-prefixed. Any unknown `#` line is ignored.
- `.m3u8` implies UTF-8. A plain `.m3u` may be Latin-1 or Windows-1252.

### 1.2 `#EXTM3U` header attributes

| Attribute | Meaning | Notes |
|---|---|---|
| `x-tvg-url` | XMLTV URL(s) | Kodi uses it only when no EPG is set in settings. Some lists hold comma-separated URLs. |
| `url-tvg` | Synonym of `x-tvg-url` | Common in Russian/OTT-Navigator lists. |
| `tvg-url` | Synonym seen in the wild | Accept all three. |
| `tvg-shift` | Default EPG shift in hours for all channels | Per-channel value overrides. |
| `catchup`, `catchup-source`, `catchup-days`, `catchup-correction` | Playlist-wide catch-up defaults | Per-channel values override. |
| `url-logo` | Base URL prefixed to relative `tvg-logo` | |
| `refresh` | Reload interval (seconds) | Rare. |
| `max-conn` | Provider connection limit | Rare, informational. |
| `m3uautoload`, `cache`, `deinterlace`, `aspect-ratio`, `crop` | Legacy player hints (SS-IPTV, Perfect Player) | Ignore safely. |

### 1.3 `#EXTINF` attributes seen in the wild

| Attribute | Semantics | Source |
|---|---|---|
| `tvg-id` | Key into XMLTV `<channel id>`. iptv-org uses the form `Name.cc@Feed`, e.g. `2MMonde.ma@SD`. | spec (Kodi) |
| `tvg-name` | EPG name and secondary match key. Historically, underscores stood for spaces. | spec (Kodi) |
| `tvg-logo` | Logo URL. Kodi appends `.png` when a relative value has no extension. | spec (Kodi) |
| `tvg-chno` (alias `ch-number`, `channel-number`, `tvg-num`) | Channel number (LCN) | spec / de-facto |
| `tvg-shift` | Per-channel EPG offset in hours, decimals allowed (`-1.5`). Some players read values outside ±12 as seconds. | spec (Kodi) |
| `group-title` | Group. Kodi accepts several groups separated by `;`. | spec (Kodi) |
| `tvg-language`, `tvg-country` | Filtering metadata. Values may be comma- or semicolon-separated, as names or ISO codes. | de-facto (iptv-org legacy) |
| `tvg-type` | `live` / `movie` / `series`. Some generators emit it, and Xtream-generated lists do not. | observed |
| `radio="true"` | Audio-only channel | spec (Kodi) |
| `catchup` (alias `catchup-type`) | Catch-up mode (see §4) | spec (Kodi) / de-facto |
| `catchup-source` | Template or query fragment | spec |
| `catchup-days` | Archive depth in days | spec |
| `catchup-correction` | Hours added to archive request times | spec (Kodi) |
| `timeshift` | Legacy: forces SIPTV `shift` mode, and its value is the days | spec (Kodi) |
| `tvg-rec` | Legacy archive flag/days (`>0` means archive) | spec (Kodi treats it like SIPTV `timeshift` days when `timeshift` is absent; also OTT Navigator) (fact-checked 2026-10-13: PlaylistLoader.cpp) |
| `catchup-time` | Archive window in seconds | observed |
| `parent-code` / `adult="1"` / `censored="1"` | Parental lock flag or PIN | observed |
| `user-agent` / `http-user-agent` | Per-stream UA | iptv-org emits `http-user-agent=` on EXTINF and duplicates it in `#EXTVLCOPT` |
| `http-referrer` / `referer` / `http-referer` | Per-stream Referer | iptv-org emits `http-referrer=` (spelled with a double r) |
| `http-origin` | Per-stream Origin | observed |
| `audio-track`, `video-track` | Preferred track index or language | observed (OTT Navigator) |
| `provider`, `provider-type`, `provider-logo`, `provider-countries`, `provider-languages` | Kodi provider metadata | spec (Kodi 20+) |
| `media`, `media-dir`, `media-size` | Mark the entry as VOD media | spec (Kodi) |
| `realtime="false"` | Treat as non-live | spec (Kodi) |
| `type="playlist"` | The URL is a nested playlist to include | observed |
| `tvg-logo-small`, `tvg-epgid`, `xui-id`, `xtream-id` | Assorted generator extras | observed, ignore |

Implementation rule: keep **every** unknown attribute in a `Map<String,String>` on the channel model. Never drop data at parse time.

### 1.4 Directives

- **`#EXTGRP:<name>`**: In Kodi this is a *begin-directive* for all following channels until an empty `#EXTGRP:` or an explicit `group-title` appears. OTT Navigator and many other players treat it as per-entry only. Recommendation: per-entry if `group-title` is absent, and sticky only when a playlist-level option says so.
- **`#EXTVLCOPT:key=value`**: The keys that matter are `http-user-agent`, `http-referrer`, `http-origin`, `http-cookie`, and `network-caching`. Kodi turns `http-user-agent`/`http-referrer` into HTTP headers.
- **`#KODIPROP:key=value`** (inputstream.adaptive):
  - `inputstream=inputstream.adaptive` (Kodi 19+, `inputstreamaddon=` on 18)
  - `inputstream.adaptive.manifest_type=mpd|hls|ism`. Deprecated in Kodi 21 and removed in Kodi 22 because the type is auto-detected. It is still used as a hint.
  - `inputstream.adaptive.license_type=com.widevine.alpha|com.microsoft.playready|org.w3.clearkey` (`clearkey` also occurs)
  - `inputstream.adaptive.license_key=<url>|<headers>|<body>|<response>`. This is the legacy four-field format, where the body uses `R{SSM}` (raw challenge), `b{SSM}`/`B{SSM}` (base64), `{SID}`, and `{KID}`. For ClearKey, the value may be `kid:key` in hex, or JSON.
  - `inputstream.adaptive.drm_legacy=<keysystem>|<license URL or kid:key,...>|<url-encoded headers>` (Kodi 21+)
  - `inputstream.adaptive.drm=<JSON>` (Kodi 22+): `{"com.widevine.alpha":{"license":{"server_url":"...","req_headers":"...","server_certificate":"<b64>","keyids":{...}}}}`
  - `inputstream.adaptive.license_data`, `server_certificate`
  - `inputstream.adaptive.stream_headers` (streams only from Kodi 21), `manifest_headers` (Kodi 20+), `common_headers` (Kodi 22+). Values are URL-encoded `a=b&c=d`.
  - `inputstream.adaptive.play_timeshift_buffer=true`, `live_delay`, `manifest_config` (JSON)
  - On Android, map these to Media3: `DrmConfiguration` (Widevine, ClearKey) with `licenseUri` and request headers. ClearKey `kid:key` is converted to a local JSON key response (`LocalMediaDrmCallback`).
- **`#EXTHTTP:{json}`**: An arbitrary header map (OTT Navigator).
- **`#EXTIMG:`, `#EXTALB:`, `#EXTART:`, `#PLAYLIST:`**: Generic M3U extras. Ignore them, or use `#PLAYLIST` as a title.
- **`#EXT-X-*`**: These are HLS tags. If the downloaded "playlist" contains `#EXT-X-TARGETDURATION` or `#EXT-X-STREAM-INF`, the user gave you a **stream manifest, not a channel list**. Offer to add it as a single channel. Kodi treats `#EXT-X-PLAYLIST-TYPE:VOD` in a channel list as "not live".

### 1.5 Pipe-header URLs

`http://host/stream.m3u8|User-Agent=Foo%20Bar&Referer=https://x/&Origin=https://x`

- Split on the **last** `|` that is followed by `key=` pairs. Some real URLs contain `|`, but rarely.
- Values may be URL-encoded. Decode them once.
- In Kodi, non-standard headers need a `!` prefix (`|!X-Token=abc`). Strip a leading `!`.
- Precedence, highest first: pipe headers, then `#EXTVLCOPT`/`#EXTHTTP`, then EXTINF `http-*`, then the playlist-level UA setting, then the app default UA.

### 1.6 Edge cases checklist

| Case | Handling |
|---|---|
| UTF-8 BOM `EF BB BF` before `#EXTM3U` | Strip it. Also accept a missing `#EXTM3U` header, which some lists omit. |
| UTF-16 LE/BE BOM | Detect it and transcode. |
| Non-UTF-8 (CP1251, CP1252, Latin-1) | Try strict UTF-8 decode, and on failure use charset detection (ICU `CharsetDetector` or juniversalchardet). Also honour the `Content-Type; charset=` header. |
| CRLF / lone CR | Normalise to LF and trim trailing whitespace. Trailing `\r` inside URLs breaks requests. |
| gzip | Check the magic `1F 8B` regardless of extension or `Content-Encoding`. Some servers double-compress. Also handle `.xz` (`FD 37 7A 58 5A 00`) and zip (`50 4B`). |
| Duplicate display names | Do not key by name. Build a stable ID from `hash(tvg-id, normalized URL path minus credentials, name, group)`, and keep favourites, history and hidden flags keyed by that ID. Allow the user to re-map favourites when providers rotate stream IDs. |
| Missing `tvg-id` | Fall back to `tvg-name`, then the display name, for EPG matching (§2.5). |
| `tvg-id` present but empty or junk (`""`, `"none"`, `"0"`) | Treat it as missing. |
| Embedded Xtream credentials (`http://h:p/user/pass/123`, `/live/user/pass/123.ts`, `get.php?username=&password=&type=m3u_plus`) | Detect them and offer to "upgrade to Xtream API login", which unlocks VOD/series metadata, catch-up and short EPG. Redact credentials in logs and UI. |
| 50k–200k entries with VOD mixed in | Stream-parse line by line (`BufferedSource`/Okio). Never read the whole file into a `String`. Bulk-insert into Room/SQLite in transactions of about 2–5k rows with FTS for search. Classify live/VOD/series by URL path (`/movie/`, `/series/`), by extension (`.mp4`, `.mkv`, `.avi`), by `tvg-type`, or by `group-title` heuristics. Show progress, and do a diff-update by stable ID instead of drop-and-reinsert. |
| Huge single lines (base64 logos) | Cap line length at about 64 KB and skip anything longer. |
| Relative URLs | Resolve them against the playlist URL. |
| `rtmp://`, `rtsp://`, `udp://@239.x.x.x:port`, `rtp://` | Multicast needs a udpxy-style relay setting: `http://relay:port/udp/239.x.x.x:port`. |
| `#EXTINF` lines with no URL, or URL lines with no `#EXTINF` | Skip the first. Accept the second as a channel named after the URL. |
| Mixed `http://` and `https://` | Allow cleartext (`usesCleartextTraffic=true` / network security config). Many providers are http-only. |

---

## 2. XMLTV EPG

### 2.1 Document structure [spec: xmltv.dtd]

```xml
<tv generator-info-name="..." source-info-url="...">
  <channel id="bbc1.uk">
    <display-name lang="en">BBC One</display-name>   <!-- 1..n -->
    <icon src="https://..." width="" height=""/>      <!-- 0..n -->
    <url>...</url>
  </channel>
  <programme start="20261013190000 +0100" stop="20261013200000 +0100"
             channel="bbc1.uk" [pdc-start vps-start showview videoplus clumpidx="0/1"]
             [catchup-id="..."]>   <!-- catchup-id: Kodi extension -->
    title+ sub-title* desc* credits? date? category* keyword* language? orig-language?
    length? icon* url* country* episode-num* video? audio? previously-shown?
    premiere? last-chance? new? subtitles* rating* star-rating* review* image*
  </programme>
</tv>
```

- **Times:** `YYYYMMDDhhmmss ±HHMM`. Any leading substring is legal, and **no offset means UTC**. In the wild you also see a missing space (`20261013190000+0100`), the offset `+00:00`, and named zones (`GMT`, `UTC`). Parse leniently.
- `stop` is optional. If it is absent, infer it from the next programme's `start` on the same channel.
- Programmes can overlap or contain gaps. Clip overlaps, and render gaps as "No information".
- **episode-num:**
  - `system="xmltv_ns"`: `S.E.P`, **zero-based**, with optional `/total`. For example, `2.9.0/2` means Season 3, Episode 10, Part 1 of 2. Parts may be empty (`.9.`).
  - `system="onscreen"`: free text such as `S03E10`.
  - `system="dd_progid"`: a Gracenote/Schedules Direct ID such as `EP01234567.0012`. The `EP`/`SH`/`MV`/`SP` prefix tells you episode, show, movie or sports, and it is useful for series-recording matching.
  - Also seen: `system="thetvdb.com"`, `"themoviedb.org"`, `"imdb.com"`, `"SxxExx"`.
- **credits** (fixed order): `director actor writer adapter producer composer editor presenter commentator guest`. `actor` takes `role` and `guest="yes"` attributes.
- **rating:** `<rating system="MPAA"><value>PG-13</value><icon/></rating>`. **star-rating:** `<star-rating><value>7.5/10</value></star-rating>`.
- **Flags:** `<new/>` (genuinely first broadcast), `<previously-shown start="" channel=""/>`, `<premiere/>`, `<last-chance/>`. Note: *absence of previously-shown does not imply new*.
- `video` (`present`, `colour`, `aspect`, `quality` such as `HDTV`/`UHD`), `audio` (`stereo`: mono/stereo/dolby/dolby digital/bilingual/surround), `subtitles type="teletext|onscreen|deaf-signed"`.
- `image` (newer DTD): `type="poster|backdrop|still|person|character" size="1|2|3" orient="P|L" system="tmdb"`.
- `lang` attribute on text elements: choose by the user's language preference, falling back to the first.
- `category`: free text, so map it to genre colours with a configurable mapping. Kodi's `genres.xml` maps text to the DVB EIT content nibble.

### 2.2 Compression and transport
- `.xml.gz`: very common. Detect by magic `1F 8B`, not by extension. Some hosts serve gz **with** `Content-Encoding: gzip`, and OkHttp then transparently decompresses once, so check the magic again after that.
- `.xml.xz`: use `org.tukaani:xz` or Apache Commons Compress.
- `.zip` containing a single XML: seen occasionally.
- Use conditional GET (`If-None-Match`/`If-Modified-Since`) to avoid reparsing 300 MB when nothing changed.
- Xtream: `http://host:port/xmltv.php?username=U&password=P`. Some panels accept `&prev_days=N&next_days=N`. Output is usually `+0000`, but often uses the **server's local offset**, or mislabels local time as `+0000`, which is the classic offset bug (§2.6).

### 2.3 Huge files (100–500 MB uncompressed, millions of programmes)
- **Streaming pull parsing only.** `XmlPullParser` (KXmlParser on Android) or StAX over a `GZIPInputStream(BufferedInputStream(…, 64K))`. Never use DOM.
- **Two-pass filter or a channel whitelist:** while parsing, keep only `<programme>` elements whose `channel` belongs to the set of EPG IDs referenced by enabled playlists. This typically discards 80–95% of a big global EPG such as epg.best or iptv-org's.
- **Time window:** drop programmes with `stop < now − max(catchupDays, pastDaysToKeep)` or `start > now + futureDays`.
- **Storage:** SQLite (Room) with the table `programme(channel_key, start_utc, stop_utc, title, …)`, index `(channel_key, start_utc)`, and an FTS4/5 table for search. Batched inserts of about 5k rows in one transaction, with `PRAGMA journal_mode=WAL` and `synchronous=NORMAL` during import. Write into a staging table and swap atomically so the guide stays usable during the refresh.
- Intern strings (categories, channel IDs). Skip `desc` length beyond about 2 KB, and store icons as URLs only.
- Run it in a `WorkManager` worker with a foreground notification. A low-RAM TV box (1–2 GB) must stay under about 100 MB heap.
- A rough target from field experience: about 1M programmes in under 60 s on an S905X4-class box.

### 2.4 Multi-source merging
- There is an ordered list of EPG sources, both playlist-supplied (`x-tvg-url`, Xtream `xmltv.php`) and user-added.
- **Per-channel resolution:** the first source (in priority order) that has data for the channel wins **for the whole channel**. Do not interleave programmes from two sources, because that produces overlaps.
- Allow a per-channel manual override of the "EPG source + EPG channel id" pair. TiviMate exposes this as *Channel → EPG → choose source/channel*.
- Deduplicate `<channel>` entries with the same id across sources, using the first one. Merge `display-name`s into the alias set used for matching.

### 2.5 Channel ↔ EPG matching (ordered)
1. Manual override.
2. Exact `tvg-id` == `channel@id`. This is case-sensitive by spec, but Kodi offers "ignore case", so make case-insensitive the default.
3. Xtream `epg_channel_id` (same as 2 for XC sources).
4. `tvg-name` == any `display-name` (normalised).
5. Display name == any `display-name` (normalised).
6. Fuzzy: normalise by lower-casing, NFKD with diacritics stripped, removing tokens such as `HD|FHD|UHD|4K|SD|HEVC|H265|50FPS|\(.*\)|\[.*\]|backup|VIP`, stripping country prefixes `^(UK|US|DE|FR)[:|\- ]+`, and collapsing non-alphanumerics. Compare by Jaro-Winkler or token-set ratio ≥ 0.92 within the same country hint. **Mark fuzzy matches as such** so the user can confirm them. Never auto-apply fuzzy matches below the threshold.
7. Cache the result per channel and recompute only when the inputs change.

### 2.6 Time-zone problems
- Store everything as **UTC epoch** internally, and convert to the device zone only for display.
- The provider's EPG is often wrong by a whole number of hours. This is the reason for the attribute `tvg-shift` (hours, per channel or playlist) and for the player-side **EPG time shift** setting (global, per source, per channel). Apply shift to *display and matching only*, never to catch-up request times. Those use `catchup-correction` instead.
- DST: XMLTV with fixed offsets is DST-safe. Providers that emit local time without an offset break twice a year. A per-source "assume timezone X when offset is missing" option fixes this.
- Xtream `server_info.timezone` / `time_now` / `timestamp_now` let you detect a server clock skew: compute `skew = timestamp_now − deviceNowUtc`. Xtream timeshift URLs need **server-local** wall time (§3.3). (fact-checked 2026-10-13: confirmed. The panel parses the segment with PHP `strtotime()` in `server_info.timezone`; see IPTVnator PR #1563.)

---

## 3. Xtream Codes API (de-facto; XC 2.9.x, XUI.one, Xtream UI R22, NXT, StreamCreed, 1-stream, etc.)

### 3.1 Endpoints
Base: `http(s)://host:port`. The port is commonly 80, 8080, 25461 or 2095, and HTTPS commonly 25463 or 443.

| Purpose | URL |
|---|---|
| Login / account | `GET /player_api.php?username=U&password=P` |
| Actions | `GET /player_api.php?username=U&password=P&action=<action>[&params]` |
| M3U export | `GET /get.php?username=U&password=P&type=m3u_plus&output=ts` (`type`: `m3u`, `m3u_plus`; `output`: `ts`, `m3u8`/`hls`, `rtmp`) |
| XMLTV | `GET /xmltv.php?username=U&password=P` |
| Legacy panel | `GET /panel_api.php?username=U&password=P` returns user_info, server_info, categories and available_channels in one huge payload (often disabled) |
| Enigma2 | `/enigma2.php?username=&password=&type=get_live_categories` (legacy) |

Credentials may also arrive via POST form fields on some panels. URL-encode username and password, because `@`, `#`, `&` and `+` occur.

### 3.2 `player_api.php` actions

| action | Params | Returns |
|---|---|---|
| *(none)* | – | `{user_info:{…}, server_info:{…}}` |
| `get_live_categories` | – | `[{category_id, category_name, parent_id}]` |
| `get_live_streams` | `category_id` (optional) | live streams |
| `get_vod_categories` | – | categories |
| `get_vod_streams` | `category_id` (optional) | movies |
| `get_vod_info` | `vod_id` | `{info:{…}, movie_data:{…}}` |
| `get_series_categories` | – | categories |
| `get_series` | `category_id` (optional) | series list |
| `get_series_info` | `series_id` (some forks/clients use `series=`) | `{seasons:[…], info:{…}, episodes:{"1":[…],"2":[…]}}` |
| `get_short_epg` | `stream_id`, `limit` (optional, default about 4) | `{epg_listings:[…]}` now and next |
| `get_simple_data_table` | `stream_id` | `{epg_listings:[…]}`, the full EPG stored for that channel, including past entries with `has_archive` |

**user_info**: `username`, `password`, `message`, `auth` (1/0), `status` (`"Active"`, `"Banned"`, `"Disabled"`, `"Expired"`), `exp_date` (unix seconds as a **string**, or `null` for unlimited), `is_trial` (`"0"`/`"1"`), `active_cons` (string), `created_at`, `max_connections` (string), `allowed_output_formats` (`["m3u8","ts","rtmp"]`).

**server_info**: `url`, `port`, `https_port`, `server_protocol` (`http`/`https`), `rtmp_port`, `timezone` (IANA, e.g. `Europe/London`), `timestamp_now` (int), `time_now` (`"YYYY-MM-DD HH:MM:SS"`, server local). XUI adds `xui:true`, `version`, `revision`.

**Live stream object**: `num`, `name`, `stream_type` (`"live"`, and `"radio_streams"` for radio on some panels), `stream_id`, `stream_icon`, `epg_channel_id` (may be `null` or `""`), `added`, `is_adult` (XUI), `category_id`, `category_ids` (XUI, array), `custom_sid`, `tv_archive` (0/1), `direct_source` (an upstream URL, usually `""`; if non-empty some clients play it directly), `tv_archive_duration` (days, int or string), `thumbnail`.

**VOD object**: `num`, `name`, `title`, `year`, `stream_type:"movie"`, `stream_id`, `stream_icon`, `rating`, `rating_5based`, `added`, `category_id`, `container_extension` (`mp4`/`mkv`/`avi`/`ts`…), `custom_sid`, `direct_source`, `tmdb` (XUI).

**get_vod_info.info**: `tmdb_id`, `name`, `o_name`, `movie_image`, `cover_big`, `backdrop_path[]`, `youtube_trailer`, `genre`, `plot`, `description`, `cast`, `actors`, `director`, `releasedate`, `duration_secs`, `duration` (`"HH:MM:SS"`), `rating`, `country`, `age`, `mpaa_rating`, `video{}`/`audio{}`/`bitrate` (ffprobe output), `subtitles`. **movie_data**: `stream_id`, `name`, `added`, `category_id`, `container_extension`, `custom_sid`, `direct_source`.

**Series object**: `num`, `name`, `series_id`, `cover`, `plot`, `cast`, `director`, `genre`, `releaseDate` (camelCase) **and/or** `release_date`, `last_modified`, `rating`, `rating_5based`, `backdrop_path[]`, `youtube_trailer`, `episode_run_time`, `category_id`.

**get_series_info.episodes[season][]**: `id` (the stream id for the URL), `episode_num`, `title`, `container_extension`, `season`, `added`, `custom_sid`, `direct_source`, and `info{movie_image, plot, releasedate, rating, duration_secs, duration, tmdb_id, video, audio, bitrate}`.

**EPG listing** (`get_short_epg`/`get_simple_data_table`): `id`, `epg_id`, `title` (**base64**), `description` (**base64**), `lang`, `start`/`end` (`"YYYY-MM-DD HH:MM:SS"` in **server timezone**), `start_timestamp`/`stop_timestamp` (unix strings, UTC, so use these), `channel_id`, `now_playing` (0/1), `has_archive` (0/1).

### 3.3 Stream URLs

| Type | Template |
|---|---|
| Live (TS) | `{base}/live/{u}/{p}/{stream_id}.ts` (short form `{base}/{u}/{p}/{stream_id}`) |
| Live (HLS) | `{base}/live/{u}/{p}/{stream_id}.m3u8` (only if `m3u8` is in `allowed_output_formats`) |
| Movie | `{base}/movie/{u}/{p}/{stream_id}.{container_extension}` |
| Series episode | `{base}/series/{u}/{p}/{episode.id}.{container_extension}` |
| Timeshift (path) | `{base}/timeshift/{u}/{p}/{duration_min}/{YYYY-MM-DD:HH-MM}/{stream_id}.ts` (or `.m3u8`) |
| Timeshift (php) | `{base}/streaming/timeshift.php?username={u}&password={p}&stream={stream_id}&start={YYYY-MM-DD:HH-MM}&duration={min}` |

Timeshift details:
- `start` uses the **server's local time zone** (`server_info.timezone`), not UTC. This is the number-one catch-up bug. (fact-checked 2026-10-13: confirmed by IPTVnator issue #1562 / PR #1563. If `timezone` is missing or non-IANA (e.g. `UTC+3`), derive the offset from `time_now` − `timestamp_now`, snapped to 15 min. Kodi formats this segment in **device** local time, so it is only right when the device zone equals the panel zone.) Convert program start UTC → server zone → `yyyy-MM-dd:HH-mm`. Some panels also accept `YYYYMMDD-HH` or `YYYY-MM-DD:HH-MM-SS`.
- `duration` is in **minutes**. Kodi computes it as `{duration:60}` (seconds / 60), plus padding.
- Kodi's regex `^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/([^/.]+)(\.m3u8?)?$` produces `host/timeshift/user/pass/{duration:60}/{Y}-{m}-{d}:{H}-{M}/id.ext`. A missing extension defaults to `.ts`.
- Live and VOD responses frequently **302** to a load-balancer node with a token (`http://lb3.host:8080/live/u/p/1.ts?token=…`). Follow redirects, but **re-resolve from the original URL** on every retry, because tokens expire within seconds to minutes.

### 3.4 Panel-fork quirks and defensive parsing
- **Types are unreliable.** Numbers come as strings (`"tv_archive":"1"`), `null`, `""` or `false`. Use lenient deserialisers: Moshi/kotlinx with custom adapters that coerce string↔int↔bool, treating `""`/`null` as absent.
- `episodes` may be an object keyed by season (`{"1":[…]}`), **or an array** (`[[…],[…]]`). `seasons` may be empty while `episodes` is populated. Derive seasons from the episode keys.
- `category_id` may be `null`, so put those items under "Uncategorised". XUI adds `category_ids` arrays, so one item can sit in multiple categories.
- `backdrop_path` may be a string, an array, or `[]`. `info` may be `[]` (an empty PHP array) instead of `{}`.
- EPG title and description base64 decoding: some forks return plain text, so try base64 and fall back if decoding fails or yields non-UTF-8 text.
- `exp_date` `null` or `"0"` means unlimited. Rendering it uses the device zone.
- Response sizes: `get_vod_streams` without `category_id` can be 50–100 MB on big providers. Fetch per category in parallel (bounded to 2–4 concurrent requests to avoid rate limits; XC defaults to roughly 20 req/s per IP with flood bans) or stream-parse the full list with `JsonReader`.
- Wrong `Content-Type` (`text/html` for JSON). Ignore it and sniff the content.
- Some panels gate on User-Agent. `okhttp/x` is blocked by some, and a VLC- or browser-like UA or the provider's "app UA" works. Expose a per-playlist UA override.
- Auth failure may return HTTP 200 with `user_info.auth=0`, an empty body, `[]`, or 401/403/513. Treat all of these as auth failure.
- XUI.one enforces `allowed_output_formats`. Requesting `.m3u8` when only `ts` is allowed returns 405/403/empty.
- "Login with M3U URL" users: parse `get.php` URLs and auto-switch to API mode.
- Multiple DNS/server URLs per provider (failover) are common. Allow a list of base URLs and rotate on failure.
- `server_info.url` may differ from the login host (LB). Keep using the login host unless it fails.

### 3.5 Connection semantics
- `max_connections` counts **concurrent stream sessions per account**, not API calls. Each open `/live/` or `/movie/` socket counts. HLS counts as one session keyed by token/IP for a timeout window (often 20–60 s after the last segment).
- `active_cons` updates lazily. A quick channel zap can show 2/1 for several seconds, and the server then rejects the new stream. Rejection behaviours seen: HTTP 403, 401, 509, 458, 503, 404, a looping "max connections reached" video, or a stream that connects and immediately closes.
- **Client mitigations:** (a) close the previous stream **before** opening the next one, by cancelling the OkHttp call and releasing ExoPlayer's `DataSource`; (b) never prefetch or preview a second live stream on single-connection accounts (disable mini-preview and multi-view, or warn); (c) retry after 1–2 s on 403/509/458 during zaps (session-slot release is panel-configured and may take up to about 2 min; fact-checked 2026-10-13); (d) display `active_cons/max_connections` in the account screen.

---

## 4. Catch-up / time-shift

### 4.1 Modes (`catchup` attribute or player setting)

| Mode | Behaviour | Archive URL derivation |
|---|---|---|
| `default` | `catchup-source` **is** the full URL template | Expand the template. If it is absent, behave as `append`. |
| `append` | Append `catchup-source` to the live URL | `liveUrl + catchupSource`, e.g. `?utc={utc}&lutc={lutc}` |
| `shift` (alias `timeshift`, SIPTV) | Fixed SIPTV query | `liveUrl + ("?"|"&") + "utc={utc}&lutc={lutc}"` |
| `flussonic` / `flussonic-hls` / `fs` / `flussonic-ts` | Derived from the Flussonic live URL | See 4.2 |
| `xc` (Xtream) | Derived from the Xtream live URL | `/timeshift/u/p/{duration:60}/{Y}-{m}-{d}:{H}-{M}/id.ts` |
| `vod` | `catchup-source` is a per-programme URL using `{catchup-id}` from XMLTV `programme@catchup-id` | |

In TiviMate the playlist-level option "Catch-up type" lists Default, Append, Shift, Flussonic and Xtream codes, plus a days field, and channel-level attributes override it [observed].

### 4.2 Flussonic DVR URL vocabulary [spec: Flussonic docs + Kodi source]
Live URL forms: `http://h/STREAM/index.m3u8`, `/video.m3u8`, `/mono.m3u8`, `/mpegts`, `/index.mpd`.

| Purpose | Template |
|---|---|
| Fixed range, HLS | `/STREAM/index-{FROM}-{DURATION}.m3u8`, `/STREAM/video-{FROM}-{DURATION}.m3u8`, `/STREAM/mono-{FROM}-{DURATION}.m3u8`, `/STREAM/archive-{FROM}-{DURATION}.m3u8` |
| Open-ended (still airing) | `/STREAM/index-{FROM}-now.m3u8`, `archive-{FROM}-now.m3u8` |
| Absolute timeshift (live-like from FROM) | `/STREAM/timeshift_abs-{FROM}.m3u8`, `/STREAM/timeshift_abs-{FROM}.ts` |
| Relative timeshift | `/STREAM/timeshift_rel-{SECONDS_AGO}.m3u8`, `/STREAM/mono-timeshift_rel-{S}.m3u8`, `/STREAM/video-timeshift_rel-{S}.m3u8` |
| MPEG-TS range | `/STREAM/archive-{FROM}-{DURATION}.ts` |
| DASH | `/STREAM/archive-{FROM}-{DURATION}.mpd`, `index-{FROM}-{DURATION}.mpd` |
| Metadata | `/STREAM/recording_status.json` (ranges available) |

`FROM` is unix UTC seconds and `DURATION` is seconds. Keep the query string (`?token=…`). Flussonic reuses the session for identical requests, so changing the token or adding a nonce forces a new one. `?ignore_gaps=true` skips holes. `?event=true` gives an EVENT-type playlist that can be seeked.

Kodi pvr.iptvsimple derivation, from the regex `^(https?://[^/]+)/(.*)/([^/]*)(mpegts|\.m3u8)(\?.+=.+)?$`:
- `…/mpegts` becomes `host/CH/timeshift_abs-${start}.ts?query`
- `…/index.m3u8` becomes `host/CH/timeshift_rel-{offset:1}.m3u8?query`
- `…/mono.m3u8` and `…/video.m3u8` become `host/CH/{type}-timeshift_rel-{offset:1}.m3u8?query`

TiviMate-style players more often generate `…/CH/{type}-{start}-{duration}.m3u8` (fixed range, seekable). This is preferable for EPG-programme playback, and `timeshift_abs` is preferable for "watch from here onward".

### 4.3 Placeholder vocabulary (union of Kodi IPTV Simple, TiviMate, OTT Navigator)

| Token | Value |
|---|---|
| `{utc}`, `${start}`, `{start}` | Programme start, unix UTC seconds (after `catchup-correction` and start buffer) |
| `{utcend}`, `${end}`, `{end}` | Programme end, unix UTC |
| `{lutc}`, `${now}`, `${timestamp}`, `{timestamp}` | Current time, unix UTC |
| `{duration}`, `${duration}` | Duration in seconds |
| `{duration:N}` | Duration ÷ N (`{duration:60}` gives minutes) |
| `{offset:N}`, `${offset}` | (now − start) ÷ N. Kodi's offset is positive "seconds ago". Some players emit it negative, so check the provider. |
| `{Y} {m} {d} {H} {M} {S}` | Start time components, zero-padded. **Kodi formats these (and `{utc:FORMAT}`) in device local time** (`SafeLocaltime` in CatchupController.cpp), after subtracting tvg-shift and catchup-correction. TiviMate has been observed using the device zone for `{Y}…` (unverified). Expose a per-playlist zone option. (fact-checked 2026-10-13: the earlier "Kodi uses UTC" claim was refuted by source.) |
| `{utc:FORMAT}` / `${start:FORMAT}` | strftime-like with `Y m d H M S` letters, e.g. `{utc:Y-m-d:H-M}` |
| `{utcend:FORMAT}`, `{lutc:FORMAT}`, `${end:...}` | Same pattern |
| `${start-iso}`, `${end-iso}` | ISO-8601 (OTT Navigator) |
| `{catchup-id}` | From XMLTV `programme@catchup-id` |

### 4.4 `catchup-correction` and buffers
- `catchup-correction` (hours, float) shifts the start and end used in archive requests. (fact-checked 2026-10-13: in Kodi's code the URL time = programme UTC − correction, so `-4.0` requests 4 h *later*. The README wording suggests the opposite. Match Kodi's code, because playlists are tuned against it.) It fixes archives whose server clock or zone is off, independently of EPG display shift (`tvg-shift`).
- Kodi has these settings: "Buffer before programme start" and "Buffer after programme end" (minutes, padding applied to start and duration), "Catchup only available on finished programmes", and "Play from EPG in Live TV mode (using timeshift)".

### 4.5 Representing availability in the guide
- Per channel, compute `archiveDays` = `catchup-days` → `timeshift` → `tvg-rec` → `catchup-time/86400` → Xtream `tv_archive_duration` → the playlist-level default. The channel is catch-up capable if any mode resolves (or `tv_archive==1`).
- A programme is playable from archive if `now − archiveDays·86400 ≤ start` **and** `start < now` (started) or `stop < now` (finished-only option). Xtream `has_archive` in `get_simple_data_table` is authoritative when it is present.
- UI: a small "rewind clock" glyph on channels with archive, and on past programme cells within the window. Shade cells older than the window differently. Allow the guide to scroll back `archiveDays` (TiviMate lets past days follow the EPG "past days to keep" setting).
- "Restart programme" on live means catch-up URL at `start=programme.start`, and seeking backwards beyond the local timeshift buffer on a catch-up channel should automatically switch to an archive URL (TiviMate behaviour).

---

## 5. Stream delivery

### 5.1 Containers and transport
| Kind | Notes for Media3/ExoPlayer |
|---|---|
| MPEG-TS over HTTP (progressive, endless) | The most common Xtream output. Use `ProgressiveMediaSource` + `TsExtractor` with flags `FLAG_ALLOW_NON_IDR_KEYFRAMES` and `FLAG_DETECT_ACCESS_UNITS`, and use `DefaultTsPayloadReaderFactory` with `FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS` where it is useful. No seeking. Reconnect on EOF, because the server closes the connection on errors. |
| HLS `.m3u8` (TS or fMP4 segments) | `HlsMediaSource`. Many IPTV HLS streams have broken `#EXT-X-PROGRAM-DATE-TIME`, discontinuities, or a media-sequence reset, so enable `setAllowChunklessPreparation(true)` and tolerate `BehindLiveWindowException` by reseeking to the default position. |
| DASH `.mpd` | `DashMediaSource`, usually with Widevine or ClearKey (from `#KODIPROP`). |
| RTMP/RTSP/UDP | Optional extensions: `media3-datasource-rtmp`, `media3-exoplayer-rtsp`, and a UDP data source for multicast. |
| VOD MKV/MP4/AVI | Progressive with seeking. AVI is poorly supported in ExoPlayer, so a libVLC/ffmpeg fallback is needed. |

Use content sniffing: an Xtream `.ts` URL may return HLS, and `.m3u8` may 302 to TS. Pick the MediaSource from the response `Content-Type` and first bytes (`0x47` sync every 188 bytes means TS; `#EXTM3U` means HLS) rather than from the extension.

### 5.2 Codecs seen
- **Video:** H.264/AVC (the large majority, High@4.0/4.1, often interlaced 1080i50/60 from broadcast sources), H.265/HEVC (4K and "HEVC" channel variants, Main10/HDR10, sometimes HLG), MPEG-2 (raw DVB/multicast sources), occasionally AV1/VP9 on newer FAST services.
- **Audio:** AAC-LC/HE-AAC (ADTS or LATM; LATM/LOAS is common from DVB-T2 and needs extractor support; fact-checked 2026-10-13: Media3's `DefaultTsPayloadReaderFactory` already maps stream type 0x11 to `LatmReader`), MP2 (MPEG-1 Layer II, ubiquitous on European DVB), MP3, AC-3 and E-AC-3 (sports and movie channels; enable passthrough or ffmpeg decoding, because many TV boxes lack a licensed software decoder), DTS (rare), Opus (rare).
- **Interlacing:** Android hardware decoders typically deinterlace H.264 and MPEG-2. On some SoCs (Amlogic/Realtek) a software decoder loses deinterlacing, so prefer hardware.
- Build ExoPlayer with the **media3 ffmpeg extension** (audio: ac3, eac3, mp2, dts, truehd) and set `EXTENSION_RENDERER_MODE_PREFER` as an option, with a "software audio decoder" toggle.

### 5.3 Subtitles and tracks in TS
- DVB subtitles (bitmap, stream_type 0x06 + descriptor 0x59), DVB teletext (descriptor 0x56, page-based; Media3 lacks teletext, so the libVLC fallback handles it), CEA-608/708 captions inside H.264 SEI (supported by Media3 via `SeiReader`, enable `DefaultTsPayloadReaderFactory` with `closedCaptionFormats`), and ID3 timed metadata.
- Multiple audio PIDs with ISO-639 language descriptors (e.g. `eng`, `spa`, `qaa` for original). Audio-description tracks are flagged via `audio_type=3`. Auto-select by the preferred-language setting.
- HLS: `#EXT-X-MEDIA:TYPE=AUDIO|SUBTITLES|CLOSED-CAPTIONS` with `LANGUAGE`, `DEFAULT`, `AUTOSELECT`.

### 5.4 HTTP behaviours
| Signal | Meaning (IPTV context) | Client action |
|---|---|---|
| 301/302/307 | LB redirect, often to another host or port, sometimes http↔https | Follow (`followSslRedirects=true`; ExoPlayer's `DefaultHttpDataSource` needs `setAllowCrossProtocolRedirects(true)`). Do not cache the target. |
| 401 | Bad credentials or expired account | Stop retrying and show the account status (re-query `player_api.php`). |
| 403 | Wrong UA/Referer, IP lock, geo-block, connection limit, wrong output format | Retry once after 1–2 s, then show a reason. Hint the user about the UA override. |
| 404 | Stream offline or wrong ID | Show "channel unavailable" and try the backup URL or alternative channel. |
| 405/406 | Format not allowed / not in bouquet (XC) | Switch `.m3u8`↔`.ts` automatically. |
| 458 | Non-standard and not IANA-registered. Widely reported by TiviMate users, and its meaning is disputed (connection limit, expired session or token, connection cut). A 2026 Play review reports 458 on every VOD seek. | Re-resolve the URL once, then treat it like 403 with a connection-limit hint. (fact-checked 2026-10-13: meaning unresolved) |
| 509 | Non-standard "Bandwidth Limit Exceeded" (Apache/cPanel convention, not IANA). No primary source ties it to Xtream. | Same. (fact-checked 2026-10-13) |
| 429 | Rate limit on the API | Exponential backoff. |
| 5xx / 520–524 (Cloudflare) | Provider or CDN outage | Backoff and DNS failover. |
| Connection reset or EOF on endless TS | Normal transient | Silent reconnect, up to N times, within about 3 s. |
| 200 with a tiny body or HTML | Provider "expired" splash or captive portal | Detect by content sniffing and surface it. |

- **Token-expiring URLs:** never persist resolved (post-redirect) URLs. Always restart from the playlist URL.
- **User-Agent gating:** some providers whitelist an app UA (e.g. `IPTVSmartersPlayer`, `TiviMate/4.7.0 (Android 11)`, `VLC/3.0.20 LibVLC/3.0.20`). Make the UA configurable per playlist, with presets. Do not impersonate another app by default.
- **HTTP vs HTTPS:** most panels are plain HTTP. Allow cleartext app-wide. Some HTTPS panels use self-signed or expired certificates, so offer a per-playlist "ignore TLS errors" option that is off by default and carries a warning.
- **IPv6:** hosts with broken AAAA records cause 20–75 s connect stalls on dual-stack networks. Implement Happy Eyeballs (OkHttp does fast fallback with `fastFallback=true`, the default since 5.x) and a "prefer IPv4" setting.
- **DNS blocking and DoH:** UK, Italian, Spanish and Portuguese ISPs block known panel domains at DNS level (and via SNI/IP blocks on match days). OkHttp `DnsOverHttps` with Cloudflare `https://cloudflare-dns.com/dns-query`, Google `https://dns.google/dns-query`, Quad9 or AdGuard as a selectable resolver is cheap and effective against DNS blocking. It does not help against IP/SNI blocks, where only a VPN or proxy works.
- **Proxy/VPN:** support system VPN transparently. Optionally add a per-playlist HTTP/SOCKS5 proxy (OkHttp `Proxy`), which helps with geo-locked FAST sources and ISP IP blocks.
- **Timeouts:** connect 10 s, read 15–20 s for live (provider stalls), and longer for API calls on huge lists (60–120 s read).

---

## 6. Settings a proper IPTV player must expose

Defaults reflect a TiviMate-like experience. "TM" marks where TiviMate has an equivalent (from public guides and the app UI). TiviMate's exact menu labels vary between versions and were not verified against official documentation.

### 6.1 Playlist-level (per playlist)
| Setting | Default | Rationale |
|---|---|---|
| Type: M3U URL / M3U file / Xtream Codes / (Stalker portal: later) | – | TM: same set |
| Name | host name | |
| Server URL(s) with failover list | – | Providers rotate DNS |
| Username / Password (XC) | – | |
| Update on app start | On | TM: Playlist → "Update on app start" |
| Update interval | 24 h (M3U), 24 h (XC lists) | Large lists are expensive |
| User-Agent | App default (`TheBox/1.x (Linux;Android x) ExoPlayer`) plus presets | UA gating (TM: per-playlist user agent) |
| Custom headers / Referer | empty | |
| Content types to load: Live / Movies / Series | All on | Memory on low-end boxes (TM: same toggles) |
| Group management: hide, reorder, rename groups; hide channels | – | TM: "Manage groups" |
| Channel numbering: from playlist `tvg-chno` / sequential / start at N | playlist, else sequential | |
| Catch-up type override: Auto / Default / Append / Shift / Flussonic / Xtream / Disabled | Auto | TM: playlist catch-up settings |
| Catch-up days override | from source | |
| Catch-up time zone for `{Y}…` tokens: UTC / device / server | UTC (XC: server) | Section 4.3 |
| Preferred output (XC): TS / HLS | TS | TS is more widely allowed, and HLS is friendlier to seek and rewind. Use HLS only when `allowed_output_formats` lists `m3u8`. (fact-checked 2026-10-13: the architecture doc is aligned) |
| EPG source(s) from playlist: use / ignore | Use | |
| Proxy (HTTP/SOCKS) | off | |
| Ignore TLS errors | off | |
| Account info screen (status, expiry, connections, formats) | – | TM shows expiry |

### 6.2 EPG
| Setting | Default | Rationale |
|---|---|---|
| EPG sources (ordered, enable/disable, add URL/file) | playlist-provided | TM: Settings → EPG → EPG sources |
| Update interval | 12 h (choices 4/8/12/24 h, daily at a set hour) | Guides suggest 4–24 h. (fact-checked 2026-10-13: the architecture doc is aligned to 12 h) |
| Update on app start | Off if updated < interval ago | Avoid hammering on every launch |
| Past days to keep | 1, or max(1, max catchup-days) automatically | TM: "past days to keep". Catch-up needs past data. |
| Future days to keep | 7 | Memory and DB size |
| EPG time shift (global, per source, per channel) | 0 h, in 0.5 h steps | Fixes bad provider offsets |
| Assume time zone when offset missing | UTC | |
| Matching: case-insensitive IDs | On | Kodi has the same option |
| Matching: allow name/fuzzy match | On (exact name), fuzzy = suggest only | |
| Per-channel EPG override (source + id) | – | TM: Channel → "EPG" |
| Prefer logos from: Playlist / EPG | Playlist | Kodi has the same option |
| Genre colour coding | On | |
| Show programme images and descriptions | On | |

### 6.3 Playback
| Setting | Default | Rationale |
|---|---|---|
| Player engine: Media3 / libVLC (fallback) / Auto | Auto (Media3, then VLC on unsupported formats) | Teletext, AVI, odd codecs |
| Video decoder: Hardware / Software / HW+ (prefer HW, fall back to SW) | HW+ | |
| Buffer size: None / Small (2 s) / Medium (5 s) / Large (10 s) / Very large (20 s) | Small | TM uses the same *labels* (None/Small/Medium/Large, and "Very/Extra large" in some guides), but no source gives TM's seconds. The seconds here are The Box's own mapping onto `bufferForPlaybackMs`. Larger buffers trade zap speed for stability. (fact-checked 2026-10-13) |
| Auto frame rate (AFR): Off / Seamless only / Always | Seamless only | Media3's default is `VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS`, which never causes HDMI re-sync. "Always" causes black screens on some TVs and needs an app-side `Surface.setFrameRate(…, CHANGE_FRAME_RATE_ALWAYS)` (TM: AFR). (fact-checked 2026-10-13: harmonised with the architecture doc) |
| Resolution switching with AFR | Off | |
| Tunneled playback | Off | Black screens and A/V-sync stutter on some chipsets (TM: toggle) |
| Audio passthrough (AC-3/E-AC-3/DTS) | Auto (as reported by HDMI caps) | |
| Software audio decoding (ffmpeg) for AC-3/E-AC-3/MP2 | On when there is no HW decoder | |
| Preferred audio language(s) | device locale, then original | |
| Preferred subtitle language / subtitles on-off / size / style | Off / locale | |
| Teletext/CC enable | Off | |
| Aspect / scaling: Fit / Fill / Zoom / 16:9 / 4:3 (per channel memory) | Fit | |
| Deinterlace (VLC engine only) | Auto | |
| Reconnect on stream drop | On, 5 attempts | Endless TS drops |
| Stream switch timeout before showing error | 15 s | |
| Local timeshift (pause live): Off / RAM / Disk, size | Disk, 30–60 min if storage allows | TM: timeshift buffer |
| Auto-play last channel on start | On | TM: General |
| Show info banner on channel change | On, 5 s | |
| Channel zap: OK opens list / guide / banner | Channel list | TM: remote-control section |
| Channel up/down direction inverted | Off | |
| Number-key zapping delay | 1.5 s | (fact-checked 2026-10-13: harmonised with other docs) |
| Resume VOD position | On | |
| Default playback speed (VOD) | 1.0× | |
| External player hand-off (VLC, MX, Just) | Off | Escape hatch |

### 6.4 Guide / UI
| Setting | Default |
|---|---|
| Theme / accent colour | Dark |
| Guide time scale (min per screen width) | 90–120 min |
| Rows visible | 7 |
| Show channel numbers / logos | On / On |
| Logo background | Transparent |
| Channel list style: list / grid / with now-playing | list + now-playing |
| Mini-preview of the selected channel | On (auto-off when `max_connections==1`) |
| Clock overlay | Off |
| Programme progress bar | On |
| Default start screen: TV guide / Channels / Last channel / Home | Last channel |
| Sort channels: playlist order / number / name | Playlist order |
| Favourites lists (multiple, reorderable) | – |
| Recently watched rows | On |
| Language | System |
| Hide "Movies" and "Series" sections when empty | On |
| Search scope: channels / programmes / VOD | All |

### 6.5 Recording and reminders
| Setting | Default |
|---|---|
| Recording location (internal / USB / SMB) | internal |
| Start early / end late padding | 2 min / 5 min |
| Concurrent recordings limit | = max_connections − 1 |
| Reminder lead time | 1 min, auto-switch On |

### 6.6 Network
| Setting | Default |
|---|---|
| DNS: System / DoH Cloudflare / DoH Google / DoH Quad9 / custom | System |
| Prefer IPv4 | Off (enable automatically after IPv6 connect failures) |
| Connect / read timeouts | 10 s / 20 s |
| Global proxy (HTTP/SOCKS5) | Off |
| Multicast relay (udpxy) host:port | empty |
| Allow cleartext HTTP | On |
| Max parallel API requests | 3 |

### 6.7 Parental
| Setting | Default |
|---|---|
| PIN (4 digits) | unset |
| Lock groups / channels / VOD categories | Auto-lock items with `adult`/`is_adult`/`parent-code` or groups matching `XXX|Adult|18+` |
| Hide locked content entirely vs show with lock | Show with lock |
| Lock settings and playlist editing | Off |
| Relock after | app exit |

### 6.8 Maintenance
- Backup and restore settings, favourites and playlists (to file or cloud). TM has "Backup data".
- Clear cache / rebuild EPG DB / reset playlist.
- Diagnostics: last refresh times, entry counts, EPG match rate per playlist, stream info overlay (codec, resolution, fps, bitrate, dropped frames, buffer), and log export with redacted credentials.

---

## Sources

- Kodi PVR IPTV Simple Client README: https://github.com/kodi-pvr/pvr.iptvsimple/blob/Omega/README.md
- pvr.iptvsimple catch-up URL generation (Channel.cpp): https://github.com/kodi-pvr/pvr.iptvsimple/blob/Omega/src/iptvsimple/data/Channel.cpp
- pvr.iptvsimple catch-up types issue: https://github.com/kodi-pvr/pvr.iptvsimple/issues/326
- XMLTV DTD: https://github.com/XMLTV/xmltv/blob/master/xmltv.dtd
- inputstream.adaptive Integration: https://github.com/xbmc/inputstream.adaptive/wiki/Integration
- inputstream.adaptive DRM: https://github.com/xbmc/inputstream.adaptive/wiki/Integration-DRM
- Flussonic DVR playback: https://flussonic.com/doc/dvr-playback and https://flussonic.com/doc/dvr/playback/
- Flussonic play protocols / timeshift: https://flussonic.com/doc/play-protocols/ and https://flussonic.com/doc/dvr/timezone-shift/
- Flussonic IPTV/OTT quickstart (archive-FROM-DURATION): https://flussonic.com/doc/iptv-ott-quickstart/
- UniPlayer M3U spec: https://uniplayer.net/docs/specs/m3u/
- UniPlayer catch-up spec: https://uniplayer.net/docs/specs/catchup/
- Xtream Codes API community docs (v2.9.2 reverse-engineered): https://drm-free.duckdns.org/thegame_1980/xtream-codes-api-documentation/src/branch/master/XTREAM_CODES_API_DOCUMENTATION.md
- Xtream endpoint reference / patterns: https://drm-free.duckdns.org/thegame_1980/xtream-codes-api-documentation/src/branch/master/ENDPOINT_REFERENCE.md , https://drm-free.duckdns.org/thegame_1980/xtream-codes-api-documentation/src/branch/master/IPTV_PATTERNS_AND_INTEGRATIONS.md
- XUI.one player API docs (community): https://drm-free.duckdns.org/thegame_1980/xui-one-api-docs/src/branch/main/xtream-codes-player-api-documentation.md
- @iptv/xtream-api (notes on API inconsistencies, base64): https://www.npmjs.com/package/@iptv/xtream-api
- Fermata discussion (get_series_info `series=` param, short EPG): https://github.com/AndreyPavlenko/Fermata/discussions/434
- tuliprox Xtream API (proxy implementation): https://mintlify.wiki/euzu/tuliprox/api/xtream
- iptv-org playlists (live sample of attribute usage: `http-referrer`, `http-user-agent`, `x-tvg-url`, `Name.cc@Feed` ids): https://iptv-org.github.io/iptv/index.m3u , https://github.com/iptv-org/iptv
- CoreELEC Kodi IPTV archive thread: https://discourse.coreelec.org/t/coreelec-9-2-kodi-18-x-leia-with-iptv-archive-support/1950?page=7
- Kodi forum on flussonic catchup: https://forum.kodi.tv/showthread.php?tid=366603
- TiviMate settings guides (third-party): https://store.grapevine.is/blogs/news/how-to-use-tivimate , https://tivimateplayer.co.uk/tivimate-buffering-fix/ , https://www.ip4ktv.org/fix/tivimate-buffering , https://www.ottv.org/blog/tivimate-setup-guide
- TiviMate error 458 reports: https://vpncentral.com/6-wege-tivimate-zu-reparieren-wenn-es-nicht-funktioniert-fehlercode-458-521-und-mehr/
- NextPVR max-connections lingering: https://forums.nextpvr.com/showthread.php?pid=573872
- kvaster iptv-proxy (connection sharing/limits): https://github.com/kvaster/iptv-proxy
- Threadfin (M3U/XMLTV proxy, tuner limits, mapping): https://github.com/Threadfin/Threadfin ; xTeVe: https://github.com/xteve-project/xTeVe
- AndroidX Media3 (TS extractor flags, HLS/DASH, ffmpeg extension): https://developer.android.com/media/media3/exoplayer
