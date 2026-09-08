package com.geflip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Invariants on the money math — the functions where a wrong answer costs real gp.
 *
 * GeflipScannerTest already pins the happy-path values; this class pins the EDGES and the
 * properties that must hold for every price, because those are what silently break under a
 * refactor and can't be spotted by eye in a ranked list.
 */
public class GeflipMoneyMathTest
{
	private static final int TAX_CAP = 5_000_000;
	private static final int MAX_PRICE = Integer.MAX_VALUE;

	/**
	 * REGRESSION LOCK on `sell * 2L`. With a plain `sell * 2` the multiply overflows int for
	 * any sell above ~1.07b, Math.floor gets a NEGATIVE number, Math.min keeps it (it's below
	 * the cap), and netMargin then SUBTRACTS a negative tax — inflating the margin instead of
	 * reducing it. The scanner would rank a guaranteed loss as a huge win. Tax can never be
	 * negative, whatever the price.
	 */
	@Test public void taxNeverGoesNegativeOnOverflowProneInputs()
	{
		int[] hostile = { 1_000_000_000, 1_073_741_824, 2_000_000_000, MAX_PRICE };
		for (int sell : hostile)
		{
			int tax = GeflipScanner.saleTax(sell, false);
			assertTrue("tax went negative at sell=" + sell + " (int overflow): " + tax, tax >= 0);
			assertEquals("expensive items must clamp to the cap at sell=" + sell, TAX_CAP, tax);
		}
	}

	/** The 5m cap boundary, from just under to just over. */
	@Test public void taxCapBoundary()
	{
		assertEquals(4_999_999, GeflipScanner.saleTax(249_999_999, false)); // 2% = 4,999,999.98
		assertEquals(TAX_CAP,   GeflipScanner.saleTax(250_000_000, false)); // 2% = exactly the cap
		assertEquals(TAX_CAP,   GeflipScanner.saleTax(250_000_001, false)); // past it -> clamped
		assertEquals(TAX_CAP,   GeflipScanner.saleTax(900_000_000, false));
	}

	/** The floor-to-zero threshold: 2% of 49 is 0.98, so sub-50 is untaxed. */
	@Test public void taxLowEndBoundaries()
	{
		assertEquals(0, GeflipScanner.saleTax(0, false));
		assertEquals(0, GeflipScanner.saleTax(1, false));
		assertEquals(0, GeflipScanner.saleTax(49, false));
		assertEquals(1, GeflipScanner.saleTax(50, false));   // 1.00
		assertEquals(1, GeflipScanner.saleTax(99, false));   // 1.98 floored
		assertEquals(2, GeflipScanner.saleTax(100, false));  // 2.00
		assertEquals(2, GeflipScanner.saleTax(149, false));  // 2.98 floored
		assertEquals(3, GeflipScanner.saleTax(150, false));  // 3.00 — floating point must not give 2
	}

	/**
	 * Properties that must hold at EVERY price: tax is non-negative, never rounds UP past the
	 * true 2%, never exceeds the cap, and never decreases as the sale price rises. A tax that
	 * dips as price rises would make the ranker prefer a worse price.
	 */
	@Test public void taxIsMonotonicNonNegativeAndNeverOverCharges()
	{
		int prev = 0;
		for (int sell = 0; sell < 300_000; sell += 7)
		{
			int tax = GeflipScanner.saleTax(sell, false);
			assertTrue("negative tax at " + sell, tax >= 0);
			assertTrue("tax above the cap at " + sell, tax <= TAX_CAP);
			assertTrue("tax over-charges (rounded up) at " + sell, tax <= sell * 0.02 + 1e-9);
			assertTrue("tax decreased at " + sell, tax >= prev);
			prev = tax;
		}
	}

	/** Exempt items are never taxed, at any price. */
	@Test public void exemptIsAlwaysFree()
	{
		int[] prices = { 0, 49, 50, 1_000, 1_000_000, 250_000_000, MAX_PRICE };
		for (int p : prices) assertEquals("exempt taxed at " + p, 0, GeflipScanner.saleTax(p, true));
	}

	/** netMargin is exactly sell - tax - buy, with no second rounding sneaking in. */
	@Test public void netMarginIsExactlySellMinusTaxMinusBuy()
	{
		for (int buy = 1; buy < 40_000; buy += 311)
			for (int sell = buy; sell < buy + 8_000; sell += 517)
				assertEquals(sell - GeflipScanner.saleTax(sell, false) - buy,
					GeflipScanner.netMargin(buy, sell, false));
	}

	/**
	 * No free lunch: with no spread at all (buy == sell) a taxed flip must LOSE. If this ever
	 * returns >= 0 the scanner would recommend flipping items at a flat price.
	 */
	@Test public void flatPriceFlipAlwaysLosesAfterTax()
	{
		for (int p = 50; p < 200_000; p += 991)
			assertTrue("flat flip not a loss at " + p, GeflipScanner.netMargin(p, p, false) < 0);
	}

	/**
	 * The penny-flip trap, end to end: a 6 -> 7 gp item. You must overcut the buy and undercut
	 * the sell to actually fill, which inverts the spread — the round trip must not show profit.
	 */
	@Test public void pennyFlipRoundTripIsNotProfitable()
	{
		int lo = 6, hi = 7;
		int buyFill  = lo + GeflipScanner.tickSize(lo);   // overcut to get filled
		int sellFill = hi - GeflipScanner.tickSize(hi);   // undercut to get filled
		assertTrue("tick cost must eat this spread", sellFill <= buyFill);
		assertTrue("penny flip must not show a profit",
			GeflipScanner.netMargin(buyFill, sellFill, false) <= 0);
	}

	/** The undercut step: at least 1gp, ~0.05% above that, and never shrinking as price rises. */
	@Test public void tickSizeFloorAndScaling()
	{
		assertEquals(1, GeflipScanner.tickSize(0));
		assertEquals(1, GeflipScanner.tickSize(1));
		assertEquals(1, GeflipScanner.tickSize(1_000));   // 0.5 -> 1
		assertEquals(1, GeflipScanner.tickSize(2_000));   // 1.0
		assertEquals(2, GeflipScanner.tickSize(3_000));   // 1.5 -> 2
		assertEquals(50, GeflipScanner.tickSize(100_000));
		int prev = 0;
		for (int p = 0; p < 2_000_000; p += 997)
		{
			int t = GeflipScanner.tickSize(p);
			assertTrue("tick below the 1gp floor at " + p, t >= 1);
			assertTrue("tick shrank as price rose at " + p, t >= prev);
			prev = t;
		}
	}

	/** trendPenalty stays a sane multiplier: within [0.6, 1.0] and never rising as decline deepens. */
	@Test public void trendPenaltyStaysInRangeAndNeverRewardsDecline()
	{
		assertEquals(1.0, GeflipScanner.trendPenalty(0.5), 1e-9);    // rising item: no penalty
		assertEquals(1.0, GeflipScanner.trendPenalty(-0.15), 1e-9);  // exactly at the threshold
		assertTrue(GeflipScanner.trendPenalty(-0.16) < 1.0);         // just past it
		double prev = 1.0;
		for (double t = 0.0; t >= -2.0; t -= 0.01)
		{
			double pen = GeflipScanner.trendPenalty(t);
			assertTrue("penalty escaped [0.6,1.0] at t90=" + t, pen >= 0.6 - 1e-9 && pen <= 1.0 + 1e-9);
			assertTrue("penalty rose as the decline deepened at t90=" + t, pen <= prev + 1e-9);
			prev = pen;
		}
	}
}
