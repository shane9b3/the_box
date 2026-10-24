# smbproxy: TCP fault proxy for the save-location tests (WP-S5, design doc 16.3)

Pure stdlib Python 3. Sits between the device and the SMB server and injects faults on command. Payload bytes are
never printed; the log shows mode changes and connection events only.

    emulator --> 10.0.2.2:4446 --> smbproxy --> 127.0.0.1:4445 (tools/smbtest Samba)
                                      ^
                       curl http://127.0.0.1:4447/control?...

## Run

    python3 tools/smbproxy/smbproxy.py [--listen 4446] [--upstream 127.0.0.1:4445] [--control 4447]
    python3 -m unittest tools/smbproxy/test_smbproxy.py        # self-tests, ~12 s

`--listen` defaults to all interfaces (a bare port means `0.0.0.0`); the control port is `127.0.0.1` only.
In the app, enter host `10.0.2.2`, port `4446`, share `rewind` or `guest`.

## Control

`GET` or `POST` `/control` with query parameters, a JSON body, or both. `GET /status` returns the state
(mode, parameters, `connections`, `total_connections`, `total_bytes`, `stall_remaining_ms`). Every `/control`
call replaces the whole fault state (`mode=pass` clears everything). A bad call returns 400 and changes nothing.

| Mode / parameter | Effect |
|---|---|
| `mode=pass` | forward untouched |
| `mode=delay&ms=N[&jitter=J]` (`delay_ms`, `jitter_ms`) | add N ms plus 0..J ms to every chunk, each way; order kept, latency not serialised |
| `mode=throttle&bps=N` (`rate_bps`) | at most N bytes/s in each direction, per connection |
| `mode=stall&ms=N` (`stall_ms`) | stop forwarding everywhere for N ms, then resume; nothing is lost |
| `mode=blackhole` | new connections are accepted and never forwarded (never reach the server); existing ones hold their data. Sockets stay open. Held data flows again after the next non-blackhole call; blackholed connections stay silent until `drop` |
| `mode=drop` | one shot: RST every current connection; the proxy keeps accepting |
| `mode=refuse` | new connections are reset at once; existing ones continue |
| `delay_first_ms=N` | extra N ms on the first server-to-client chunk of each new connection (disk spin-up, A-B4) |
| `close_after_bytes=N` | close each connection after N forwarded bytes (both ways together) |
| `mode=sequence` + `steps` | run steps in order; see below |

Parameters combine without a mode, for example `rate_bps=1000000&stall_ms=6000` (I-SL-7) or
`delay_ms=40&rate_bps=600000`. The mode label then reads `custom`. Any other `/control` call cancels a running
sequence.

### Sequence

Each step is a control object plus `hold_ms` (time before the next step; ignored on the last, whose state stays).

    curl -X POST localhost:4447/control -d '{"mode":"sequence","steps":[
      {"mode":"blackhole","hold_ms":20000},
      {"mode":"drop","hold_ms":500},
      {"mode":"pass"}]}'

From a shell without a body, pass `steps` as a URL-encoded JSON string.

### Example calls

    curl 'localhost:4447/control?mode=delay&ms=200&jitter=100'
    curl 'localhost:4447/control?mode=throttle&bps=600000'        # I-SL-9 SLOW wording
    curl 'localhost:4447/control?rate_bps=1000000&stall_ms=6000'  # I-SL-7
    curl 'localhost:4447/control?mode=blackhole'                  # I-SL-8: wait 20 s, then mode=pass
    curl 'localhost:4447/control?delay_first_ms=12000'            # I-SL-21
    curl 'localhost:4447/control?mode=drop'                       # I-SL-13: then mode=pass within 30 s
    curl 'localhost:4447/control?mode=refuse'
    curl 'localhost:4447/control?mode=pass'
    curl localhost:4447/status

## How the tests use it

- **Device suite** (`SaveLocationTest`, section 16.4): the host-side runner starts the proxy, points the app at
  `10.0.2.2:4446`, and drives faults with the calls above at the steps each case lists (I-SL-7, 8, 9, 12, 13, 21,
  22). Always finish a case with `mode=pass`; the proxy holds no state between runs.
- **GuideDebugReceiver-driven tests**: a script sends the broadcast that chooses the network-folder location
  (host `10.0.2.2`, port `4446`), flips a fault with `curl`, then sends the broadcast that prints the target and
  rates, and compares. The proxy is host-side, so the emulator never needs to reach the control port.
- JVM opt-in tests can connect to `127.0.0.1:4446` directly.

## Notes

- Limits: it is TCP-level; it knows nothing about SMB. `drop` sends RST; a half-close from either side is passed on.
- Not covered: packet loss or reordering. Use `delay`/`stall`/`drop` to approximate.
- Threads: two per direction per connection; fine for the tens of connections the tests open.
