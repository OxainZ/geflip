package com.geflip;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The gp/hour ranking, ported faithfully from the geflip web app so the plugin
 * agrees with the site. Pure logic + HTTP only — no RuneLite types — so it can be
 * unit-tested standalone and reused. Read-only: pulls the CORS-open wiki API plus
 * geflip's committed data/trends.json (the long-term trend the browser cannot fetch
 * itself). Never touches the game or places an offer.
 */
class GeflipScanner
{
	private static final String API = "https://prices.runescape.wiki/api/v1/osrs";
	private static final String TRENDS = "https://oxainz.github.io/geflip/data/trends.json";
	private static final String UA = "geflip-runelite (github.com/OxainZ/geflip)";
	private static final int TAX_CAP = 5_000_000;

	/** Item metadata from /mapping, cached for the session. */
	private Map<Integer, Meta> mapping;
	private long mappingAt = 0;

	static final class Meta
	{
		final int id; final String name; final boolean members; final int limit; final boolean exempt;
		Meta(int id, String name, boolean members, int limit, boolean exempt)
		{ this.id = id; this.name = name; this.members = members; this.limit = limit; this.exempt = exempt; }
	}

	/** A ranked flip, ready for the panel. */
	static final class Flip
	{
		int id; String name; int buy, sell, tax, margin, quantity, limit;
		double roi, gph, expGph, confidence, fillHours; Double t90, t180; boolean decliner;
	}

	/** Item name from the cached mapping (null if not loaded / unknown). */
	String nameFor(int id)
	{
		Meta m = (mapping != null) ? mapping.get(id) : null;
		return m != null ? m.name : null;
	}

	// --- tax (identical math to the app) -----------------------------------
	static int saleTax(int sell, boolean exempt)
	{
		if (exempt || sell < 50) return 0;
		return Math.min((int) Math.floor(sell * 2L / 100.0), TAX_CAP);
	}
	static int netMargin(int buy, int sell, boolean exempt) { return sell - saleTax(sell, exempt) - buy; }

	/** Structural-decline haircut: 90d < -15% scales confidence down, floored 0.6. */
	static double trendPenalty(Double t90)
	{
		if (t90 == null || t90 >= -0.15) return 1.0;
		return Math.max(0.6, 1 + (t90 + 0.15) * 1.6);
	}

