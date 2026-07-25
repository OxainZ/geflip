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
	long realizedFlip;       // gp from matched buy->sell round-trips (net of sale tax)
	long inventoryCost;      // cost of bought-but-unsold flip items (open capital)
	long keptNet;            // net gp on excluded "I use this" items (buys - sells)
	int  matchedUnits;       // units that completed a round-trip
	int  openUnits;          // units held (unsold flip buys)
	int  unmatchedSellUnits; // sold with no logged buy (basis unknown; counted as pure proceeds)
	int  flips;              // completed round-trips (sell events that closed >=1 unit) — CALIBRATION
	int  wins;               // of those flips, the profitable ones
	long holdSecSum;         // unit-weighted hold time (secs), for the average

	/** Your ACTUAL win-rate on completed flips (0..1). */
	double winRate() { return flips > 0 ? (double) wins / flips : 0; }
	/** Your ACTUAL average hold time, in hours, across completed round-trips. */
	double avgHoldHours() { return matchedUnits > 0 ? holdSecSum / (double) matchedUnits / 3600.0 : 0; }

	static GeflipLedger compute(List<GeflipPlugin.Fill> fills, Set<Integer> excluded)
	{
		GeflipLedger l = new GeflipLedger();
		if (fills == null) return l;
		Map<Integer, Deque<long[]>> lots = new HashMap<>();   // id -> FIFO of [unitCost, qtyRemaining, buyTs]
		for (GeflipPlugin.Fill f : fills)
		{
			if (f == null) continue;
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
				Deque<long[]> dq = lots.get(f.id);
				while (remaining > 0 && dq != null && !dq.isEmpty())
				{
					long[] lot = dq.peekFirst();
					int m = (int) Math.min(remaining, lot[1]);
					sellProfit += (net - lot[0]) * m;
					matchedThisSell += m;
					l.holdSecSum += Math.max(0, f.ts - lot[2]) * (long) m;   // buy->sell duration, unit-weighted
					lot[1] -= m; remaining -= m;
					if (lot[1] <= 0) dq.pollFirst();
				}
				l.realizedFlip += sellProfit;
				l.matchedUnits += matchedThisSell;
				if (matchedThisSell > 0) { l.flips++; if (sellProfit > 0) l.wins++; }
				if (remaining > 0)                            // sold something we never logged buying
				{
					l.realizedFlip += net * remaining;
					l.unmatchedSellUnits += remaining;
				}
			}
		}
		for (Deque<long[]> dq : lots.values())
			for (long[] lot : dq) { l.inventoryCost += lot[0] * lot[1]; l.openUnits += (int) lot[1]; }
		return l;
	}
}
