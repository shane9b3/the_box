#!/usr/bin/env python3
"""Reference model and replay of the CHL power-play derivation (docs/design/m6-chl-leagues.md §5).

This is the executable form of §5.3 / §5.6, kept so the Kotlin `ChlPowerPlay` can be checked against it and so the
replay numbers in the design can be reproduced. Pure standard library; reads the recorded Firebase captures.

Usage (from the repo root):
  python3 -I tools/chl/pp_replay.py goals    # derived advantage at every goal second vs the feed's PP / SH flags
  python3 -I tools/chl/pp_replay.py ends     # modelled end of every flagged minor vs its recorded TimeOn (OHL/WHL)
  python3 -I tools/chl/pp_replay.py probe ohl 29185 1:10:30 ...   # state at (period:mm:ss elapsed)
  python3 -I tools/chl/pp_replay.py all
  python3 -I tools/chl/pp_replay.py live <folder> [--ot-len 1200]   # a QUICK CHECK of a record_live.py recording
    (the Python model against itself, H3/H6/H8/H9 only). **Not the release gate**: the S8 gate runs the shipped Kotlin
    mapper, `ChlLiveGateTest` (CHL_LIVE_RECORDINGS=<folder> ./gradlew :core:test --tests '*ChlLiveGateTest*'; see
    tools/chl/README.md).
Optional: --dir <captures dir> (default core/src/testFixtures/resources/sports/chl/firebase/finals-2026-11-13).

Model (one-second steps; the Kotlin version may be event-driven, the rules are the same):
  1 counted = PowerPlay == 1, PenaltyShot != 1, Minutes in {2, 4, 5}, Period >= 1, a parsable start (4 = two links).
  2 start = TimeStart, else Time; S2: a TimeOnSet row whose TimeOn is not after its TimeStart (and the penalty would end
    inside the period) is inconsistent: its start is Time.
  3 chain = same (side, period, Time, PenalizedPlayerId), the player id non-zero (0 = a bench minor: never chained);
    links ordered by (start, major first, LSEventId) (S2 fix pass: a major and a minor at one start serve the major
    first); a later link starts at the previous link's end, its own TimeStart only orders it (S1 fix pass).
  4 at most 2 serving per side; a further row waits for a seat.
  5 end = start + 60 x minutes; 6 a recorded TimeOn inside (start, end] caps it (unless --no-timeon).
  7 at a goal second (before expiries) the scorer's opponent, if it has more serving, loses its earliest-started serving
    2-minute row (tie: lowest id); penalty-shot goals release nothing.
  8 advantage = the side with fewer serving; TWO_MAN when the difference is >= 2.
  H9 hide while an unflagged 2- or 5-minute row, left after pairing unflagged rows of equal minutes across the two
    sides at the same (period, Time), sits at the same (period, Time) as a counted row of the other side and is within
    its own duration.
"""
import json
import os
import sys
from collections import Counter, defaultdict

REG = 1200
OT_REG = 300
DEFAULT_DIR = "core/src/testFixtures/resources/sports/chl/firebase/finals-2026-11-13"
LEAGUES = ["ohl", "whl", "lhjmq"]


def mmss(s):
    try:
        m, x = str(s).split(":")
        return int(m) * 60 + int(x)
    except (ValueError, TypeError):
        return None


def plen(p, ot_len=OT_REG):
    return REG if p <= 3 else ot_len


def g(p, el, ot_len=OT_REG):
    return sum(plen(q, ot_len) for q in range(1, p)) + el


def side_of(home):
    return "H" if home else "A"


def counted_rows(pens, ot_len=OT_REG, use_timeon=True):
    rows = []
    for p in pens.values():
        if p.get("PowerPlay") != 1 or p.get("PenaltyShot") == 1 or p.get("Minutes") not in (2, 4, 5):
            continue
        per = int(p.get("Period") or 0)
        if per < 1:
            continue
        t = mmss(p.get("Time"))
        st = mmss(p.get("TimeStart")) if p.get("TimeStartSet", 1) != 0 else None
        on = mmss(p.get("TimeOn")) if p.get("TimeOnSet") == 1 else None
        mins = p["Minutes"]
        # S2: TimeOn not after TimeStart, for a penalty that would end inside its period, is an inconsistent row.
        if st is not None and on is not None and on <= st and st + 60 * mins <= plen(per, ot_len):
            st = None
        start = st if st is not None else t
        if start is None:
            continue
        pid = p.get("PenalizedPlayerId") or 0
        key = (p.get("Home"), per, p.get("Time"), pid) if pid else ("solo", p.get("LSEventId"))
        for i, m in enumerate([2, 2] if mins == 4 else [mins]):
            rows.append(dict(side=side_of(p.get("Home") == 1), s=g(per, start, ot_len), dur=m * 60, minor=m == 2,
                             key=key, id=(p.get("LSEventId") or 0, i), on=on if use_timeon else None, per=per))
    # links in (start, major first, id) order (S2); the chain position follows that order
    rows.sort(key=order)
    seen = set()
    for r in rows:
        r["first"] = r["key"] not in seen  # S1: only a chain's first link waits for its own start
        seen.add(r["key"])
    return rows


