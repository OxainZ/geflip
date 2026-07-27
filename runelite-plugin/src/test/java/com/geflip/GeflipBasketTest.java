package com.geflip;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Tests the capital/slot basket allocator (#3): it fills slots top-down, sizes by cash and buy limit,
 *  skips won't-fill rows, and never over-spends. */
public class GeflipBasketTest
{
	private static GeflipScanner.Flip flip(int id, int buy, int qty, double expGph, boolean wontFill)
	{
		GeflipScanner.Flip f = new GeflipScanner.Flip();
		f.id = id; f.buy = buy; f.quantity = qty; f.expGph = expGph; f.wontFill = wontFill;
		return f;
	}

	@Test
	public void fillsSlotsAndRespectsCashAndLimit()
	{
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 100, 50, 1000, false));   // wants 50 @100 = 5000
		ranked.add(flip(2, 200, 10, 900, false));    // wants 10 @200 = 2000
		ranked.add(flip(3, 50, 1000, 800, false));   // huge qty, cheap
		// cash 6000, 2 slots, no per-item cap (100%)
		sc.basket(ranked, 6000, 2, 1.0);
		assertEquals("slot 1 takes its full 50 (5000)", 50, ranked.get(0).basketQty);
		// 1000 cash left, slot 2 buy=200 => 5 units
		assertEquals("slot 2 sized by remaining cash", 5, ranked.get(1).basketQty);
		assertEquals("only 2 slots => third untouched", 0, ranked.get(2).basketQty);
		long spent = (long) ranked.get(0).basketQty * 100 + (long) ranked.get(1).basketQty * 200;
		assertTrue("never overspends cash", spent <= 6000);
	}

	@Test
	public void skipsWontFillRows()
	{
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 100, 50, 1000, true));    // won't fill — skip
		ranked.add(flip(2, 100, 50, 900, false));    // gets the slot
		sc.basket(ranked, 100000, 1, 1.0);
		assertEquals(0, ranked.get(0).basketQty);
		assertEquals(50, ranked.get(1).basketQty);
	}

	@Test
	public void zeroCashPicksNothing()
	{
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 100, 50, 1000, false));
		sc.basket(ranked, 0, 8, 1.0);
		assertEquals(0, ranked.get(0).basketQty);
	}

	@Test
	public void perItemCapLimitsOneSlot()
	{
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 100, 1000, 1000, false));   // wants 1000 @100 = 100k
		// cash 100k, 8 slots, 25% per-item cap → one slot capped at 25k = 250 units
		sc.basket(ranked, 100000, 8, 0.25);
		assertEquals("per-item cap holds the slot to 25% of cash", 250, ranked.get(0).basketQty);
	}
}
