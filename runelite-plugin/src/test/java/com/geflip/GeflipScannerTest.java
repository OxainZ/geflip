package com.geflip;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Locks the ported gp/hour math to the web app's numbers (cross-checked equal to the
 * JS before this was written). Pure functions, no RuneLite/network — runs under a
 * plain `./gradlew test`.
 */
public class GeflipScannerTest
{
	@Test public void tax()
	{
		assertEquals(0, GeflipScanner.saleTax(49, false));        // <50 rounds to 0
		assertEquals(1, GeflipScanner.saleTax(50, false));        // 2% floored
		assertEquals(20, GeflipScanner.saleTax(1000, false));
		assertEquals(5_000_000, GeflipScanner.saleTax(300_000_000, false)); // 5m cap
		assertEquals(0, GeflipScanner.saleTax(1_000_000, true));  // exempt item
	}

	@Test public void margin()
	{
		// sell 1000 (tax 20) buy 950 -> net 30
		assertEquals(30, GeflipScanner.netMargin(950, 1000, false));
		// exempt: no tax
		assertEquals(50, GeflipScanner.netMargin(950, 1000, true));
	}

	@Test public void trendPenalty()
	{
		assertEquals(1.0, GeflipScanner.trendPenalty(null), 1e-9);
		assertEquals(1.0, GeflipScanner.trendPenalty(-0.15), 1e-9);
		assertEquals(0.84, GeflipScanner.trendPenalty(-0.25), 1e-9);
		assertEquals(0.60, GeflipScanner.trendPenalty(-0.40), 1e-9);
		assertEquals(0.60, GeflipScanner.trendPenalty(-0.90), 1e-9); // floored
	}
}
