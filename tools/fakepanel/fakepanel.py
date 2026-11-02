#!/usr/bin/env python3
"""fakepanel - fake Xtream Codes panel + stream server for The Box (dev/test tool, stdlib only).

    python3 tools/fakepanel/fakepanel.py --port 8080

See README.md. The live TS endpoint loops a 10 s clip and rewrites PTS/DTS/PCR base and continuity
counters on every loop so the stream is continuous (see TsPlan / TsState and the reconnect_mode fault).
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
import select
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.parse
import zlib
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog as cat  # noqa: E402
import ed25519  # noqa: E402

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
    "media_override": {},       # {"<stream id>": "std|mp2|50fps|radio|hdr"}: serve another clip for that live stream
    "synthetic": 0, "synthetic_days_back": 3, "synthetic_days_ahead": 7,  # --synthetic N: N generated live channels
    "epg_version": 0,           # bump (via /admin/config) to change synthetic programme titles (" v2", ...)
    "epg_status": 0,            # >= 400: xmltv.php answers this status with a short body (owner finding 2026-11-01)
    # M4 (docs/design/m4-player-ux.md §5.6): archive behaviour for the player suite
    "timeshift_mode": "clip3",  # clip3 = clip x 3 (M2/M3); duration = body sized to the requested duration, continuous PCR/PTS
    "timeshift_window": True,   # 404 for a start outside the channel's archive (older than archive_days, in the future, no archive)
    "archive_lag_sec": 0,       # 404 for a start within N s of now ("not written yet")
    # (code review 2026-10-16, U-S9) duration mode only: "clamp" serves at most what the panel has written
    # (now - archive_lag_sec - start), as a real Xtream panel; "serve" sends the whole requested duration at once
    "timeshift_future": "clamp",
    "timeshift_chunked": False,  # duration mode: no Content-Length / Accept-Ranges (close-delimited), as many panels
    "timeshift_reject_forms": {},  # {form: status}: path, path_sec, php, shift, flussonic_range, flussonic_now, timeshift_abs, timeshift_rel
    "timeshift_range_ignored": False,  # 200 + full Content-Length, Range ignored (bytesToSkip path)
    "player_fixtures": False,   # synthetic mode: short-programme ch2, correction ch3 (+2) / ch4 (-1.5), multi ch7 (TS) / ch8 (HLS)
    # M5 (docs/design/m5-setup-settings.md §6.4)
    "adult_category": False,    # catalog: category 5 "Adult 18+" + channel 14 (is_adult 1); synthetic: the last group
    "sleep_login": 0,           # seconds player_api.php login (no action) waits before answering (the busy state)
    "api_fault": None,          # player_api.php: html_splash | timeout | 429 | redirect (S16)
    "dns_records": {},          # /dns-query (RFC 8484) A records, e.g. {"panel.test": "10.0.2.2"}
    # The update endpoints (/update/update.json, .sig, /update/<apk>.apk), signed with the debug update key.
    # update_mode: valid | bad_signature | wrong_hash | wrong_cert (the manifest lists another certificate digest)
    "update_mode": "valid",
    "update_manifest": {},      # overrides of the generated manifest (versionCode, versionName, packageName, sequence, ...)
    "update_apk": "app",        # which uploaded APK (POST /admin/update_apk?name=...) the manifest offers
}
FAULT_DOC = {
    "redirect": False, "token_ttl": None, "linger": 0, "linger_status": 403, "close_every": 0,
    "reconnect_mode": "continue", "stall": 0, "stall_after": None, "slow": 0, "ua_whitelist": [], "wrong_content_type": False,
    "html_splash": False, "405_on_m3u8": False, "range_rejects": False,
    # M4 §5.6: timeshift-only faults
    "timeshift_range_rejects": False,  # true (458) or a status: reject timeshift requests with a Range other than bytes=0-
    "timeshift_redirect": False,       # 302 to the LB port with a token, timeshift requests only (token_ttl applies)
    "token_kill": False,               # the LB closes an open body when its token expires (mid-play expiry)
    "timeshift_reject_forms": {},      # per stream: as the config key, overrides it
    # M6 time-shift buffer (docs/design/m6-timeshift.md §17.5): live-TS shaping, all off by default (then the
    # output is byte-identical to the unshaped generator). Times are media time: clip bytes sent / clip rate.
    "strip_rai": False,                # clear the adaptation-field random-access indicator on every packet
    "pts_offset": None,                # 90 kHz: the clip's lowest PTS starts here (PCR keeps its lead); wraps mod 2^33
    "inline_jump_after": None,         # [s, ms]: s s into one connection step PTS then PCR by ms, no reconnect
    "psi_every_s": None,               # PAT/PMT only at connection start and every N s (the clip's own PSI is dropped)
    "pmt_change_after": None,          # s: PMT version +1 and the audio moves to a new PID (new PMT first)
    "pad_bps": None,                   # null packets (PID 0x1FFF) added at this many bytes/s on top of the content
}
# Timeshift URL forms the panel can tell apart (the request log's `form`, the keys of timeshift_reject_forms).
API_FAULTS = (None, "html_splash", "timeout", "429", "redirect")
UPDATE_MODES = ("valid", "bad_signature", "wrong_hash", "wrong_cert")
UPDATE_KEY_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "update_test_key")
TS_FORMS = ("path", "path_sec", "php", "shift", "flussonic_range", "flussonic_now", "timeshift_abs", "timeshift_rel")
TS_FORM_ALIASES = {"xc-path": "path", "xc-path-sec": "path_sec", "xc-php": "php", "flussonic-range": "flussonic_range",
                   "flussonic-abs": "timeshift_abs", "flussonic-kodi": "timeshift_rel"}
TS_LOG_MAX = 50
CONN_LOG_MAX = 500
TS_FAULT_KEYS = ("strip_rai", "pts_offset", "inline_jump_after", "psi_every_s", "pmt_change_after", "pad_bps")
CLAMP_SNAPSHOT_S = 5  # U-S9: the written length is re-read at most every 5 s (a panel writing its archive in chunks)
EVENT_MAX_SEGMENTS = 5400  # an EVENT playlist lists at most 3 h of 2 s segments
SPLASH = b"<html><head><title>Subscription</title></head><body><h1>Your subscription has expired</h1></body></html>"


# ------------------------------------------------------------------ media

MOD33 = 1 << 33
JUMP_90K = 5 * 90000  # reconnect_mode=jump: forward timestamp jump
NULL_PKT = b"\x47\x1f\xff\x10" + b"\xff" * 184
AUDIO_STREAM_TYPES = {0x03, 0x04, 0x0F, 0x11, 0x81, 0x87, 0x06, 0x82, 0x83, 0x84, 0x85, 0x86}


def crc32_mpeg(data):
    """CRC-32/MPEG-2 (poly 0x04C11DB7, init 0xFFFFFFFF, no reflection, no final xor) of a PSI section."""
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc


def psi_section(table_id, id_field, version, body):
    """A long-form PSI section (section_syntax_indicator 1, one section, current_next 1) with its CRC."""
    n = 5 + len(body) + 4
    sec = bytes([table_id, 0xB0 | (n >> 8), n & 0xFF, id_field >> 8, id_field & 0xFF, 0xC1 | ((version & 31) << 1), 0, 0]) + body
    return sec + crc32_mpeg(sec).to_bytes(4, "big")


def psi_packet(pid, cc, section):
    assert len(section) <= 183
    return bytes([0x47, 0x40 | (pid >> 8), pid & 0xFF, 0x10 | (cc & 15), 0]) + section + b"\xff" * (183 - len(section))


class Psi:
    """The clip's PAT and PMT (single-packet sections), enough to regenerate them with another version / audio PID."""

    def __init__(self, data):
        self.ok = False
        n = len(data) // PKT
        pat = pmt = None
        pmt_pid = None
        for i in range(n):
            o = i * PKT
            if data[o] != 0x47 or not data[o + 1] & 0x40:
                continue
            pid = ((data[o + 1] & 0x1F) << 8) | data[o + 2]
            afc = (data[o + 3] >> 4) & 3
            if not afc & 1:
                continue
            p = o + 4 + (1 + data[o + 4] if afc & 2 else 0)
            sec = data[p + 1 + data[p]:o + PKT]
            if pid == 0 and pat is None and sec[:1] == b"\x00":
                pat = sec
                for k in range(8, 3 + (((sec[1] & 15) << 8) | sec[2]) - 4, 4):
                    if (sec[k] << 8 | sec[k + 1]) != 0:
                        self.program, pmt_pid = sec[k] << 8 | sec[k + 1], ((sec[k + 2] & 0x1F) << 8) | sec[k + 3]
                        break
                self.ts_id = sec[3] << 8 | sec[4]
            elif pmt_pid is not None and pid == pmt_pid and pmt is None and sec[:1] == b"\x02":
                pmt = sec
            if pat is not None and pmt is not None:
                break
        if pat is None or pmt is None or pmt_pid is None:
            return
        self.pmt_pid, self.version = pmt_pid, (pmt[5] >> 1) & 31
        self.pcr_pid = ((pmt[8] & 0x1F) << 8) | pmt[9]
        pil = ((pmt[10] & 15) << 8) | pmt[11]
        self.prog_desc = bytes(pmt[12:12 + pil])
        end, k, self.streams = 3 + (((pmt[1] & 15) << 8) | pmt[2]) - 4, 12 + pil, []
        while k + 5 <= end:
            el = ((pmt[k + 3] & 15) << 8) | pmt[k + 4]
            self.streams.append((pmt[k], ((pmt[k + 1] & 0x1F) << 8) | pmt[k + 2], bytes(pmt[k + 5:k + 5 + el])))
            k += 5 + el
        self.audio_pid = next((pid for t, pid, _ in self.streams if t in AUDIO_STREAM_TYPES), None)
        self.ok = True

    def pat(self):
        return psi_section(0, self.ts_id, 0, bytes([self.program >> 8, self.program & 0xFF, 0xE0 | (self.pmt_pid >> 8), self.pmt_pid & 0xFF]))

    def pmt(self, version, pidmap=None):
        pidmap = pidmap or {}
        body = bytes([0xE0 | (self.pcr_pid >> 8), self.pcr_pid & 0xFF, 0xF0 | (len(self.prog_desc) >> 8), len(self.prog_desc) & 0xFF]) + self.prog_desc
        for t, pid, desc in self.streams:
            pid = pidmap.get(pid, pid)
            body += bytes([t, 0xE0 | (pid >> 8), pid & 0xFF, 0xF0 | (len(desc) >> 8), len(desc) & 0xFF]) + desc
        return psi_section(2, self.program, version, body)


def _get_ts(b, i):
    """Decode a 33-bit PES PTS/DTS spread over 5 bytes with marker bits."""
    return (((b[i] >> 1) & 7) << 30) | (b[i + 1] << 22) | ((b[i + 2] >> 1) << 15) | (b[i + 3] << 7) | (b[i + 4] >> 1)


def _put_ts(b, i, v):
    b[i] = (b[i] & 0xF0) | (((v >> 30) & 7) << 1) | 1
    b[i + 1] = (v >> 22) & 0xFF
    b[i + 2] = (((v >> 15) & 0x7F) << 1) | 1
    b[i + 3] = (v >> 7) & 0xFF
    b[i + 4] = ((v & 0x7F) << 1) | 1


def _get_pcr_base(b, i):
    return (b[i] << 25) | (b[i + 1] << 17) | (b[i + 2] << 9) | (b[i + 3] << 1) | (b[i + 4] >> 7)


def _put_pcr_base(b, i, v):
    b[i] = (v >> 25) & 0xFF
    b[i + 1] = (v >> 17) & 0xFF
    b[i + 2] = (v >> 9) & 0xFF
    b[i + 3] = (v >> 1) & 0xFF
    b[i + 4] = ((v & 1) << 7) | (b[i + 4] & 0x7F)  # keep reserved bits and the 9-bit 27 MHz extension


# stream ids without a PES optional header
_NO_PES_HEADER = {0xBC, 0xBE, 0xBF, 0xF0, 0xF1, 0xF2, 0xF8, 0xFF}


