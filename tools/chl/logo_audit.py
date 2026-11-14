#!/usr/bin/env python3
"""CHL logo audit (docs/design/m6-chl-leagues.md §3.5, review S10).

For every team in the recorded `teamsbyseason` fixtures, resolve the logo URL the app would use (a .jpg/.jpeg URL is
swapped for `{teamId}.png` in the same folder; `50x50/` is never used), fetch it, and report the HTTP status, the PNG
colour type and the alpha of the four corners. The app's CHL no-alpha rule rejects a PNG without an alpha channel or
with four opaque corners; a 404 falls back to the abbreviation.

Usage (repo root; fetches 61 small images with a generic User-Agent, sends nothing else):
  python3 -I tools/chl/logo_audit.py > core/src/testFixtures/resources/sports/chl/logos/logo-outcomes.tsv
Offline, against files already downloaded as `<league>_<CODE>.bin`:
  python3 -I tools/chl/logo_audit.py --files <dir>
Standard library only (zlib + struct PNG decoder; interlaced PNGs report corners as "interlaced", which Android decodes).
"""
import json
import os
import re
import struct
import sys
import urllib.request
import zlib

FIX = "core/src/testFixtures/resources/sports/chl/lscluster"
TEAMS = {"ohl": "teamsbyseason-ohl-2026-27.json", "whl": "teamsbyseason-whl-2026-27.json",
         "lhjmq": "teamsbyseason-lhjmq-2026-27.json"}
OK_PATH = re.compile(r"^https://assets\.leaguestat\.com/(ohl|whl|lhjmq)/logos/([0-9]+(?:_[0-9]+)?)\.(png|jpg|jpeg)$")


def app_url(url, client, team_id):
    m = OK_PATH.match(url or "")
    if not m or m.group(1) != client:
        return None
    if m.group(3) in ("jpg", "jpeg"):
        return f"https://assets.leaguestat.com/{client}/logos/{team_id}.png"
    return url


def paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    return a if pa <= pb and pa <= pc else (b if pb <= pc else c)


def png_info(d):
    if d[:8] != b"\x89PNG\r\n\x1a\n":
        return "not-png", None
    i, idat, trns = 8, b"", None
    w = h = bd = ct = il = None
    while i < len(d):
        n = struct.unpack(">I", d[i:i + 4])[0]
        t, c = d[i + 4:i + 8], d[i + 8:i + 8 + n]
        i += 12 + n
        if t == b"IHDR":
            w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", c)
        elif t == b"IDAT":
            idat += c
        elif t == b"tRNS":
            trns = c
    kind = {6: "RGBA", 2: "RGB", 3: "palette", 4: "grey+alpha", 0: "grey"}.get(ct, str(ct))
    if il:
        return kind, "interlaced"
    if bd != 8:
        return kind, f"bitdepth{bd}"
    ch = {6: 4, 2: 3, 3: 1, 4: 2, 0: 1}[ct]
    raw, stride = zlib.decompress(idat), w * ch
    rows, prev, p = [], bytearray(stride), 0
    for _ in range(h):
        f = raw[p]
        line = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for x in range(stride):
            a = line[x - ch] if x >= ch else 0
            b = prev[x]
            cc = prev[x - ch] if x >= ch else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + ((a + b) >> 1)) & 255
            elif f == 4:
                line[x] = (line[x] + paeth(a, b, cc)) & 255
        rows.append(line)
        prev = line

    def alpha(x, y):
        px = rows[y][x * ch:(x + 1) * ch]
        if ct == 6:
            return px[3]
        if ct == 4:
            return px[1]
        if ct == 3:
            return trns[px[0]] if trns and px[0] < len(trns) else 255
        return 255

    return kind, [alpha(0, 0), alpha(w - 1, 0), alpha(0, h - 1), alpha(w - 1, h - 1)]


def verdict(status, kind, corners):
    if status != 200 or kind == "not-png":
        return "fallback (no image)"
    if kind in ("RGB", "grey"):
        return "fallback (no alpha)"
    if isinstance(corners, list) and all(c > 8 for c in corners):
        return "fallback (opaque corners)"
    return "logo"


def main(argv):
    files = argv[argv.index("--files") + 1] if "--files" in argv else None
    print("league\tcode\tteam_id\tfeed_url\tapp_url\tstatus\tpng\tcorners\tverdict")
    for client, name in TEAMS.items():
        with open(os.path.join(FIX, name)) as f:
            teams = json.load(f)["SiteKit"]["Teamsbyseason"]
        for t in teams:
            code = t["code"].upper()
            url = app_url(t.get("team_logo_url"), client, t["id"])
            status, kind, corners = None, None, None
            if url is None:
                status = "rejected"
            elif files:
                path = os.path.join(files, f"{client}_{code}.bin")
                d = open(path, "rb").read()
                status = 200 if d[:8] == b"\x89PNG\r\n\x1a\n" else 404
                kind, corners = png_info(d) if status == 200 else (None, None)
            else:
                req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
                try:
                    with urllib.request.urlopen(req, timeout=20) as r:
                        status, d = r.status, r.read()
                    kind, corners = png_info(d)
                except urllib.error.HTTPError as e:
                    status = e.code
            print("\t".join(str(x) for x in (client, code, t["id"], t.get("team_logo_url"), url, status, kind, corners,
                                              verdict(status, kind, corners))))


if __name__ == "__main__":
    main(sys.argv)
