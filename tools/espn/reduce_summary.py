#!/usr/bin/env python3
"""Reduce recorded ESPN samples to small, committable test fixtures (docs/design/m6-nfl-hub.md §8.1, arch m14).

Reads LOCAL samples only (the lead's probe captures); never fetches anything. A full sample is never committed: the
output keeps only what the NFL hub mappers read, plus a few stub items so the decoder's skip path runs over real shapes.

    python3 tools/espn/reduce_summary.py summary  IN.json OUT.json [--videos ID,ID,...]
    python3 tools/espn/reduce_summary.py record   IN.json OUT.json
    python3 tools/espn/reduce_summary.py board    IN.json OUT.json --events ID,ID

What it does (summary):
- keeps only the DTO's top-level keys (header, boxscore, scoringPlays, leaders, gameInfo, article, videos,
  lastFiveGames, injuries) in the order recorded, plus a stub `drives` (one drive, 3 plays) and one-item stubs of `news`,
  `winprobability` and `pickcenter` (the last with every value blanked: no odds are committed) when the sample has them;
- drops every `links`, `logos`, `ad`, `cerebroId`, `dataSourceIdentifier`, `contributingPartner`, `pccId`, `uid`,
  `guid`, `tracking.trackingId` / `trackingName`, every `article` key but `type` and `headline`, `officials` and ticket
  info;
- keeps the passing, rushing and receiving box categories (the ones mapped), 2 athletes each, and 3 injuries per team
  (one of each status first);
- rewrites every media URL to `https://clips.invalid/{clipId}/...`, every image to `https://img.invalid/{name}.jpg` and
  headshots to `https://img.invalid/h/{athleteId}.png`, so no real path (or UUID) is committed;
- keeps ids, scores, clocks, texts, `timeRestrictions`, `coverageType`, and the key order and team order as recorded.

The output is checked to be under MAX_BYTES; the script fails otherwise.
"""
import argparse
import json
import sys

MAX_BYTES = 40 * 1024

TOP_KEYS = ["header", "boxscore", "scoringPlays", "leaders", "gameInfo", "article", "videos", "lastFiveGames", "injuries"]
STUB_KEYS = ["drives", "news", "winprobability", "pickcenter"]
DROP_KEYS = {
    "links", "logos", "logo", "ad", "cerebroId", "dataSourceIdentifier", "contributingPartner", "pccId", "uid", "guid",
    "officials", "ticketsInfo", "tickets", "$ref", "images", "posterImages", "categories", "keywords", "collegeAthlete",
}
BOX_CATEGORIES = {"passing", "rushing", "receiving"}
TEAM_KEYS = ["id", "abbreviation", "displayName", "shortDisplayName", "name", "location", "nickname"]


def pick(d, keys):
    """The keys of [d] that exist, in [d]'s own order (the recorded order)."""
    return {k: v for k, v in d.items() if k in keys}


def team(t):
    return pick(t, TEAM_KEYS) if isinstance(t, dict) else t


def scrub(x):
    """Drops the noise keys everywhere below [x]."""
    if isinstance(x, dict):
        return {k: scrub(v) for k, v in x.items() if k not in DROP_KEYS}
    if isinstance(x, list):
        return [scrub(v) for v in x]
    return x


def headshot(athlete):
    aid = athlete.get("id", "0")
    out = pick(athlete, ["id", "displayName", "shortName", "lastName", "position", "jersey"])
    if "position" in out and isinstance(out["position"], dict):
        out["position"] = pick(out["position"], ["abbreviation"])
    if "headshot" in athlete:
        out["headshot"] = {"href": f"https://img.invalid/h/{aid}.png"}
    return out


def video(v):
    """One video object: the fields `EspnVideoMapper` reads, media rewritten to invalid hosts."""
    vid = v.get("id")
    out = pick(v, ["id", "headline", "description", "lastModified", "originalPublishDate", "duration",
                   "timeRestrictions", "deviceRestrictions", "gameId"])
    if "thumbnail" in v:
        out["thumbnail"] = f"https://img.invalid/{vid}.jpg"
    if "plays" in v:  # the record's plays[].id is a JSON number (FACTS correction 9); kept as the shape only
        out["plays"] = [pick(p, ["id"]) for p in v["plays"][:2]]
    src = (v.get("links") or {}).get("source") or {}
    links = {}
    if src:
        s = {}
        if "href" in src:
            s["href"] = f"https://clips.invalid/{vid}/source.mp4"
        hls = src.get("HLS")
        if hls:
            h = {"href": f"https://clips.invalid/{vid}/playlist.m3u8"}
            if "cmaf" in hls:
                h["cmaf"] = {"href": f"https://clips.invalid/{vid}/cmaf/playlist.m3u8"}
            s["HLS"] = h
        links["source"] = s
    if (v.get("links") or {}).get("mobile"):
        links["mobile"] = {"href": f"https://login.invalid/{vid}"}
    if links:
        out["links"] = links
    tr = v.get("tracking")
    if tr:
        out["tracking"] = pick(tr, ["coverageType", "leagueName", "sportName"])
    return out


