package com.geflip;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Tests the capital/slot basket allocator (#3): it fills each free slot with the pick that makes the MOST
 *  this cycle (expected profit = margin × fundable qty × fill-probability), sizes by cash + buy limit + a
 *  per-item exposure cap, skips won't-fill / volatile / declining rows, never over-spends, and REFUSES to
 *  use a slot on a pick below the "worth a slot" floor (the +424-penny-flip trap). */
public class GeflipBasketTest
{
	private static GeflipScanner.Flip flip(int id, int buy, int qty, int margin, double fillProb, boolean wontFill)
	{
		GeflipScanner.Flip f = new GeflipScanner.Flip();
		f.id = id; f.buy = buy; f.quantity = qty; f.margin = margin; f.fillProb = fillProb; f.wontFill = wontFill;
		return f;
	}

	@Test
	public void fillsSlotsByValueAndRespectsCash()
	{
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 1000, 50, 500, 1.0, false));   // val 25k, wants 50 @1000 = 50k
		ranked.add(flip(2, 2000, 10, 800, 1.0, false));   // val 8k,  wants 10 @2000 = 20k
		// cash 60k, 2 slots, no per-item cap (100%)
		sc.basket(ranked, 60_000, 2, 1.0);
		assertEquals("highest-value pick takes its full 50 (50k)", 50, ranked.get(0).basketQty);
		// 10k cash left, pick 2 buy=2000 => 5 units (sized by remaining cash), still clears the floor
		assertEquals("second slot sized by remaining cash", 5, ranked.get(1).basketQty);
		long spent = (long) ranked.get(0).basketQty * 1000 + (long) ranked.get(1).basketQty * 2000;
		assertTrue("never overspends cash", spent <= 60_000);
	}

	@Test
	public void skipsWontFillRows()
	{
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 100, 50, 200, 1.0, true));     // won't fill — skip
		ranked.add(flip(2, 100, 50, 200, 1.0, false));    // gets the slot (val 10k)
		sc.basket(ranked, 100_000, 1, 1.0);
		assertEquals(0, ranked.get(0).basketQty);
		assertEquals(50, ranked.get(1).basketQty);
	}

	@Test
	public void zeroCashPicksNothing()
	{
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 100, 50, 200, 1.0, false));
		sc.basket(ranked, 0, 8, 1.0);
		assertEquals(0, ranked.get(0).basketQty);
	}

	@Test
	public void perItemCapLimitsOneSlot()
	{
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 100, 1000, 100, 1.0, false));  // wants 1000 @100 = 100k
		// cash 100k, 8 slots, 25% per-item cap → one slot capped at 25k = 250 units
		sc.basket(ranked, 100_000, 8, 0.25);
		assertEquals("per-item cap holds the slot to 25% of cash", 250, ranked.get(0).basketQty);
	}

	@Test
	public void picksHigherValueOverHigherGpH()
	{
		// the core fix: a fast penny flip must NOT beat a fatter one for a slot. Both fundable; the
		// higher expected-PROFIT pick wins the (single) slot regardless of list order.
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 3251, 2, 212, 1.0, false));    // the "+424" penny flip, listed FIRST
		ranked.add(flip(2, 5000, 20, 4000, 1.0, false));  // val 80k, listed second
		sc.basket(ranked, 1_000_000, 1, 1.0);
		assertEquals("penny flip does NOT get the slot", 0, ranked.get(0).basketQty);
		assertEquals("the fat flip gets it", 20, ranked.get(1).basketQty);
	}

	@Test
	public void refusesTrashBelowTheFloor()
	{
		// regression guard for the exact bug: on a fat bank, a +424-total slot must be REFUSED, not padded in.
		GeflipScanner sc = new GeflipScanner();
		java.util.List<GeflipScanner.Flip> ranked = new java.util.ArrayList<>();
		ranked.add(flip(1, 3251, 2, 212, 1.0, false));    // margin 212 × qty 2 = +424 expected
		sc.basket(ranked, 29_000_000, 8, 0.25);           // 29M bank → floor ≈ 14.5k
		assertEquals("a +424 slot is worthless on a 29M bank → refused", 0, ranked.get(0).basketQty);
	}
}
