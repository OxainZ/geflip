package com.geflip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Savings-goal ETA. Uses the REALISED rate on purpose: at the measured 11,319 gp/day a
 * 1.33b Twisted bow gap is ~322 years, and seeing that is the whole point - it says the
 * constraint is turnover, not bankroll.
 */
public class GeflipGoalTest
{
	/** The real case: 65m held, 1.395b bow, 11,319 gp/day realised. */
	@Test public void theTwistedBowRealityCheck()
	{
		long gap = 1_395_000_000L - 65_000_000L;
		long days = GeflipPlugin.daysToGoal(gap, 11_319L);
		assertTrue("expected centuries, got " + days / 365 + " yr", days / 365 > 300);
	}

	/** Rounds UP: a partial day still needs that day. */
	@Test public void roundsUpToWholeDays()
	{
		assertEquals(1, GeflipPlugin.daysToGoal(1, 1_000_000));
		assertEquals(2, GeflipPlugin.daysToGoal(1_000_001, 1_000_000));
		assertEquals(10, GeflipPlugin.daysToGoal(10_000_000, 1_000_000));
	}

	/** Already affordable is 0 days, never a negative countdown. */
	@Test public void noGapMeansDoneNow()
	{
		assertEquals(0, GeflipPlugin.daysToGoal(0, 5_000));
		assertEquals(0, GeflipPlugin.daysToGoal(-50_000_000, 5_000));
	}

	/** No earning rate cannot be answered - -1, never a divide-by-zero or a fake date. */
	@Test public void noRateIsUnanswerableNotInfinite()
	{
		assertEquals(-1, GeflipPlugin.daysToGoal(1_000_000, 0));
		assertEquals(-1, GeflipPlugin.daysToGoal(1_000_000, -10));
	}

	/** Big gaps must not overflow: a 1.3b gap at 1 gp/day is 1.3b days. */
	@Test public void hugeGapsStayExact()
	{
		assertEquals(1_330_000_000L, GeflipPlugin.daysToGoal(1_330_000_000L, 1));
	}

	/** Faster earning shortens the wait monotonically. */
	@Test public void moreGpPerDayIsAlwaysSooner()
	{
		long gap = 1_330_000_000L;
		long prev = Long.MAX_VALUE;
		for (long rate : new long[]{ 11_319L, 100_000L, 1_000_000L, 5_000_000L, 20_000_000L })
		{
			long d = GeflipPlugin.daysToGoal(gap, rate);
			assertTrue(d < prev);
			prev = d;
		}
		assertEquals(67, GeflipPlugin.daysToGoal(gap, 20_000_000L));
	}
}
