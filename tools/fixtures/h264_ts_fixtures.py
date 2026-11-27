#!/usr/bin/env python3
"""H.264 access-unit TS fixtures (docs/design/m6-h264-access-units.md, review S2).

Synthetic content only (ffmpeg testsrc + libx264). Each fixture is 30 frames of 320x180 at 60 fps (0.5 s), video only,
`-bf 2 -g 30`. ffmpeg's mpegts muxer always writes an AUD in front of every access unit, so this script takes the
video PES out of ffmpeg's TS, optionally strips the AUD NAL units, and writes its own TS (PAT, PMT with stream type
0x1B, PCR on the video PID) with the PES layout the fixture asks for. PTS/DTS are ffmpeg's.

Usage (stdlib only):  python3 -I tools/fixtures/h264_ts_fixtures.py core/src/test/resources/ts
Needs `ffmpeg` with libx264 on PATH. The output is equivalent, not byte-identical, across x264 versions.
"""
import os
import subprocess
import sys
import tempfile

FRAMES = 30
VIDEO_PID = 0x100
PMT_PID = 0x1000
VBV = "nal-hrd=vbr:vbv-maxrate=2000:vbv-bufsize=2000"
CBR = "nal-hrd=cbr:filler=1"

# name -> (x264 params, extra ffmpeg args, strip AUDs, layout)
FIXTURES = {
    "h264_aud_sei_s1.ts": ("slices=1:aud=1:" + VBV, [], False, "aligned"),
    "h264_aud_s4.ts": ("slices=4:aud=1", [], False, "aligned"),
    "h264_noaud_s1.ts": ("slices=1:aud=0", [], True, "aligned"),
    "h264_noaud_sei_s1.ts": ("slices=1:aud=0:" + VBV, [], True, "aligned"),
    "h264_aud_sei_filler_s1.ts": ("slices=1:aud=1:" + CBR, ["-b:v", "800k", "-minrate", "800k", "-maxrate", "800k", "-bufsize", "800k"], False, "aligned"),
    "h264_aud_sei_awkward.ts": ("slices=1:aud=1:" + VBV, [], False, "awkward"),
}


def encode(params, extra, path):
    cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=60",
           "-frames:v", str(FRAMES), "-c:v", "libx264", "-preset", "veryfast", "-bf", "2", "-g", "30", "-pix_fmt", "yuv420p",
           "-x264-params", params] + extra + ["-an", "-f", "mpegts", path]
    subprocess.run(cmd, check=True)


def read_pes(ts):
    """Video PES of an ffmpeg TS as (pts, dts, es bytes)."""
    pmt_pid, vpid, pes, cur = None, None, [], None
    for i in range(0, len(ts) - 187, 188):
        p = ts[i:i + 188]
        assert p[0] == 0x47
        pid = ((p[1] & 0x1F) << 8) | p[2]
        pusi = p[1] & 0x40
        afc = (p[3] >> 4) & 3
        off = 4
        if afc & 2:
            off += 1 + p[4]
        if not afc & 1:
            continue
        payload = p[off:]
        if pid == 0 and pusi:
            sec = payload[1 + payload[0]:]
            pmt_pid = ((sec[10] & 0x1F) << 8) | sec[11]
        elif pid == pmt_pid and pusi and vpid is None:
            sec = payload[1 + payload[0]:]
            n = ((sec[1] & 0x0F) << 8) | sec[2]
            j = 12 + (((sec[10] & 0x0F) << 8) | sec[11])
            while j < 3 + n - 4:
                st, epid, el = sec[j], ((sec[j + 1] & 0x1F) << 8) | sec[j + 2], ((sec[j + 3] & 0x0F) << 8) | sec[j + 4]
                if st == 0x1B:
                    vpid = epid
                j += 5 + el
        elif pid == vpid:
            if pusi:
                if cur:
                    pes.append(cur)
                flags, hl = payload[7], payload[8]
                pts = parse_ts(payload[9:14]) if flags & 0x80 else None
                dts = parse_ts(payload[14:19]) if flags & 0x40 else pts
                cur = [pts, dts, bytearray(payload[9 + hl:])]
            elif cur:
                cur[2] += payload
    if cur:
        pes.append(cur)
    return pes


def parse_ts(b):
    return ((b[0] >> 1) & 7) << 30 | b[1] << 22 | (b[2] >> 1) << 15 | b[3] << 7 | b[4] >> 1


def nal_units(es):
    """(start incl. start code, header index) per NAL unit."""
    out, i, n = [], 0, len(es)
    while i + 3 <= n:
        if es[i] == 0 and es[i + 1] == 0 and es[i + 2] == 1:
            s = i - 1 if i > 0 and es[i - 1] == 0 else i
            out.append((s, i + 3))
            i += 3
        else:
            i += 1
    return out


def strip_aud(es):
    nals = nal_units(es)
    keep = bytearray(es[:nals[0][0]] if nals else es)
    for k, (s, h) in enumerate(nals):
        e = nals[k + 1][0] if k + 1 < len(nals) else len(es)
        if es[h] & 0x1F != 9:
            keep += es[s:e]
    return keep


def enc_ts(marker, v):
    return bytes([(marker << 4) | (((v >> 30) & 7) << 1) | 1, (v >> 22) & 0xFF, (((v >> 15) & 0x7F) << 1) | 1,
                  (v >> 7) & 0xFF, ((v & 0x7F) << 1) | 1])


