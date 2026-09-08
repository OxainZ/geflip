package com.geflip;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Stalled-capital detection. A 2026-09 ledger audit found 12 bulk positions idle 2-5 weeks
 * holding ~10.8M gp - barely moved in value, just not earning, and nothing surfaced them.
 * The one thing this must never do is nag about gear bought to USE, or it gets ignored.
 */
public class GeflipStuckTest
{
	private static GeflipPlugin.Hold hold(String name, int qty, long avgCost, int idleDays)
	{
		return new GeflipPlugin.Hold(1, name, qty, avgCost, 0, false, 0, idleDays);
	}

	/** The real case: 2,000 Diamond dragon bolts (e) at 2,773, untouched 35 days. */
	@Test public void flagsABulkPositionGoneQuiet()
	{
		assertTrue(hold("Diamond dragon bolts (e)", 2000, 2773, 35).stuck(7));
	}

	/** Gear is not a stalled flip - a single Mage's book you bought to use must never nag. */
	@Test public void neverFlagsSingleItemGear()
	{
		assertFalse(hold("Mage's book", 1, 3_723_158, 39).stuck(7));
		assertFalse(hold("Zombie axe", 1, 1_790_160, 400).stuck(7));
	}

	/** Something bought today is working capital, not parked. */
	@Test public void doesNotFlagFreshPositions()
	{
		assertFalse(hold("Death rune", 6000, 190, 0).stuck(7));
		assertFalse(hold("Death rune", 6000, 190, 6).stuck(7));
	}

	/** Boundary is inclusive at the threshold. */
	@Test public void boundaryIsInclusive()
	{
		assertFalse(hold("x", 100, 10, 6).stuck(7));
		assertTrue(hold("x", 100, 10, 7).stuck(7));
	}

	/** Unknown age (untracked item, idleDays -1) must not be reported as stuck. */
	@Test public void unknownAgeIsNeverStuck()
	{
		assertFalse(hold("untracked", 500, 100, -1).stuck(7));
	}

	/** Parked capital totals cost basis over stuck rows only, skipping gear and fresh stock. */
	@Test public void parkedGpCountsOnlyStalledBulk()
	{
		List<GeflipPlugin.Hold> hs = Arrays.asList(
			hold("Diamond dragon bolts (e)", 2000, 2773, 35),   // 5,546,000 stuck
			hold("Defence potion(4)", 2700, 538, 35),           // 1,452,600 stuck
			hold("Mage's book", 1, 3_723_158, 39),              // gear - excluded
			hold("Death rune", 6000, 190, 1));                  // fresh - excluded
		assertEquals(2, GeflipPlugin.stuckCount(hs, 7));
		assertEquals(5_546_000L + 1_452_600L, GeflipPlugin.parkedGp(hs, 7));
	}

	@Test public void emptyAndNullAreSafe()
	{
		assertEquals(0, GeflipPlugin.stuckCount(null, 7));
		assertEquals(0L, GeflipPlugin.parkedGp(null, 7));
		assertEquals(0L, GeflipPlugin.parkedGp(new ArrayList<>(), 7));
	}
}