def header(h):
    out = pick(h, ["id", "season", "week"])
    comps = []
    for c in h.get("competitions", [])[:1]:
        cc = pick(c, ["id", "date", "neutralSite"])
        cc["competitors"] = []
        for cp in c.get("competitors", []):
            x = pick(cp, ["id", "order", "homeAway", "winner", "score", "record", "possession"])
            x["team"] = team(cp.get("team", {}))
            if "record" in x:
                x["record"] = [pick(r, ["type", "summary", "displayValue"]) for r in x["record"]]
            cc["competitors"].append(x)
        if "status" in c:
            cc["status"] = scrub(c["status"])
        # key order: competitors before status, as recorded
        comps.append({k: cc[k] for k in sorted(cc, key=lambda k: list(c.keys()).index(k) if k in c else 99)})
    out["competitions"] = comps
    return {k: out[k] for k in sorted(out, key=lambda k: list(h.keys()).index(k) if k in h else 99)}


def boxscore(b):
    out = {}
    for key in b:
        if key == "teams":
            out["teams"] = [{"team": team(t.get("team", {})),
                             "statistics": [pick(s, ["name", "displayValue"]) for s in t.get("statistics", [])],
                             **pick(t, ["homeAway"])} for t in b["teams"]]
        elif key == "players":
            ps = []
            for p in b["players"]:
                cats = []
                for s in p.get("statistics", []):
                    if s.get("name") not in BOX_CATEGORIES:
                        continue
                    c = pick(s, ["name", "keys", "labels"])
                    c["athletes"] = [{"athlete": pick(a.get("athlete", {}), ["id", "displayName"]),
                                      "stats": a.get("stats", [])} for a in s.get("athletes", [])[:2]]
                    cats.append(c)
                ps.append({"team": team(p.get("team", {})), "statistics": cats})
            out["players"] = ps
    return out


def scoring(plays):
    out = []
    for p in plays:
        x = pick(p, ["id", "type", "text", "awayScore", "homeScore", "period", "clock"])
        x["team"] = team(p.get("team", {}))
        out.append(scrub(x))
    return out


def leaders(ls):
    out = []
    for t in ls:
        cats = []
        for c in t.get("leaders", []):
            cc = pick(c, ["name", "displayName"])
            cc["leaders"] = [{**pick(l, ["displayValue", "value"]), "athlete": headshot(l.get("athlete", {}))}
                             for l in c.get("leaders", [])[:1]]
            cats.append(cc)
        out.append({"team": team(t.get("team", {})), "leaders": cats})
    return out


def injuries(ts):
    out = []
    for t in ts:
        items = t.get("injuries", [])
        chosen, seen = [], set()
        for i in items:  # one of each status first (so IR, Out and Questionable all appear when recorded)
            if i.get("status") not in seen:
                seen.add(i.get("status"))
                chosen.append(i)
        for i in items:
            if len(chosen) >= 3:
                break
            if i not in chosen:
                chosen.append(i)
        chosen = sorted(chosen[:3], key=items.index)
        red = []
        for i in chosen:
            x = pick(i, ["status", "date"])
            x["athlete"] = headshot(i.get("athlete", {}))
            x["athlete"].pop("headshot", None)
            if "details" in i:
                x["details"] = pick(i["details"], ["type", "location", "detail", "side", "returnDate"])
            red.append(x)
        out.append({"team": team(t.get("team", {})), "injuries": red})
    return out


def last_five(ls):
    out = []
    for t in ls:
        ev = [{**pick(e, ["id", "week", "atVs", "gameDate", "score", "homeTeamId", "awayTeamId", "homeTeamScore",
                         "awayTeamScore", "gameResult"]), "opponent": team(e.get("opponent", {}))} for e in t.get("events", [])]
        out.append({"team": team(t.get("team", {})), "events": ev})
    return out


def blank(x):
    """Every leaf replaced by a neutral value of its type: the shape stays, the content (odds) goes."""
    if isinstance(x, dict):
        return {k: blank(v) for k, v in x.items() if k not in DROP_KEYS}
    if isinstance(x, list):
        return [blank(v) for v in x[:1]]
    if isinstance(x, bool):
        return False
    if isinstance(x, (int, float)):
        return 0
    if isinstance(x, str):
        return "x"
    return x


