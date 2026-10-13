#!/usr/bin/env python3
"""fakepanel - fake Xtream Codes panel + stream server for The Box (dev/test tool, stdlib only).

    python3 tools/fakepanel/fakepanel.py --port 8080

See README.md. Known v0 limitation: live TS loops a 10 s clip without rewriting PCR/PTS/continuity
counters, so the loop point is a timestamp discontinuity and CC errors; players must tolerate it.
"""
import argparse
import copy
import datetime as dt
import http.server
import json
import math
import os
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog as cat  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
PKT = 188
CHUNK = PKT * 28

DEFAULTS = {
    "username": "test", "password": "test", "timezone": "Europe/Berlin",
    "quirks": False, "series_episodes_array": False, "epg_plain": False,
    "auth_mode": "ok",          # ok|auth0|empty|array|401|403|513 (forced failure for player_api and streams)
    "bad_creds_mode": "auth0",  # what wrong credentials produce
    "status": "Active",         # Active|Expired|Banned|Disabled (non-Active: streams 403)
    "max_connections": 2, "active_cons_override": None, "exp_days": 30,
    "allowed_output_formats": ["ts", "m3u8"],
    "epg_offset": "+0000", "epg_gzip": False,
    "strict_timeshift_tz": False, "timeshift_expect": [], "timeshift_ranges": True,
    "hls_timeout": 20, "hls_window": 5, "token_ttl": 30, "token_single_use": False, "pace": True,
    "faults": {}, "stream_faults": {},
}
FAULT_DOC = {
    "redirect": False, "token_ttl": None, "linger": 0, "linger_status": 403, "close_every": 0,
    "reconnect_mode": "continue", "stall": 0, "slow": 0, "ua_whitelist": [], "wrong_content_type": False,
    "html_splash": False, "405_on_m3u8": False, "range_rejects": False,
}
SPLASH = b"<html><head><title>Subscription</title></head><body><h1>Your subscription has expired</h1></body></html>"


# ------------------------------------------------------------------ media

class Clip:
    def __init__(self, data, duration, segments):
        self.data, self.duration, self.segments = data, duration, segments
        self.rate = len(data) / duration  # bytes/sec


def _read(path):
    with open(path, "rb") as fh:
        return fh.read()


def _run(cmd):
    return subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=180)


def build_media(media_dir, use_ffmpeg, log):
    """Return ({variant: Clip}, vod_bytes, vod_ctype)."""
    fb = os.path.join(HERE, "fallback_null.ts")
    fb_data = _read(fb) if os.path.exists(fb) else (b"\x47\x1f\xff\x10" + b"\xff" * 184) * 500
    n = len(fb_data) // PKT // 5 * PKT
    fb_clip = Clip(fb_data, 2.0, [(fb_data[i * n:(i + 1) * n], 0.4) for i in range(5)])
    ff = shutil.which("ffmpeg") if use_ffmpeg else None
    if not ff:
        log("media: ffmpeg not used; serving null-packet fallback TS (no decodable video)")
        return {k: fb_clip for k in ("std", "mp2", "50fps")}, fb_data * 3, "video/mp2t"
    os.makedirs(os.path.join(media_dir, "hls"), exist_ok=True)
    spec = {"std": ("h264_aac_25", 25, "aac"), "mp2": ("h264_mp2_25", 25, "mp2"), "50fps": ("h264_aac_50", 50, "aac")}
    clips = {}
    for key, (name, fps, ac) in spec.items():
        path = os.path.join(media_dir, name + ".ts")
        if not os.path.exists(path):
            log(f"media: generating {path}")
            r = _run([ff, "-y", "-v", "error", "-f", "lavfi", "-i", f"testsrc2=size=1280x720:rate={fps}",
                      "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "10",
                      "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-g", str(fps * 2),
                      "-keyint_min", str(fps * 2), "-sc_threshold", "0", "-b:v", "2M", "-maxrate", "2M", "-bufsize", "2M",
                      "-c:a", "aac" if ac == "aac" else "mp2", "-b:a", "128k", "-f", "mpegts", path])
            if r.returncode:
                log("media: ffmpeg failed: " + r.stderr.decode(errors="replace")[-300:])
                clips[key] = fb_clip
                continue
        segs_glob = os.path.join(media_dir, "hls", name + "_%03d.ts")
        if not os.path.exists(segs_glob % 0):
            _run([ff, "-y", "-v", "error", "-i", path, "-c", "copy", "-f", "segment", "-segment_time", "2",
                  "-segment_format", "mpegts", segs_glob])
        segs, i = [], 0
        while os.path.exists(segs_glob % i):
            segs.append((_read(segs_glob % i), 2.0))
            i += 1
        data = _read(path)
        clips[key] = Clip(data, 10.0, segs or [(data, 10.0)])
    vod = os.path.join(media_dir, "vod.mp4")
    if not os.path.exists(vod):
        _run([ff, "-y", "-v", "error", "-stream_loop", "2", "-i", os.path.join(media_dir, "h264_aac_25.ts"),
              "-c", "copy", "-movflags", "+faststart", vod])
    if os.path.exists(vod):
        return clips, _read(vod), "video/mp4"
    return clips, clips["std"].data * 3, "video/mp2t"


