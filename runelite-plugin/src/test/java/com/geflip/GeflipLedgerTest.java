package com.geflip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Locks the FIFO buy->sell matching — the "honest flip P&L" that the panel and web show.
 * These are the cases the audit flagged as the highest-value untested logic.
 */
public class GeflipLedgerTest
{
	private static GeflipPlugin.Fill buy(int id, int price, int qty)
	{
		return new GeflipPlugin.Fill(id, "BUY", price, qty, 0, 0);
	}

	private static GeflipPlugin.Fill sell(int id, int price, int qty)
	{
		int tax = GeflipScanner.saleTax(price, false) * qty;   // same tax the plugin records
		return new GeflipPlugin.Fill(id, "SELL", price, qty, tax, 0);
	}

	private static List<GeflipPlugin.Fill> fills(GeflipPlugin.Fill... f)
	{
		List<GeflipPlugin.Fill> l = new ArrayList<>();
		Collections.addAll(l, f);
		return l;
	}

	@Test
	public void completeRoundTripNetsTaxOnce()
	{
		// buy 100 @100, sell 100 @150. tax/unit = floor(150*0.02)=3 -> net 147 -> +47/unit.
		GeflipLedger l = GeflipLedger.compute(fills(buy(1, 100, 100), sell(1, 150, 100)), null);
		assertEquals(4700, l.realizedFlip);
		assertEquals(100, l.matchedUnits);
		assertEquals(0, l.inventoryCost);
		assertEquals(0, l.openUnits);
	}

	@Test
	public void partialSellLeavesInventoryNotLoss()
	{
		// buy 100 @100, sell only 60 @150. 40 units remain as inventory at cost, not a loss.
		GeflipLedger l = GeflipLedger.compute(fills(buy(1, 100, 100), sell(1, 150, 60)), null);
		assertEquals(2820, l.realizedFlip);        // 47 * 60
		assertEquals(60, l.matchedUnits);
		assertEquals(4000, l.inventoryCost);       // 40 @100
		assertEquals(40, l.openUnits);
	}

	@Test
	public void excludedItemStaysOutOfFlipPnl()
	{
		// a "not a flip" item (id 2) you buy to use: goes to keptNet, never realizedFlip.
		Set<Integer> excl = new HashSet<>();
		excl.add(2);
		GeflipLedger l = GeflipLedger.compute(fills(buy(2, 5, 1000), buy(1, 100, 10), sell(1, 150, 10)), excl);
		assertEquals(5000, l.keptNet);             // 1000 @5, kept aside
		assertEquals(470, l.realizedFlip);         // only the real flip counts
		assertEquals(0, l.inventoryCost);          // the flip closed; the kept item isn't inventory
	}

	@Test
	public void unmatchedSellIsFlaggedNotHidden()
	{
		// sell with no logged buy (pre-install inventory): proceeds counted, but flagged.
		GeflipLedger l = GeflipLedger.compute(fills(sell(1, 150, 10)), null);
		assertEquals(10, l.unmatchedSellUnits);
		assertTrue(l.realizedFlip > 0);
	}

	@Test
	public void tracksCompletedFlipsWinRateAndHold()
	{
		List<GeflipPlugin.Fill> fs = new ArrayList<>();
		// win: buy 10 @100 at t=0, sell 10 @150 two hours later
		fs.add(new GeflipPlugin.Fill(1, "BUY", 100, 10, 0, 0));
		fs.add(new GeflipPlugin.Fill(1, "SELL", 150, 10, GeflipScanner.saleTax(150, false) * 10, 7200));
		// loss: buy 5 @200, sell 5 @180 one hour later
		fs.add(new GeflipPlugin.Fill(2, "BUY", 200, 5, 0, 0));
		fs.add(new GeflipPlugin.Fill(2, "SELL", 180, 5, GeflipScanner.saleTax(180, false) * 5, 3600));
		GeflipLedger l = GeflipLedger.compute(fs, null);
		assertEquals(2, l.flips);
		assertEquals(1, l.wins);
		assertEquals(0.5, l.winRate(), 1e-9);
		// unit-weighted hold: (10 units * 2h + 5 units * 1h) / 15 units = 1.667h
		assertEquals((10 * 2.0 + 5 * 1.0) / 15.0, l.avgHoldHours(), 1e-6);
	}

	@Test
	public void costOverrideCorrectsHeldInventory()
	{
		// bought 10 @100 (held). Override says you really paid 60 each.
		java.util.Map<Integer, Long> ov = new java.util.HashMap<>();
		ov.put(1, 60L);
		GeflipLedger l = GeflipLedger.compute(fills(buy(1, 100, 10)), null, ov);
		assertEquals(600, l.inventoryCost);          // 10 * 60, not 10 * 100
		assertEquals(600, l.holdings.get(1)[1]);     // holdings cost corrected too
		assertEquals(10, l.holdings.get(1)[0]);
	}

	@Test
	public void fifoMatchesOldestLotsFirst()
	{
		// two buy lots, one sell that spans both — cost basis is FIFO (30 @100, then 20 @120).
		GeflipLedger l = GeflipLedger.compute(
			fills(buy(1, 100, 30), buy(1, 120, 30), sell(1, 200, 50)), null);
		int taxUnit = GeflipScanner.saleTax(200, false);   // floor(200*0.02)=4 -> net 196
		int expect = (196 - 100) * 30 + (196 - 120) * 20;  // 2880 + 1520 = 4400
		assertEquals(expect, l.realizedFlip);
		assertEquals(50, l.matchedUnits);
		assertEquals(1200, l.inventoryCost);               // 10 @120 left
		assertEquals(4, taxUnit);
	}
}
