# fakepanel

A fake Xtream Codes panel + stream server for The Box developers and instrumented tests
(master plan section 7; review experiments D3, D4, D5, D10). Python 3.12, standard library only.
Dev tool: no admin authentication, do not expose it beyond a trusted LAN.

## Run

```
python3 tools/fakepanel/fakepanel.py --port 8080
python3 tools/fakepanel/fakepanel.py --port 8080 --quirks --timezone Asia/Tokyo --epg-offset +0900
python3 tools/fakepanel/fakepanel.py --config tools/fakepanel/config.example.json
```

On first start, with `ffmpeg` on PATH, it generates into `tools/fakepanel/media/` (gitignored): 10 s H.264+AAC 1280x720 25 fps
(`testsrc2`+`sine`), an MP2-audio variant, a 50 fps variant, an HDR10-signalled variant (`h264_aac_25_hdr10.ts`: 8-bit H.264
whose VUI says BT.2020 + PQ, only served through `media_override`), the `multi` clip (below), 2 s HLS segments of each, and a
`vod.mp4`. Takes ~15 s once.

`multi` (`h264_multi_25.ts`, M4 pickers and subtitles): H.264 25 fps without B-frames, **two audio PIDs** (AAC `eng` 440 Hz,
MP2 `deu` 880 Hz, ISO-639 descriptors from `-metadata:s:a:N language=`) and **CEA-608 captions** (CC1, roll-up 2 rows,
"CC1 line N" every 2 s). ffmpeg has no CEA-608 encoder, so `fakepanel.py` writes the captions itself as ATSC A/53 `cc_data`
in an H.264 SEI (`user_data_registered_itu_t_t35`, `GA94`) before the first slice of every access unit, then muxes with
`-c:v copy` (`build_multi`; check with `ffmpeg -f lavfi -i "movie=media/h264_multi_25.ts[out0+subcc]" -map 0:s -f srt -`).
The PMT carries no caption descriptor, so players expose the 608 track speculatively (API notes §1.3). Over HLS the clip gets
a **WebVTT** rendition instead, built at request time from the committed `multi_subs.srt` (7 cues inside the 10 s loop).
**DVB bitmap subtitles are not generated**: ffmpeg's `dvbsub` encoder needs bitmap input and nothing here rasterises text,
so DVB stays a manual check on a real stream (design §8 Q5). The HLS segments of `multi` lose the audio language tags.
Without ffmpeg (or with `--no-ffmpeg`) it serves the checked-in `fallback_null.ts` (null packets: valid 0x47 framing, no decodable video).

Point The Box at it: Xtream server `http://<mac-ip>:8080`, user `test`, password `test`.
M3U: `http://<mac-ip>:8080/get.php?username=test&password=test&type=m3u_plus&output=ts`,
XMLTV: `http://<mac-ip>:8080/xmltv.php?username=test&password=test`.
Tokenised redirect targets are served on a second port (default main port + 1, `--redirect-port`).

Catalog: live 1-13 (cat 1 News, 2 Sports, 3 Movies, 4 Radio; 13 "Radio Test" is AAC-audio-only (`media/aac_only_25.ts`, no video PID; M3U carries `radio="true"` and `group-title="Radio"`); 3 and 12 have MP2 audio, 4 is 50 fps, 5 uses `#EXTVLCOPT` UA, 6 uses a `|User-Agent=` pipe URL,
archive on 1,2,3,4,7,9; catch-up variants xc/default/shift/flussonic on 1-4), movies 101-106 (`.mp4`), series 201-203 (episode ids 3xxx).

## Flags

`--port --host --redirect-port --config FILE --username --password --timezone IANA --quirks --series-episodes-array --epg-plain
--epg-offset +HHMM --epg-gzip --strict-timeshift-tz --max-connections N --auth-mode ok|auth0|empty|array|401|403|513
--status Active|Expired|Banned|Disabled --fault K=JSON --stream-fault ID:K=JSON --set K=JSON --synthetic N --synthetic-days-back D --synthetic-days-ahead D --media-dir --no-ffmpeg --no-pace --verbose`

Examples: `--fault linger=10 --fault linger_status=458 --max-connections 1`, `--stream-fault 3:redirect=true`.
All are changeable at runtime through `/admin`. See `config.example.json` for every config key.