	// --- HTTP --------------------------------------------------------------
	private static String httpGet(String url) throws Exception
	{
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestProperty("User-Agent", UA);
		c.setRequestProperty("Accept", "application/json");
		c.setConnectTimeout(15000);
		c.setReadTimeout(25000);
		try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)))
		{
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = r.readLine()) != null) sb.append(line);
			return sb.toString();
		}
		finally { c.disconnect(); }
	}

	private Map<Integer, Meta> loadMapping() throws Exception
	{
		if (mapping != null && System.currentTimeMillis() - mappingAt < 86_400_000L) return mapping;
		Map<Integer, Meta> m = new HashMap<>();
		for (JsonElement e : new JsonParser().parse(httpGet(API + "/mapping")).getAsJsonArray())
		{
			JsonObject o = e.getAsJsonObject();
			if (!o.has("id") || !o.has("name")) continue;
			int id = o.get("id").getAsInt();
			String name = o.get("name").getAsString();
			boolean members = o.has("members") && o.get("members").getAsBoolean();
			int limit = o.has("limit") && !o.get("limit").isJsonNull() ? o.get("limit").getAsInt() : -1;
			boolean exempt = GeflipExempt.EXEMPT.contains(name.trim().toLowerCase());
			m.put(id, new Meta(id, name, members, limit, exempt));
		}
		mapping = m; mappingAt = System.currentTimeMillis();
		return m;
	}

	private static Map<Integer, Double> loadT90(boolean useTrends)
	{
		Map<Integer, Double> out = new HashMap<>();
		if (!useTrends) return out;
		try
		{
			JsonObject items = new JsonParser().parse(httpGet(TRENDS)).getAsJsonObject().getAsJsonObject("items");
			for (Map.Entry<String, JsonElement> en : items.entrySet())
			{
				JsonObject t = en.getValue().getAsJsonObject();
				if (t.has("t90") && !t.get("t90").isJsonNull())
					out.put(Integer.parseInt(en.getKey()), t.get("t90").getAsDouble());
			}
		}
		catch (Exception ignored) { /* optional; rank without it */ }
		return out;
	}

	/** Full scan → ranked flips. Mirrors the app's filters + gp/h model. */
	List<Flip> scan(GeflipConfig cfg) throws Exception
	{
		Map<Integer, Meta> map = loadMapping();
		JsonObject latest = new JsonParser().parse(httpGet(API + "/latest")).getAsJsonObject().getAsJsonObject("data");
		JsonObject h1 = new JsonParser().parse(httpGet(API + "/1h")).getAsJsonObject().getAsJsonObject("data");
		JsonObject d24 = null;
		try { d24 = new JsonParser().parse(httpGet(API + "/24h")).getAsJsonObject().getAsJsonObject("data"); }
		catch (Exception ignored) { /* optional liquidity gate */ }
		Map<Integer, Double> t90s = loadT90(cfg.useTrends());

		long bankroll = cfg.bankrollM() * 1_000_000L;
		long perItemCap = (long) (bankroll * 0.25);
		double cycleH = 4.0;
		long now = System.currentTimeMillis() / 1000;

		List<Flip> out = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : latest.entrySet())
		{
			int id = Integer.parseInt(e.getKey());
			Meta meta = map.get(id);
			if (meta == null || meta.limit <= 0) continue;
			if (meta.members && !cfg.members()) continue;

			JsonObject q = e.getValue().getAsJsonObject();
			if (q.get("high").isJsonNull() || q.get("low").isJsonNull()) continue;
			int hi = q.get("high").getAsInt(), lo = q.get("low").getAsInt();
			if (lo <= 0) continue;
			long age = q.has("lowTime") && !q.get("lowTime").isJsonNull()
				? now - Math.min(q.get("highTime").getAsLong(), q.get("lowTime").getAsLong()) : 0;
			if (age > 3600) continue;

			int instant = netMargin(lo, hi, meta.exempt);

			JsonObject w1 = h1.has(e.getKey()) ? h1.getAsJsonObject(e.getKey()) : null;
			Integer hourly = null;
			int vol1 = 0;
			if (w1 != null && !w1.get("avgHighPrice").isJsonNull() && !w1.get("avgLowPrice").isJsonNull())
			{
				hourly = netMargin(w1.get("avgLowPrice").getAsInt(), w1.get("avgHighPrice").getAsInt(), meta.exempt);
				int vh = w1.get("highPriceVolume").getAsInt(), vl = w1.get("lowPriceVolume").getAsInt();
				vol1 = Math.min(vh, vl);
			}
			if (vol1 < cfg.minVol1h()) continue;

			int margin;
			if (hourly == null) margin = (int) Math.floor(instant * 0.5);
			else
			{
				double sc = Math.max(Math.max(Math.abs(instant), Math.abs(hourly)), 1);
				double agree = Math.max(0, 1 - Math.abs(instant - hourly) / sc);
				margin = agree >= 0.7 ? Math.round((instant + hourly) / 2f) : Math.min(instant, hourly);
			}
			if (margin < cfg.minMargin()) continue;

			int limit = meta.limit;
			long afford = perItemCap / lo;
			long through = (long) (0.25 * vol1 * cycleH);
			int qty = (int) Math.max(0, Math.min(limit, Math.min(afford, through)));
			if (qty <= 0) continue;

			double roi = (double) margin / lo;
			// staleness + volume quality (app's fresh*volS), confidence-weighted
			double fresh = Math.exp(-age / 1200.0);
			double volS = Math.sqrt(Math.min(1.0, vol1 / 200.0));
			double conf = Math.sqrt(fresh * volS);
			Double t90 = t90s.get(id);
			double pen = trendPenalty(t90);
			conf = Math.max(0, Math.min(1, conf * pen));

			Flip f = new Flip();
			f.id = id; f.name = meta.name; f.buy = lo; f.sell = hi;
			f.tax = saleTax(hi, meta.exempt); f.margin = margin; f.quantity = qty; f.limit = limit;
			// Honest cycle time: you can rebuy every 4h (buy-limit reset), BUT a thin item's
			// offers can take LONGER than 4h to fill. Estimate fill hours from volume (both
			// legs at ~25% participation) and divide profit by whichever is the real
			// bottleneck — so an item that takes days to fill shows a LOW gp/h, not a fake one.
			double estFillH = vol1 > 0 ? ((double) qty / (0.25 * vol1)) * 2.0 : 999.0;
			double effCycleH = Math.max(estFillH, cycleH);
			f.roi = roi; f.fillHours = estFillH;
			f.gph = (double) margin * qty / effCycleH; f.expGph = f.gph * conf;
			f.confidence = conf; f.t90 = t90; f.decliner = pen < 1.0;
			out.add(f);
		}
		out.sort(Comparator.<Flip>comparingDouble(x -> -x.expGph).thenComparing(x -> x.name));
		int rows = Math.max(5, cfg.rows());
		return out.size() > rows ? new ArrayList<>(out.subList(0, rows)) : out;
	}
}
