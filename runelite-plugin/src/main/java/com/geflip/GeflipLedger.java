package com.geflip;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw fill log into an HONEST flip P&amp;L by matching buys to sells (FIFO).
 * Only completed round-trips count as profit; a bought-but-unsold item is inventory
 * you hold, not a loss. Items you tag as "not a flip" (things you buy to use, like
 * potions or runes) are kept out of the flip ledger entirely. Pure logic, replayed
 * from scratch on every render — so reloading fills or changing the exclude list
 * always reflows correctly, with no drifting running total.
 */
final class GeflipLedger
{
	long realizedFlip;       // gp from matched buy->sell round-trips (net of sale tax) — FLIP profit only
	long inventoryCost;      // cost of bought-but-unsold flip items (open capital)
	long keptNet;            // net gp on excluded "I use this" items (buys - sells)
	long unmatchedProceeds;  // net gp from selling stock with NO logged buy (bank/mobile) — NOT flip
	                         // profit (no cost basis), tracked apart so it can't inflate the P&L
	int  matchedUnits;       // units that completed a round-trip
	int  openUnits;          // units held (unsold flip buys)
	int  unmatchedSellUnits; // sold with no logged buy (basis unknown; NOT counted as profit)
	int  flips;              // completed round-trips (sell events that closed >=1 unit) — CALIBRATION
	int  wins;               // of those flips, the profitable ones
	long holdSecSum;         // unit-weighted hold time (secs), for the average
	long firstTs, lastTs;    // span of the fill log (secs), for the realized gp/day rate
	// what you're still holding (bought, not yet sold): item id -> [qty, totalCost]
	final java.util.Map<Integer, long[]> holdings = new HashMap<>();
	// realized performance per item — the proof-of-edge journal:
	// id -> [realizedProfit, completedFlips, winningFlips, holdSecSum, matchedUnits]
	final java.util.Map<Integer, long[]> byItem = new HashMap<>();

	/** Your ACTUAL win-rate on completed flips (0..1). */
	double winRate() { return flips > 0 ? (double) wins / flips : 0; }
	/** Your ACTUAL average hold time, in hours, across completed round-trips. */
	double avgHoldHours() { return matchedUnits > 0 ? holdSecSum / (double) matchedUnits / 3600.0 : 0; }
	/** Calendar days the fill log spans (0 if <2 fills / no timestamps). */
	double spanDays() { return (firstTs > 0 && lastTs > firstTs) ? (lastTs - firstTs) / 86400.0 : 0; }
	/** Realized flip profit per day over that span (0 until the span is at least ~1h). */
	long realizedPerDay() { double d = spanDays(); return d >= (1.0 / 24) ? Math.round(realizedFlip / d) : 0; }

	static GeflipLedger compute(List<GeflipPlugin.Fill> fills, Set<Integer> excluded)
	{
		return compute(fills, excluded, null);
	}