def order(r):
    """The row order: start, then a major before a minor (S2), then LSEventId and link."""
    return (r["s"], r["minor"], r["id"])


def h9_windows(pens, ot_len=OT_REG):
    """[(side_of_counted_row, start, end)] windows in which the bar is hidden (S1)."""
    groups = defaultdict(list)
    for p in pens.values():
        per = int(p.get("Period") or 0)
        if per < 1 or p.get("PenaltyShot") == 1:
            continue
        groups[(per, p.get("Time"))].append(p)
    out = []
    for (per, time), ps in groups.items():
        t = mmss(time)
        if t is None:
            continue
        counted = [p for p in ps if p.get("PowerPlay") == 1 and p.get("Minutes") in (2, 4, 5)]
        if not counted:
            continue
        unflagged = [p for p in ps if p.get("PowerPlay") != 1 and p.get("Minutes") in (2, 5)]
        left = []
        for m in (2, 5):
            hs = [p for p in unflagged if p.get("Minutes") == m and p.get("Home") == 1]
            aw = [p for p in unflagged if p.get("Minutes") == m and p.get("Home") != 1]
            k = min(len(hs), len(aw))
            left += hs[k:] + aw[k:]
        for u in left:
            u_side = side_of(u.get("Home") == 1)
            if any(side_of(c.get("Home") == 1) != u_side for c in counted):
                s = g(per, t, ot_len)
                out.append((s, s + 60 * u["Minutes"]))
    return out


class Sim:
    """Runs one game; afterwards state(t) answers (adv_side, kind, seconds, hidden) for any second."""

    def __init__(self, rows, goals, hides, ot_len=OT_REG, end=None):
        self.rows, self.goals, self.hides = rows, sorted(goals, key=lambda x: x["t"]), hides
        self.end = end if end is not None else max([r["s"] for r in rows] + [x["t"] for x in goals] + [0]) + 900
        self.snap = {}
        self.run()

    def run(self):
        serving = {"A": [], "H": []}
        pending = list(self.rows)
        busy = Counter()
        done_links = defaultdict(list)
        gi = 0
        for t in range(0, self.end + 1):
            while gi < len(self.goals) and self.goals[gi]["t"] == t:
                gl = self.goals[gi]
                gi += 1
                a, h = len(serving["A"]), len(serving["H"])
                gl["adv"] = None if a == h else ("H" if a > h else "A")
                gl["hidden"] = self.hidden(t)
                opp = "A" if gl["side"] == "H" else "H"
                if not gl["ps"] and len(serving[opp]) > len(serving[gl["side"]]):
                    minors = [r for r in serving[opp] if r["minor"]]
                    if minors:
                        min(minors, key=lambda r: (r["start"], r["id"]))["e"] = t
            for sd in "AH":
                for r in list(serving[sd]):
                    if r["e"] <= t:
                        serving[sd].remove(r)
                        busy[r["key"]] -= 1
            progressed = True
            while progressed:
                progressed = False
                for r in pending:
                    prev_ok = busy[r["key"]] == 0 and all(
                        x.get("e") is not None and x["e"] <= t for x in self.rows
                        if x["key"] == r["key"] and order(x) < order(r))
                    if (r["s"] <= t or not r["first"]) and len(serving[r["side"]]) < 2 and prev_ok:
                        r["start"] = t
                        e = t + r["dur"]
                        if r["on"] is not None:
                            for pp in (r["per"], r["per"] + 1):
                                x = g(pp, r["on"])
                                if t < x <= e:
                                    e = x
                                    break
                        r["e"] = e
                        serving[r["side"]].append(r)
                        busy[r["key"]] += 1
                        pending.remove(r)
                        progressed = True
                        break
            self.snap[t] = (len(serving["A"]), len(serving["H"]),
                            [self.seat_end(r, serving, pending) for r in serving["A"]],
                            [self.seat_end(r, serving, pending) for r in serving["H"]])

    @staticmethod
    def seat_end(r, serving, pending):
        """§5.3 step 9: the seat's end as computed now: the serving row's end, extended by every row that follows on in
        the same seat with no gap (the next link of its chain, else a waiting row of the side while the other seat is
        still busy)."""
        end = r["e"]
        side = r["side"]
        key = r["key"]
        others = [x["e"] for x in serving[side] if x is not r]
        queue = sorted((x for x in pending if x["side"] == side), key=order)
        used = set()
        while True:
            nxt = next((x for x in queue if id(x) not in used and x["key"] == key and (x["s"] <= end or not x["first"])), None)
            if nxt is None and others and min(others) > end:
                nxt = next((x for x in queue if id(x) not in used and x["key"] != key and x["s"] <= end
                            and all(y["key"] != x["key"] or id(y) in used or order(y) >= order(x)
                                    for y in queue)), None)
            if nxt is None:
                return end
            used.add(id(nxt))
            key = nxt["key"]
            end += nxt["dur"]

    def hidden(self, t):
        return any(s <= t < e for (s, e) in self.hides)

    def state(self, t):
        a, h, ea, eh = self.snap.get(t, (0, 0, [], []))
        if a == h:
            return None
        adv = "H" if a > h else "A"
        short_ends = ea if adv == "H" else eh
        kind = "TWO_MAN" if abs(a - h) >= 2 else "ONE_MAN"
        return adv, kind, min(short_ends) - t, self.hidden(t)


