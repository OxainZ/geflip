package com.geflip;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The ledger dedup guard. A second client (or a relaunch over a live one) starts its
 * slotBookedQty counters at zero and re-books the whole filled quantity — the 2026-09 audit
 * found 56 such records, 23% of fills.json. Those corrupt realised P&L and the per-item
 * personalisation that scales the ranking, so the guard has to hold without eating real trades.
 */
public class GeflipDedupTest
{
	private static final long W = 120;

	private static List<GeflipPlugin.Fill> ledger(GeflipPlugin.Fill... f)
	{
		List<GeflipPlugin.Fill> l = new ArrayList<>();
		for (GeflipPlugin.Fill x : f) l.add(x);
		return l;
	}

	private static GeflipPlugin.Fill fill(int id, String side, int price, int qty, long ts)
	{
		return new GeflipPlugin.Fill(id, side, price, qty, 0, ts);
	}

	/** The exact shape from the audit: identical fill re-booked ~60s later. */
	@Test public void catchesTheSecondClientRebook()
	{
		List<GeflipPlugin.Fill> l = ledger(fill(21932, "BUY", 2824, 3153, 1_000));
		assertTrue(GeflipPlugin.isDuplicateFill(l, fill(21932, "BUY", 2824, 3153, 1_060), W));
	}

	/** Same second is still a duplicate (the tightest observed gap was 12s). */
	@Test public void catchesAnInstantRebook()
	{
		List<GeflipPlugin.Fill> l = ledger(fill(4153, "BUY", 104511, 70, 5_000));
		assertTrue(GeflipPlugin.isDuplicateFill(l, fill(4153, "BUY", 104511, 70, 5_012), W));
	}

	/** Outside the window it is a genuine re-buy — the audit deliberately excluded >1h repeats. */
	@Test public void allowsAGenuineRebuyLater()
	{
		List<GeflipPlugin.Fill> l = ledger(fill(4153, "BUY", 104511, 70, 5_000));
		assertFalse(GeflipPlugin.isDuplicateFill(l, fill(4153, "BUY", 104511, 70, 5_000 + 3600), W));
	}

	/** A real partial fill differs in quantity — must NOT be suppressed. */
	@Test public void allowsADifferentQuantity()
	{
		List<GeflipPlugin.Fill> l = ledger(fill(12938, "SELL", 11800, 120, 9_000));
		assertFalse(GeflipPlugin.isDuplicateFill(l, fill(12938, "SELL", 11800, 25, 9_030), W));
	}

	/** Price moved — a different trade. */
	@Test public void allowsADifferentPrice()
	{
		List<GeflipPlugin.Fill> l = ledger(fill(12938, "SELL", 11800, 120, 9_000));
		assertFalse(GeflipPlugin.isDuplicateFill(l, fill(12938, "SELL", 11700, 120, 9_030), W));
	}

	/** The opposite leg of a flip is never a duplicate of its buy. */
	@Test public void neverConfusesBuyWithSell()
	{
		List<GeflipPlugin.Fill> l = ledger(fill(21000, "BUY", 13631425, 1, 7_000));
		assertFalse(GeflipPlugin.isDuplicateFill(l, fill(21000, "SELL", 13631425, 1, 7_005), W));
	}

	/** Different item entirely. */
	@Test public void allowsADifferentItem()
	{
		List<GeflipPlugin.Fill> l = ledger(fill(4153, "BUY", 2824, 3153, 3_000));
		assertFalse(GeflipPlugin.isDuplicateFill(l, fill(21932, "BUY", 2824, 3153, 3_030), W));
	}

	/** Scans back past intervening fills, and stops at the window rather than the whole history. */
	@Test public void scansPastInterveningFillsButStopsAtTheWindow()
	{
		List<GeflipPlugin.Fill> l = ledger(
			fill(555, "BUY", 100, 10, 1_000),
			fill(4153, "BUY", 104511, 70, 1_010),
			fill(777, "SELL", 900, 5, 1_020));
		assertTrue(GeflipPlugin.isDuplicateFill(l, fill(4153, "BUY", 104511, 70, 1_050), W));
		// same fill, but now far outside the window
		assertFalse(GeflipPlugin.isDuplicateFill(l, fill(4153, "BUY", 104511, 70, 99_000), W));
	}

	@Test public void emptyLedgerIsNeverADuplicate()
	{
		assertFalse(GeflipPlugin.isDuplicateFill(new ArrayList<>(), fill(1, "BUY", 1, 1, 1), W));
	}
}