# ------------------------------------------------------------------ connection registry

class Slot:
    def __init__(self, user, sid, linger, hls=False):
        self.user, self.sid, self.linger, self.hls = user, sid, linger, hls
        self.closed_at = None
        self.last_seen = time.monotonic()
        self.killed = False


class Registry:
    def __init__(self):
        self.lock = threading.Lock()
        self.slots = []

    def _purge(self, now, hls_timeout):
        keep = []
        for s in self.slots:
            if s.hls and now - s.last_seen >= hls_timeout:
                continue
            if not s.hls and s.closed_at is not None and now - s.closed_at >= s.linger:
                continue
            keep.append(s)
        self.slots = keep

    def counts(self, user, hls_timeout=20):
        with self.lock:
            self._purge(time.monotonic(), hls_timeout)
            mine = [s for s in self.slots if s.user == user]
            open_ = sum(1 for s in mine if s.closed_at is None)
            return {"active": len(mine), "open": open_, "lingering": len(mine) - open_}

    def acquire(self, user, sid, maxc, linger, hls_timeout, hls=False):
        now = time.monotonic()
        with self.lock:
            self._purge(now, hls_timeout)
            if hls:
                for s in self.slots:
                    if s.hls and s.user == user and s.sid == sid:
                        s.last_seen = now
                        return s
            if sum(1 for s in self.slots if s.user == user) >= maxc:
                return None
            s = Slot(user, sid, linger, hls)
            self.slots.append(s)
            return s

    def touch(self, user, sid):
        with self.lock:
            for s in self.slots:
                if s.hls and s.user == user and s.sid == sid:
                    s.last_seen = time.monotonic()

    def close(self, slot):
        with self.lock:
            slot.closed_at = time.monotonic()

    def kill_all(self):
        with self.lock:
            for s in self.slots:
                s.killed = True


# ------------------------------------------------------------------ panel state

def deep_merge_faults(dst, src):
    for k, v in src.items():
        if v is None:
            dst.pop(k, None)
        elif isinstance(v, dict) and isinstance(dst.get(k), dict):
            deep_merge_faults(dst[k], v)
        else:
            dst[k] = copy.deepcopy(v)


class Panel:
    def __init__(self, cfg, media_dir=None, use_ffmpeg=True, verbose=False, quiet=False):
        self.initial = copy.deepcopy(cfg)
        self.cfg = copy.deepcopy(cfg)
        self.lock = threading.Lock()
        self.registry = Registry()
        self.verbose, self.quiet = verbose, quiet
        self.stopping = threading.Event()
        self.tokens = {}
        self.live_pos = {}
        self.main_port = self.lb_port = 0
        self.reset_stats()
        self.clips, self.vod_data, self.vod_ctype = build_media(
            media_dir or os.path.join(HERE, "media"), use_ffmpeg, self.log)

    def reset_stats(self):
        self.stats = {"requests": 0, "stream_opens": 0, "rejections": {}, "range_requests_seen": 0,
                      "range_requests": [], "auth_failures": 0, "redirects": 0, "token_expired": 0,
                      "forced_closes": 0, "stalls": 0, "timeshift_tz_warnings": 0}

    def tz(self):
        try:
            return ZoneInfo(self.cfg["timezone"])
        except Exception:
            self.log(f"WARNING bad timezone {self.cfg['timezone']!r}, using UTC")
            return dt.timezone.utc

    def log(self, msg):
        if not self.quiet:
            print(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}", flush=True)

    def bump(self, key, sub=None):
        with self.lock:
            if sub is None:
                self.stats[key] += 1
            else:
                self.stats[key][sub] = self.stats[key].get(sub, 0) + 1

    def faults(self, sid):
        f = dict(self.cfg.get("faults", {}))
        f.update(self.cfg.get("stream_faults", {}).get(str(sid), {}))
        return f

    def update_config(self, patch):
        unknown = [k for k in patch if k not in DEFAULTS]
        if unknown:
            raise ValueError("unknown config keys: " + ", ".join(unknown))
        with self.lock:
            for k, v in patch.items():
                if k in ("faults", "stream_faults"):
                    deep_merge_faults(self.cfg[k], v)
                else:
                    self.cfg[k] = copy.deepcopy(v)

    def reset(self):
        with self.lock:
            self.cfg = copy.deepcopy(self.initial)
            self.tokens.clear()
            self.live_pos.clear()
        self.registry.kill_all()
        self.registry.slots = []
        self.reset_stats()

    def make_token(self, path, ttl):
        tok = secrets.token_hex(8)
        with self.lock:
            now = time.time()
            self.tokens = {k: v for k, v in self.tokens.items() if v[0] > now - 600}
            self.tokens[tok] = [now + ttl, path, 0]
        return tok

    def check_token(self, tok, path):
        with self.lock:
            t = self.tokens.get(tok)
            if not t or t[1] != path:
                return "invalid"
            if time.time() >= t[0]:
                return "expired"
            if self.cfg.get("token_single_use") and t[2] >= 1:
                return "expired"
            t[2] += 1
            return "ok"