def load(d, league, node):
    path = os.path.join(d, f"{league}_{node}.json")
    with open(path) as f:
        return json.load(f) or {}


def game_inputs(d, league, gid, use_timeon=True):
    pens = ((load(d, league, "penalties").get(gid) or {}).get("GamePenalties")) or {}
    goals = []
    for x in (((load(d, league, "goals").get(gid) or {}).get("GameGoals")) or {}).values():
        per, t = int(x["Period"]), mmss(x["Time"])
        if t is None:
            continue
        goals.append(dict(side=side_of(x["IsHome"]), t=g(per, t), pp=x["PowerPlay"] == 1, sh=x["ShortHanded"] == 1,
                          ps=x["PenaltyShot"] == 1, en=x["EmptyNet"] == 1, per=per, time=x["Time"]))
    return pens, goals


def cmd_goals(d, use_timeon=True):
    res, misses = Counter(), []
    for L in LEAGUES:
        pen_all = load(d, L, "penalties")
        for gid in load(d, L, "goals"):
            if gid not in pen_all:
                res[(L, "no penalties node")] += 1
                continue
            pens, goals = game_inputs(d, L, gid, use_timeon)
            Sim(counted_rows(pens, use_timeon=use_timeon), goals, h9_windows(pens))
            for x in goals:
                a = x["adv"]
                kind = "PP" if x["pp"] else "SH" if x["sh"] else "EV"
                ok = (a == x["side"]) if kind == "PP" else (a is not None and a != x["side"]) if kind == "SH" else a is None
                res[(L, kind, "ok" if ok else "miss")] += 1
                if x["hidden"]:
                    res[(L, kind, "hidden by H9")] += 1
                if not ok:
                    misses.append((L, gid, x["per"], x["time"], kind, "model advantage:", a))
    for k, v in sorted(res.items()):
        print(*k, v)
    tot = Counter()
    for (L, *rest), v in res.items():
        if len(rest) == 2 and rest[1] in ("ok", "miss"):
            tot[(rest[0], rest[1])] += v
    print("TOTAL", " · ".join(f"{k} {tot[(k, 'ok')]}/{tot[(k, 'ok')] + tot[(k, 'miss')]}" for k in ("PP", "SH", "EV")))
    for m in misses:
        print("miss", *m)


