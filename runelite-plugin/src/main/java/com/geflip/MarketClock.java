package com.geflip;

/**
 * Hour-of-week market-activity logger. The GE's trade volume (and thus fill speed + spread width) is
 * cyclical — busier at peak evenings/weekends, quiet off-peak. The wiki API can't backfill this at fine
 * granularity, so the plugin banks its OWN observations: 168 bins (day-of-week × hour), each a running
 * average of the global 1h volume index sampled every scan. Once it's accumulated a couple of weeks it can
 * tell you whether right now is a fast-fill (peak) or slow-fill (trough) window. Persisted as a CSV string.
 */
final class MarketClock
{
	private final long[] sum = new long[168];
	private final long[] n = new long[168];

	/** Current hour-of-week in UTC (Mon 00:00 = 0 … Sun 23:00 = 167) — GE volume tracks UTC playercounts. */
	static int hourOfWeekUtc()
	{
		java.time.ZonedDateTime z = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC);
		return (z.getDayOfWeek().getValue() - 1) * 24 + z.getHour();
	}

	void record(int bin, long v)
	{
		if (bin < 0 || bin >= 168 || v <= 0) return;
		sum[bin] += v; n[bin]++;
	}

	/** How busy the given bin is vs the busiest observed bin, 0-100 (−1 = not enough data yet). */
	int activityPct(int bin)
	{
		if (bin < 0 || bin >= 168 || n[bin] == 0) return -1;
		double cur = sum[bin] / (double) n[bin];
		double peak = 0;
		int filled = 0;
		for (int i = 0; i < 168; i++) if (n[i] > 0) { filled++; double a = sum[i] / (double) n[i]; if (a > peak) peak = a; }
		if (filled < 6 || peak <= 0) return -1;   // need a handful of distinct hours before it means anything
		return (int) Math.round(100.0 * cur / peak);
	}

	/** Serialise both arrays to a compact CSV (336 values) for config storage. */
	String toCsv()
	{
		StringBuilder b = new StringBuilder();
		for (long s : sum) b.append(s).append(',');
		for (int i = 0; i < 168; i++) b.append(n[i]).append(i < 167 ? ',' : ' ');
		return b.toString().trim();
	}

	void loadCsv(String csv)
	{
		if (csv == null || csv.isEmpty()) return;
		String[] p = csv.trim().split(",");
		if (p.length < 336) return;   // malformed / old format — start fresh
		try
		{
			for (int i = 0; i < 168; i++) sum[i] = Long.parseLong(p[i].trim());
			for (int i = 0; i < 168; i++) n[i] = Long.parseLong(p[168 + i].trim());
		}
		catch (NumberFormatException e) { java.util.Arrays.fill(sum, 0); java.util.Arrays.fill(n, 0); }
	}
}