# ------------------------------------------------------------------ HTTP handler

TS_START_RE = re.compile(r"^(\d{4})-(\d{2})-(\d{2}):(\d{2})-(\d{2})(?:-(\d{2}))?$")
ALT_START_RE = re.compile(r"^(\d{4})(\d{2})(\d{2})-(\d{2})$")


def parse_start(s):
    m = TS_START_RE.match(s)
    if m:
        y, mo, d, h, mi, se = (int(x) if x else 0 for x in m.groups())
    else:
        m = ALT_START_RE.match(s)
        if not m:
            return None
        y, mo, d, h = (int(x) for x in m.groups())
        mi = se = 0
    try:
        return dt.datetime(y, mo, d, h, mi, se)
    except ValueError:
        return None


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"  # close-delimited bodies: simplest for endless streams
    server_version = "fakepanel/0.1"

    def log_message(self, *a):
        pass

    def log_request(self, *a):
        pass

    # ---- plumbing
    @property
    def panel(self):
        return self.server.panel

    def do_GET(self):
        self.route()

    do_HEAD = do_POST = do_PUT = do_GET

    def route(self):
        self._status, self._bytes, self._notes, self._user = 0, 0, [], None
        self.panel.bump("requests")
        u = urllib.parse.urlsplit(self.path)
        self.path_only = urllib.parse.unquote(u.path)
        self.q = {k: v[-1] for k, v in urllib.parse.parse_qs(u.query, keep_blank_values=True).items()}
        try:
            self.dispatch()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, socket.timeout):
            self._notes.append("client-gone")
        except Exception:
            traceback.print_exc()
            try:
                self.send(500, b"internal error", "text/plain")
            except Exception:
                pass
        finally:
            self.access_log()

    def access_log(self):
        p = self.panel
        cons = ""
        if self._user is not None:
            c = p.registry.counts(self._user, p.cfg["hls_timeout"])
            cons = f" cons[{self._user}]={c['active']}/{p.cfg['max_connections']}"
        ua = self.headers.get("User-Agent", "-")
        notes = (" [" + ",".join(self._notes) + "]") if self._notes else ""
        p.log(f"{self.client_address[0]} {self.command} {self.path} ua=\"{ua}\" -> {self._status} {self._bytes}B{cons}{notes}")
        if p.verbose:
            for k, v in self.headers.items():
                p.log(f"    > {k}: {v}")

    def send(self, status, body=b"", ctype="application/json", headers=None, reason=None):
        if isinstance(body, str):
            body = body.encode()
        self._status = status
        self.send_response(status, reason)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)
            self._bytes += len(body)

    def json(self, obj, status=200):
        self.send(status, json.dumps(obj), "application/json")

    def begin_stream(self, status, ctype, headers=None):
        self._status = status
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-cache")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()

    def out(self, data):
        self.wfile.write(data)
        self._bytes += len(data)

    def nap(self, secs, slot=None):
        """Sleep in small steps; False if the server is stopping or the slot was kicked."""
        end = time.monotonic() + secs
        while True:
            left = end - time.monotonic()
            if self.panel.stopping.is_set() or (slot and slot.killed):
                return False
            if left <= 0:
                return True
            time.sleep(min(left, 0.1))

    def reject(self, status, reason, body=b"", ctype="text/plain"):
        self.panel.bump("rejections", reason)
        self._notes.append("REJECT:" + reason)
        self.send(status, body or reason, ctype)

    # ---- routing
    def dispatch(self):
        p, path = self.panel, self.path_only
        if path.startswith("/admin"):
            return self.admin(path)
        if self.server.is_lb and not re.match(r"^/(live|movie|series|timeshift)/", path):
            return self.send(404, b"lb: stream paths only", "text/plain")
        if path == "/player_api.php":
            return self.player_api()
        if path == "/get.php":
            return self.get_php()
        if path == "/xmltv.php":
            return self.xmltv_php()
        if path == "/streaming/timeshift.php":
            q = self.q
            return self.stream_request("timeshift", q.get("username"), q.get("password"),
                                       q.get("stream", ""), "ts", start=q.get("start", ""), dur=q.get("duration", "60"))
        m = re.match(r"^/vodseg/(\d+)_(\d+)\.ts$", path)
        if m and int(m[1]) in cat.CH_BY_ID:
            segs = p.clips[cat.CH_BY_ID[int(m[1])]["media"]].segments
            return self.send(200, segs[int(m[2]) % len(segs)][0], "video/mp2t")
        if path.startswith("/logo/"):
            return self.send(200, b"\x89PNG\r\n\x1a\n", "image/png")
        m = re.match(r"^/(live|movie|series)/([^/]+)/([^/]+)/([^/]+?)(?:\.([A-Za-z0-9]+))?$", path)
        if m:
            return self.stream_request(m[1], m[2], m[3], m[4], m[5] or "ts")
        m = re.match(r"^/timeshift/([^/]+)/([^/]+)/(\d+)/([^/]+)/(\d+)(?:\.([A-Za-z0-9]+))?$", path)
        if m:
            return self.stream_request("timeshift", m[1], m[2], m[5], m[6] or "ts", start=m[4], dur=m[3])
        m = re.match(r"^/([^/]+)/([^/]+)/(\d+)(?:\.([A-Za-z0-9]+))?$", path)  # short live form
        if m:
            return self.stream_request("live", m[1], m[2], m[3], m[4] or "ts")
        self.send(404, b"not found", "text/plain")

    # ---- admin
    def admin(self, path):
        p = self.panel
        body = b""
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            body = self.rfile.read(n)
        try:
            data = json.loads(body) if body.strip() else {}
        except ValueError:
            return self.json({"error": "invalid JSON"}, 400)
        if path in ("/admin", "/admin/config"):
            if self.command in ("POST", "PUT") and data:
                try:
                    p.update_config(data)
                except ValueError as e:
                    return self.json({"error": str(e)}, 400)
                p.log(f"ADMIN config updated: {json.dumps(data)}")
            return self.json(p.cfg)
        if path == "/admin/stats":
            s = copy.deepcopy(p.stats)
            s["connections"] = p.registry.counts(p.cfg["username"], p.cfg["hls_timeout"])
            return self.json(s)
        if path == "/admin/reset" and self.command != "GET":
            p.reset()
            p.log("ADMIN reset")
            return self.json(p.cfg)
        if path == "/admin/kick" and self.command != "GET":
            p.registry.kill_all()
            return self.json({"kicked": True})
        if path == "/admin/faults":
            return self.json({"documented_fault_keys": FAULT_DOC, "global": p.cfg["faults"], "per_stream": p.cfg["stream_faults"]})
        self.json({"error": "unknown admin endpoint"}, 404)

    # ---- Xtream API
    def auth_fail(self, mode):
        self.panel.bump("auth_failures")
        self._notes.append("AUTHFAIL:" + mode)
        if mode == "auth0":
            return self.json({"user_info": {"auth": 0}})
        if mode == "empty":
            return self.send(200, b"", "text/html")
        if mode == "array":
            return self.json([])
        return self.send(int(mode), b"", "text/plain")

    def creds_ok(self, u, pw):
        c = self.panel.cfg
        return u == c["username"] and pw == c["password"]

    def base(self):
        return f"http://{self.headers.get('Host') or ('127.0.0.1:%d' % self.panel.main_port)}"

    def hostport(self):
        h = (self.headers.get("Host") or "127.0.0.1").split(":")[0]
        return h, self.panel.main_port

    def player_api(self):
        p, q = self.panel, self.q
        c = p.cfg
        self._user = q.get("username")
        if c["auth_mode"] != "ok":
            return self.auth_fail(c["auth_mode"])
        if not self.creds_ok(q.get("username"), q.get("password")):
            return self.auth_fail(c["bad_creds_mode"])
        host, port = self.hostport()
        tz, now = p.tz(), time.time()
        act = q.get("action")
        cid = q.get("category_id")
        if act is None:
            n = c["active_cons_override"]
            if n is None:
                n = p.registry.counts(c["username"], c["hls_timeout"])["active"]
            return self.json(cat.user_info(c, n, host, port, tz, now))
        if act == "get_live_categories":
            return self.json(cat.categories(cat.LIVE_CATS))
        if act == "get_vod_categories":
            return self.json(cat.categories(cat.VOD_CATS))
        if act == "get_series_categories":
            return self.json(cat.categories(cat.SERIES_CATS))
        if act == "get_live_streams":
            return self.json(cat.live_streams(c, host, port, cid))
        if act == "get_vod_streams":
            return self.json(cat.vod_streams(c, host, port, cid))
        if act == "get_vod_info":
            return self.json(cat.vod_info(c, host, port, q.get("vod_id", "")))
        if act == "get_series":
            return self.json(cat.series_list(c, host, port, cid))
        if act == "get_series_info":
            return self.json(cat.series_info(c, host, port, q.get("series_id", q.get("series", ""))))
        if act in ("get_short_epg", "get_simple_data_table"):
            sid = q.get("stream_id", "")
            ch = cat.CH_BY_ID.get(int(sid)) if sid.isdigit() else None
            if not ch:
                return self.json({"epg_listings": []})
            short = act == "get_short_epg"
            lim = int(q["limit"]) if q.get("limit", "").isdigit() else 4
            return self.json(cat.epg_listings(c, ch, tz, now, short, lim))
        self.json([])

    def get_php(self):
        q, c = self.q, self.panel.cfg
        self._user = q.get("username")
        if c["auth_mode"] != "ok":
            return self.auth_fail(c["auth_mode"])
        if not self.creds_ok(q.get("username"), q.get("password")):
            return self.auth_fail("403" if c["bad_creds_mode"] == "auth0" else c["bad_creds_mode"])
        ext = "m3u8" if q.get("output") in ("m3u8", "hls") else "ts"
        host, port = self.hostport()
        self.send(200, cat.m3u(c, self.base(), host, port, ext, q.get("type", "m3u_plus") == "m3u_plus"),
                  "audio/x-mpegurl", {"Content-Disposition": 'attachment; filename="playlist.m3u"'})

    def xmltv_php(self):
        q, c = self.q, self.panel.cfg
        self._user = q.get("username")
        if c["auth_mode"] != "ok":
            return self.auth_fail(c["auth_mode"])
        if not self.creds_ok(q.get("username"), q.get("password")):
            return self.auth_fail("403" if c["bad_creds_mode"] == "auth0" else c["bad_creds_mode"])
        body = cat.xmltv(c, self.base(), None, time.time(), c["epg_offset"])
        if c["epg_gzip"]:
            return self.send(200, cat.maybe_gzip(body, True), "application/gzip")
        self.send(200, body, "text/xml; charset=utf-8")

    # ---- streams
    def timeshift_check(self, start, dur):
        """Returns (ok, message). Logs WARNING on suspected wrong-timezone start."""
        p, c = self.panel, self.panel.cfg
        naive = parse_start(start)
        if naive is None:
            return False, "unparseable start"
        tz = p.tz()
        inst = naive.replace(tzinfo=tz).timestamp()
        now = time.time()
        problem = None
        exp = []
        for e in c.get("timeshift_expect", []):
            if isinstance(e, (int, float)):
                exp.append(float(e))
            else:
                n2 = parse_start(str(e))
                if n2:
                    exp.append(n2.replace(tzinfo=tz).timestamp())
        if exp:
            if not any(abs(inst - e) <= 90 for e in exp):
                as_utc = naive.replace(tzinfo=dt.timezone.utc).timestamp()
                diag = "client sent UTC wall time" if any(abs(as_utc - e) <= 90 for e in exp) else "matches neither server zone nor UTC (device zone?)"
                problem = (f"timeshift start {start} read in {c['timezone']} is {dt.datetime.fromtimestamp(inst, tz).isoformat()}, "
                           f"expected one of {c['timeshift_expect']}: {diag}")
        elif inst > now + 60:
            problem = f"timeshift start {start} is in the FUTURE in {c['timezone']}: client probably sent UTC/device time (zone west of server)"
        elif now - inst > cat.ARCHIVE_DAYS * 86400 + 3600:
            problem = f"timeshift start {start} is older than the {cat.ARCHIVE_DAYS}-day archive window"
        if problem:
            p.bump("timeshift_tz_warnings")
            p.log("WARNING " + problem)
            self._notes.append("TZ-WARN")
            if c["strict_timeshift_tz"]:
                return False, problem
        return True, ""

    def stream_request(self, kind, user, pw, ident, ext, start=None, dur=None):
        p, c = self.panel, self.panel.cfg
        self._user = user
        ext = (ext or "ts").lower()
        if c["auth_mode"] in ("401", "403", "513"):
            return self.reject(int(c["auth_mode"]), "auth_mode")
        if not self.creds_ok(user, pw):
            return self.reject(403, "bad_credentials")
        if c["status"] != "Active":
            return self.reject(403, "account_" + c["status"].lower())
        # HLS segment: /live/u/p/{id}_seg{n}.ts
        seg = re.match(r"^(\d+)_seg(\d+)$", ident) if kind == "live" else None
        sid_s = seg[1] if seg else ident
        if not sid_s.isdigit() or not self.known(kind, int(sid_s)):
            return self.reject(404, "unknown_stream")
        sid = int(sid_s)
        f = p.faults(sid)
        path = self.path_only

        if self.server.is_lb:  # second port: token required
            res = p.check_token(self.q.get("token", ""), path)
            if res != "ok":
                p.bump("token_expired") if res == "expired" else None
                return self.reject(403, "token_" + res)
        elif f.get("redirect") and not seg:
            ttl = f.get("token_ttl") or c["token_ttl"]
            tok = p.make_token(path, ttl)
            h = (self.headers.get("Host") or "127.0.0.1").split(":")[0]
            p.bump("redirects")
            self._notes.append("REDIRECT")
            self._status = 302
            self.send_response(302)
            self.send_header("Location", f"http://{h}:{p.lb_port}{path}?token={tok}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        wl = f.get("ua_whitelist") or []
        ua = self.headers.get("User-Agent", "")
        if wl and not any(w.lower() in ua.lower() for w in wl):
            return self.reject(403, "ua_whitelist")
        allowed = [x.lower() for x in c["allowed_output_formats"]]
        if kind == "live" and ((ext == "m3u8" and (f.get("405_on_m3u8") or "m3u8" not in allowed))
                               or (ext == "ts" and "ts" not in allowed)):
            return self.reject(405, "output_format_not_allowed")
        rng = self.headers.get("Range")
        if rng and kind == "live" and ext == "ts":
            p.bump("range_requests_seen")
            with p.lock:
                p.stats["range_requests"] = (p.stats["range_requests"] + [{"path": path, "range": rng, "ua": ua}])[-20:]
            p.log(f"!!!!! RANGE REQUEST ON LIVE TS: '{rng}' {self.command} {path} ua={ua!r} "
                  "(IPTVnator-class bug: live TS must never be range-requested)")
            if f.get("range_rejects"):
                code = 400 if f["range_rejects"] == 400 else 416
                return self.reject(code, "range_rejects")
        if f.get("html_splash"):
            self._notes.append("HTML-SPLASH")
            return self.send(200, SPLASH, "text/html")
        if kind == "timeshift":
            ok, msg = self.timeshift_check(start or "", dur)
            if not ok:
                return self.reject(404, "timeshift_tz" if msg != "unparseable start" else "timeshift_bad_start")

        clip = p.clips[cat.CH_BY_ID[sid]["media"]] if kind in ("live", "timeshift") else None
        hls = kind in ("live", "timeshift") and ext == "m3u8"
        if seg:
            p.registry.touch(user, sid)
            return self.hls_segment(clip, sid, int(seg[2]))
        slot = p.registry.acquire(user, sid, c["max_connections"], float(f.get("linger") or 0), c["hls_timeout"], hls=(kind == "live" and hls))
        if slot is None:
            st = f.get("linger_status", 403)
            if st == "close":  # connect then close immediately
                self.begin_stream(200, "video/mp2t")
                self.panel.bump("rejections", "max_connections_close")
                self._notes.append("REJECT:max_connections_close")
                return
            return self.reject(int(st), "max_connections")
        p.bump("stream_opens")
        try:
            if f.get("stall"):
                p.bump("stalls")
                self._notes.append(f"STALL{f['stall']}s")
                if not self.nap(float(f["stall"]), slot):
                    return
            if kind == "live" and hls:
                return self.hls_playlist(clip, sid, f)
            if kind == "live":
                return self.live_ts(clip, sid, f, slot)
            if kind == "timeshift" and hls:
                return self.timeshift_playlist(clip)
            data, ctype = (clip.data * 3, "video/mp2t") if kind == "timeshift" else (p.vod_data, p.vod_ctype)
            if f.get("wrong_content_type"):
                ctype = "text/html"
            return self.finite(data, ctype, f, ranges=(c["timeshift_ranges"] if kind == "timeshift" else True))
        finally:
            if not slot.hls:
                p.registry.close(slot)

    def known(self, kind, i):
        if kind in ("live", "timeshift"):
            return i in cat.CH_BY_ID
        if kind == "movie":
            return i in cat.MOVIE_BY_ID
        return i in cat.EPISODE_IDS

    def finite(self, data, ctype, f, ranges=True):
        n = len(data)
        rng = self.headers.get("Range")
        start, end, status = 0, n - 1, 200
        hdr = {"Accept-Ranges": "bytes"} if ranges else {}
        if rng and ranges:
            m = re.match(r"^bytes=(\d*)-(\d*)$", rng.strip())
            if m and (m[1] or m[2]):
                if m[1]:
                    start = int(m[1])
                    end = int(m[2]) if m[2] else n - 1
                else:
                    start = max(0, n - int(m[2]))
                if start >= n:
                    return self.send(416, b"", "text/plain", {"Content-Range": f"bytes */{n}"})
                end = min(end, n - 1)
                status = 206
                hdr["Content-Range"] = f"bytes {start}-{end}/{n}"
        body_len = end - start + 1
        if ranges:
            hdr["Content-Length"] = str(body_len)
        self.begin_stream(status, ctype, hdr)
        if self.command == "HEAD":
            return
        slow = float(f.get("slow") or 0)
        step = max(PKT, int(slow / 10)) if slow else 64 * 1024
        pos = start
        while pos <= end:
            e = min(pos + step, end + 1)
            self.out(data[pos:e])
            pos = e
            if slow and not self.nap(step / slow):
                break

    def live_ts(self, clip, sid, f, slot):
        p, data, n = self.panel, clip.data, len(clip.data)
        ctype = "text/html" if f.get("wrong_content_type") else "video/mp2t"
        self.begin_stream(200, ctype, {"Connection": "close"})
        if self.command == "HEAD":
            return
        mode = f.get("reconnect_mode", "continue")
        last = p.live_pos.get(sid, 0)
        pos = {"continue": last, "reset": 0, "jump": (last + int(clip.rate * 5)) // PKT * PKT % n}.get(mode, last)
        self._notes.append(f"start@{pos}/{mode}")
        slow = float(f.get("slow") or 0)
        rate = slow or clip.rate
        chunk = max(PKT, min(CHUNK, int(rate / 10) // PKT * PKT))
        pace = bool(p.cfg["pace"]) or bool(slow)
        burst = 0 if slow else rate  # 1 s of burst so zaps start fast
        close_every = float(f.get("close_every") or 0)
        t0, sent = time.monotonic(), 0
        try:
            while not p.stopping.is_set() and not slot.killed:
                el = time.monotonic() - t0
                if close_every and el >= close_every:
                    p.bump("forced_closes")
                    self._notes.append(f"FORCED-CLOSE after {close_every}s")
                    try:
                        self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    break
                e = min(pos + chunk, n)
                self.out(data[pos:e])
                sent += e - pos
                pos = 0 if e == n else e
                if pace:
                    wait = (sent - burst) / rate - (time.monotonic() - t0)
                    if wait > 0:
                        time.sleep(min(wait, 0.1))
        finally:
            p.live_pos[sid] = pos
            self.close_connection = True

    # ---- HLS
    def seg_dur(self, clip):
        return clip.segments[0][1]

    def hls_playlist(self, clip, sid, f):
        c = self.panel.cfg
        d, nseg = self.seg_dur(clip), len(clip.segments)
        cur = int(time.time() / d)
        first = max(0, cur - c["hls_window"] + 1)
        L = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{math.ceil(d)}", f"#EXT-X-MEDIA-SEQUENCE:{first}",
             f"#EXT-X-DISCONTINUITY-SEQUENCE:{(first - 1) // nseg if first > 0 else 0}"]
        for n in range(first, cur + 1):
            if n > 0 and n % nseg == 0:
                L.append("#EXT-X-DISCONTINUITY")  # clip loop: timestamps restart
            L += [f"#EXTINF:{clip.segments[n % nseg][1]:.3f},", f"{sid}_seg{n}.ts"]
        ctype = "text/html" if f.get("wrong_content_type") else "application/vnd.apple.mpegurl"
        self.send(200, "\n".join(L) + "\n", ctype, {"Cache-Control": "no-cache"})

    def hls_segment(self, clip, sid, n):
        d, nseg = self.seg_dur(clip), len(clip.segments)
        cur = int(time.time() / d)
        if n > cur + 1 or n < cur - self.panel.cfg["hls_window"] - 3:
            return self.reject(404, "segment_out_of_window")
        self.send(200, clip.segments[n % nseg][0], "video/mp2t")

    def timeshift_playlist(self, clip):
        d, nseg = self.seg_dur(clip), len(clip.segments)
        L = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{math.ceil(d)}", "#EXT-X-MEDIA-SEQUENCE:0",
             "#EXT-X-PLAYLIST-TYPE:VOD"]
        sid = self.path_only.rsplit("/", 1)[1].split(".")[0]
        for n in range(nseg):
            L += [f"#EXTINF:{clip.segments[n][1]:.3f},", f"/vodseg/{sid}_{n}.ts"]
        L.append("#EXT-X-ENDLIST")
        self.send(200, "\n".join(L) + "\n", "application/vnd.apple.mpegurl")


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 128
    allow_reuse_address = True

    def __init__(self, addr, panel, is_lb):
        super().__init__(addr, Handler)
        self.panel, self.is_lb = panel, is_lb


def start_servers(panel, host="0.0.0.0", port=8080, redirect_port=None):
    """Start main + redirect-target servers in daemon threads. Returns (main, lb)."""
    main = Server((host, port), panel, False)
    panel.main_port = main.server_address[1]
    lb = Server((host, panel.main_port + 1 if redirect_port is None and port else (redirect_port or 0)), panel, True)
    panel.lb_port = lb.server_address[1]
    for s in (main, lb):
        threading.Thread(target=s.serve_forever, kwargs={"poll_interval": 0.2}, daemon=True).start()
    return main, lb


def stop_servers(panel, *servers):
    panel.stopping.set()
    for s in servers:
        s.shutdown()
        s.server_close()


# ------------------------------------------------------------------ CLI

def parse_kv(s):
    k, _, v = s.partition("=")
    try:
        return k, json.loads(v)
    except ValueError:
        return k, v


def main(argv=None):
    ap = argparse.ArgumentParser(description="Fake Xtream Codes panel + stream server")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--redirect-port", type=int, default=None, help="port for 302 token targets (default port+1)")
    ap.add_argument("--config", help="JSON config file (see config.example.json)")
    ap.add_argument("--username"), ap.add_argument("--password")
    ap.add_argument("--timezone", help="IANA server zone, e.g. Europe/London")
    ap.add_argument("--quirks", action="store_true", help="real-world JSON type quirks")
    ap.add_argument("--series-episodes-array", action="store_true")
    ap.add_argument("--epg-plain", action="store_true", help="plain-text EPG titles instead of base64")
    ap.add_argument("--epg-offset", help="XMLTV +HHMM offset label, e.g. +0100")
    ap.add_argument("--epg-gzip", action="store_true")
    ap.add_argument("--strict-timeshift-tz", action="store_true")
    ap.add_argument("--max-connections", type=int)
    ap.add_argument("--auth-mode", choices=["ok", "auth0", "empty", "array", "401", "403", "513"])
    ap.add_argument("--status", choices=["Active", "Expired", "Banned", "Disabled"])
    ap.add_argument("--set", action="append", default=[], metavar="KEY=JSON", help="any top-level config key")
    ap.add_argument("--fault", action="append", default=[], metavar="KEY=JSON", help="global fault, e.g. linger=5")
    ap.add_argument("--stream-fault", action="append", default=[], metavar="ID:KEY=JSON")
    ap.add_argument("--media-dir", default=os.path.join(HERE, "media"))
    ap.add_argument("--no-ffmpeg", action="store_true")
    ap.add_argument("--no-pace", action="store_true", help="send live TS as fast as possible")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args(argv)

    cfg = copy.deepcopy(DEFAULTS)
    if a.config:
        with open(a.config) as fh:
            user = json.load(fh)
        for k, v in user.items():
            if k.startswith("_"):
                continue
            if k not in cfg:
                ap.error(f"unknown config key {k!r}")
            cfg[k] = v
    for flag, key in [("username", "username"), ("password", "password"), ("timezone", "timezone"),
                      ("epg_offset", "epg_offset"), ("max_connections", "max_connections"),
                      ("auth_mode", "auth_mode"), ("status", "status")]:
        if getattr(a, flag) is not None:
            cfg[key] = getattr(a, flag)
    for flag in ("quirks", "series_episodes_array", "epg_plain", "epg_gzip", "strict_timeshift_tz"):
        if getattr(a, flag):
            cfg[flag] = True
    if a.no_pace:
        cfg["pace"] = False
    for kv in a.set:
        k, v = parse_kv(kv)
        cfg[k] = v
    for kv in a.fault:
        k, v = parse_kv(kv)
        cfg["faults"][k] = v
    for kv in a.stream_fault:
        sid, _, rest = kv.partition(":")
        k, v = parse_kv(rest)
        cfg["stream_faults"].setdefault(sid, {})[k] = v

    panel = Panel(cfg, a.media_dir, not a.no_ffmpeg, a.verbose)
    main_srv, lb = start_servers(panel, a.host, a.port, a.redirect_port)
    panel.log(f"fakepanel up: http://{a.host}:{panel.main_port}  (token/redirect target port {panel.lb_port})  "
              f"user={cfg['username']} pass={cfg['password']} tz={cfg['timezone']} quirks={cfg['quirks']}")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        stop_servers(panel, main_srv, lb)


if __name__ == "__main__":
    main()