def cmd_ends(d):
    """Duration audit: the model (without TimeOn, so it is independent of it) vs each flagged minor's recorded TimeOn."""
    res, diffs = Counter(), []
    for L in LEAGUES:
        for gid in load(d, L, "penalties"):
            pens, goals = game_inputs(d, L, gid)
            rows = counted_rows(pens, use_timeon=False)
            Sim(rows, goals, [])
            byid = defaultdict(list)
            for r in rows:
                byid[r["id"][0]].append(r)
            for p in pens.values():
                if p.get("TimeOnSet") != 1 or p.get("PowerPlay") != 1 or p.get("Minutes") != 2:
                    continue
                rs = byid.get(p["LSEventId"])
                if not rs or rs[-1].get("e") is None:
                    res[(L, "not started")] += 1
                    continue
                on, per = mmss(p["TimeOn"]), int(p["Period"])
                d0 = min(abs(rs[-1]["e"] - g(q, on)) for q in (per, per + 1))
                k = "match" if d0 == 0 else ("within 5 s" if d0 <= 5 else "DIFF")
                res[(L, k)] += 1
                if k == "DIFF":
                    diffs.append((L, gid, per, p["Time"], p.get("TimeStart"), p["TimeOn"], "model end", divmod(rs[-1]["e"], REG)))
    for k, v in sorted(res.items()):
        print(*k, v)
    n = sum(v for (L, k), v in res.items() if k in ("match", "within 5 s", "DIFF"))
    m = sum(v for (L, k), v in res.items() if k == "match")
    print(f"TOTAL ends match {m}/{n}")
    for x in diffs:
        print("diff", *x)


def parse_at(s):
    p, m, x = s.split(":")
    return g(int(p), int(m) * 60 + int(x))


def cmd_probe(d, league, gid, ats):
    pens, goals = game_inputs(d, league, gid)
    sim = Sim(counted_rows(pens), goals, h9_windows(pens))
    for s in ats:
        t = parse_at(s)
        st = sim.state(t)
        print(f"{league} {gid} at P{s}: " + ("even" if st is None else
              f"{'HOME' if st[0] == 'H' else 'AWAY'} {st[1]} {st[2]} s" + (" (HIDDEN, H9)" if st[3] else "")))


LIVE_WRONG_SHARE_MAX = 0.05   # S8 gate (proposed, O1): wrong-bar seconds <= 5 % of the final model's PP seconds
LIVE_WRONG_STRETCH_MAX = 30   # and no single wrong stretch longer than 30 s


def live_polls(folder):
    """The recorded polls of `record_live.py` rebuilt: [(unix, {node: body})], each poll with every node's latest body."""
    cur, out = {}, []
    with open(os.path.join(folder, "polls.jsonl")) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            p = json.loads(line)
            cur.update(p.get("nodes") or {})
            failed = {n for n, st in (p.get("status") or {}).items() if st not in (200, 304)}
            out.append((p["unix"], dict(cur), failed))
    return out


def poll_inputs(nodes, ot_len):
    pens = ((nodes.get("penalties") or {}).get("GamePenalties")) or {}
    goals = []
    for x in (((nodes.get("goals") or {}).get("GameGoals")) or {}).values():
        try:
            per, t = int(x["Period"]), mmss(x["Time"])
        except (KeyError, TypeError, ValueError):
            continue
        if t is None:
            continue
        goals.append(dict(side=side_of(x.get("IsHome") == 1), t=g(per, t, ot_len), pp=x.get("PowerPlay") == 1,
                          sh=x.get("ShortHanded") == 1, ps=x.get("PenaltyShot") == 1, en=x.get("EmptyNet") == 1,
                          per=per, time=x["Time"]))
    return pens, goals


def poll_now(nodes, ot_len):
    """Game time now (§5.2), or None when the poll is not in play (pre-game, a shootout, final, no clock)."""
    rc = nodes.get("runningclock") or {}
    pc = nodes.get("publishedclock") or {}
    gs = nodes.get("goalssummary") or {}
    if pc.get("Final") is True or rc.get("status_id") in (1, 4, 5) or gs.get("Shootout") is True:
        return None
    c = rc.get("Clock") or {}
    try:
        p = int(c.get("period"))
        rem = int(c.get("Minutes")) * 60 + int(c.get("Seconds"))
    except (TypeError, ValueError):
        return None
    if p < 1 or not 0 <= rem <= plen(p, ot_len):
        return None
    if rem == plen(p, ot_len) and p >= 2 and not c.get("Running"):  # S12: "20:00 3rd" during the break
        return g(p - 1, plen(p - 1, ot_len), ot_len)
    return g(p, plen(p, ot_len) - rem, ot_len)


