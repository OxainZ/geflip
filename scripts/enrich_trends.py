#!/usr/bin/env python3
"""enrich_trends.py — build data/trends.json for geflip.

WHY. geflip runs client-side on GitHub Pages, so it can only reach CORS-open APIs
(the wiki real-time prices). The long-horizon TREND that separates "a dip inside an
uptrend" (buy) from "a structural death-spiral" (trap) is NOT in the wiki's short
windows, and the one API that gives it directly — the official Jagex GE API — sends
no CORS header, so a browser cannot fetch it.

This script runs SERVER-SIDE (a scheduled GitHub Action), where CORS does not apply.
It computes 30/90/180-day trends + recent volatility from the wiki /timeseries (24h
timestep = ~1 year of daily mids, consistent with the price basis geflip already
uses), for the most-liquid slice of the market, and writes data/trends.json. The
static site then fetches that file SAME-ORIGIN — no CORS, no backend, no server.

Scope: only the top-N items by 24h volume. That is the entire realistically-flippable
universe; computing trends for all ~4000 items (one /timeseries call each) would be
neither polite nor useful (illiquid items have no trend worth trading).

  python scripts/enrich_trends.py           # writes ../data/trends.json
  UNIVERSE=400 python scripts/enrich_trends.py
"""
import json, os, sys, time, urllib.request
from pathlib import Path

API = "https://prices.runescape.wiki/api/v1/osrs"
UA  = "geflip-enrich (github.com/OxainZ/geflip) - technoobob@gmail.com"
UNIVERSE = int(os.environ.get("UNIVERSE", "700"))   # top-N by 24h volume
DELAY_S  = float(os.environ.get("DELAY_S", "0.15"))  # politeness between calls
OUT = Path(__file__).resolve().parent.parent / "data" / "trends.json"


def get(url, tries=3):
    for a in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA,
                                                       "Accept": "application/json"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read().decode("utf-8"))
        except Exception as e:
            if a == tries - 1:
                raise
            time.sleep(0.8 * (a + 1))


def mid(p):
    h, l = p.get("avgHighPrice"), p.get("avgLowPrice")
    if h and l:
        return (h + l) / 2.0
    return h or l or None


def trend(series, days):
    """Fractional change: latest mid vs the mid `days` points ago (24h timestep)."""
    if len(series) <= days:
        return None
    now, then = series[-1], series[-1 - days]
    if now is None or then is None or then <= 0:
        return None
    return round((now - then) / then, 4)


def volatility(series, n=30):
    """Std of the last n daily log-ish returns — swing risk sizing."""
    xs = [x for x in series[-(n + 1):] if x and x > 0]
    if len(xs) < 8:
        return None
    rets = [(xs[i] / xs[i - 1] - 1) for i in range(1, len(xs))]
    m = sum(rets) / len(rets)
    var = sum((r - m) ** 2 for r in rets) / len(rets)
    return round(var ** 0.5, 4)


def main():
    print(f"[enrich] fetching 24h volumes to rank the universe (top {UNIVERSE})…")
    d24 = get(f"{API}/24h").get("data", {})
    # rank by the thinner-leg 24h volume (both sides must trade to flip)
    ranked = sorted(
        ((int(i), min(v.get("highPriceVolume") or 0, v.get("lowPriceVolume") or 0))
         for i, v in d24.items()),
        key=lambda kv: kv[1], reverse=True)
    ids = [i for i, vol in ranked[:UNIVERSE] if vol > 0]
    print(f"[enrich] {len(ids)} liquid items; pulling 24h timeseries for each…")

    items, done, failed = {}, 0, 0
    for id_ in ids:
        try:
            data = get(f"{API}/timeseries?timestep=24h&id={id_}").get("data", [])
        except Exception as e:
            failed += 1
            continue
        series = [mid(p) for p in data]
        clean = [x for x in series if x is not None]
        if len(clean) < 30:
            continue
        rec = {"t30": trend(series, 30), "t90": trend(series, 90),
               "t180": trend(series, 180), "vol": volatility(series),
               "n": len(clean)}
        # drop an all-null row (nothing computed) to keep the file lean
        if any(rec[k] is not None for k in ("t30", "t90", "t180")):
            items[str(id_)] = rec
        done += 1
        if done % 100 == 0:
            print(f"[enrich]   {done}/{len(ids)} (failed {failed})")
        time.sleep(DELAY_S)

    # SANITY FLOOR: never let an API outage overwrite a good trends.json with a near-empty
    # one. If we got far fewer items than the existing file, abort WITHOUT writing (nonzero
    # exit fails the Action) so the last good data stays committed.
    prev_count = 0
    if OUT.is_file():
        try:
            prev_count = len(json.loads(OUT.read_text()).get("items", {}))
        except Exception:
            prev_count = 0
    if prev_count and len(items) < 0.8 * prev_count:
        print(f"[enrich] ABORT: only {len(items)} items vs {prev_count} previously "
              f"({failed} fetch failures) — refusing to overwrite good data")
        return 1

    OUT.parent.mkdir(parents=True, exist_ok=True)
    payload = {"generated": int(time.time()), "timestep": "24h",
               "universe": len(items), "note": "30/90/180d trend (fractional) + "
               "recent daily-return volatility, from wiki /timeseries. Long trend is "
               "the death-spiral filter the client-side app cannot fetch itself.",
               "items": items}
    OUT.write_text(json.dumps(payload, separators=(",", ":")))
    print(f"[enrich] wrote {OUT} — {len(items)} items, {OUT.stat().st_size} bytes "
          f"({failed} fetch failures)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
