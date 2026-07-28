package com.geflip;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Sanity for the hour-of-week activity logger: recording, peak-relative %, and CSV round-trip. */
public class MarketClockTest
{
	@Test
	public void needsEnoughDistinctHoursBeforeReporting()
	{
		MarketClock c = new MarketClock();
		for (int i = 0; i < 5; i++) c.record(i, 100);   // only 5 filled bins
		assertEquals("under 6 filled hours → not enough data", -1, c.activityPct(0));
	}

	@Test
	public void peakRelativePercent()
	{
		MarketClock c = new MarketClock();
		for (int i = 0; i < 10; i++) c.record(i, (i + 1) * 100L);   // bin 9 is the busiest (1000)
		assertEquals("busiest bin = 100% of peak", 100, c.activityPct(9));
		assertEquals("bin 4 (500) = 50% of the 1000 peak", 50, c.activityPct(4));
		// a running average, not a raw sum: recording the same value keeps the bin's average stable
		c.record(9, 1000); c.record(9, 1000);
		assertEquals(100, c.activityPct(9));
	}

	@Test
	public void csvRoundTrips()
	{
		MarketClock a = new MarketClock();
		for (int i = 0; i < 20; i++) c(a, i);
		MarketClock b = new MarketClock();
		b.loadCsv(a.toCsv());
		for (int i = 0; i < 168; i++) assertEquals("bin " + i + " survives round-trip", a.activityPct(i), b.activityPct(i));
	}

	private static void c(MarketClock m, int bin) { m.record(bin, (bin + 1) * 250L); }
}