def live_state(nodes, failed, now, ot_len):
    """What the app would show at [now] from this poll: None (no bar), "hidden", or (side, kind)."""
    if "penalties" in failed or "goals" in failed:
        return "hidden"  # H8
    pens, goals = poll_inputs(nodes, ot_len)
    rows = counted_rows(pens, ot_len)
    if any(r["s"] > now + 5 for r in rows):
        return "hidden"  # H3
    sim = Sim(rows, goals, h9_windows(pens, ot_len), ot_len, end=now + 1)
    st = sim.state(now)
    if st is None:
        return None
    gs = nodes.get("goalssummary") or {}
    total = (gs.get("VisitorGoalTotal") or 0) + (gs.get("HomeGoalTotal") or 0)
    if len(goals) != total:
        return "hidden"  # H6
    if st[3]:
        return "hidden"  # H9
    return st[0], st[1]


def cmd_live(folder, ot_len=OT_REG):
    """A quick check (not the S8 release gate, which is the Kotlin `ChlLiveGateTest`): the Python derivation poll by poll
    against the model on the game's final data (the last poll's nodes)."""
    polls = live_polls(folder)
    if not polls:
        print("no polls")
        return 1
    with open(os.path.join(folder, "meta.json")) as f:
        meta = json.load(f)
    interval = float(meta.get("interval_s") or 5)
    fpens, fgoals = poll_inputs(polls[-1][1], ot_len)
    ends = [r["s"] for r in counted_rows(fpens, ot_len)] + [x["t"] for x in fgoals]
    final = Sim(counted_rows(fpens, ot_len), fgoals, h9_windows(fpens, ot_len), ot_len, end=max(ends + [0]) + 5000)
    wrong = hidden = missing = model_pp = 0.0
    stretch = longest = 0.0
    for i, (unix, nodes, failed) in enumerate(polls):
        now = poll_now(nodes, ot_len)
        if now is None:
            stretch = 0.0
            continue
        dt = min((polls[i + 1][0] - unix) if i + 1 < len(polls) else interval, 2 * interval)
        f = final.state(now)
        f = None if f is None or f[3] else (f[0], f[1])
        shown = live_state(nodes, failed, now, ot_len)
        if f is not None:
            model_pp += dt
        if shown == "hidden":
            hidden += dt
            stretch = 0.0
        elif shown is None:
            if f is not None:
                missing += dt
            stretch = 0.0
        elif shown != f:
            wrong += dt
            stretch += dt
            longest = max(longest, stretch)
        else:
            stretch = 0.0
    share = wrong / model_pp if model_pp else 0.0
    ok = share <= LIVE_WRONG_SHARE_MAX and longest <= LIVE_WRONG_STRETCH_MAX
    print(f"{meta.get('league')} {meta.get('game')}: {len(polls)} polls; final-model PP {model_pp:.0f} s; "
          f"wrong-bar {wrong:.0f} s ({100 * share:.1f} %), longest wrong stretch {longest:.0f} s; "
          f"hidden {hidden:.0f} s; missing {missing:.0f} s")
    print(("PASS" if ok else "FAIL") + f" (quick check: wrong <= {100 * LIVE_WRONG_SHARE_MAX:.0f} %, stretch <= {LIVE_WRONG_STRETCH_MAX} s;"
          " the release gate is ChlLiveGateTest)")
    return 0 if ok else 2


def main(argv):
    if len(argv) > 1 and argv[1] == "live":
        # pp_replay.py live <recording folder> [--ot-len 1200]: the S8 live gate on one record_live.py recording.
        ot = int(argv[argv.index("--ot-len") + 1]) if "--ot-len" in argv else OT_REG
        sys.exit(cmd_live(argv[2], ot))
    d = DEFAULT_DIR
    if "--dir" in argv:
        i = argv.index("--dir")
        d = argv[i + 1]
        argv = argv[:i] + argv[i + 2:]
    use_timeon = "--no-timeon" not in argv
    argv = [a for a in argv if a != "--no-timeon"]
    cmd = argv[1] if len(argv) > 1 else "all"
    if cmd in ("goals", "all"):
        cmd_goals(d, use_timeon)
    if cmd in ("ends", "all"):
        cmd_ends(d)
    if cmd == "probe":
        cmd_probe(d, argv[2], argv[3], argv[4:])
    if cmd == "all":
        for L, gid, ats in [("lhjmq", "32755", ["1:17:30", "1:19:30"]), ("lhjmq", "32721", ["3:04:00"]),
                            ("ohl", "29185", ["1:06:30", "1:10:30"]), ("whl", "1023281", ["2:05:30", "2:08:00"]),
                            ("ohl", "29179", ["2:15:10", "2:16:00", "2:18:00"])]:
            cmd_probe(d, L, gid, ats)


if __name__ == "__main__":
    main(sys.argv)
