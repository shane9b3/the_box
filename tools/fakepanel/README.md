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
(`testsrc2`+`sine`), an MP2-audio variant, a 50 fps variant, 2 s HLS segments of each, and a `vod.mp4`. Takes ~10 s once.
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
--status Active|Expired|Banned|Disabled --fault K=JSON --stream-fault ID:K=JSON --set K=JSON --media-dir --no-ffmpeg --no-pace --verbose`

Examples: `--fault linger=10 --fault linger_status=458 --max-connections 1`, `--stream-fault 3:redirect=true`.
All are changeable at runtime through `/admin`. See `config.example.json` for every config key.

## Config keys (top level)

`username password timezone quirks series_episodes_array epg_plain auth_mode bad_creds_mode status max_connections
active_cons_override exp_days allowed_output_formats epg_offset epg_gzip strict_timeshift_tz timeshift_expect timeshift_ranges
hls_timeout hls_window token_ttl token_single_use pace faults stream_faults`

`--quirks`: numbers as strings (`stream_id`, `tv_archive:"1"`, `max_connections`...), `exp_date` string, `category_id` null for stream 5,
`backdrop_path` string for vod 103 / series 202, `info: []` for vod 102, `seasons: []` with populated `episodes`.
EPG titles are base64 unless `epg_plain`. `epg_gzip` returns a gzip file body from `xmltv.php` (Content-Type `application/gzip`, no Content-Encoding).

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

Non-`Active` `status` and `auth_mode` 401/403/513 make streams 403/401/513 as well. HLS counts as one session per (user, stream), freed `hls_timeout` s after the last request.

## Timeshift

`/timeshift/{u}/{p}/{minutes}/{YYYY-MM-DD:HH-MM}/{id}.ts|.m3u8` and `/streaming/timeshift.php?username&password&stream&start&duration`.
Also served as archive (start = unix UTC seconds, no zone): Flussonic `/live/{u}/{p}/{id}/{archive|index|video|mono}-{FROM}-{DUR|now}.m3u8|.ts`
(catalog ch4) and SHIFT `/live/{u}/{p}/{id}.ts?utc={FROM}&lutc={NOW}` (catalog ch3). Redirected live HLS playlists carry the token on segment URLs.
The XC start must be server-zone wall time. If `timeshift_expect` is set (list of unix instants or server-zone strings, e.g. the programme start the test fixture just
selected), the start is compared against it (tolerance 90 s) and the log says whether the client sent UTC. Without it, starts in the future or outside the 3-day window are flagged.
A WARNING is logged on mismatch; with `strict_timeshift_tz` the response is 404. The `.ts` form returns `Content-Length` + `Accept-Ranges` + 206 support (set `timeshift_ranges:false` to emulate panels that do not, for D10).

## Admin API

```
curl localhost:8080/admin/config                                   # current config
curl -X POST localhost:8080/admin/config -d '{"max_connections":1,"faults":{"linger":10,"linger_status":458}}'
curl -X POST localhost:8080/admin/config -d '{"stream_faults":{"3":{"redirect":true,"token_ttl":5}}}'
curl -X POST localhost:8080/admin/config -d '{"faults":{"linger":null}}'   # null inside faults deletes the key
curl -X POST localhost:8080/admin/config -d '{"status":"Expired","quirks":true}'
curl -X POST localhost:8080/admin/config -d '{"timeshift_expect":[1791900000],"strict_timeshift_tz":true}'
curl localhost:8080/admin/stats        # requests, request_log[{path,ua,token_present,end_reason}], client_eof, rejections by reason, range_requests_seen, connections{active,open,lingering,max_open_seen}, ...
curl localhost:8080/admin/faults       # documented fault keys and current faults
curl -X POST localhost:8080/admin/kick # close every open stream
curl -X POST localhost:8080/admin/reset
```

## Logging

One line per request: timestamp, client, method+path, UA, status, bytes, `cons[user]=active/max`, notes (`REJECT:reason`, `REDIRECT`, `FORCED-CLOSE`, `TZ-WARN`...).
Streams log when they end. `--verbose` adds request headers.

## Tests

From the repo root: `python3 -m unittest tools/fakepanel/test_fakepanel.py`.
With `-I` (which drops the cwd from `sys.path`) use `python3 -I -m unittest discover -s tools/fakepanel -p test_fakepanel.py`.
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
- Timestamps are only rewritten for live TS (`/live/.../N.ts`). VOD, timeshift and archive TS are served unmodified.
- HLS live playlists use `#EXT-X-DISCONTINUITY` at each loop; segments are 2 s keyframe-aligned cuts of the clip.
- Client disconnects (peer FIN/RST) are detected within ~50 ms by polling the socket for readability (`select` + `MSG_PEEK`), also while the server is not writing (`stall`, `stall_after` hold, `slow` naps, pacing sleeps). The stream stops, the slot is released (the `linger` fault still applies), the log says `CLIENT-EOF`, `client_eof` in `/admin/stats` increments and the request's `end_reason` is `client_eof` (others: `server_close`, `forced_close`, `error`; `null` while open). A client that stays connected and sends nothing is not EOF; a client that half-closes its write side (`shutdown(SHUT_WR)`) after the request is.
- Audio-only ch13: the loop length comes from the audio PTS span plus one median PES step (ffmpeg packs ~15 AAC frames per PES), so the loop point can have an audio gap of up to one PES (< ~0.4 s); timestamps never go backwards. Manual check: `curl -m 25 -o /tmp/r.ts http://localhost:8080/live/test/test/13.ts` and scan PTS (see `pid_pts`/`parse_psi` in `test_fakepanel.py`).
- HTTP only (no TLS), HTTP/1.0 close-delimited responses, no HTTP keep-alive.