## Config keys (top level)

`username password timezone quirks series_episodes_array epg_plain auth_mode bad_creds_mode status max_connections
active_cons_override exp_days allowed_output_formats epg_offset epg_gzip strict_timeshift_tz timeshift_expect timeshift_ranges
hls_timeout hls_window token_ttl token_single_use pace faults stream_faults media_override synthetic synthetic_days_back synthetic_days_ahead epg_version epg_status
timeshift_mode timeshift_window archive_lag_sec timeshift_future timeshift_chunked timeshift_reject_forms timeshift_range_ignored player_fixtures`

`epg_status` (int, default 0): when ≥ 400, `xmltv.php` answers that status with a short text body (after the auth checks;
counted as rejection `epg_status`), in both catalog and synthetic mode. `POST /admin/config {"epg_status":500}` reproduces a
provider whose guide server fails (owner finding 2026-11-01: the guide chip's "HTTP 500 · guide from …"); `0` restores normal
answers.

`media_override`: `{"<stream id>": "std|mp2|50fps|radio|hdr|multi"}` serves another clip for that stream, live and archive
(Q-1: `{"1": "hdr"}` makes ch1 HDR10 for the HDR-poster test). `multi` on a live `.m3u8` serves the master playlist with
the WebVTT rendition (see "Player fixtures").

`--quirks`: numbers as strings (`stream_id`, `tv_archive:"1"`, `max_connections`...), `exp_date` string, `category_id` null for stream 5,
`backdrop_path` string for vod 103 / series 202, `info: []` for vod 102, `seasons: []` with populated `episodes`.
EPG titles are base64 unless `epg_plain`. `epg_gzip` returns a gzip file body from `xmltv.php` (Content-Type `application/gzip`, no Content-Encoding).

## Synthetic mode

`--synthetic N` (or config `synthetic`, N <= 20000; `--synthetic-days-back 3`, `--synthetic-days-ahead 7`) replaces the 13 catalog live channels
with N generated ones (VOD and series unchanged), for the guide's instrumented tests (docs/design/m3-guide.md section 5.6).
- Ids and Xtream `num` are 1..N; groups ("categories") of 50 with ids `1000+g` and names `News 1`, `Sports 2`, `Movies 3`... (8 themes cycling);
  channel names are `<theme> <id>`.
- 30 % of channels carry a prefix by `id % 10`: 3 `UK: `, 5 `[DE] `, 7 `FR | `. 20 % (`id % 5 == 1`) have `tv_archive = 1`
  with 3 or 7 days (channel 1: 7), catch-up through the plain Xtream timeshift URL (`catchup="xc"` in the M3U). All streams serve the "std" clip.
- EPG for 95 % of channels (all but `id % 20 == 9`; channel 1 always) from start-hour minus back days to start-hour plus ahead days, durations from
  {5, 10, 30, 60, 120, 360} min, about 10 % of titles over 60 characters, some titles contain "News". Whole-minute boundaries. Seeded per channel
  and anchored to the server start time, so every request in one run returns the identical schedule. No-EPG channels have an empty `epg_channel_id`.
- Channel 1 also has programmes starting exactly 2 min and 5 min after the server start (floored to the minute): `... -> [S+2, S+5) -> [S+5, S+35) -> ...`.
  `GET /admin/stats` returns `synthetic: {n, start_unix, reminder_starts: [S+2, S+5], epg_window: [from, to], epg_version, days_back, days_ahead, group_size}`
  (only in synthetic mode).
- `xmltv.php` is generated while streaming (64 KB chunks, no Content-Length, nothing held in memory); `epg_gzip` is gzip-streamed and `epg_offset` applies.
  Cost: roughly 4 MB and 0.25 s per 200 channels over the default 10 days (N=2000: ~43 MB, ~1.5 s; N=20000: ~430 MB, ~25 s). `get_short_epg` / `get_simple_data_table`,
  `get.php` and `get_live_streams` use the same lineup. `epg_plain` still selects base64 for the player_api listings.
- `epg_version` (int, default 0): `POST /admin/config {"epg_version":1}` appends ` v2` to every programme title (` v<N+1>`), so the next EPG import differs
  (generation-swap test). The schedule times do not change.
- Limits: all archive channels use the plain Xtream `xc` variant (Flussonic/SHIFT URLs are accepted for synthetic ids but not advertised); the window is fixed at server start, so a server left running for days drifts away from "now".

## Faults (in `faults` for all streams, `stream_faults[<id>]` per stream; per-stream overrides global)

| key | effect |
|---|---|
| `redirect` | 302 to `http://host:<redirect-port>/<same path>?token=T`; token valid `token_ttl` seconds (fault `token_ttl` or config), expired/unknown -> 403 |
| `linger` + `linger_status` | closed connection still counts toward `max_connections` for N s; a rejected open gets 403 (default), 458, 509, 503, or `"close"` = 200 headers then immediate close |
| `close_every` | server closes the socket N s into a live TS |
| `reconnect_mode` | on reconnect after a close: `continue` (keep byte position, timestamp offset and CCs: as if nothing happened), `reset` (restart the clip and the timestamp offset at 0: PTS/PCR go back to the clip start), `jump` (timestamp offset +5 s, i.e. PTS/DTS/PCR jump forward 5 s; the clip position and CCs continue) |
| `stall` | accept, send nothing for N s, then serve normally |
| `stall_after` | `[after_s, duration_s]`: serve live TS normally for `after_s` s, then hold the socket open writing nothing for `duration_s` s, then continue (timestamps stay continuous; paced output resumes at real-time speed, no catch-up burst). Fires once per connection |
| `slow` | throttle to N bytes/sec |
| `ua_whitelist` | list of substrings; 403 unless User-Agent contains one |
| `wrong_content_type` | serve with `text/html` |
| `html_splash` | 200 HTML "subscription expired" instead of media |
| `405_on_m3u8` | `.m3u8` gets 405 (also whenever `allowed_output_formats` lacks `m3u8`) |
| `range_rejects` | `true`/`416` -> 416, `400` -> 400 when a live `.ts` request carries `Range`; always logged loudly and counted |
| `timeshift_range_rejects` | `true` (458) or a status (400, 416, 458, 509, 503...): a **timeshift** request with a `Range` other than `bytes=0-` (the tail/head reopens of a finite-TS prepare, a native seek) is refused; reason `timeshift_range_rejects` |
| `strip_rai` | `true`: clear the adaptation-field random-access indicator on every live-TS packet (M6 I-TS-17: seeks must still reach a first frame) |
| `pts_offset` | 90 kHz integer in `[0, 2^33)`: the clip's lowest PTS starts here and the PCR keeps its lead (`--set 'faults={"pts_offset":8589844592}'` wraps about 1 s into the run; I-TS-16). Applies to every connection, so a `reset` reconnect restarts at the offset. 33-bit wrap handled; continuity unchanged |
| `inline_jump_after` | `[s, ms]`: `s` s of media time into one connection, step PTS/DTS and then PCR by `ms` (negative allowed), no reconnect. The first stepped PTS packet precedes the first stepped PCR packet in byte order (the PCR steps at the first PCR packet after it). Fires once per connection; the step stays in the saved state, so `reconnect_mode: continue` carries it on (I-TS-8) |
| `psi_every_s` | seconds: the clip's own PAT/PMT are dropped and replaced by a generated PAT+PMT at connection start and every N s of media time (own CRC and continuity counters; I-TS-18). Needs a clip with a PAT/PMT (the ffmpeg clips; not the null fallback, where it is a no-op) |
| `pmt_change_after` | `s`: after `s` s of media time bump the PMT version and move the first audio stream to a new PID (old PID + 0x10); the new PAT+PMT are emitted before the first packet of the new PID. Without `psi_every_s` the clip's own PMT packets keep their cadence but carry the current version / PID map |
| `pad_bps` | bytes/s of null packets (PID 0x1FFF) added on top of the content, spread per chunk (the content keeps its pacing, so the wire rate rises) |
| `timeshift_redirect` | like `redirect`, for timeshift requests only (live stays direct); the `Location` keeps the original query (`timeshift.php`, `?utc=`) |
| `token_kill` | on the redirect port, an open body is closed when its token expires (`token_ttl`): mid-play token expiry; counts `token_expired` |
| `timeshift_reject_forms` | per stream (`stream_faults`), as the config key below; wins over it |

Time for `inline_jump_after`, `psi_every_s` and `pmt_change_after` is media time (clip bytes sent / clip rate): wall time when paced (`pace: true`, the default), deterministic when not.
Shaping applies to live TS (`/live/.../N.ts` and the live-shifted archive forms); with every key off the output is byte-identical to the unshaped generator.
Set them like any fault: `--set 'faults={"strip_rai":true}'` or `POST /admin/config {"faults":{"inline_jump_after":[20,1500]}}`.

`linger`/`linger_status` and `max_connections` apply to timeshift opens too, so `{"max_connections":1,"faults":{"linger":5,"linger_status":458}}`
refuses the ranged reopen a finite-TS prepare makes right after its first open (I-M4-10 linger variant).

Non-`Active` `status` and `auth_mode` 401/403/513 make streams 403/401/513 as well. HLS counts as one session per (user, stream), freed `hls_timeout` s after the last request.

## Timeshift

`/timeshift/{u}/{p}/{minutes}/{YYYY-MM-DD:HH-MM}/{id}.ts|.m3u8` and `/streaming/timeshift.php?username&password&stream&start&duration`.
Also served as archive (start = unix UTC seconds, no zone): Flussonic `/live/{u}/{p}/{id}/{archive|index|video|mono}-{FROM}-{DUR|now}.m3u8|.ts`
(catalog ch4) and SHIFT `/live/{u}/{p}/{id}.ts?utc={FROM}&lutc={NOW}` (catalog ch3). Redirected live HLS playlists carry the token on segment URLs.
The XC start must be server-zone wall time. If `timeshift_expect` is set (list of unix instants or server-zone strings, e.g. the programme start the test fixture just
selected), the start is compared against it (tolerance 90 s) and the log says whether the client sent UTC. Without it, starts in the future or outside the 3-day window are flagged.
A WARNING is logged on mismatch; with `strict_timeshift_tz` the response is 404. The `.ts` form returns `Content-Length` + `Accept-Ranges` + 206 support (set `timeshift_ranges:false` to emulate panels that do not, for D10).

### Archive behaviour for the player (M4, docs/design/m4-player-ux.md §5.6)

URL forms the panel tells apart (`form` in the request log, keys of `timeshift_reject_forms`): `path` (`/timeshift/.../YYYY-MM-DD:HH-MM/id`,
the `xc-path` variant), `path_sec` (`...:HH-MM-SS`, `xc-path-sec`), `php` (`/streaming/timeshift.php`, `xc-php`; also what catalog ch2's
`default` source expands to), `shift` (`/live/u/p/id.ts?utc=`), `flussonic_range` (`.../{archive|index|video|mono}-FROM-DUR.{ts,m3u8}`),
`flussonic_now` (`...-FROM-now...`), `timeshift_abs` (`/live/u/p/id/[index-|video-|mono-]timeshift_abs-FROM.{ts,m3u8}`), `timeshift_rel`
(`...timeshift_rel-N.m3u8`, start = now − N, anchored at the first request). Aliases `xc-path`, `xc-php`, `xc-path-sec`, `flussonic-range`,
`flussonic-abs`, `flussonic-kodi` are accepted. A `default`/`append` template reaches the panel as whatever URL it expands to.

| key | default | effect |
|---|---|---|
| `timeshift_mode` | `"clip3"` | `clip3`: the `.ts` archive is the clip × 3 (30 s, byte-identical loops), `.m3u8` the clip's segments once (M2/M3 behaviour). `"duration"`: the `.ts` body is **sized to the requested duration** (`{minutes}` × 60 for `path`/`php`, seconds for Flussonic `DUR`), the clip looped with the live rewriter so **PCR/PTS/CC run on** across loops (a 30-min request is a 30-min file; `TsDurationReader` reads the true length); exact `Content-Length`, `Range`/206/416 as before; rendered on demand (`LoopedTs`), so ranged reopens start mid-file with the right timestamps. `.m3u8`: a VOD playlist of `ceil(D/2)` continuous-timestamp segments (`/tseg/{id}_{n}.ts`). Open-ended forms (`shift`, `flussonic_now`) stream **paced like live, without Content-Length** from the clip start |
| (always) | | `timeshift_abs`/`timeshift_rel` `.ts` stream live-like; their `.m3u8` and (duration mode) `-now.m3u8` are **EVENT** playlists (`#EXT-X-PLAYLIST-TYPE:EVENT`, no `ENDLIST`) listing `/tseg/` segments from the start up to now, growing with wall time (≤ 3 h) |
| `timeshift_window` | `true` | **404** (`timeshift_window`) for a channel without archive, a start older than its `archive_days` (+120 s slack; catalog 3 days, synthetic per channel), or later than now + 60 s. Independent of `timeshift_expect`/`strict_timeshift_tz`; evaluated against each request's wall clock, so a long-running panel does not drift. `false` restores the old warn-only behaviour |
| `archive_lag_sec` | `0` | **404** (`archive_lag`) for a start within N s of now: the archive's last minutes are not written yet (I-M4-14) |
| `timeshift_future` | `"clamp"` | (code review 2026-10-16, U-S9) `duration` mode only. `"clamp"`: the body ends at what the panel has written, `now − archive_lag_sec − start`, as a real Xtream panel (an xc request is `ceil((now − S)/60)` minutes, so its end is up to 59 s past now); the log notes `CLAMPED{n}s/{D}s`. `"serve"`: the whole requested duration at once (the 2026-10-15 behaviour) |
| `timeshift_chunked` | `false` | (U-S9) `duration` mode only: no `Content-Length` and no `Accept-Ranges`, a close-delimited body (HTTP/1.0), `Range` ignored (200 from byte 0), as many panels serve archive; the log notes `NO-LENGTH` |
| `timeshift_reject_forms` | `{}` | `{form: status}`: the panel does not serve that form. Status: any HTTP code (`404`, `400`, `403`, `500`...), `"200-html"` (200 + the HTML splash), `"200-empty"` (200, empty body). Per channel through `stream_faults["<id>"].timeshift_reject_forms` (I-M4-11/12/21) |
| `timeshift_range_ignored` | `false` | `Content-Length` of the whole body, no `Accept-Ranges`, `Range` ignored (200 from byte 0): the `bytesToSkip` path a native seek hits on real panels |

Order of checks for a timeshift request: auth/account, redirect/token, `ua_whitelist`, `html_splash`, `timeshift_reject_forms`,
unparseable start (404 `timeshift_bad_start`), `timeshift_window`, `archive_lag_sec`, `timeshift_expect`/`strict_timeshift_tz`,
`timeshift_range_rejects`, then the connection slot (`max_connections`, `linger`).

### Player fixtures (`player_fixtures: true`, synthetic mode only)

`--synthetic N --set player_fixtures=true` changes five generated channels (`/admin/stats.synthetic.player_fixtures` lists them):

| id | role | what |
|---|---|---|
| 2 | `short` | **1-minute programmes** from server start − 3 h to + 4 h (`synthetic.short_window`; the generated schedule outside, cut at the edges; title "Minute Programme"), archive 1 day, `catchup="xc"` (I-M4-9) |
| 3 | `plus2` | `catchup-correction="+2"` in the M3U, archive 1 day, `xc` (I-M4-13b) |
| 4 | `minus15` | `catchup-correction="-1.5"`, archive 1 day, `xc` |
| 7 | `multi_ts` | the `multi` clip (two audio languages + CEA-608), archive 1 day, `xc` (I-M4-7, I-M4-23) |
| 8 | `multi_hls` | the `multi` clip as **live HLS**: its M3U URL is `.m3u8`, a master playlist with `#EXT-X-MEDIA:TYPE=SUBTITLES` (`LANGUAGE="en"`, `NAME="English"`) whose WebVTT segments (`8_vtt{n}.vtt`, `X-TIMESTAMP-MAP` per loop) share the media playlist's sequence numbers; media segments `8_tseg{n}.ts` have continuous timestamps (I-M4-8) |

The correction exists only in the M3U (`get.php`): the Xtream API has no such field, so a client must import the panel as an
M3U URL playlist to see it (the M4 suite does, `m3u=1` on the debug seed link). The panel's zone stays `timezone`
(default `Europe/Berlin`, ≠ UTC). With the flag off, the synthetic lineup is exactly the M3 one.

## Admin API

```
curl localhost:8080/admin/config                                   # current config
curl -X POST localhost:8080/admin/config -d '{"max_connections":1,"faults":{"linger":10,"linger_status":458}}'
curl -X POST localhost:8080/admin/config -d '{"stream_faults":{"3":{"redirect":true,"token_ttl":5}}}'
curl -X POST localhost:8080/admin/config -d '{"faults":{"linger":null}}'   # null inside faults deletes the key
curl -X POST localhost:8080/admin/config -d '{"status":"Expired","quirks":true}'
curl -X POST localhost:8080/admin/config -d '{"timeshift_expect":[1791900000],"strict_timeshift_tz":true}'
curl localhost:8080/admin/stats        # requests, stream_requests (monotonic), request_log[{path,ua,token_present,end_reason}] (last 200), client_eof, rejections by reason, range_requests_seen, connections{active,open,lingering,max_open_seen}, ...
curl localhost:8080/admin/faults       # documented fault keys and current faults
curl localhost:8080/admin/now          # {"unix", "panel_wall", "timezone"}: the panel's clock (live-anchored expectations)
curl -X POST localhost:8080/admin/timeshift_expect -d '{"start_unix":1791900000,"channel":3,"padding_before":60,"strict":true}'
#   sets timeshift_expect to start_unix - correction(channel)*3600 - padding_before; returns {expect_unix, correction_h, panel_wall, xc_start}.
#   For a live-anchored instant (pause, RW: no EPG component) pass "apply_correction": false, so the panel's own clock is expected.
curl -X POST localhost:8080/admin/config -d '{"timeshift_mode":"duration","archive_lag_sec":120}'
curl -X POST localhost:8080/admin/config -d '{"timeshift_reject_forms":{"path":404},"stream_faults":{"3":{"timeshift_reject_forms":{"php":"200-html"}}}}'
curl -X POST localhost:8080/admin/config -d '{"max_connections":1,"faults":{"timeshift_range_rejects":458}}'
curl -X POST localhost:8080/admin/kick # close every open stream
curl -X POST localhost:8080/admin/reset
```

`/admin/stats` also has `timeshift_requests` (last 50, oldest first; M4 tests assert request sequences from it):
`{form, channel, start (raw token), start_unix (as the panel read it, in its zone), duration_s (null when open-ended), range (the Range
header or null), status (set when the response starts, 302/404/... included), bytes, at (unix), ext, lb (served on the redirect port)}`,
and `timeshift_request_count` (monotonic). `/admin/reset` restores the start configuration (CLI flags and `--set` values survive it).

## M4 player suite

`python3 tools/fakepanel/fakepanel.py --port 18080 --synthetic 2000 --set player_fixtures=true --set timeshift_mode=duration`
(the suite also POSTs both keys after every reset, so a panel started without them works for every class except the M4 seed,
which must see the fixture M3U).

## M5 additions (docs/design/m5-setup-settings.md §6.4)

| Key / endpoint | What |
|---|---|
| `adult_category: true` | catalog: category 5 "Adult 18+" and channel 14 "Late Night 18+" (`is_adult: "1"`, in the M3U and XMLTV); synthetic mode: the last group is "Adult 18+" with `is_adult: "1"`. Off by default (the M2–M4 suites count 13 catalog channels) |
| `sleep_login: N` | `player_api.php` login (no `action`) waits N s before answering (the onboarding busy state) |
| `api_fault` | on `player_api.php`: `html_splash` (200 HTML page), `timeout` (no answer for 120 s), `429` (`Retry-After: 2`), `redirect` (one 302 to the same URL + `_redirected=1`) |
| `dns_records: {"panel.test": "10.0.2.2"}` + `/dns-query` | RFC 8484 DoH, GET (`?dns=` base64url) and POST (`application/dns-message`): A records from the map, an empty NOERROR for other types of a known name, NXDOMAIN otherwise; `dns_queries` in `/admin/stats` |
| `/update/update.json`, `/update/update.json.sig`, `/update/<name>.apk`, `/update/pubkey` | the updater's manifest (signed over its exact bytes with Ed25519, `ed25519.py`), its raw 64-byte signature and the APKs uploaded with `POST /admin/update_apk?name=<name>` (raw bytes). Manifest defaults: `app.thebox.debug`, versionCode 2, sequence 1, the uploaded APK's SHA-256 and size; `update_manifest: {...}` overrides fields (`signingCertSha256`, `versionCode`, `sequence`, …); `update_apk` picks the APK; `update_mode`: `valid`, `bad_signature` (one bit flipped), `wrong_hash`, `wrong_cert` (the manifest lists another certificate digest) |
| update key | `--update-key FILE` (hex seed), else `tools/fakepanel/update_test_key` when present (gitignored; the private half of the debug key whose public half is compiled into debug builds), else a fresh key per run: the suite reads `/admin/update_key` (or `/update/pubkey`) and registers it through `thebox-debug://guide?update_key=…` |
| `/admin/hold_connection?stream=ID`, `POST /admin/release_holds` | hold one connection slot of the panel user (I-M5-6), 409 when none is free |
| `/admin/occupy?user=&sid=&mode=hold\|kick_oldest\|release` | another device on the account (M6 I-TS-4, I-TS-25). `hold` (default) takes a free slot of `user` (default: the panel user) until `mode=release` / `POST /admin/release_occupy`; 409 `{"occupied":false}` when none is free. `kick_oldest` closes the oldest open connection of `user` when it is full (the victim's handler stops, its log row ends `kicked_by_occupy`) and takes its slot; that occupier is evictable: the next client open that finds the panel full takes the slot back (`kicked_by_client_open`) instead of getting `linger_status`, and a second `occupy` call kicks the client again (ping-pong). A `hold` occupier is never evicted (the client's reconnect is refused) |
| `connection_log` in `/admin/stats` | last 500 rows `{id, kind: stream\|occupier, user, stream, path, open_unix, close_unix (null while open), bytes (live while open), close_reason, rejected}`; `close_reason`: `client_eof`, `forced_close`, `kicked` (`/admin/kick`), `kicked_by_occupy`, `kicked_by_client_open`, `released`, `server_close`, `server_stop`, `rejected_max_connections`, `rejected_no_free_slot`. HLS playlist/segment requests are not rows. Cleared by `/admin/reset` |
| `media_override: {"<id>": "ac3"}` | an H.264 + AC-3 clip for the "Always passthrough" test (I-M5-27) |

`make_update_apk.py IN.apk OUT.apk --version-code N [--new-key]` rewrites `versionCode` in a debug APK and re-signs it
(debug key, or a throwaway key for the "another signer" case); `run-instrumented.sh --update-fixtures` builds both fixtures,
pushes them to `/data/local/tmp`, installs the app and grants it "Install unknown apps" before the run (I-M5-19).

## Sports: the fake NHL score API (docs/design/m6-score-bug.md §8)

`--sports` (config `sports: true`; `--sports-timeline S` = `sports_timeline_s`, default 360) serves the NHL's own paths
from `sports.py`, so the app only swaps the origin:

- `GET /v1/score/now` → 307 to `/v1/score/{today}` (the panel's Eastern date); `GET /v1/score/{yyyy-MM-dd}`: the day
  list (yesterday: two finals, one in OT; today: five games; tomorrow: two scheduled; any other day: no games);
  `GET /v1/gamecenter/{id}/landing` (404 JSON for an unknown id); `GET /logos/nhl/svg/{ABBR}_{dark|light}.svg`: a neutral
  placeholder badge of ours (paths only, 960 × 640 viewBox; no NHL art in the repo).
- CDN behaviour: every answer is the state at the start of the current 20 s bucket, `Date` = now, `Age` = seconds since
  the bucket start, `Cache-Control: max-age=19`, a bucket ETag (the app must ignore it).
- The featured game (CGY @ EDM, id `{season}020901`) runs FUT → PRE → LIVE 1st (10 s stoppage) → INT → LIVE 2nd (CGY
  goal) → INT → CRIT 3rd (EDM goal) → OT → SO → OFF → FINAL (EDM wins the shootout) in about 6 min from the server start;
  clocks run at 1 s/s. `POST /admin/sports {"restart": true}` (or `{"anchor_unix": T}`) re-anchors it; `GET /admin/sports`
  lists the slate with each game's current state. `/admin/stats` carries `sports: {requests, by_path, log}` (zero-traffic
  checks on a device).
- `sports_script`: `{"<game id>": {gameState, period, periodType, secondsRemaining, running, inIntermission, away, home,
  scheduleState, lastPeriodType, gone}}` overrides a game (`"scheduleState": "PPD"` postpones it; `"gone": true` makes the
  landing 404 and drops it from the day list).
- `sports_faults`: `status` (every `/v1` answer), `score_status`, `landing_status`, `html_404` (404s carry HTML),
  `retry_after`, `pad_bytes` (a filler field; over 524288 trips the app's body cap), `age` (fixed), `no_date`, `stall_s`.

Point a **debug** build at it (the override is read only in debug builds, at app start):

```
adb shell run-as app.thebox.debug sh -c 'mkdir -p files/debug && echo http://10.0.2.2:8080 > files/debug/sports_base'
adb shell am force-stop app.thebox.debug   # then start the app; delete the file to go back to api-web.nhle.com
```

## Logging

One line per request: timestamp, client, method+path, UA, status, bytes, `cons[user]=active/max`, notes (`REJECT:reason`, `REDIRECT`, `FORCED-CLOSE`, `TZ-WARN`...).
Streams log when they end. `--verbose` adds request headers.

## Tests

From the repo root: `python3 -m unittest tools/fakepanel/test_fakepanel.py`.
With `-I` (which drops the cwd from `sys.path`) use `python3 -I -m unittest discover -s tools/fakepanel -p test_fakepanel.py`.
The sports routes: `python3 -I -m unittest discover -s tools/fakepanel -p test_sports.py` (about 2 s).
Tests start the server on ephemeral ports with the null-packet media and need neither ffmpeg nor network.

## Live TS timestamps

The live endpoint loops the 10 s clip and makes the loop invisible: on every loop it adds `loop_count x clip_duration` (90 kHz, measured
from the clip: last minus first PTS of the lowest-frame-rate PID plus one frame interval) to every PES PTS/DTS and to the PCR base
(33-bit fields, modulo 2^33; the 9-bit 27 MHz PCR extension is untouched), and rewrites the continuity counter per PID so it keeps
counting (AF-only packets repeat the previous value). The first loop is byte-identical to the clip. It works on any 188-aligned TS;
`fallback_null.ts` (PID 0x1FFF only) has nothing to rewrite and is served as is. One 10 s loop rewrites in ~15 ms.
Each connection's position, timestamp offset and CCs are saved per stream when it ends, so `reconnect_mode` decides what the next connection sees (see Faults).

## Limitations

- Clip loop continuity is exact for video; audio frames are not re-cut, so at a loop point the audio can overlap or gap by up to
  ~1 AAC frame plus the difference between the audio and video clip lengths (a few hundred ms in the ffmpeg-generated clips).
  PCR spacing at the loop point can likewise be larger than elsewhere (the clip's last PCR precedes the end of its last frame); it never goes backwards.
- Timestamps are rewritten for live TS (`/live/.../N.ts`), for timeshift in `duration` mode and the live-shifted forms, and for
  `/tseg/` and multi-HLS segments. VOD, `clip3` timeshift and `/vodseg/` are served unmodified.
- Live-shifted archive forms start at the clip's first frame, whatever the requested start; only the request log records the start.
- HLS live playlists use `#EXT-X-DISCONTINUITY` at each loop; segments are 2 s keyframe-aligned cuts of the clip.
- Client disconnects (peer FIN/RST) are detected within ~50 ms by polling the socket for readability (`select` + `MSG_PEEK`), also while the server is not writing (`stall`, `stall_after` hold, `slow` naps, pacing sleeps). The stream stops, the slot is released (the `linger` fault still applies), the log says `CLIENT-EOF`, `client_eof` in `/admin/stats` increments and the request's `end_reason` is `client_eof` (others: `server_close`, `forced_close`, `error`; `null` while open). A client that stays connected and sends nothing is not EOF; a client that half-closes its write side (`shutdown(SHUT_WR)`) after the request is.
- Audio-only ch13: the loop length comes from the audio PTS span plus one median PES step (ffmpeg packs ~15 AAC frames per PES), so the loop point can have an audio gap of up to one PES (< ~0.4 s); timestamps never go backwards. Manual check: `curl -m 25 -o /tmp/r.ts http://localhost:8080/live/test/test/13.ts` and scan PTS (see `pid_pts`/`parse_psi` in `test_fakepanel.py`).
- HTTP only (no TLS), HTTP/1.0 close-delimited responses, no HTTP keep-alive.
