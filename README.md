# geflip — OSRS Grand Exchange flip finder

**Live: https://oxainz.github.io/geflip/**

Ranks every tradeable Old School RuneScape item by **expected gp/hour**, with the
2% GE tax, per-item buy limits, liquidity, price drift and quote staleness all priced
in. Built for the phone — one file, no build step, installs to your home screen.

Not affiliated with Jagex. Prices from the [OSRS Wiki real-time prices API](https://prices.runescape.wiki/).

---

## What it does

- **Flip scan** — pulls the whole GE, filters the junk, and ranks what's left by how
  much gold it actually earns per hour, not by raw margin (which is a trap).
- **Item lookup** — live spread, tax-correct margin, break-even sell price, and a
  price history chart for any of the ~4,000 tradeable items.
- **Swing mode** — separate scan for multi-day mean-reversion holds (z-score + RSI +
  trend slope on ~30 days of history), scored on risk/reward, not 4-hour cycles.
- **Allocation** — given your bankroll and open GE slots, it packs the best basket:
  density-greedy with a swap local search, so a big slow absorber can knock out a
  small high-ROI pick when that earns the bank more gp/hour overall.
- **Journal + calibrate** — log predicted vs realized fills for a few days, hit
  Verify, and it back-solves your real slippage so later scans stop lying to you.

## Why gp/h *and* %/h

Two items rank completely differently on the two numbers, and **the one that matters
is whichever resource you run out of first**:

- **GP/h** — what an item earns per hour. Rank by this when your **bankroll** is the
  constraint (you have gold to deploy and want the most total profit).
- **%/h** — what it earns per gp tied up. Rank by this when the **market** is the
  constraint (thin volume / buy limits cap how much you can move, so squeeze every
  coin).

The scan tells you which one is binding (idle gp is shown), so you know which column
to trust today.

## The model (what's actually priced in)

- **Tax** — 2% of the sell price (since 29 May 2025), floored, capped at 5m/item.
  Items selling ≤49 gp and a fixed exempt list (bonds + starter tools) pay nothing.
  Every margin, break-even and profit figure is **net of tax**.
- **Buy limits** — modeled as real, consecutive **4-hour windows anchored to your
  first buy** in each window (not a naive "last 4 hours", which over-counts across a
  reset). Dose variants like `Prayer potion(1..4)` correctly **share one allowance**.
- **Liquidity** — uses the *thinner* of the two legs (you need buyers *and* sellers),
  forecasts hourly flow by shrinking this hour toward the day's mean hour, and
  estimates realistic fill times per leg to flag the bottleneck (buy vs sell).
- **Quote quality** — staleness decay on the older leg's timestamp, a volume-based
  quality term, and agreement between the instant quote and the 1h average. A great
  spread that two readings disagree on gets floored, not trusted.
- **Drift** — a fat margin on a price sliding >3% in the last hour is a falling knife;
  it's penalized, because you'd hold it through your own sell leg.
- **Slippage** — compares the quoted price to what actually traded in the last 5m and
  charges only the **adverse** direction (a favorable gap is luck, not edge). Your
  journal calibrates this to your real fills over time.
- **Confidence** folds freshness × volume × agreement × drift into one 0–1 number,
  and every gp/hour figure is confidence-weighted so a shaky read can't top the board.

## Using it

1. **Config** — set your bankroll, GE slots, F2P/members, and the volume/margin floors.
   Leave slippage at 0 until you've journaled a few days, then set it to about half of
   (predicted − realized) ROI.
2. **Scan** — hit Scan. Sort by GP/h or %/h (tap the header). Filter by name.
3. **Place offers** — each pick shows the patient rest price and the "competitive" price
   (one tick in front of the queue), plus break-even and the cost to check the spread.
4. **Journal** — after your flips fill, log the result and hit Verify to calibrate.

## Privacy

Everything — config, journal, positions — lives in your browser's `localStorage`.
Nothing is uploaded. Clear your browser data and it's gone (the journal is the only
thing that can't be refetched, so export before you wipe).

## Tech

Single `index.html`, no dependencies, no build. Served static on GitHub Pages. A full
scan fetches 4 wiki endpoints (`latest`, `1h`, `5m`, `24h`) and caches the item
mapping for 24h; the two optional endpoints degrade gracefully on a weak signal, and
**Lite mode** halves the data for a bad connection. `osrsflip.pyz` is a Python
command-line build of the same idea.

> Play fair. This is a decision aid for manual flipping — no automation, no botting.