	/** overrides = your manual per-item cost corrections (id -> real avg cost); applied to the
	 *  held inventory so "held" and the To-sell rows agree. Past realized profit is unchanged. */
	static GeflipLedger compute(List<GeflipPlugin.Fill> fills, Set<Integer> excluded, java.util.Map<Integer, Long> overrides)
	{
		GeflipLedger l = new GeflipLedger();
		if (fills == null) return l;
		// FIFO relies on chronological order. Live appends are ordered, but the logged-out GE reconcile
		// books deltas in SLOT-index order, which can append a SELL before its own BUY and leak the
		// round-trip to unmatchedProceeds. Stable-sort a COPY by timestamp (buys before sells on a tie,
		// since you must buy before you sell) so those cases match correctly; the caller's list is untouched.
		java.util.List<GeflipPlugin.Fill> ordered = new java.util.ArrayList<>(fills);
		ordered.sort((a, b) ->
		{
			if (a == null || b == null) return 0;
			int c = Long.compare(a.ts, b.ts);
			if (c != 0) return c;
			boolean ab = "BUY".equals(a.side), bb = "BUY".equals(b.side);
			return ab == bb ? 0 : (ab ? -1 : 1);
		});
		Map<Integer, Deque<long[]>> lots = new HashMap<>();   // id -> FIFO of [unitCost, qtyRemaining, buyTs]
		for (GeflipPlugin.Fill f : ordered)
		{
			if (f == null) continue;
			if (f.ts > 0) { if (l.firstTs == 0 || f.ts < l.firstTs) l.firstTs = f.ts; if (f.ts > l.lastTs) l.lastTs = f.ts; }
			boolean skip = excluded != null && excluded.contains(f.id);
			if ("BUY".equals(f.side))
			{
				if (skip) { l.keptNet += (long) f.price * f.qty; continue; }
				lots.computeIfAbsent(f.id, k -> new ArrayDeque<>()).addLast(new long[]{ f.price, f.qty, f.ts });
			}
			else // SELL
			{
				long taxPer = f.qty > 0 ? (long) f.tax / f.qty : 0;
				long net = f.price - taxPer;                  // proceeds per unit after the 2% tax
				if (skip) { l.keptNet -= net * f.qty; continue; }
				int remaining = f.qty;
				long sellProfit = 0; int matchedThisSell = 0;   // this round-trip's realized profit
				long sellHoldSec = 0;                            // unit-weighted hold time of THIS sell
				Deque<long[]> dq = lots.get(f.id);
				while (remaining > 0 && dq != null && !dq.isEmpty())
				{
					long[] lot = dq.peekFirst();
					int m = (int) Math.min(remaining, lot[1]);
					sellProfit += (net - lot[0]) * m;
					matchedThisSell += m;
					sellHoldSec += Math.max(0, f.ts - lot[2]) * (long) m;   // buy->sell duration, unit-weighted
					lot[1] -= m; remaining -= m;
					if (lot[1] <= 0) dq.pollFirst();
				}
				l.holdSecSum += sellHoldSec;
				l.realizedFlip += sellProfit;
				l.matchedUnits += matchedThisSell;
				if (matchedThisSell > 0)
				{
					l.flips++; if (sellProfit > 0) l.wins++;
					long[] bi = l.byItem.computeIfAbsent(f.id, k -> new long[5]);
					bi[0] += sellProfit; bi[1]++;                    // realized profit + flip count
					if (sellProfit > 0) bi[2]++;                     // winning flips
					bi[3] += sellHoldSec; bi[4] += matchedThisSell;  // hold time + units, for velocity
				}
				if (remaining > 0)                            // sold something we never logged buying
				{
					// NO cost basis → this is NOT flip profit. Selling a fang from your bank isn't a
					// +16m flip. Track the proceeds separately so they never inflate realizedFlip or
					// the per-item "Best items" journal (which is flips only).
					l.unmatchedProceeds += net * remaining;
					l.unmatchedSellUnits += remaining;
				}
			}
		}
		for (Map.Entry<Integer, Deque<long[]>> en : lots.entrySet())
			for (long[] lot : en.getValue())
			{
				l.inventoryCost += lot[0] * lot[1];
				l.openUnits += (int) lot[1];
				long[] h = l.holdings.computeIfAbsent(en.getKey(), k -> new long[2]);
				h[0] += lot[1];              // qty held
				h[1] += lot[0] * lot[1];     // total cost
			}
		// apply manual cost corrections so held-inventory cost matches what you say you paid
		if (overrides != null)
			for (Map.Entry<Integer, long[]> he : l.holdings.entrySet())
			{
				Long ov = overrides.get(he.getKey());
				if (ov == null || he.getValue()[0] <= 0) continue;
				long corrected = ov * he.getValue()[0];
				l.inventoryCost += corrected - he.getValue()[1];   // adjust the total held cost
				he.getValue()[1] = corrected;
			}
		return l;
	}
}