class TsPlan:
    """Per-packet rewrite plan for a 188-byte aligned MPEG-TS clip (any TS, not only ffmpeg output).

    pkts[i] is None (null packet, nothing to do, also not CC-rewritten) or
    (pid, has_payload, pcr_pos, pts_pos, dts_pos) with positions relative to the packet start (-1 = absent).
    dur90k is the loop length in 90 kHz ticks: (last - first PTS of the lowest-frame-rate PID) + one frame interval,
    or from PCRs when there are no PTS. active is False when there is nothing to rewrite.
    """

    def __init__(self, data):
        self.pkts, self.dur90k, self.active = [], 0, False
        n = len(data) // PKT
        if n == 0 or len(data) % PKT:
            return
        pkts, pts_by_pid, pcrs = [None] * n, {}, []
        for i in range(n):
            o = i * PKT
            if data[o] != 0x47:
                return  # not aligned: leave the clip alone
            b1, b2, b3 = data[o + 1], data[o + 2], data[o + 3]
            pid = ((b1 & 0x1F) << 8) | b2
            if pid == 0x1FFF:
                continue
            afc = (b3 >> 4) & 3
            payload = bool(afc & 1)
            p = o + 4
            pcr_pos = pts_pos = dts_pos = -1
            if afc & 2:
                af_len = data[p]
                if af_len >= 7 and data[p + 1] & 0x10 and p + 7 <= o + PKT:
                    pcr_pos = p + 2 - o
                    pcrs.append(_get_pcr_base(data, p + 2))
                p += 1 + af_len
            if payload and (b1 & 0x40) and p + 14 <= o + PKT and data[p] == 0 and data[p + 1] == 0 and data[p + 2] == 1 \
                    and data[p + 3] not in _NO_PES_HEADER and (data[p + 7] >> 6) in (2, 3):
                pts_pos = p + 9 - o
                pts_by_pid.setdefault(pid, []).append(_get_ts(data, p + 9))
                if (data[p + 7] >> 6) == 3 and p + 19 <= o + PKT:
                    dts_pos = p + 14 - o
            pkts[i] = (pid, payload, pcr_pos, pts_pos, dts_pos)
        self.pkts = pkts
        self.active = any(pkts)
        best = 0
        for vals in pts_by_pid.values():
            if max(vals) - min(vals) > MOD33 // 2:  # the clip itself straddles a 33-bit wrap: unwrap
                vals = [v + MOD33 if v < MOD33 // 2 else v for v in vals]
            u = sorted(set(vals))
            if len(u) < 2:
                continue
            diffs = sorted(b - a for a, b in zip(u, u[1:]))
            step = diffs[len(diffs) // 2]
            if step > best:  # lowest frame rate PID = video
                best, self.dur90k = step, u[-1] - u[0] + step
        if not self.dur90k and len(pcrs) >= 2:
            span = pcrs[-1] - pcrs[0]
            self.dur90k = span + span // (len(pcrs) - 1)
        allpts = [v for vals in pts_by_pid.values() for v in vals]
        self.first_pts = min(allpts) if allpts else 0  # HLS WebVTT X-TIMESTAMP-MAP anchor (multi clip)
        self._cc = None

    def cc_table(self, data):
        """Deterministic CCs for random access into the looped clip (timeshift `duration` mode, M4 §5.6 item 1).

        Returns (first, before, per_loop): `first[pid]` = the counter the live rewriter gives that PID's first packet,
        `before[i]` = payload packets of packet i's PID earlier in the clip, `per_loop[pid]` = payload packets per loop.
        A payload packet in loop L then carries (first + L*per_loop + before) & 15, an AF-only packet one less: exactly
        the sequence TsState.emit produces when it streams the loops back to back from loop 0."""
        if self._cc is None:
            first, before, count = {}, [0] * len(self.pkts), {}
            for i, ent in enumerate(self.pkts):
                if ent is None:
                    continue
                pid, payload = ent[0], ent[1]
                if pid not in first:
                    c = data[i * PKT + 3] & 15
                    first[pid] = c if payload else (c + 1) & 15
                before[i] = count.get(pid, 0)
                if payload:
                    count[pid] = before[i] + 1
            self._cc = (first, before, count)
        return self._cc

    def emit_loop(self, data, loop, a, e):
        """data[a:e] (packet aligned) as it appears in loop [loop] of an endless looped stream: timestamps + loop*dur90k
        (mod 2^33) and continuity counters continuing across loops. Random access: no state carried between calls."""
        if not self.active:
            return data[a:e]
        first, before, per_loop = self.cc_table(data)
        off = (loop * self.dur90k) % MOD33
        buf = bytearray(data[a:e])
        pk = self.pkts
        for k, i in enumerate(range(a // PKT, e // PKT)):
            ent = pk[i]
            if ent is None:
                continue
            pid, payload, pcr, pts, dts = ent
            b = k * PKT
            cc = first[pid] + loop * per_loop.get(pid, 0) + before[i] - (0 if payload else 1)
            buf[b + 3] = (buf[b + 3] & 0xF0) | (cc & 15)
            if off:
                if pcr >= 0:
                    _put_pcr_base(buf, b + pcr, (_get_pcr_base(buf, b + pcr) + off) % MOD33)
                if pts >= 0:
                    _put_ts(buf, b + pts, (_get_ts(buf, b + pts) + off) % MOD33)
                    if dts >= 0:
                        _put_ts(buf, b + dts, (_get_ts(buf, b + dts) + off) % MOD33)
        return buf


class LoopedTs:
    """A virtual finite TS body of [size] bytes: the clip looped with continuous PCR/PTS/CC (timeshift `duration` mode).

    Never materialised: slice(a, e) renders only the requested bytes, so a 3-hour archive file costs nothing until read
    and a ranged reopen (Range: bytes=N-) starts mid-file with the right timestamps."""

    def __init__(self, clip, size):
        self.clip, self.size = clip, size

    def __len__(self):
        return self.size

    def slice(self, a, e):
        data, n, plan = self.clip.data, len(self.clip.data), self.clip.plan
        out = bytearray()
        pos = a
        while pos < e:
            loop, off = divmod(pos, n)
            end_in = min(n, off + (e - pos))
            pa = off // PKT * PKT
            pe = min(n, -(-end_in // PKT) * PKT)
            chunk = plan.emit_loop(data, loop, pa, pe)
            out += chunk[off - pa:end_in - pa]
            pos += end_in - off
        return bytes(out)


class TsState:
    """Where a live connection is in the looped clip: byte position, timestamp offset (90 kHz) and per-PID CC.

    off is the loop/reconnect offset; shift is the constant pts_offset shift; pts_x / pcr_x are the inline-jump steps
    (the PCR one lags the PTS one: it starts at the first PCR packet after the first stepped PTS packet)."""

    def __init__(self, pos=0, off=0, cc=None):
        self.pos, self.off, self.cc = pos, off, dict(cc or {})
        self.shift = self.pts_x = self.pcr_x = self.pcr_pend = 0
        self.pcr_wait = self.pts_seen = False
        self.psi_cc = {}

    def inherit(self, last):
        self.shift, self.pts_x, self.pcr_x, self.pcr_pend = last.shift, last.pts_x, last.pcr_x, last.pcr_pend
        self.pcr_wait, self.pts_seen, self.psi_cc = last.pcr_wait, last.pts_seen, dict(last.psi_cc)
        return self

    def step(self, ticks):
        """Inline jump: PTS/DTS step now, PCR after the first stepped PTS packet (PTS before PCR in byte order)."""
        self.pts_x = (self.pts_x + ticks) % MOD33
        self.pcr_pend, self.pcr_wait, self.pts_seen = ticks % MOD33, True, False

    def emit(self, plan, data, pos, e):
        """Return data[pos:e] (packet aligned) with timestamps and CCs rewritten for this state."""
        if not plan.active:
            return data[pos:e]
        buf = bytearray(data[pos:e])
        pk, ccs = plan.pkts, self.cc
        base = (self.off + self.shift) % MOD33
        for k, i in enumerate(range(pos // PKT, e // PKT)):
            ent = pk[i]
            if ent is None:
                continue
            pid, payload, pcr, pts, dts = ent
            b = k * PKT
            nxt = ccs.get(pid)
            if nxt is None:  # first sight of this PID on this connection: keep the clip's own counter
                nxt = buf[b + 3] & 15
                if not payload:
                    nxt = (nxt + 1) & 15
            if payload:
                cc, ccs[pid] = nxt, (nxt + 1) & 15
            else:
                cc, ccs[pid] = (nxt - 1) & 15, nxt
            buf[b + 3] = (buf[b + 3] & 0xF0) | cc
            if pcr >= 0:
                if self.pcr_wait and self.pts_seen:
                    self.pcr_x, self.pcr_wait = (self.pcr_x + self.pcr_pend) % MOD33, False
                po = (base + self.pcr_x) % MOD33
                if po:
                    _put_pcr_base(buf, b + pcr, (_get_pcr_base(buf, b + pcr) + po) % MOD33)
            if pts >= 0:
                if self.pcr_wait:
                    self.pts_seen = True
                po = (base + self.pts_x) % MOD33
                if po:
                    _put_ts(buf, b + pts, (_get_ts(buf, b + pts) + po) % MOD33)
                    if dts >= 0:
                        _put_ts(buf, b + dts, (_get_ts(buf, b + dts) + po) % MOD33)
        return buf


class Shaper:
    """Per-connection live-TS shaping (M6 §17.5): strip_rai, psi_every_s, pmt_change_after, pad_bps.

    process() runs on each emitted chunk (a whole number of packets). media_t is the media time of the chunk start
    (clip bytes consumed / rate), so the cadence is the same paced or unpaced. With every key off, active is False and
    the generator's output is untouched."""

    def __init__(self, f, clip, rate):
        self.rai = bool(f.get("strip_rai"))
        self.psi_every = float(f.get("psi_every_s") or 0)
        pa = f.get("pmt_change_after")
        self.pmt_after = None if pa is None or pa is False else float(pa)
        self.pad_bps = float(f.get("pad_bps") or 0)
        self.rate = rate
        self.psi = clip.psi() if (self.psi_every or self.pmt_after is not None) else None
        if self.psi is not None and not self.psi.ok:
            self.psi = None  # the clip has no PAT/PMT (null fallback): nothing to repeat or change
        self.version = self.psi.version if self.psi else 0
        self.pidmap, self.changed, self.next_psi, self.pad_acc = {}, False, 0.0, 0.0
        self.active = bool(self.rai or self.psi or self.pad_bps)
        self.new_audio = None
        if self.psi and self.psi.audio_pid is not None:
            used = {p for _, p, _ in self.psi.streams} | {self.psi.pmt_pid, 0}
            cand = self.psi.audio_pid + 0x10
            while cand in used:
                cand += 1
            self.new_audio = cand

    def _emit_psi(self, out, st, with_pat=True):
        psi = self.psi
        for pid, sec in ((0, psi.pat() if with_pat else None), (psi.pmt_pid, psi.pmt(self.version, self.pidmap))):
            if sec is None:
                continue
            cc = st.psi_cc.get(pid, 0)
            st.psi_cc[pid] = (cc + 1) & 15
            out += psi_packet(pid, cc, sec)

    def process(self, buf, media_t, st, content_bytes):
        out = bytearray()
        psi = self.psi
        if psi:
            if self.pmt_after is not None and not self.changed and media_t >= self.pmt_after and self.new_audio is not None:
                self.changed = True
                self.version = (self.version + 1) & 31
                self.pidmap = {psi.audio_pid: self.new_audio}
                self._emit_psi(out, st)  # the new PMT precedes every packet of the new PID
            due = bool(self.psi_every) and media_t >= self.next_psi
            if due and not out:
                self._emit_psi(out, st)
            if due:
                while self.next_psi <= media_t:
                    self.next_psi += self.psi_every
        drop_psi = bool(psi and self.psi_every)
        rewrite = bool(psi and self.pidmap)
        for o in range(0, len(buf), PKT):
            pid = ((buf[o + 1] & 0x1F) << 8) | buf[o + 2]
            if psi and (pid == 0 or pid == psi.pmt_pid) and buf[o + 1] & 0x40:
                if drop_psi:
                    continue
                if pid == psi.pmt_pid:  # keep the clip's cadence, but with the current version / PID map
                    out += psi_packet(pid, buf[o + 3] & 15, psi.pmt(self.version, self.pidmap))
                    continue
            pkt = bytearray(buf[o:o + PKT])
            if rewrite and pid in self.pidmap:
                np_ = self.pidmap[pid]
                pkt[1], pkt[2] = (pkt[1] & 0xE0) | (np_ >> 8), np_ & 0xFF
            if self.rai and (pkt[3] >> 4) & 2 and pkt[4] >= 1:
                pkt[5] &= ~0x40 & 0xFF
            out += pkt
        if self.pad_bps and self.rate:
            self.pad_acc += content_bytes * self.pad_bps / self.rate
            n = int(self.pad_acc // PKT)
            self.pad_acc -= n * PKT
            out += NULL_PKT * n
        return out


class Clip:
    def __init__(self, data, duration, segments):
        self.data, self.duration, self.segments = data, duration, segments
        self.rate = len(data) / duration  # bytes/sec
        self.plan = TsPlan(data)
        self._seg_plans = {}
        self._psi = None

    def psi(self):
        if self._psi is None:
            self._psi = Psi(self.data)
        return self._psi

    def seg_plan(self, i):
        """TsPlan of HLS segment i (continuous-timestamp segments, /tseg/ and the multi live HLS)."""
        p = self._seg_plans.get(i)
        if p is None:
            p = self._seg_plans[i] = TsPlan(self.segments[i][0])
        return p

    def loop_segment(self, n):
        """Absolute segment n of the endlessly looped clip: segment n % count with timestamps + (n // count) loops."""
        nseg = len(self.segments)
        loop, i = divmod(n, nseg)
        data = self.segments[i][0]
        plan = self.seg_plan(i)
        if not plan.active or not self.plan.dur90k:
            return data
        off = (loop * self.plan.dur90k) % MOD33
        if not off:
            return data
        buf = bytearray(data)
        for k, ent in enumerate(plan.pkts):
            if ent is None:
                continue
            _, _, pcr, pts, dts = ent
            b = k * PKT
            if pcr >= 0:
                _put_pcr_base(buf, b + pcr, (_get_pcr_base(buf, b + pcr) + off) % MOD33)
            if pts >= 0:
                _put_ts(buf, b + pts, (_get_ts(buf, b + pts) + off) % MOD33)
                if dts >= 0:
                    _put_ts(buf, b + dts, (_get_ts(buf, b + dts) + off) % MOD33)
        return bytes(buf)


_PNG_CACHE = {}


def tiny_png(key, w=96, h=54):
    """A valid 8-bit RGB PNG (w x h) in one colour derived from [key]: a hue wheel step per id, full saturation."""
    if key in _PNG_CACHE:
        return _PNG_CACHE[key]
    hue = (key * 47) % 360
    c, x = 200, int(200 * (1 - abs((hue / 60) % 2 - 1)))
    r, g, b = [(c, x, 0), (x, c, 0), (0, c, x), (0, x, c), (x, 0, c), (c, 0, x)][hue // 60]
    raw = b"".join(b"\x00" + bytes((r + 40, g + 40, b + 40)) * w for _ in range(h))

    def chunk(kind, data):
        body = kind + data
        return len(data).to_bytes(4, "big") + body + (zlib.crc32(body) & 0xFFFFFFFF).to_bytes(4, "big")

    ihdr = w.to_bytes(4, "big") + h.to_bytes(4, "big") + bytes((8, 2, 0, 0, 0))
    png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    if len(_PNG_CACHE) < 25000:
        _PNG_CACHE[key] = png
    return png


def _read(path):
    with open(path, "rb") as fh:
        return fh.read()


def _run(cmd):
    return subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=180)


# ---- CEA-608 captions for the `multi` clip (M4 §5.6 item 10). ffmpeg 4.x/6.x has no CEA-608 encoder, so the captions
# are written here as ATSC A/53 cc_data in H.264 SEI (user_data_registered_itu_t_t35, payload type 4), one SEI per
# access unit, before the first slice NAL. Field 1, channel 1 (CC1), roll-up 2 rows.

CC_PAD = (0x80, 0x80)
CC_EVERY = 50  # frames between caption lines (2 s at 25 fps): 5 lines per 10 s loop


def _odd(b):
    b &= 0x7F
    return b | (0x80 if bin(b).count("1") % 2 == 0 else 0)


def cea608_pairs(nframes):
    """One (b1, b2) byte pair per frame (odd parity), CC1 roll-up: RU2, CR, then 'CC1 line N' every CC_EVERY frames.
    Control codes are sent twice in consecutive frames (608 redundancy; decoders drop the repeat)."""
    pairs = [CC_PAD] * nframes
    for line, f0 in enumerate(range(0, nframes, CC_EVERY)):
        seq = [(0x14, 0x25), (0x14, 0x25), (0x14, 0x2D), (0x14, 0x2D)]  # RU2 x2, CR x2
        text = f"CC1 line {line + 1}"
        if len(text) % 2:
            text += " "
        seq += [(ord(text[i]), ord(text[i + 1])) for i in range(0, len(text), 2)]
        for k, (a, b) in enumerate(seq):
            if f0 + k < nframes:
                pairs[f0 + k] = (_odd(a), _odd(b))
    return pairs


def _epb(rbsp):
    """Insert H.264 emulation-prevention bytes (00 00 0x -> 00 00 03 0x for x <= 3)."""
    out, zeros = bytearray(), 0
    for b in rbsp:
        if zeros >= 2 and b <= 3:
            out.append(3)
            zeros = 0
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return bytes(out)


def cc_sei_nal(b1, b2):
    cc = bytes([0x40 | 1, 0xFF, 0xFC, b1, b2, 0xFF])  # process_cc_data_flag | cc_count=1, em_data, field-1 triplet, marker
    payload = bytes([0xB5, 0x00, 0x31]) + b"GA94" + bytes([0x03]) + cc
    return b"\x00\x00\x00\x01\x06" + _epb(bytes([4, len(payload)]) + payload + b"\x80")


def split_annexb(data):
    """NAL unit payloads (header byte first) of an Annex B H.264 stream."""
    starts, i = [], 0
    while True:
        j = data.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        starts.append(j + 3)
        i = j + 3
    nals = []
    for k, s in enumerate(starts):
        e = starts[k + 1] - 3 if k + 1 < len(starts) else len(data)
        nals.append(data[s:e].rstrip(b"\x00") if k + 1 < len(starts) else data[s:e])
    return nals


def inject_cea608(h264):
    """Add one cc_data SEI per access unit (AUD-delimited) before its first slice; returns (stream, frames)."""
    nals = split_annexb(h264)
    nframes = sum(1 for n in nals if n and n[0] & 0x1F == 9)
    pairs = cea608_pairs(nframes)
    out, frame, pending = bytearray(), -1, False
    for n in nals:
        if not n:
            continue
        t = n[0] & 0x1F
        if t == 9:
            frame += 1
            pending = True
        if t in (1, 5) and pending and 0 <= frame < len(pairs):
            out += cc_sei_nal(*pairs[frame])
            pending = False
        out += b"\x00\x00\x00\x01" + n
    return bytes(out), nframes


def build_multi(ff, media_dir, path, log):
    """`multi` clip: H.264 25 fps (no B-frames, AUDs, CEA-608 CC1 in SEI) + AAC eng 440 Hz + MP2 deu 880 Hz, 10 s."""
    raw = os.path.join(media_dir, "multi_video.h264")
    cc = os.path.join(media_dir, "multi_video_cc.h264")
    r = _run([ff, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25", "-t", "10",
              "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-g", "50", "-keyint_min", "50",
              "-sc_threshold", "0", "-bf", "0", "-b:v", "2M", "-maxrate", "2M", "-bufsize", "2M",
              "-x264-params", "aud=1", "-f", "h264", raw])
    if r.returncode:
        return r
    data, frames = inject_cea608(_read(raw))
    with open(cc, "wb") as fh:
        fh.write(data)
    log(f"media: multi: CEA-608 SEI injected into {frames} frames")
    r = _run([ff, "-y", "-v", "error", "-fflags", "+genpts", "-framerate", "25", "-f", "h264", "-i", cc,
              "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
              "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000", "-t", "10",
              "-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "copy",
              "-c:a:0", "aac", "-b:a:0", "128k", "-c:a:1", "mp2", "-b:a:1", "128k",
              "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=deu", "-f", "mpegts", path])
    for f in (raw, cc):
        try:
            os.remove(f)
        except OSError:
            pass
    return r


def load_srt(path):
    """[(start_s, end_s, text)] from a SubRip file (the multi clip's WebVTT rendition)."""
    cues = []
    try:
        with open(path, encoding="utf-8") as fh:
            blocks = fh.read().replace("\r\n", "\n").strip().split("\n\n")
    except OSError:
        return cues

    def secs(t):
        h, m, rest = t.strip().split(":")
        s, ms = rest.split(",")
        return int(h) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000

    for b in blocks:
        lines = b.strip().split("\n")
        if len(lines) >= 3 and "-->" in lines[1]:
            a, z = lines[1].split("-->")
            cues.append((secs(a), secs(z), "\n".join(lines[2:])))
    return cues


def vtt_time(t):
    ms = int(round(t * 1000))
    return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d}.{ms % 1000:03d}"


def build_media(media_dir, use_ffmpeg, log):
    """Return ({variant: Clip}, vod_bytes, vod_ctype)."""
    fb = os.path.join(HERE, "fallback_null.ts")
    fb_data = _read(fb) if os.path.exists(fb) else (b"\x47\x1f\xff\x10" + b"\xff" * 184) * 500
    n = len(fb_data) // PKT // 5 * PKT
    fb_clip = Clip(fb_data, 2.0, [(fb_data[i * n:(i + 1) * n], 0.4) for i in range(5)])
    ff = shutil.which("ffmpeg") if use_ffmpeg else None
    if not ff:
        log("media: ffmpeg not used; serving null-packet fallback TS (no decodable video)")
        return {k: fb_clip for k in ("std", "mp2", "50fps", "radio", "hdr", "multi", "ac3")}, fb_data * 3, "video/mp2t"
    os.makedirs(os.path.join(media_dir, "hls"), exist_ok=True)
    spec = {"std": ("h264_aac_25", 25, "aac"), "mp2": ("h264_mp2_25", 25, "mp2"), "50fps": ("h264_aac_50", 50, "aac"),
            "radio": ("aac_only_25", 0, "aac"),  # fps 0 = audio only (no video PID)
            # Q-1 HDR poster: 8-bit H.264 whose SPS VUI signals BT.2020 + PQ, so the player sees HDR10 (§3.9.5).
            "hdr": ("h264_aac_25_hdr10", 25, "aac"),
            # M4 §5.6 item 10: two audio languages + CEA-608 captions (pickers, subtitles)
            "multi": ("h264_multi_25", 25, "multi"),
            # M5 I-M5-27 (B2): AC-3 audio for "Always passthrough" on a PCM-only sink (media_override {"<id>": "ac3"})
            "ac3": ("h264_ac3_25", 25, "ac3")}
    clips = {}
    for key, (name, fps, ac) in spec.items():
        path = os.path.join(media_dir, name + ".ts")
        if not os.path.exists(path):
            log(f"media: generating {path}")
            if ac == "multi":
                r = build_multi(ff, media_dir, path, log)
                if r.returncode or not os.path.exists(path):
                    log("media: multi: ffmpeg failed: " + r.stderr.decode(errors="replace")[-300:])
                    clips[key] = fb_clip
                    continue
                cmd = None
            elif fps:
                cmd = [ff, "-y", "-v", "error", "-f", "lavfi", "-i", f"testsrc2=size=1280x720:rate={fps}",
                       "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "10",
                       "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-g", str(fps * 2),
                       "-keyint_min", str(fps * 2), "-sc_threshold", "0", "-b:v", "2M", "-maxrate", "2M", "-bufsize", "2M",
                       "-c:a", ac, "-b:a", "192k" if ac == "ac3" else "128k", "-f", "mpegts", path]
                if key == "hdr":
                    cmd[-3:-3] = ["-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc"]
            else:  # audio only: no video stream, no video PID in the PMT
                cmd = [ff, "-y", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "10",
                       "-vn", "-c:a", "aac", "-b:a", "128k", "-f", "mpegts", path]
            r = _run(cmd) if cmd else None
            if r is not None and r.returncode:
                log("media: ffmpeg failed: " + r.stderr.decode(errors="replace")[-300:])
                clips[key] = fb_clip
                continue
        segs_glob = os.path.join(media_dir, "hls", name + "_%03d.ts")
        if not os.path.exists(segs_glob % 0):
            _run([ff, "-y", "-v", "error", "-i", path, "-map", "0", "-c", "copy", "-f", "segment", "-segment_time", "2",
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
        self.last_seen = self.opened = time.monotonic()
        self.killed = False
        self.kill_reason = None
        self.occupier = None   # /admin/occupy: None | "hold" | "kick_oldest" (the latter is evictable by a client open)
        self.entry = None      # its connection-log row


class Registry:
    def __init__(self):
        self.lock = threading.Lock()
        self.slots = []
        self.max_open_seen = 0

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
            self.max_open_seen = max(self.max_open_seen, sum(1 for x in self.slots if not x.hls and x.closed_at is None))
            return s

    def occupy(self, user, sid, maxc, mode, hls_timeout):
        """Another device takes a slot of [user]. hold: only if one is free. kick_oldest: closes the oldest open
        connection first when the user is full. Returns (slot|None, kicked slot|None)."""
        now = time.monotonic()
        with self.lock:
            self._purge(now, hls_timeout)
            mine = [s for s in self.slots if s.user == user]
            kicked = None
            if len(mine) >= maxc:
                if mode != "kick_oldest":
                    return None, None
                live = sorted((s for s in mine if s.closed_at is None and not s.hls), key=lambda s: s.opened)
                victim = live[0] if live else min(mine, key=lambda s: s.opened)
                victim.killed, victim.kill_reason = True, "kicked_by_occupy"
                self.slots.remove(victim)  # the slot is free at once; its handler notices `killed` and stops
                kicked = victim
            slot = Slot(user, sid, 0)
            slot.occupier = mode
            self.slots.append(slot)
            return slot, kicked

    def evict_occupier(self, user):
        """A client open found [user] full: an occupier taken in kick_oldest mode loses its slot (ping-pong)."""
        with self.lock:
            cand = sorted((s for s in self.slots if s.user == user and s.occupier == "kick_oldest" and s.closed_at is None),
                          key=lambda s: s.opened)
            if not cand:
                return None
            cand[0].killed, cand[0].kill_reason = True, "kicked_by_client_open"
            self.slots.remove(cand[0])
            return cand[0]

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

def check_reject_forms(v):
    if not isinstance(v, dict):
        raise ValueError("timeshift_reject_forms must be an object {form: status}")
    for k, st in v.items():
        if TS_FORM_ALIASES.get(k, k) not in TS_FORMS:
            raise ValueError(f"unknown timeshift form {k!r} (one of {', '.join(TS_FORMS)})")
        if st is None:
            continue  # deletes the key (faults are deep-merged)
        if not (isinstance(st, int) and not isinstance(st, bool) and 100 <= st <= 599) and st not in ("200-html", "200-empty"):
            raise ValueError(f"timeshift_reject_forms[{k!r}]: status must be an HTTP code, '200-html' or '200-empty'")


def check_ts_faults(t):
    """Validate the M6 live-TS shaping faults (null deletes the key)."""
    def num(k, lo, hi=None, lo_open=False):
        v = t.get(k)
        if v is None:
            return
        if isinstance(v, bool) or not isinstance(v, (int, float)) or v < lo or (lo_open and v == lo) or (hi is not None and v >= hi):
            raise ValueError(f"{k} must be a number {'>' if lo_open else '>='} {lo}" + (f" and < {hi}" if hi else ""))
    if t.get("strip_rai") is not None and not isinstance(t["strip_rai"], bool):
        raise ValueError("strip_rai must be true or false")
    num("pts_offset", 0, MOD33)
    num("psi_every_s", 0, lo_open=True)
    num("pmt_change_after", 0)
    num("pad_bps", 0)
    ij = t.get("inline_jump_after")
    if ij is not None and not (isinstance(ij, (list, tuple)) and len(ij) == 2 and all(
            isinstance(x, (int, float)) and not isinstance(x, bool) for x in ij) and ij[0] >= 0):
        raise ValueError("inline_jump_after must be [seconds >= 0, milliseconds]")


def deep_merge_faults(dst, src):
    for k, v in src.items():
        if v is None:
            dst.pop(k, None)
        elif isinstance(v, dict) and isinstance(dst.get(k), dict):
            deep_merge_faults(dst[k], v)
        else:
            dst[k] = copy.deepcopy(v)


class Panel:
    def __init__(self, cfg, media_dir=None, use_ffmpeg=True, verbose=False, quiet=False, update_key=None):
        self.initial = copy.deepcopy(cfg)
        self.cfg = copy.deepcopy(cfg)
        self.lock = threading.Lock()
        self.registry = Registry()
        self.verbose, self.quiet = verbose, quiet
        self.stopping = threading.Event()
        self.tokens = {}
        self.live_pos = {}
        self.clamp_cache = {}  # U-S9: (form, stream, start, duration) -> (first open, written seconds)
        self.main_port = self.lb_port = 0
        self.start_time = time.time()  # anchors the synthetic EPG schedule for this server run
        self.event_anchor = {}  # timeshift_rel EVENT playlists: (sid, path) -> fixed start (unix s)
        self.subs = load_srt(os.path.join(HERE, "multi_subs.srt"))
        self.holds = []        # M5: /admin/hold_connection slots (I-M5-6)
        self.occupiers = []    # M6: /admin/occupy slots (another device on the account)
        self.update_apks = {}  # M5: name -> APK bytes (POST /admin/update_apk, or --update-apk-dir)
        self.update_seed = load_update_key(update_key)
        self.reset_stats()
        self.clips, self.vod_data, self.vod_ctype = build_media(
            media_dir or os.path.join(HERE, "media"), use_ffmpeg, self.log)

    def reset_stats(self):
        self.stats = {"dns_queries": 0, "requests": 0, "stream_opens": 0, "rejections": {}, "range_requests_seen": 0,
                      "range_requests": [], "auth_failures": 0, "redirects": 0, "token_expired": 0,
                      "forced_closes": 0, "client_eof": 0, "stalls": 0, "timeshift_tz_warnings": 0, "request_log": [], "stream_requests": 0,
                      "timeshift_requests": [], "timeshift_request_count": 0,
                      "connection_log": [], "connection_seq": 0}
        self.registry.max_open_seen = 0

    def conn_open(self, kind, user, sid, path, rejected=False, reason=None):
        """Append a row to the connection log (stats.connection_log, last CONN_LOG_MAX rows) and return it."""
        now = time.time()
        with self.lock:
            self.stats["connection_seq"] += 1
            e = {"id": self.stats["connection_seq"], "kind": kind, "user": user, "stream": str(sid), "path": path,
                 "open_unix": now, "close_unix": now if rejected else None, "bytes": 0, "close_reason": reason if rejected else None,
                 "rejected": rejected}
            self.stats["connection_log"] = (self.stats["connection_log"] + [e])[-CONN_LOG_MAX:]
        return e

    def conn_close(self, e, reason, nbytes=None):
        if e is None:
            return
        with self.lock:
            if e["close_unix"] is None:
                e["close_unix"], e["close_reason"] = time.time(), reason
                if nbytes is not None:
                    e["bytes"] = nbytes

    def syn(self):
        """The synthetic lineup helper for the current config, or None when synthetic mode is off."""
        if int(self.cfg.get("synthetic") or 0) > 0:
            return cat.Synthetic(self.cfg, self.start_time)
        return None

    def channel(self, i):
        """Live channel dict by id (catalog, or synthetic in synthetic mode), or None."""
        syn = self.syn()
        return syn.channel(i) if syn else cat.channel_by_id(self.cfg, i)

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

    def media(self, sid):
        """Clip key of live stream [sid]: the `media_override` entry, else the catalog's."""
        key = self.cfg.get("media_override", {}).get(str(sid))
        return key if key in self.clips else self.channel(sid)["media"]

    def faults(self, sid):
        f = dict(self.cfg.get("faults", {}))
        f.update(self.cfg.get("stream_faults", {}).get(str(sid), {}))
        return f

    def correction_h(self, sid):
        """The channel's catchup-correction in hours (player fixtures: ch3 +2, ch4 -1.5), 0 otherwise."""
        ch = self.channel(sid) if sid is not None else None
        return float((ch or {}).get("correction") or 0)

    def reject_status(self, sid, form):
        """timeshift_reject_forms for this channel and form (stream fault first, then the config key), or None."""
        for table in (self.faults(sid).get("timeshift_reject_forms") or {}, self.cfg.get("timeshift_reject_forms") or {}):
            for k, v in table.items():
                if TS_FORM_ALIASES.get(k, k) == form:
                    return v
        return None

    def release_occupiers(self, user=None):
        with self.lock:
            gone = [s for s in self.occupiers if user is None or s.user == user]
            self.occupiers = [s for s in self.occupiers if s not in gone]
        for s in gone:
            self.registry.close(s)
            self.conn_close(s.entry, "released")
        return len(gone)

    def update_config(self, patch):
        unknown = [k for k in patch if k not in DEFAULTS]
        if unknown:
            raise ValueError("unknown config keys: " + ", ".join(unknown))
        for k, hi in (("synthetic", 20000), ("synthetic_days_back", 400), ("synthetic_days_ahead", 400), ("epg_version", 10**6), ("epg_status", 599)):
            if k in patch and (isinstance(patch[k], bool) or not isinstance(patch[k], int) or not 0 <= patch[k] <= hi):
                raise ValueError(f"{k} must be an integer 0..{hi}")
        if "timeshift_mode" in patch and patch["timeshift_mode"] not in ("clip3", "duration"):
            raise ValueError("timeshift_mode must be clip3 or duration")
        if "timeshift_future" in patch and patch["timeshift_future"] not in ("clamp", "serve"):
            raise ValueError("timeshift_future must be clamp or serve")
        if "timeshift_chunked" in patch and not isinstance(patch["timeshift_chunked"], bool):
            raise ValueError("timeshift_chunked must be true or false")
        lag = patch.get("archive_lag_sec", 0)
        if isinstance(lag, bool) or not isinstance(lag, int) or lag < 0:
            raise ValueError("archive_lag_sec must be an integer >= 0")
        if "timeshift_reject_forms" in patch:
            check_reject_forms(patch["timeshift_reject_forms"])
        if "api_fault" in patch and patch["api_fault"] not in API_FAULTS:
            raise ValueError("api_fault must be null, html_splash, timeout, 429 or redirect")
        if "update_mode" in patch and patch["update_mode"] not in UPDATE_MODES:
            raise ValueError("update_mode must be one of " + ", ".join(UPDATE_MODES))
        sl = patch.get("sleep_login", 0)
        if isinstance(sl, bool) or not isinstance(sl, (int, float)) or sl < 0:
            raise ValueError("sleep_login must be a number >= 0")
        if "dns_records" in patch and not isinstance(patch["dns_records"], dict):
            raise ValueError("dns_records must be an object {name: ipv4}")
        for table in [patch.get("faults") or {}] + list((patch.get("stream_faults") or {}).values()):
            if isinstance(table, dict) and table.get("timeshift_reject_forms") is not None:
                check_reject_forms(table["timeshift_reject_forms"])
            if isinstance(table, dict):
                check_ts_faults(table)
        with self.lock:
            for k, v in patch.items():
                if k in ("faults", "stream_faults"):
                    deep_merge_faults(self.cfg[k], v)
                else:
                    self.cfg[k] = copy.deepcopy(v)

    def load_update_apks(self, directory):
        """U-S14 (M5 code review 2026-10-16): registers every `*.apk` in [directory] by name, so the updater suite selects
        an APK with the `update_apk` config key instead of uploading 36 MB through the emulator per case. The name is the
        file stem without a `thebox-update-` prefix (`thebox-update-v2.apk` -> `v2`). Returns the names loaded."""
        names = []
        for f in sorted(os.listdir(directory)):
            if not f.endswith(".apk"):
                continue
            name = f[:-4]
            if name.startswith("thebox-update-"):
                name = name[len("thebox-update-"):]
            if not re.match(r"^[A-Za-z0-9_.-]+$", name):
                continue
            with open(os.path.join(directory, f), "rb") as fh:
                data = fh.read()
            with self.lock:
                self.update_apks[name] = data
            names.append(name)
        self.log(f"update APKs from {directory}: {', '.join(names) or 'none'}")
        return names

    def reset(self):
        with self.lock:
            self.clamp_cache = {}
            self.cfg = copy.deepcopy(self.initial)
            self.tokens.clear()
            self.live_pos.clear()
        self.registry.kill_all()
        self.registry.slots = []
        self.holds = []
        self.occupiers = []
        self.reset_stats()

    # ---- M5: update manifest (docs/design/m5-setup-settings.md §3.12.1, §6.4)
    def update_public_hex(self):
        return ed25519.public_key(self.update_seed).hex()

    def update_manifest_bytes(self, base):
        """The exact bytes of update.json for the current config (signed as they are)."""
        c = self.cfg
        name = c.get("update_apk") or "app"
        apk = self.update_apks.get(name, b"")
        sha = hashlib_sha256(apk)
        if c.get("update_mode") == "wrong_hash":
            sha = ("0" if sha[0] != "0" else "1") + sha[1:]
        certs = list((c.get("update_manifest") or {}).get("signingCertSha256") or [])
        if c.get("update_mode") == "wrong_cert":
            certs = ["AB" * 32]
        m = {
            "schema": 1, "sequence": 1, "packageName": "app.thebox.debug", "channel": "stable",
            "versionCode": 2, "versionName": "0.0.2-test", "minSdk": 25, "publishedAt": "2026-10-16T12:00:00Z",
            "notes": "Fake panel test release\n- Signed with the debug update key",
            "apks": [{"abi": "universal", "url": f"{base}/update/{name}.apk", "sizeBytes": len(apk), "sha256": sha}],
            "signingCertSha256": certs or ["00" * 32], "withdrawn": [], "rolloutPercent": 100,
        }
        m.update({k: v for k, v in (c.get("update_manifest") or {}).items() if k != "signingCertSha256"})
        return json.dumps(m, separators=(",", ":")).encode()

    def update_signature(self, data):
        sig = bytearray(ed25519.sign(self.update_seed, data))
        if self.cfg.get("update_mode") == "bad_signature":
            sig[5] ^= 0x01
        return bytes(sig)

    def make_token(self, path, ttl):
        tok = secrets.token_hex(8)
        with self.lock:
            now = time.time()
            self.tokens = {k: v for k, v in self.tokens.items() if v[0] > now - 600}
            self.tokens[tok] = [now + ttl, path, 0]
        return tok

    def check_token(self, tok, path, count=True):
        with self.lock:
            t = self.tokens.get(tok)
            if not t or t[1] != path:
                return "invalid"
            if time.time() >= t[0]:
                return "expired"
            if self.cfg.get("token_single_use") and t[2] >= 1:
                return "expired"
            if count:
                t[2] += 1
            return "ok"


# ------------------------------------------------------------------ HTTP handler

TS_START_RE = re.compile(r"^(\d{4})-(\d{2})-(\d{2}):(\d{2})-(\d{2})(?:-(\d{2}))?$")
ALT_START_RE = re.compile(r"^(\d{4})(\d{2})(\d{2})-(\d{2})$")


def hashlib_sha256(b):
    import hashlib
    return hashlib.sha256(b).hexdigest().upper()


def load_update_key(path=None):
    """The debug update key's 32-byte seed: [path], else tools/fakepanel/update_test_key (gitignored), else a fresh
    per-run key (the suite then registers /update/pubkey through the debug link)."""
    for p in [path, UPDATE_KEY_FILE]:
        if p and os.path.isfile(p):
            with open(p) as fh:
                return bytes.fromhex(fh.read().strip())
    return ed25519.new_seed()


def dns_answer(query, records):
    """RFC 1035 answer for an RFC 8484 query: A records from [records]; known name without A data = NOERROR, no
    answer; unknown name = NXDOMAIN. Returns None for a malformed query."""
    if len(query) < 12:
        return None
    qid, flags, qd = query[0:2], int.from_bytes(query[2:4], "big"), int.from_bytes(query[4:6], "big")
    if qd != 1:
        return None
    i, labels = 12, []
    while i < len(query):
        n = query[i]
        i += 1
        if n == 0:
            break
        labels.append(query[i:i + n].decode("ascii", "replace"))
        i += n
    if i + 4 > len(query):
        return None
    qtype = int.from_bytes(query[i:i + 2], "big")
    question = query[12:i + 4]
    name = ".".join(labels).lower().rstrip(".")
    ip = {k.lower().rstrip("."): v for k, v in (records or {}).items()}.get(name)
    rd = flags & 0x0100
    answers = b""
    rcode = 0
    if ip is None:
        rcode = 3
    elif qtype == 1:
        answers = (b"\xc0\x0c" + (1).to_bytes(2, "big") + (1).to_bytes(2, "big") + (60).to_bytes(4, "big")
                   + (4).to_bytes(2, "big") + socket.inet_aton(ip))
    header = (qid + (0x8000 | 0x0080 | rd | rcode).to_bytes(2, "big") + (1).to_bytes(2, "big")
              + (1 if answers else 0).to_bytes(2, "big") + b"\x00\x00\x00\x00")
    return header + question + answers


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
        self._end, self._log_entry, self._ts_entry, self._token_exp = None, None, None, None
        self._conn = None
        self.panel.bump("requests")
        u = urllib.parse.urlsplit(self.path)
        self.path_only = urllib.parse.unquote(u.path)
        self.q = {k: v[-1] for k, v in urllib.parse.parse_qs(u.query, keep_blank_values=True).items()}
        try:
            self.dispatch()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, socket.timeout):
            self._notes.append("client-gone")
            self.mark_end("client_eof" if self._log_entry is not None else "error")
        except Exception:
            traceback.print_exc()
            self.mark_end("error")
            try:
                self.send(500, b"internal error", "text/plain")
            except Exception:
                pass
        finally:
            if self._log_entry is not None:
                with self.panel.lock:
                    self._log_entry["end_reason"] = self._end or "server_close"
                    if self._ts_entry is not None:
                        self._ts_entry["status"] = self._status
                        self._ts_entry["bytes"] = self._bytes
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
        self.set_status(status)
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

    def set_status(self, status):
        self._status = status
        if self._ts_entry is not None:
            with self.panel.lock:
                self._ts_entry["status"] = status

    def begin_stream(self, status, ctype, headers=None):
        self.set_status(status)
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-cache")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()

    def out(self, data):
        self.wfile.write(data)
        self._bytes += len(data)
        if self._conn is not None:
            self._conn["bytes"] = self._bytes

    def mark_end(self, reason):
        """Record why the stream ended (first reason wins); client_eof also bumps the counter."""
        if self._end is None:
            self._end = reason
            if reason == "client_eof":
                self._notes.append("CLIENT-EOF")
                self.panel.bump("client_eof")
                self.panel.log(f"CLIENT-EOF {self.path_only} (peer closed the socket)")

    def peer_closed(self, timeout=0.0):
        """Wait up to `timeout` s for the socket to become readable; True (and marked) if the peer closed it.

        Readable-with-data (a client that sent extra bytes) is not EOF; then the remaining time is slept so
        callers never busy-loop. A client that stays connected and sends nothing stays unreadable."""
        sock = self.connection
        try:
            r, _, x = select.select([sock], [], [sock], max(0.0, timeout))
            if r or x:
                try:
                    if sock.recv(1, socket.MSG_PEEK) == b"":
                        self.mark_end("client_eof")
                        return True
                except (BlockingIOError, InterruptedError):
                    pass
                except OSError:
                    self.mark_end("client_eof")
                    return True
                time.sleep(min(timeout, 0.02))  # data pending but not EOF
        except (OSError, ValueError):
            self.mark_end("client_eof")
            return True
        return False

    def nap(self, secs, slot=None):
        """Sleep in small steps; False if the server is stopping, the slot was kicked or the client closed."""
        end = time.monotonic() + secs
        while True:
            left = end - time.monotonic()
            if self.panel.stopping.is_set() or (slot and slot.killed):
                return False
            if left <= 0:
                return True
            if self.peer_closed(min(left, 0.05)):
                return False

    def reject(self, status, reason, body=b"", ctype="text/plain"):
        self.panel.bump("rejections", reason)
        self._notes.append("REJECT:" + reason)
        self.send(status, body or reason, ctype)

    # ---- routing
    def dispatch(self):
        p, path = self.panel, self.path_only
        if path.startswith("/admin"):
            return self.admin(path)
        # The redirect port also serves the archive playlists' absolute segment paths (/vodseg/, /tseg/).
        if self.server.is_lb and not re.match(r"^/(live|movie|series|timeshift|vodseg|tseg)/|^/streaming/timeshift\.php$", path):
            return self.send(404, b"lb: stream paths only", "text/plain")
        if path == "/player_api.php":
            return self.player_api()
        if path.startswith("/update/"):
            return self.update_endpoint(path)
        if path == "/dns-query":
            return self.dns_query()
        if path == "/get.php":
            return self.get_php()
        if path == "/xmltv.php":
            return self.xmltv_php()
        if path == "/streaming/timeshift.php":
            q = self.q
            return self.stream_request("timeshift", q.get("username"), q.get("password"),
                                       q.get("stream", ""), "ts", start=q.get("start", ""), dur=q.get("duration", "60"),
                                       form="php", dur_unit=60)
        m = re.match(r"^/vodseg/(\d+)_(\d+)\.ts$", path)
        if m and p.channel(int(m[1])):
            segs = p.clips[p.media(int(m[1]))].segments
            return self.send(200, segs[int(m[2]) % len(segs)][0], "video/mp2t")
        # M4: segment n of the endlessly looped clip with continuous timestamps (duration-mode VOD and EVENT archives)
        m = re.match(r"^/tseg/(\d+)_(\d+)\.ts$", path)
        if m and p.channel(int(m[1])):
            return self.send(200, p.clips[p.media(int(m[1]))].loop_segment(int(m[2])), "video/mp2t")
        # M4 (API notes §7 gap 7): Flussonic live-like archive forms, start = FROM (abs) or now - N (rel).
        m = re.match(r"^/live/([^/]+)/([^/]+)/(\d+)/(?:(?:index|video|mono)-)?timeshift_(abs|rel)-(\d+)\.(m3u8|ts)$", path)
        if m:
            self._notes.append("FLUSSONIC-" + m[4].upper())
            if m[4] == "abs":
                start = int(m[5])
            else:
                with p.lock:
                    start = p.event_anchor.setdefault((m[3], path), int(time.time()) - int(m[5]))
            return self.stream_request("timeshift", m[1], m[2], m[3], m[6], start="@" + str(start), dur=None,
                                       form="timeshift_" + m[4])
        if path.startswith("/logo/"):
            # Synthetic mode (M3 code review 2026-10-14, O-3): a tiny valid PNG per channel, one colour per id, so the
            # guide's logo display, LogoCache hits and the ambient hue path run as with a real panel. Otherwise the
            # historical 8-byte signature (decoders reject it; the M2 suite never draws logos).
            if p.syn() is not None:
                m = re.match(r"^/logo/(\d+)\.png$", path)
                if m:
                    return self.send(200, tiny_png(int(m[1])), "image/png")
            return self.send(200, b"\x89PNG\r\n\x1a\n", "image/png")
        # Flussonic-style archive under the live path (catalog: catchup="flussonic" on ch4):
        # /live/u/p/{id}/{archive|index|video|mono}-{FROM}-{DURATION|now}.{m3u8|ts}, FROM = unix UTC s.
        m = re.match(r"^/live/([^/]+)/([^/]+)/(\d+)/(?:archive|index|video|mono)-(\d+)-(\d+|now)\.(m3u8|ts)$", path)
        if m:
            self._notes.append("FLUSSONIC-ARCHIVE")
            return self.stream_request("timeshift", m[1], m[2], m[3], m[6], start="@" + m[4], dur=m[5],
                                       form="flussonic_now" if m[5] == "now" else "flussonic_range", dur_unit=1)
        m = re.match(r"^/(live|movie|series)/([^/]+)/([^/]+)/([^/]+?)(?:\.([A-Za-z0-9]+))?$", path)
        if m:
            utc = self.q.get("utc", "")
            if m[1] == "live" and utc.isdigit():  # catchup="shift": liveUrl?utc={utc}&lutc={lutc}
                self._notes.append("SHIFT-ARCHIVE")
                return self.stream_request("timeshift", m[2], m[3], m[4], m[5] or "ts", start="@" + utc, dur=None, form="shift")
            return self.stream_request(m[1], m[2], m[3], m[4], m[5] or "ts")
        m = re.match(r"^/timeshift/([^/]+)/([^/]+)/(\d+)/([^/]+)/(\d+)(?:\.([A-Za-z0-9]+))?$", path)
        if m:
            sm = TS_START_RE.match(m[4])
            return self.stream_request("timeshift", m[1], m[2], m[5], m[6] or "ts", start=m[4], dur=m[3],
                                       form="path_sec" if sm and sm[6] else "path", dur_unit=60)
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
            # M5: /admin/update_apk takes the raw APK bytes, not JSON
            data = {} if path == "/admin/update_apk" else (json.loads(body) if body.strip() else {})
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
            with p.lock:
                s = copy.deepcopy(p.stats)
            s["connections"] = p.registry.counts(p.cfg["username"], p.cfg["hls_timeout"])
            s["connections"]["max_open_seen"] = p.registry.max_open_seen
            syn = p.syn()
            if syn:
                s["synthetic"] = syn.info()
            return self.json(s)
        if path == "/admin/now":
            now = time.time()
            return self.json({"unix": now, "panel_wall": dt.datetime.fromtimestamp(now, p.tz()).strftime("%Y-%m-%d %H:%M:%S"),
                              "timezone": p.cfg["timezone"]})
        if path == "/admin/timeshift_expect" and self.command in ("POST", "PUT"):
            return self.admin_expect(data)
        if path == "/admin/reset" and self.command != "GET":
            p.reset()
            p.log("ADMIN reset")
            return self.json(p.cfg)
        if path == "/admin/kick" and self.command != "GET":
            p.registry.kill_all()
            return self.json({"kicked": True})
        if path == "/admin/hold_connection":
            # M5 I-M5-6: hold one connection slot of the panel user (as an open stream would) until /admin/release_holds
            c = p.cfg
            sid = self.q.get("stream", "0")
            slot = p.registry.acquire(c["username"], sid, c["max_connections"], 0, c["hls_timeout"])
            if slot is None:
                return self.json({"held": False, "reason": "no free slot"}, 409)
            with p.lock:
                p.holds.append(slot)
            return self.json({"held": True, "holds": len(p.holds)})
        if path == "/admin/occupy":
            return self.admin_occupy()
        if path == "/admin/release_occupy" and self.command != "GET":
            return self.json({"released": p.release_occupiers(self.q.get("user"))})
        if path == "/admin/release_holds" and self.command != "GET":
            with p.lock:
                holds, p.holds = p.holds, []
            for slot in holds:
                p.registry.close(slot)
            return self.json({"released": len(holds)})
        if path == "/admin/update_apk" and self.command in ("POST", "PUT"):
            name = self.q.get("name", "app")
            if not re.match(r"^[A-Za-z0-9_.-]+$", name):
                return self.json({"error": "bad name"}, 400)
            with p.lock:
                p.update_apks[name] = body
            return self.json({"name": name, "bytes": len(body), "sha256": hashlib_sha256(body)})
        if path == "/admin/update_apks":
            # U-S14: the names the updater suite can select with the `update_apk` key (uploaded or from --update-apk-dir).
            with p.lock:
                return self.json({"names": sorted(p.update_apks), "sha256": {k: hashlib_sha256(v) for k, v in p.update_apks.items()}})
        if path == "/admin/update_key":
            return self.json({"public_key": p.update_public_hex()})
        if path == "/admin/faults":
            return self.json({"documented_fault_keys": FAULT_DOC, "global": p.cfg["faults"], "per_stream": p.cfg["stream_faults"]})
        self.json({"error": "unknown admin endpoint"}, 404)

    def admin_occupy(self):
        """/admin/occupy?user=&sid=&mode=hold|kick_oldest|release: another device on the account (M6 §17.5).

        hold: takes a free slot of `user` (default: the panel user) and keeps it until released; 409 when none is free.
        kick_oldest: when `user` is full, closes the oldest open connection and takes its slot; that occupier is in turn
        evicted by the next client open that finds the panel full (so a ping-pong needs a second occupy call).
        release: frees every occupier slot of `user` (also POST /admin/release_occupy)."""
        p, c = self.panel, self.panel.cfg
        user = self.q.get("user") or c["username"]
        sid = self.q.get("sid", "0")
        mode = self.q.get("mode", "hold")
        if mode == "release":
            return self.json({"released": p.release_occupiers(user)})
        if mode not in ("hold", "kick_oldest"):
            return self.json({"error": "mode must be hold, kick_oldest or release"}, 400)
        slot, kicked = p.registry.occupy(user, sid, c["max_connections"], mode, c["hls_timeout"])
        if slot is None:
            e = p.conn_open("occupier", user, sid, self.path_only, rejected=True, reason="rejected_no_free_slot")
            return self.json({"occupied": False, "reason": "no free slot"}, 409)
        slot.entry = p.conn_open("occupier", user, sid, self.path_only)
        with p.lock:
            p.occupiers.append(slot)
        if kicked is not None:
            p.conn_close(kicked.entry, "kicked_by_occupy")
            p.log(f"OCCUPY {mode} user={user}: kicked the oldest connection (stream {kicked.sid})")
        return self.json({"occupied": True, "mode": mode, "kicked": None if kicked is None else {"stream": str(kicked.sid)},
                          "occupiers": len(p.occupiers)})

    def admin_expect(self, data):
        """POST /admin/timeshift_expect {start_unix, channel?, padding_before?, apply_correction?, strict?}: the archive
        (panel-clock) start a correct client requests = start_unix - correction(channel) - padding_before; set as
        `timeshift_expect` (I-M4-13b, §5.6 item 9). For a live-anchored instant (pause, RW) pass apply_correction=false:
        it has no EPG component, so the panel's own clock is the expectation for every sign of the correction."""
        p = self.panel
        try:
            start = float(data["start_unix"])
            sid = int(data["channel"]) if data.get("channel") is not None else None
            pad = float(data.get("padding_before") or 0)
        except (KeyError, TypeError, ValueError):
            return self.json({"error": "start_unix (number) required; channel and padding_before optional numbers"}, 400)
        corr = p.correction_h(sid) if data.get("apply_correction", True) else 0.0
        exp = start - corr * 3600 - pad
        patch = {"timeshift_expect": [exp]}
        if "strict" in data:
            patch["strict_timeshift_tz"] = bool(data["strict"])
        p.update_config(patch)
        local = dt.datetime.fromtimestamp(exp, p.tz())
        return self.json({"expect_unix": exp, "correction_h": corr, "panel_wall": local.strftime("%Y-%m-%d %H:%M:%S"),
                          "xc_start": local.strftime("%Y-%m-%d:%H-%M")})

    # ---- M5: update endpoints and DNS-over-HTTPS
    def update_endpoint(self, path):
        p = self.panel
        base = self.base()
        if path == "/update/update.json":
            return self.send(200, p.update_manifest_bytes(base), "application/json")
        if path == "/update/update.json.sig":
            return self.send(200, p.update_signature(p.update_manifest_bytes(base)), "application/octet-stream")
        if path == "/update/pubkey":
            return self.send(200, p.update_public_hex(), "text/plain")
        m = re.match(r"^/update/([A-Za-z0-9_.-]+)\.apk$", path)
        if m and m[1] in p.update_apks:
            return self.send(200, p.update_apks[m[1]], "application/vnd.android.package-archive")
        return self.send(404, b"no such update file", "text/plain")

    def dns_query(self):
        import base64
        if self.command == "POST":
            n = int(self.headers.get("Content-Length") or 0)
            q = self.rfile.read(n) if n else b""
        else:
            raw = self.q.get("dns", "")
            try:
                q = base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4))
            except ValueError:
                q = b""
        ans = dns_answer(q, self.panel.cfg.get("dns_records") or {})
        if ans is None:
            return self.send(400, b"bad dns query", "text/plain")
        self.panel.bump("dns_queries")
        return self.send(200, ans, "application/dns-message")

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
        fault = c.get("api_fault")
        if fault and q.get("_redirected") != "1":
            self._notes.append("API_FAULT:" + fault)
            if fault == "html_splash":
                return self.send(200, SPLASH, "text/html")
            if fault == "timeout":
                self.nap(120)
                return None
            if fault == "429":
                return self.send(429, b"Too Many Requests", "text/plain", {"Retry-After": "2"})
            if fault == "redirect":
                sep = "&" if "?" in self.path else "?"
                return self.send(302, b"", "text/plain", {"Location": f"{self.base()}{self.path}{sep}_redirected=1"})
        if c["auth_mode"] != "ok":
            return self.auth_fail(c["auth_mode"])
        if not self.creds_ok(q.get("username"), q.get("password")):
            return self.auth_fail(c["bad_creds_mode"])
        host, port = self.hostport()
        tz, now = p.tz(), time.time()
        act = q.get("action")
        cid = q.get("category_id")
        if act is None and c.get("sleep_login"):
            self.nap(float(c["sleep_login"]))
        if act is None:
            n = c["active_cons_override"]
            if n is None:
                n = p.registry.counts(c["username"], c["hls_timeout"])["active"]
            return self.json(cat.user_info(c, n, host, port, tz, now))
        syn = p.syn()
        if act == "get_live_categories":
            return self.json(cat.categories(syn.categories() if syn else cat.live_cats(c)))
        if act == "get_vod_categories":
            return self.json(cat.categories(cat.VOD_CATS))
        if act == "get_series_categories":
            return self.json(cat.categories(cat.SERIES_CATS))
        if act == "get_live_streams":
            return self.json(cat.live_streams(c, host, port, cid, syn))
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
            ch = p.channel(int(sid)) if sid.isdigit() else None
            if not ch:
                return self.json({"epg_listings": []})
            short = act == "get_short_epg"
            lim = int(q["limit"]) if q.get("limit", "").isdigit() else 4
            return self.json(cat.epg_listings(c, ch, tz, now, short, lim, syn))
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
        self.send(200, cat.m3u(c, self.base(), host, port, ext, q.get("type", "m3u_plus") == "m3u_plus", self.panel.syn()),
                  "audio/x-mpegurl", {"Content-Disposition": 'attachment; filename="playlist.m3u"'})

    def xmltv_php(self):
        q, c = self.q, self.panel.cfg
        self._user = q.get("username")
        if c["auth_mode"] != "ok":
            return self.auth_fail(c["auth_mode"])
        if not self.creds_ok(q.get("username"), q.get("password")):
            return self.auth_fail("403" if c["bad_creds_mode"] == "auth0" else c["bad_creds_mode"])
        if c.get("epg_status", 0) >= 400:  # the provider's guide server failing (owner finding 2026-11-01)
            return self.reject(int(c["epg_status"]), "epg_status", f"xmltv.php: HTTP {c['epg_status']}\n".encode())
        syn = self.panel.syn()
        if syn:  # streamed: the document is generated while writing, never held in memory
            return self.xmltv_stream(syn, c)
        body = cat.xmltv(c, self.base(), None, time.time(), c["epg_offset"])
        if c["epg_gzip"]:
            return self.send(200, cat.maybe_gzip(body, True), "application/gzip")
        self.send(200, body, "text/xml; charset=utf-8")

    def xmltv_stream(self, syn, c):
        gz = bool(c["epg_gzip"])
        self.begin_stream(200, "application/gzip" if gz else "text/xml; charset=utf-8", {"Connection": "close"})
        self.close_connection = True
        if self.command == "HEAD":
            return
        co = zlib.compressobj(6, zlib.DEFLATED, 31) if gz else None
        for chunk in syn.xmltv_chunks(self.base(), c["epg_offset"]):
            if co:
                chunk = co.compress(chunk)
            if chunk:
                self.out(chunk)
        if co:
            self.out(co.flush())

    # ---- streams
    def start_instant(self, start):
        """(naive panel-wall datetime or None, unix instant) of a timeshift start token, or (None, None)."""
        if start.startswith("@") and start[1:].isdigit():  # Flussonic / shift: unix UTC, zone-free
            return None, float(start[1:])
        naive = parse_start(start)
        if naive is None:
            return None, None
        return naive, naive.replace(tzinfo=self.panel.tz()).timestamp()

    def archive_gate(self, sid, inst):
        """M4 §5.6 items 2-3: (reason, message) when the panel has no such archive, else None. `timeshift_window`
        (default on): no archive on the channel, a start older than its archive days (+120 s slack for the client's
        edge margin and clock skew), or later than now + 60 s. `archive_lag_sec`: the last N s are not written yet.
        Evaluated against the wall clock of each request, so a long-running panel does not drift (gap 8)."""
        c, now = self.panel.cfg, time.time()
        ch = self.panel.channel(sid) or {}
        if c.get("timeshift_window", True):
            days = ch.get("archive_days") or (cat.ARCHIVE_DAYS if ch.get("archive") else 0)
            if not ch.get("archive"):
                return "timeshift_window", "the channel has no archive"
            if inst < now - days * 86400 - 120:
                return "timeshift_window", f"start is older than the {days}-day archive"
            if inst > now + 60:
                return "timeshift_window", "start is in the future"
        lag = int(c.get("archive_lag_sec") or 0)
        if lag and inst > now - lag:
            return "archive_lag", f"the last {lag} s of the archive are not written yet"
        return None

    def timeshift_check(self, start, dur, days=None):
        """Returns (ok, message). Logs WARNING on suspected wrong-timezone start."""
        p, c = self.panel, self.panel.cfg
        tz = p.tz()
        naive, inst = self.start_instant(start)
        if inst is None:
            return False, "unparseable start"
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
                if naive is None:
                    diag = "unix start does not match (correction/padding applied twice?)"
                else:
                    as_utc = naive.replace(tzinfo=dt.timezone.utc).timestamp()
                    diag = "client sent UTC wall time" if any(abs(as_utc - e) <= 90 for e in exp) else "matches neither server zone nor UTC (device zone?)"
                problem = (f"timeshift start {start} read in {c['timezone']} is {dt.datetime.fromtimestamp(inst, tz).isoformat()}, "
                           f"expected one of {c['timeshift_expect']}: {diag}")
        elif inst > now + 60:
            problem = f"timeshift start {start} is in the FUTURE in {c['timezone']}: client probably sent UTC/device time (zone west of server)"
        elif now - inst > (days or cat.ARCHIVE_DAYS) * 86400 + 3600:
            problem = f"timeshift start {start} is older than the {days or cat.ARCHIVE_DAYS}-day archive window"
        if problem:
            p.bump("timeshift_tz_warnings")
            p.log("WARNING " + problem)
            self._notes.append("TZ-WARN")
            if c["strict_timeshift_tz"]:
                return False, problem
        return True, ""

    def log_timeshift(self, form, ident, start, dur, dur_unit, ext):
        """`/admin/stats.timeshift_requests` (last TS_LOG_MAX, oldest first): the request sequence the M4 suite asserts."""
        p = self.panel
        _, inst = self.start_instant(start or "")
        dur_s = int(dur) * dur_unit if dur is not None and str(dur).isdigit() else None
        e = {"form": form, "channel": int(ident) if str(ident).isdigit() else ident, "start": start, "start_unix": inst,
             "duration_s": dur_s, "range": self.headers.get("Range"), "status": None, "bytes": 0, "at": time.time(),
             "ext": ext, "lb": bool(self.server.is_lb)}
        with p.lock:
            p.stats["timeshift_requests"] = (p.stats["timeshift_requests"] + [e])[-TS_LOG_MAX:]
            p.stats["timeshift_request_count"] += 1
        self._ts_entry = e
        return dur_s

    def stream_request(self, kind, user, pw, ident, ext, start=None, dur=None, form=None, dur_unit=60):
        p, c = self.panel, self.panel.cfg
        self._user = user
        entry = {"path": self.path_only, "ua": self.headers.get("User-Agent", ""), "token_present": bool(self.q.get("token")),
                 "end_reason": None}
        with p.lock:  # `requests` stays the counter; per-request detail is `request_log` (last 200 stream requests)
            p.stats["request_log"] = (p.stats["request_log"] + [entry])[-200:]
            p.stats["stream_requests"] = p.stats.get("stream_requests", 0) + 1  # monotonic; request_log is capped
        self._log_entry = entry
        ext = (ext or "ts").lower()
        dur_s = self.log_timeshift(form, ident, start, dur, dur_unit, ext) if kind == "timeshift" else None
        if c["auth_mode"] in ("401", "403", "513"):
            return self.reject(int(c["auth_mode"]), "auth_mode")
        if not self.creds_ok(user, pw):
            return self.reject(403, "bad_credentials")
        if c["status"] != "Active":
            return self.reject(403, "account_" + c["status"].lower())
        # HLS segment: /live/u/p/{id}_seg{n}.ts; multi live HLS (§5.6 item 10): {id}_media.m3u8, {id}_subs.m3u8,
        # {id}_tseg{n}.ts (continuous-timestamp segment), {id}_vtt{n}.vtt (WebVTT segment)
        seg = re.match(r"^(\d+)_seg(\d+)$", ident) if kind == "live" else None
        sub = re.match(r"^(\d+)_(media|subs|tseg|vtt)(\d*)$", ident) if kind == "live" else None
        sid_s = seg[1] if seg else (sub[1] if sub else ident)
        if not sid_s.isdigit() or not self.known(kind, int(sid_s)):
            return self.reject(404, "unknown_stream")
        sid = int(sid_s)
        f = p.faults(sid)
        path = self.path_only
        seg_like = bool(seg) or bool(sub and sub[2] in ("tseg", "vtt"))

        if self.server.is_lb:  # second port: token required
            # HLS segments and the multi sub-playlists carry the playlist's token (see hls_playlist), so check it
            # against that path.
            tok_path = re.sub(r"/\d+_(?:seg\d+\.ts|media\.m3u8|subs\.m3u8|tseg\d+\.ts|vtt\d+\.vtt)$", f"/{sid}.m3u8", path) \
                if (seg or sub) else path
            tok = self.q.get("token", "")
            res = p.check_token(tok, tok_path, count=not (seg or sub))
            if res != "ok":
                p.bump("token_expired") if res == "expired" else None
                return self.reject(403, "token_" + res)
            with p.lock:
                self._token_exp = (p.tokens.get(tok) or [None])[0]
        elif (f.get("redirect") or (kind == "timeshift" and f.get("timeshift_redirect"))) and not seg_like:
            ttl = f.get("token_ttl") or c["token_ttl"]
            tok = p.make_token(path, ttl)
            h = (self.headers.get("Host") or "127.0.0.1").split(":")[0]
            query = urllib.parse.urlsplit(self.path).query  # timeshift.php and ?utc= keep their parameters
            p.bump("redirects")
            self._notes.append("REDIRECT")
            self.set_status(302)
            self.send_response(302)
            self.send_header("Location", f"http://{h}:{p.lb_port}{path}?{query + '&' if query else ''}token={tok}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        wl = f.get("ua_whitelist") or []
        ua = self.headers.get("User-Agent", "")
        if wl and not any(w.lower() in ua.lower() for w in wl):
            return self.reject(403, "ua_whitelist")
        allowed = [x.lower() for x in c["allowed_output_formats"]]
        if kind == "live" and not seg_like and ((ext == "m3u8" and (f.get("405_on_m3u8") or "m3u8" not in allowed))
                               or (ext == "ts" and "ts" not in allowed)):
            return self.reject(405, "output_format_not_allowed")
        rng = self.headers.get("Range")
        if rng and kind == "live" and ext == "ts" and not seg_like:
            p.bump("range_requests_seen")
            with p.lock:
                p.stats["range_requests"] = (p.stats["range_requests"] + [{"path": path, "range": rng, "ua": ua, "token_present": "token" in self.q}])[-20:]
            p.log(f"!!!!! RANGE REQUEST ON LIVE TS: '{rng}' {self.command} {path} ua={ua!r} "
                  "(IPTVnator-class bug: live TS must never be range-requested)")
            if f.get("range_rejects"):
                code = 400 if f["range_rejects"] == 400 else 416
                return self.reject(code, "range_rejects")
        if f.get("html_splash"):
            self._notes.append("HTML-SPLASH")
            return self.send(200, SPLASH, "text/html")
        if kind == "timeshift":
            st = p.reject_status(sid, form)  # §5.6 item 4: this panel does not serve this URL form
            if st is not None:
                p.bump("rejections", "timeshift_reject_form")
                self._notes.append(f"REJECT-FORM:{form}={st}")
                if st == "200-html":
                    return self.send(200, SPLASH, "text/html")
                if st == "200-empty":
                    return self.send(200, b"", "video/mp2t")
                return self.reject(int(st), "timeshift_reject_form")
            _, inst = self.start_instant(start or "")
            if inst is None:
                return self.reject(404, "timeshift_bad_start")
            gate = self.archive_gate(sid, inst)
            if gate:
                self._notes.append("ARCHIVE-404:" + gate[1])
                return self.reject(404, gate[0])
            ok, msg = self.timeshift_check(start or "", dur, (p.channel(sid) or {}).get("archive_days"))
            if not ok:
                return self.reject(404, "timeshift_tz" if msg != "unparseable start" else "timeshift_bad_start")
            trr = f.get("timeshift_range_rejects")
            rng = (self.headers.get("Range") or "").strip()
            if trr and rng and rng != "bytes=0-":  # §5.6 item 5: the extra ranged opens of a finite-TS prepare / seek
                return self.reject(458 if trr is True else int(trr), "timeshift_range_rejects")

        clip = p.clips[p.media(sid)] if kind in ("live", "timeshift") else None
        hls = kind in ("live", "timeshift") and ext == "m3u8"
        if seg:
            p.registry.touch(user, sid)
            return self.hls_segment(clip, sid, int(seg[2]))
        if sub and sub[2] in ("tseg", "vtt"):
            p.registry.touch(user, sid)
            return self.multi_segment(clip, sid, sub[2], int(sub[3] or 0))
        hls = hls or bool(sub)
        is_hls = kind == "live" and hls
        slot = p.registry.acquire(user, sid, c["max_connections"], float(f.get("linger") or 0), c["hls_timeout"], hls=is_hls)
        if slot is None and not is_hls:
            ev = p.registry.evict_occupier(user)  # a kick_oldest occupier loses its slot to this open (ping-pong)
            if ev is not None:
                p.conn_close(ev.entry, "kicked_by_client_open")
                with p.lock:
                    p.occupiers = [o for o in p.occupiers if o is not ev]
                slot = p.registry.acquire(user, sid, c["max_connections"], float(f.get("linger") or 0), c["hls_timeout"])
        if slot is None:
            if not is_hls:
                p.conn_open("stream", user, sid, path, rejected=True, reason="rejected_max_connections")
            st = f.get("linger_status", 403)
            if st == "close":  # connect then close immediately
                self.begin_stream(200, "video/mp2t")
                self.panel.bump("rejections", "max_connections_close")
                self._notes.append("REJECT:max_connections_close")
                return
            return self.reject(int(st), "max_connections")
        p.bump("stream_opens")
        if not slot.hls:
            self._conn = p.conn_open("stream", user, sid, path)
        try:
            if f.get("stall"):
                p.bump("stalls")
                self._notes.append(f"STALL{f['stall']}s")
                if not self.nap(float(f["stall"]), slot):
                    return
            if kind == "live" and sub:
                return self.multi_playlist(clip, sid, sub[2], f)
            if kind == "live" and hls:
                if p.media(sid) == "multi":
                    return self.multi_master(sid, f)
                return self.hls_playlist(clip, sid, f)
            if kind == "live":
                return self.live_ts(clip, sid, f, slot)
            if kind == "timeshift":
                _, inst = self.start_instant(start or "")
                duration_mode = c.get("timeshift_mode") == "duration"
                # §5.6 items 1, 7: live-shifted forms have no length (Flussonic timeshift_abs/rel always; SHIFT ?utc= and
                # Flussonic -now in duration mode; in clip3 mode those keep the M2/M3 finite file)
                live_like = form in ("timeshift_abs", "timeshift_rel") or (duration_mode and dur_s is None)
                if hls:
                    if live_like:
                        return self.event_playlist(clip, sid, inst)
                    if duration_mode:
                        return self.timeshift_playlist(clip, sid, dur_s or 60)
                    return self.timeshift_playlist(clip, sid)
                if live_like:
                    self._notes.append("LIVE-SHIFTED")
                    return self.live_ts(clip, sid, f, slot, state=TsState())
                chunked = False
                if duration_mode:
                    secs = max(1, dur_s or 60)
                    if c.get("timeshift_future", "clamp") == "clamp" and inst is not None:
                        # U-S9: only what is written by now (the xc minute count reaches up to 59 s past the panel's now).
                        # The written length is snapshotted for CLAMP_SNAPSHOT_S, as a panel that writes its archive in
                        # chunks: the reopens of one load (TsDurationReader's tail read, binary-search seeks) see one
                        # total (a total growing per millisecond sent Media3's seeker into a reopen loop on the
                        # emulator), and a continuation a few seconds later sees the new data (a long snapshot made
                        # every continuation of the same minute end at the old edge: a re-request storm).
                        key = (form, sid, start, dur_s)
                        cache = self.panel.clamp_cache
                        hit = cache.get(key)
                        if hit is None or time.time() - hit[0] > CLAMP_SNAPSHOT_S:
                            hit = (time.time(), time.time() - int(c.get("archive_lag_sec") or 0) - inst)
                            cache[key] = hit
                        written = hit[1]
                        if written < secs:
                            self._notes.append(f"CLAMPED{max(0, int(written))}s/{int(secs)}s")
                            secs = max(1, written)
                    size = max(PKT, int(secs * clip.rate) // PKT * PKT)
                    data = LoopedTs(clip, size)
                    chunked = bool(c.get("timeshift_chunked"))
                else:
                    data = clip.data * 3
                ctype = "text/html" if f.get("wrong_content_type") else "video/mp2t"
                if chunked:
                    # U-S9: no Content-Length, no ranges: a close-delimited body (HTTP/1.0), the player cannot seek in it.
                    self._notes.append("NO-LENGTH")
                    return self.finite(data, ctype, f, ranges=False)
                return self.finite(data, ctype, f, ranges=c["timeshift_ranges"], ignore_range=bool(c.get("timeshift_range_ignored")))
            data, ctype = p.vod_data, p.vod_ctype
            if f.get("wrong_content_type"):
                ctype = "text/html"
            return self.finite(data, ctype, f, ranges=True)
        finally:
            if not slot.hls:
                p.registry.close(slot)
                if slot.killed:
                    self.mark_end(slot.kill_reason or "kicked")
                p.conn_close(self._conn, self._end or ("server_stop" if p.stopping.is_set() else "server_close"), self._bytes)

    def known(self, kind, i):
        if kind in ("live", "timeshift"):
            return self.panel.channel(i) is not None
        if kind == "movie":
            return i in cat.MOVIE_BY_ID
        return i in cat.EPISODE_IDS

    def token_dead(self, f):
        """token_kill: the LB ends a body whose token has expired (mid-play expiry, §5.6 item 8)."""
        if f.get("token_kill") and self._token_exp is not None and time.time() >= self._token_exp:
            self.panel.bump("token_expired")
            self._notes.append("TOKEN-KILL")
            self.mark_end("token_expired")
            return True
        return False

    def finite(self, data, ctype, f, ranges=True, ignore_range=False):
        """A finite body (bytes, or a LoopedTs rendered on demand). ranges: Accept-Ranges/Content-Length/206 as a VOD
        server; ignore_range: Content-Length of the whole body but Range ignored (200 from byte 0, §5.6 item 6)."""
        n = len(data)
        rng = self.headers.get("Range")
        start, end, status = 0, n - 1, 200
        hdr = {"Accept-Ranges": "bytes"} if ranges and not ignore_range else {}
        if rng and ignore_range:
            self._notes.append("RANGE-IGNORED")
        if rng and ranges and not ignore_range:
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
        if ranges or ignore_range:
            hdr["Content-Length"] = str(body_len)
        self.begin_stream(status, ctype, hdr)
        if self.command == "HEAD":
            return
        slow = float(f.get("slow") or 0)
        step = max(PKT, int(slow / 10)) if slow else 64 * 1024
        render = data.slice if isinstance(data, LoopedTs) else (lambda a, b: data[a:b])
        pos = start
        while pos <= end:
            e = min(pos + step, end + 1)
            if self.peer_closed() or self.panel.stopping.is_set() or self.token_dead(f):
                break
            self.out(render(pos, e))
            pos = e
            if slow and not self.nap(step / slow):
                break

    def live_ts(self, clip, sid, f, slot, state=None):
        """Endless paced TS. [state] given (live-shifted archive forms): start there and do not touch the live
        channel's saved position; otherwise continue from the channel's last connection per reconnect_mode."""
        p, data, n, plan = self.panel, clip.data, len(clip.data), clip.plan
        ctype = "text/html" if f.get("wrong_content_type") else "video/mp2t"
        self.begin_stream(200, ctype, {"Connection": "close"})
        if self.command == "HEAD":
            return
        mode = f.get("reconnect_mode", "continue")
        last = p.live_pos.get(sid) or TsState()
        if state is not None:
            st, mode = state, "archive"
        elif mode == "reset":
            st = TsState()                                              # clip start, timestamps back at 0
        elif mode == "jump":
            st = TsState(last.pos, (last.off + JUMP_90K) % MOD33, last.cc).inherit(last)  # forward timestamp jump
        else:
            st = TsState(last.pos, last.off, last.cc).inherit(last)     # as if nothing happened
        po = f.get("pts_offset")
        st.shift = 0 if po is None else (int(po) - getattr(plan, "first_pts", 0)) % MOD33   # M6: the clip's lowest PTS starts at pts_offset
        ij = f.get("inline_jump_after")
        jump_at, jump_ms = (float(ij[0]), float(ij[1])) if isinstance(ij, (list, tuple)) and len(ij) == 2 else (None, 0.0)
        self._notes.append(f"start@{st.pos}+{st.off}/{mode}")
        slow = float(f.get("slow") or 0)
        rate = slow or clip.rate
        chunk = max(PKT, min(CHUNK, int(rate / 10) // PKT * PKT))
        pace = bool(p.cfg["pace"]) or bool(slow)
        burst = 0 if slow else rate  # 1 s of burst so zaps start fast
        close_every = float(f.get("close_every") or 0)
        sa = f.get("stall_after")
        stall_at, stall_for = (float(sa[0]), float(sa[1])) if isinstance(sa, (list, tuple)) and len(sa) == 2 else (None, 0.0)
        t0, sent = time.monotonic(), 0
        t_pace = t0  # pacing clock; shifted forward by a stall_after window so playback resumes at real-time speed
        shaper = Shaper(f, clip, rate)
        consumed = 0  # clip bytes emitted on this connection: media time = consumed / rate
        try:
            while not p.stopping.is_set() and not slot.killed:
                if self.peer_closed() or self.token_dead(f):
                    break
                now = time.monotonic()
                el = now - t0
                if close_every and el >= close_every:
                    p.bump("forced_closes")
                    self.mark_end("forced_close")
                    self._notes.append(f"FORCED-CLOSE after {close_every}s")
                    try:
                        self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    break
                if stall_at is not None and el >= stall_at:
                    stall_at = None
                    p.bump("stalls")
                    self._notes.append(f"STALL-AFTER {sa[0]}s for {sa[1]}s")
                    if not self.nap(stall_for, slot):
                        break
                    t_pace += time.monotonic() - now
                    continue
                e = min(st.pos + chunk, n)
                media_t = consumed / rate
                if jump_at is not None and media_t >= jump_at:
                    jump_at = None
                    st.step(int(round(jump_ms * 90)))
                    self._notes.append(f"INLINE-JUMP {jump_ms:g}ms at {media_t:.2f}s")
                buf = st.emit(plan, data, st.pos, e)
                if shaper.active:
                    buf = shaper.process(buf, media_t, st, e - st.pos)
                self.out(buf)
                sent += e - st.pos
                consumed += e - st.pos
                if e == n:
                    st.pos = 0
                    st.off = (st.off + plan.dur90k) % MOD33 if plan.active else 0
                else:
                    st.pos = e
                if pace:
                    eof = False
                    while not eof:  # sleep out the whole pacing debt, polling the socket for EOF every <= 50 ms
                        wait = (sent - burst) / rate - (time.monotonic() - t_pace)
                        if wait <= 0 or p.stopping.is_set() or slot.killed:
                            break
                        eof = self.peer_closed(min(wait, 0.05))
                    if eof:
                        break
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            self.mark_end("client_eof")
        finally:
            if state is None:
                p.live_pos[sid] = st
            self.close_connection = True

    # ---- HLS
    def seg_dur(self, clip):
        return clip.segments[0][1]

    def hls_playlist(self, clip, sid, f):
        c = self.panel.cfg
        d, nseg = self.seg_dur(clip), len(clip.segments)
        cur = int(time.time() / d)
        first = max(0, cur - c["hls_window"] + 1)
        tok = f"?token={self.q['token']}" if self.server.is_lb and self.q.get("token") else ""
        L = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{math.ceil(d)}", f"#EXT-X-MEDIA-SEQUENCE:{first}",
             f"#EXT-X-DISCONTINUITY-SEQUENCE:{(first - 1) // nseg if first > 0 else 0}"]
        for n in range(first, cur + 1):
            if n > 0 and n % nseg == 0:
                L.append("#EXT-X-DISCONTINUITY")  # clip loop: timestamps restart
            L += [f"#EXTINF:{clip.segments[n % nseg][1]:.3f},", f"{sid}_seg{n}.ts{tok}"]
        ctype = "text/html" if f.get("wrong_content_type") else "application/vnd.apple.mpegurl"
        self.send(200, "\n".join(L) + "\n", ctype, {"Cache-Control": "no-cache"})

    def hls_segment(self, clip, sid, n):
        d, nseg = self.seg_dur(clip), len(clip.segments)
        cur = int(time.time() / d)
        if n > cur + 1 or n < cur - self.panel.cfg["hls_window"] - 3:
            return self.reject(404, "segment_out_of_window")
        self.send(200, clip.segments[n % nseg][0], "video/mp2t")

    def timeshift_playlist(self, clip, sid, dur_s=None):
        """VOD archive playlist: the clip's segments once (clip3), or [dur_s] seconds of continuous-timestamp segments
        (duration mode, /tseg/)."""
        d, nseg = self.seg_dur(clip), len(clip.segments)
        L = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{math.ceil(d)}", "#EXT-X-MEDIA-SEQUENCE:0",
             "#EXT-X-PLAYLIST-TYPE:VOD"]
        if dur_s is None:
            for n in range(nseg):
                L += [f"#EXTINF:{clip.segments[n][1]:.3f},", f"/vodseg/{sid}_{n}.ts"]
        else:
            for n in range(max(1, math.ceil(dur_s / d))):
                L += [f"#EXTINF:{clip.segments[n % nseg][1]:.3f},", f"/tseg/{sid}_{n}.ts"]
        L.append("#EXT-X-ENDLIST")
        self.send(200, "\n".join(L) + "\n", "application/vnd.apple.mpegurl")

    def event_playlist(self, clip, sid, start):
        """§5.6 item 7: EVENT archive (Flussonic -now.m3u8, timeshift_abs/rel .m3u8): segments from [start] up to the
        panel's now, no ENDLIST, growing with wall time; continuous timestamps (/tseg/), at most EVENT_MAX_SEGMENTS."""
        d, nseg = self.seg_dur(clip), len(clip.segments)
        count = max(1, min(EVENT_MAX_SEGMENTS, int((time.time() - start) / d)))
        L = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{math.ceil(d)}", "#EXT-X-MEDIA-SEQUENCE:0",
             "#EXT-X-PLAYLIST-TYPE:EVENT"]
        for n in range(count):
            L += [f"#EXTINF:{clip.segments[n % nseg][1]:.3f},", f"/tseg/{sid}_{n}.ts"]
        self.send(200, "\n".join(L) + "\n", "application/vnd.apple.mpegurl", {"Cache-Control": "no-cache"})

    # ---- multi live HLS (§5.6 item 10): master + media playlist + WebVTT subtitle rendition

    def multi_master(self, sid, f):
        tok = f"?token={self.q['token']}" if self.server.is_lb and self.q.get("token") else ""
        L = ["#EXTM3U", "#EXT-X-VERSION:4",
             f'#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,'
             f'FORCED=NO,URI="{sid}_subs.m3u8{tok}"',
             '#EXT-X-STREAM-INF:BANDWIDTH=2600000,RESOLUTION=1280x720,SUBTITLES="subs"',
             f"{sid}_media.m3u8{tok}"]
        ctype = "text/html" if f.get("wrong_content_type") else "application/vnd.apple.mpegurl"
        self.send(200, "\n".join(L) + "\n", ctype, {"Cache-Control": "no-cache"})

    def multi_playlist(self, clip, sid, which, f):
        """The live window (same sequence numbers for media and subtitles): {sid}_tseg{n}.ts or {sid}_vtt{n}.vtt."""
        c = self.panel.cfg
        d = self.seg_dur(clip)
        cur = int(time.time() / d)
        first = max(0, cur - c["hls_window"] + 1)
        tok = f"?token={self.q['token']}" if self.server.is_lb and self.q.get("token") else ""
        L = ["#EXTM3U", "#EXT-X-VERSION:3", f"#EXT-X-TARGETDURATION:{math.ceil(d)}", f"#EXT-X-MEDIA-SEQUENCE:{first}"]
        for n in range(first, cur + 1):
            name = f"{sid}_tseg{n}.ts" if which == "media" else f"{sid}_vtt{n}.vtt"
            L += [f"#EXTINF:{d:.3f},", name + tok]
        self.send(200, "\n".join(L) + "\n", "application/vnd.apple.mpegurl", {"Cache-Control": "no-cache"})

    def multi_segment(self, clip, sid, which, n):
        d = self.seg_dur(clip)
        cur = int(time.time() / d)
        if n > cur + 1 or n < cur - self.panel.cfg["hls_window"] - 3:
            return self.reject(404, "segment_out_of_window")
        if which == "tseg":
            return self.send(200, clip.loop_segment(n), "video/mp2t")
        # WebVTT segment n: cues of the clip loop overlapping [t0, t0 + d), local time 0 = this loop's first PTS
        nseg = len(clip.segments)
        loop, i = divmod(n, nseg)
        t0 = i * d
        mpegts = (clip.plan.first_pts + loop * clip.plan.dur90k) % MOD33
        L = ["WEBVTT", f"X-TIMESTAMP-MAP=MPEGTS:{mpegts},LOCAL:00:00:00.000", ""]
        for a, b, text in self.panel.subs:
            if a < t0 + d and b > t0:
                L += [f"{vtt_time(a)} --> {vtt_time(b)}", text, ""]
        self.send(200, "\n".join(L) + "\n", "text/vtt")


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
    ap.add_argument("--synthetic", type=int, metavar="N", help="replace the catalog live channels with N generated ones (<= 20000)")
    ap.add_argument("--synthetic-days-back", type=int, help="synthetic EPG days before server start (default 3)")
    ap.add_argument("--synthetic-days-ahead", type=int, help="synthetic EPG days after server start (default 7)")
    ap.add_argument("--no-ffmpeg", action="store_true")
    ap.add_argument("--no-pace", action="store_true", help="send live TS as fast as possible")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--update-apk-dir", help="U-S14: load every *.apk in this directory as an update APK by name "
                                             "(thebox-update-v2.apk -> v2); select one with the update_apk config key")
    ap.add_argument("--update-key", help="file with the 32-byte Ed25519 seed (hex) the update manifest is signed with "
                                         "(default tools/fakepanel/update_test_key if present, else a fresh key per run)")
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
    for flag in ("synthetic", "synthetic_days_back", "synthetic_days_ahead"):
        if getattr(a, flag) is not None:
            cfg[flag] = getattr(a, flag)
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

    panel = Panel(cfg, a.media_dir, not a.no_ffmpeg, a.verbose, update_key=a.update_key)
    if a.update_apk_dir:
        panel.load_update_apks(a.update_apk_dir)
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
