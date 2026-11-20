#!/usr/bin/env python3
"""Builds ui/src/main/assets/weather/basemap.bin, the radar page's base map, from Natural Earth (public domain).

Input: a directory holding these files from https://github.com/nvkelso/natural-earth-vector (geojson/):
  ne_50m_land, ne_50m_lakes, ne_50m_admin_0_boundary_lines_land, ne_50m_admin_1_states_provinces_lines,
  ne_50m_populated_places_simple   (each with the .geojson extension)

Usage: python3 tools/weather/build_basemap.py <input dir> ui/src/main/assets/weather/basemap.bin

Format (big-endian; read by app.thebox.ui.weather.BaseMap):
  int magic 0x424D4150 ("BMAP"), int version 1
  int layerCount; per layer: byte kind (0 land, 1 lake, 2 country border, 3 state border), int ringCount;
    per ring: u16 minX, minY, maxX, maxY, int pointCount, then pointCount × (u16 x, u16 y)
  int placeCount; per place: u16 x, u16 y, byte rank, UTF name (DataOutput.writeUTF)
Coordinates: x = (lon + 180) / 360 × 65535, y = (90 − lat) / 180 × 65535.
"""
import json, struct, sys, os

def q(lon, lat):
    return (max(0, min(65535, round((lon + 180.0) / 360.0 * 65535))), max(0, min(65535, round((90.0 - lat) / 180.0 * 65535))))

def rings_of(geom, polygons):
    t, c = geom["type"], geom["coordinates"]
    if t == "Polygon": return [c[0]] if polygons else c
    if t == "MultiPolygon": return [p[0] for p in c]
    if t == "LineString": return [c]
    if t == "MultiLineString": return c
    return []

def layer(path, polygons, min_span):
    out = []
    for f in json.load(open(path, encoding="utf-8"))["features"]:
        if f.get("geometry") is None: continue
        for ring in rings_of(f["geometry"], polygons):
            pts = []
            for lon, lat in (p[:2] for p in ring):
                p = q(lon, lat)
                if not pts or pts[-1] != p: pts.append(p)
            if len(pts) < (3 if polygons else 2): continue
            xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
            if max(xs) - min(xs) < min_span and max(ys) - min(ys) < min_span: continue
            out.append(pts)
    return out

def main(src, dst):
    layers = [
        (0, layer(os.path.join(src, "ne_50m_land.geojson"), True, 6)),
        (1, layer(os.path.join(src, "ne_50m_lakes.geojson"), True, 6)),
        (2, layer(os.path.join(src, "ne_50m_admin_0_boundary_lines_land.geojson"), False, 2)),
        (3, layer(os.path.join(src, "ne_50m_admin_1_states_provinces_lines.geojson"), False, 2)),
    ]
    places = []
    for f in json.load(open(os.path.join(src, "ne_50m_populated_places_simple.geojson"), encoding="utf-8"))["features"]:
        p = f["properties"]
        name = p.get("name")
        if not name: continue
        lon, lat = f["geometry"]["coordinates"][:2]
        places.append((q(lon, lat), int(p.get("scalerank", 9)), name))
    places.sort(key=lambda x: x[1])
    with open(dst, "wb") as o:
        o.write(struct.pack(">ii", 0x424D4150, 1))
        o.write(struct.pack(">i", len(layers)))
        for kind, rings in layers:
            o.write(struct.pack(">bi", kind, len(rings)))
            for pts in rings:
                xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
                o.write(struct.pack(">HHHHi", min(xs), min(ys), max(xs), max(ys), len(pts)))
                o.write(b"".join(struct.pack(">HH", x, y) for x, y in pts))
        o.write(struct.pack(">i", len(places)))
        for (x, y), rank, name in places:
            b = name.encode("utf-8")  # no NUL or supplementary characters in the source names: plain UTF-8 = modified UTF-8
            o.write(struct.pack(">HHbH", x, y, rank, len(b))); o.write(b)
    print({k: (len(r), sum(len(p) for p in r)) for k, r in layers}, len(places), os.path.getsize(dst))

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