def pes_bytes(pts, dts, es, bounded):
    hdr = b""
    flags = 0
    if pts is not None:
        if dts is not None and dts != pts:
            flags, hdr = 0xC0, enc_ts(3, pts) + enc_ts(1, dts)
        else:
            flags, hdr = 0x80, enc_ts(2, pts)
    body = bytes([0x80, flags, len(hdr)]) + hdr + bytes(es)
    length = len(body) if bounded else 0
    assert length < 65536
    return b"\x00\x00\x01\xE0" + bytes([length >> 8, length & 0xFF]) + body


CRC_TABLE = []
for _i in range(256):
    _c = _i << 24
    for _ in range(8):
        _c = ((_c << 1) ^ 0x04C11DB7) if _c & 0x80000000 else _c << 1
    CRC_TABLE.append(_c & 0xFFFFFFFF)


def crc32(data):
    c = 0xFFFFFFFF
    for b in data:
        c = ((c << 8) & 0xFFFFFFFF) ^ CRC_TABLE[((c >> 24) ^ b) & 0xFF]
    return c


def section(table_id, ext, body):
    n = 5 + len(body) + 4
    s = bytes([table_id, 0xB0 | (n >> 8), n & 0xFF, ext >> 8, ext & 0xFF, 0xC1, 0, 0]) + body
    return s + crc32(s).to_bytes(4, "big")


class Writer:
    def __init__(self):
        self.out = bytearray()
        self.cc = {}

    def packet(self, pid, payload, pusi, pcr=None):
        """One packet; returns how many payload bytes it took. Stuffing goes into the adaptation field."""
        af_body = None
        if pcr is not None:
            base, ext = pcr // 300, pcr % 300
            af_body = bytes([0x10, (base >> 25) & 0xFF, (base >> 17) & 0xFF, (base >> 9) & 0xFF, (base >> 1) & 0xFF,
                             ((base & 1) << 7) | 0x7E | (ext >> 8), ext & 0xFF])
        room = 184 - (1 + len(af_body) if af_body is not None else 0)
        take = min(room, len(payload))
        stuff = room - take
        if af_body is not None:
            af = bytes([len(af_body) + stuff]) + af_body + b"\xFF" * stuff
        elif stuff == 1:
            af = b"\x00"
        elif stuff > 1:
            af = bytes([stuff - 1, 0x00]) + b"\xFF" * (stuff - 2)
        else:
            af = b""
        cc = self.cc.get(pid, 0)
        self.cc[pid] = (cc + 1) & 0xF
        hdr = bytes([0x47, (0x40 if pusi else 0) | (pid >> 8), pid & 0xFF, (0x30 if af else 0x10) | cc])
        pkt = hdr + af + bytes(payload[:take])
        assert len(pkt) == 188, len(pkt)
        self.out += pkt
        return take

    def psi(self, pid, sec):
        self.packet(pid, b"\x00" + sec + b"\xFF" * (183 - len(sec)), True)

    def pes(self, data, pcr):
        pos, first = 0, True
        while pos < len(data):
            pos += self.packet(VIDEO_PID, data[pos:], first, pcr if first else None)
            first = False


def build(pes_list, layout):
    w = Writer()
    w.psi(0, section(0x00, 1, bytes([0, 1, 0xE0 | (PMT_PID >> 8), PMT_PID & 0xFF])))
    pmt = bytes([0xE0 | (VIDEO_PID >> 8), VIDEO_PID & 0xFF, 0xF0, 0x00, 0x1B, 0xE0 | (VIDEO_PID >> 8), VIDEO_PID & 0xFF, 0xF0, 0x00])
    w.psi(PMT_PID, section(0x02, 1, pmt))

    def pcr_for(dts):
        return max(0, (dts - 9000)) * 300  # 100 ms ahead of decode

    if layout == "aligned":
        for pts, dts, es in pes_list:
            w.pes(pes_bytes(pts, dts, es, bounded=False), pcr_for(dts))
        return w.out
    # "awkward" (review S2): AUs 3+4 share one PES; AU 7 is split over two bounded PES (the second without PTS);
    # the AUD of every AU from 10 on rides at the tail of the previous PES; the rest is aligned.
    items = [[pts, dts, bytearray(es)] for pts, dts, es in pes_list]
    for k in range(len(items) - 1, 9, -1):
        es = items[k][2]
        nals = nal_units(es)
        if nals and es[nals[0][1]] & 0x1F == 9:
            end = nals[1][0] if len(nals) > 1 else len(es)
            items[k - 1][2] += es[:end]
            del es[:end]
    k = 0
    while k < len(items):
        pts, dts, es = items[k]
        if k == 3:
            w.pes(pes_bytes(pts, dts, es + items[k + 1][2], bounded=False), pcr_for(dts))
            k += 2
            continue
        if k == 7:
            half = len(es) // 2
            w.pes(pes_bytes(pts, dts, es[:half], bounded=True), pcr_for(dts))
            w.pes(pes_bytes(None, None, es[half:], bounded=True), None)
        else:
            w.pes(pes_bytes(pts, dts, es, bounded=False), pcr_for(dts))
        k += 1
    return w.out


def main(out_dir):
    with tempfile.TemporaryDirectory() as tmp:
        for name, (params, extra, noaud, layout) in FIXTURES.items():
            src = os.path.join(tmp, name)
            encode(params, extra, src)
            with open(src, "rb") as f:
                pes = read_pes(f.read())
            if noaud:
                pes = [[p, d, strip_aud(e)] for p, d, e in pes]
            data = build(pes, layout)
            with open(os.path.join(out_dir, name), "wb") as f:
                f.write(data)
            types = {}
            for p, d, e in pes:
                for s, h in nal_units(e):
                    t = e[h] & 0x1F
                    types[t] = types.get(t, 0) + 1
            print(f"{name}: {len(data)} B, {len(pes)} PES, NAL types {dict(sorted(types.items()))}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
