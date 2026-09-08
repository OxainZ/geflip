package com.geflip;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Whole-position P&L and "what should I close first".
 * Per-unit numbers are easy to scroll past; the total is what makes a winner act-on-able.
 */
public class GeflipPositionPnlTest
{
	private static GeflipPlugin.Hold hold(String name, int qty, long avgCost, int sellHint, boolean exempt)
	{
		return new GeflipPlugin.Hold(1, name, qty, avgCost, sellHint, exempt, 0, 0);
	}

	/** The shape that started this: bought 538, now sells 664, 2,700 of them. */
	@Test public void totalsAWholeWinningPosition()
	{
		GeflipPlugin.Hold h = hold("Defence potion(4)", 2700, 538, 664, false);
		long tax = GeflipScanner.saleTax(664, false);            // 2% floored = 13
		assertEquals((664 - tax - 538) * 2700L, h.positionPnl());
		assertTrue(h.positionPnl() > 300_000L);
	}

	/** Tax comes from the single source, never re-derived. */
	@Test public void taxDelegatesToTheScanner()
	{
		assertEquals(GeflipScanner.saleTax(664, false), hold("x", 1, 1, 664, false).unitTax());
		assertEquals(0, hold("x", 1, 1, 664, true).unitTax());   // exempt item
		assertEquals(0, hold("x", 1, 1, 49, false).unitTax());   // under the 50gp floor
	}

	/** A losing position reports a negative total, not an absolute value. */
	@Test public void losingPositionIsNegative()
	{
		GeflipPlugin.Hold h = hold("Rune brutal", 1000, 1127, 983, false);
		assertTrue(h.positionPnl() < 0);
	}

	/** No cost basis or no live price means we cannot claim a number. */
	@Test public void unknownsReportZeroRatherThanGuessing()
	{
		assertEquals(0, hold("untracked", 500, -1, 900, false).positionPnl());
		assertEquals(0, hold("no price", 500, 100, 0, false).positionPnl());
	}

	/** long math: a fat stack at a high price overflows int. */
	@Test public void doesNotOverflowOnALargeStack()
	{
		GeflipPlugin.Hold h = hold("Death rune", 1_000_000, 100, 200, true);
		assertEquals(100_000_000L, h.positionPnl());
	}

	/** bestClose picks the biggest TOTAL, not the biggest per-unit margin. */
	@Test public void bestCloseRanksByTotalNotPerUnit()
	{
		GeflipPlugin.Hold fat  = hold("Defence potion(4)", 2700, 538, 664, true);   // +340,200 total
		GeflipPlugin.Hold thin = hold("Mage's book", 1, 3_723_158, 4_691_124, true); // +967,966 total
		GeflipPlugin.Hold loss = hold("Rune brutal", 1000, 1127, 983, true);
		assertEquals(thin, GeflipPlugin.bestClose(Arrays.asList(fat, thin, loss)));
		// per-unit, fat is tiny (126/ea) but on 2700 units it still beats a small winner
		GeflipPlugin.Hold small = hold("small", 1, 100, 1000, true);                 // +900 total
		assertEquals(fat, GeflipPlugin.bestClose(Arrays.asList(fat, small, loss)));
	}

	/** Nothing profitable, empty, or null: no recommendation rather than a bad one. */
	@Test public void noWinnerMeansNoRecommendation()
	{
		List<GeflipPlugin.Hold> losers = Arrays.asList(hold("a", 10, 500, 400, true));
		assertNull(GeflipPlugin.bestClose(losers));
		assertNull(GeflipPlugin.bestClose(null));
		assertNull(GeflipPlugin.bestClose(Arrays.asList()));
	}
}