def stub(key, v):
    if key == "drives":
        prev = (v.get("previous") or [])[:1]
        out = []
        for d in prev:
            dd = scrub(pick(d, ["id", "description", "yards", "isScore", "result", "displayResult"]))
            dd["plays"] = [scrub(pick(p, ["id", "sequenceNumber", "type", "text", "awayScore", "homeScore", "period",
                                         "clock", "scoringPlay"])) for p in d.get("plays", [])[:3]]
            out.append(dd)
        return {"previous": out}
    if key == "news":
        arts = (v.get("articles") or [])[:1]
        return {"articles": [scrub(pick(a, ["id", "type", "headline"])) for a in arts]}
    if key == "winprobability":
        return v[:1]
    if key == "pickcenter":
        return [blank(v[0])] if v else []
    return None


def summary(d, keep_videos):
    out = {}
    for k, v in d.items():
        if k in STUB_KEYS:
            out[k] = stub(k, v)
        elif k == "header":
            out[k] = header(v)
        elif k == "boxscore":
            out[k] = boxscore(v)
        elif k == "scoringPlays":
            out[k] = scoring(v)
        elif k == "leaders":
            out[k] = leaders(v)
        elif k == "gameInfo":
            out[k] = {"venue": pick(v.get("venue", {}), ["id", "fullName"])}
        elif k == "article":
            out[k] = pick(v, ["type", "headline"])
        elif k == "videos":
            vids = [x for x in v if keep_videos is None or x.get("id") in keep_videos]
            out[k] = [video(x) for x in vids]
        elif k == "lastFiveGames":
            out[k] = last_five(v)
        elif k == "injuries":
            out[k] = injuries(v)
    return out


def record(d):
    out = {}
    for k, v in d.items():
        if k == "videos":
            out[k] = [video(x) for x in v]
        elif k in ("resultsCount", "resultsLimit", "resultsOffset", "timestamp", "status"):
            out[k] = v
    return out


def board(d, events):
    out = scrub(pick(d, ["season", "week"]))
    out.get("week", {}).pop("teamsOnBye", None)
    evs = []
    for e in d.get("events", []):
        if events is not None and e.get("id") not in events:
            continue
        x = pick(e, ["id", "date", "name", "shortName", "season", "week"])
        comps = []
        for c in e.get("competitions", [])[:1]:
            cc = pick(c, ["id", "date", "attendance", "neutralSite"])
            cps = []
            for cp in c.get("competitors", []):
                y = pick(cp, ["id", "homeAway", "winner", "score", "linescores", "records"])
                y["team"] = team(cp.get("team", {}))
                if "linescores" in y:
                    y["linescores"] = [pick(l, ["value", "displayValue", "period"]) for l in y["linescores"]]
                if "records" in y:
                    y["records"] = [pick(r, ["name", "type", "abbreviation", "summary"]) for r in y["records"]]
                cps.append(y)
            cc["competitors"] = cps
            if "status" in c:
                cc["status"] = scrub(c["status"])
            if "situation" in c:
                cc["situation"] = scrub(c["situation"])
            if "broadcasts" in c:
                cc["broadcasts"] = c["broadcasts"]
            if "leaders" in c:
                cc["leaders"] = [{**pick(l, ["name", "displayName"]),
                                  "leaders": [{**pick(ll, ["displayValue", "value"]),
                                               "athlete": headshot(ll.get("athlete", {})),
                                               "team": pick(ll.get("team", {}), ["id"])} for ll in l.get("leaders", [])[:1]]}
                                 for l in c["leaders"]]
            if "highlights" in c:
                cc["highlights"] = [video(h) for h in c["highlights"]]
            comps.append(cc)
        x["competitions"] = comps
        evs.append(x)
    out["events"] = evs
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("kind", choices=["summary", "record", "board"])
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--videos", help="summary: the clip ids to keep (default: all)")
    ap.add_argument("--events", help="board: the event ids to keep (default: all)")
    a = ap.parse_args()
    with open(a.src, encoding="utf-8") as f:
        d = json.load(f)
    if a.kind == "summary":
        keep = None if not a.videos else {int(x) for x in a.videos.split(",")}
        out = summary(d, keep)
    elif a.kind == "record":
        out = record(d)
    else:
        out = board(d, None if not a.events else set(a.events.split(",")))
    text = json.dumps(out, ensure_ascii=False, indent=1) + "\n"
    size = len(text.encode("utf-8"))
    if size > MAX_BYTES:
        sys.exit(f"{a.dst}: {size} B is over the {MAX_BYTES} B fixture limit; reduce further")
    with open(a.dst, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"{a.dst}: {size} B")


if __name__ == "__main__":
    main()
