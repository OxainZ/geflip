package com.geflip;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Behaviour coaching. The scanner already coaches the PICK well; this names what is costing gp
 * RIGHT NOW. Priority order is the whole point - it must lead with the most expensive problem,
 * because only one line gets shown.
 */
public class GeflipCoachLineTest
{
	private static final long BANK = 65_000_000L;

	/** Not trading at all beats every other inefficiency, even with slots and a basket waiting. */
	@Test public void notTradingOutranksEverything()
	{
		String s = GeflipPlugin.coachLine(60_000_000L, BANK, 8, 2_300_000L, 5, 500_000L, 4);
		assertTrue(s, s.contains("No flips in 4d"));
	}

	/** With free slots and a fundable basket, name the gp on the table. */
	@Test public void freeSlotsWithABasketIsTheNextPriority()
	{
		String s = GeflipPlugin.coachLine(60_000_000L, BANK, 6, 2_300_000L, 0, 0, 0);
		assertTrue(s, s.contains("6 slots free") && s.contains("2.3m"));
	}

	/** Slots full but sitting on cash: still a leak, lower priority. */
	@Test public void idleCapitalWhenSlotsAreFull()
	{
		String s = GeflipPlugin.coachLine(40_000_000L, BANK, 0, 0, 0, 0, 0);
		assertTrue(s, s.contains("idle") && s.contains("62%"));
	}

	/** Stalled positions surface only once the louder problems are gone. */
	@Test public void stuckPositionsAreLastBeforeSilence()
	{
		String s = GeflipPlugin.coachLine(1_000_000L, BANK, 0, 0, 7, 532_438L, 0);
		assertTrue(s, s.contains("7 positions idle >14d") && s.contains("532k"));
	}

	/** Doing it right gets acknowledged rather than nagged. */
	@Test public void fullyDeployedIsPraisedNotNagged()
	{
		String s = GeflipPlugin.coachLine(2_000_000L, BANK, 0, 0, 0, 0, 0);
		assertTrue(s, s.startsWith("All slots working"));
	}

	/** Nothing worth saying means silence, not a permanent banner. */
	@Test public void staysQuietWhenThereIsNothingToSay()
	{
		// slots free but no fundable basket, little idle, nothing stuck
		assertNull(GeflipPlugin.coachLine(1_000_000L, BANK, 2, 0, 0, 0, 0));
		assertNull(GeflipPlugin.coachLine(0, 0, 0, 0, 0, 0, 0));   // unknown bankroll
	}

	/** Singular/plural must read correctly - sloppy text reads as a bug. */
	@Test public void wordingIsGrammatical()
	{
		assertTrue(GeflipPlugin.coachLine(0, BANK, 1, 500_000L, 0, 0, 0).contains("1 slot free"));
		assertTrue(GeflipPlugin.coachLine(1_000L, BANK, 0, 0, 1, 100_000L, 0).contains("1 position idle"));
	}

	/** Compact money formatting used in the coach text. */
	@Test public void moneyFormatsCompactly()
	{
		assertEquals("1.4b", GeflipPlugin.money(1_395_000_000L));
		assertEquals("13.5m", GeflipPlugin.money(13_500_000L));
		assertEquals("532k", GeflipPlugin.money(532_438L));
		assertEquals("900", GeflipPlugin.money(900L));
	}
}
