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
	// scoring constants — kept identical to the web app's DEF_CFG so the panel and site agree
	private static final int MIN_VOL24 = 500;    // 24h liquidity gate
	private static final double PART = 0.20;      // share of a side's flow we realistically capture
	private static final double TAU_S = 1200.0;   // staleness decay
	private static final double VOL_SAT = 200.0;  // volume-quality saturation

	/** Item metadata from /mapping, cached for the session (read cross-thread → volatile). */
	private volatile Map<Integer, Meta> mapping;
	private volatile long mappingAt = 0;

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
		int resetMins = -1;   // minutes until this item's 4h buy window resets (−1 = none active)
		boolean dumping;      // cheap right now vs its recent norm — a dip/buy signal
		double fillProb;      // P(the round-trip actually completes in a 4h cycle), 0..1
		boolean wontFill;     // too little counter-flow — expect slow/failed fills
		String why = "";      // one-line plain-English rationale (the transparency edge)
	}

	/** Item name from the cached mapping (null if not loaded / unknown). */
	String nameFor(int id)
	{
		Meta m = (mapping != null) ? mapping.get(id) : null;
		return m != null ? m.name : null;
	}

	/** Whether an item is GE-tax-exempt (bonds/starter tools) — for the fill recorder. */
	boolean isExempt(int id)
	{
		Meta m = (mapping != null) ? mapping.get(id) : null;
		return m != null && m.exempt;
	}

	/** Resolve a set of lower-cased item names to their IDs (empty until mapping loads). */
	java.util.Set<Integer> idsForNames(java.util.Set<String> loweredNames)
	{
		java.util.Set<Integer> out = new java.util.HashSet<>();
		if (mapping == null || loweredNames == null || loweredNames.isEmpty()) return out;
		for (Meta m : mapping.values())
			if (loweredNames.contains(m.name.trim().toLowerCase())) out.add(m.id);
		return out;
	}

	// --- tax (identical math to the app) -----------------------------------
	static int saleTax(int sell, boolean exempt)
	{
		if (exempt || sell < 50) return 0;
		return Math.min((int) Math.floor(sell * 2L / 100.0), TAX_CAP);
	}
	static int netMargin(int buy, int sell, boolean exempt) { return sell - saleTax(sell, exempt) - buy; }
	/** The undercut step (~0.05% of price, min 1gp) used to jump the GE queue. */
	static int tickSize(int p) { return Math.max(1, (int) Math.round(p * 0.0005)); }

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

	/** Buy limit for an item (−1 if unknown / mapping not loaded). */
	int limitFor(int id)
	{
		Meta m = (mapping != null) ? mapping.get(id) : null;
		return m != null ? m.limit : -1;
	}

	// raw quotes from the last scan, so we can price any held item on demand
	private volatile JsonObject lastLatest, lastM5, lastH1;

	/** Find an item id by name (exact case-insensitive first, then contains). −1 if unknown. */
	int idForName(String name)
	{
		if (mapping == null || name == null || name.trim().isEmpty()) return -1;
		String n = name.trim().toLowerCase();
		for (Meta m : mapping.values()) if (m.name.toLowerCase().equals(n)) return m.id;
		for (Meta m : mapping.values()) if (m.name.toLowerCase().contains(n)) return m.id;
		return -1;
	}

	/** A recent volume-weighted price for an item — the robust "where it's trading" number,
	 *  immune to a single stale instant print. field = "avgLowPrice" or "avgHighPrice". */
	private Integer vwap(int id, String field)
	{
		String k = String.valueOf(id);
		for (JsonObject src : new JsonObject[]{ lastM5, lastH1 })
			if (src != null && src.has(k))
			{
				JsonObject g = src.getAsJsonObject(k);
				if (g.has(field) && !g.get(field).isJsonNull()) return g.get(field).getAsInt();
			}
		return null;
	}

	private Integer instant(int id, String field)
	{
		if (lastLatest == null) return null;
		String k = String.valueOf(id);
		if (!lastLatest.has(k)) return null;
		JsonObject q = lastLatest.getAsJsonObject(k);
		return (q.has(field) && !q.get(field).isJsonNull()) ? q.get(field).getAsInt() : null;
	}

	/** Recommended price to PLACE A BUY at (one tick over where sellers dump). Prefers the
	 *  recent VWAP so a stale instant print can't invert it; falls back to the instant low. */
	int buyHint(int id)
	{
		Integer buy = vwap(id, "avgLowPrice");
		if (buy == null) buy = instant(id, "low");
		if (buy == null || buy <= 0) return -1;
		return buy + tickSize(buy);   // overcut a tick so a resting buy actually fills
	}

	/** Recommended price to LIST A SELL at (one tick under where buyers buy). Prefers the
	 *  recent VWAP so a stale instant print can't invert it; falls back to the instant high. */
	int sellHint(int id)
	{
		Integer sell = vwap(id, "avgHighPrice");
		if (sell == null) sell = instant(id, "high");
		if (sell == null || sell <= 0) return -1;
		return sell - tickSize(sell);   // undercut a tick so a resting sell actually fills
	}

	List<Flip> scan(GeflipConfig cfg) throws Exception { return scan(cfg, java.util.Collections.emptyMap()); }
	List<Flip> scan(GeflipConfig cfg, java.util.Map<Integer, Integer> remaining) throws Exception
	{ return scan(cfg, remaining, cfg.bankrollM() * 1_000_000L); }

	/**
	 * Full scan → ranked flips. `remaining` maps item id → units still buyable in the
	 * current 4h window (only for items you've partly/fully bought); an item at 0 is
	 * dropped, and quantity is capped by what's left — so it never recommends a flip you
	 * can't act on right now.
	 */
	List<Flip> scan(GeflipConfig cfg, java.util.Map<Integer, Integer> remaining, long bankrollGp) throws Exception
	{
		Map<Integer, Meta> map = loadMapping();
		JsonObject latest = new JsonParser().parse(httpGet(API + "/latest")).getAsJsonObject().getAsJsonObject("data");
		JsonObject h1 = new JsonParser().parse(httpGet(API + "/1h")).getAsJsonObject().getAsJsonObject("data");
		JsonObject m5 = null;
		try { m5 = new JsonParser().parse(httpGet(API + "/5m")).getAsJsonObject().getAsJsonObject("data"); }
		catch (Exception ignored) { /* optional — realistic-price guard */ }
		// cache the raw quotes so we can price ANY item on demand (e.g. things you're holding
		// that dropped off the ranked list) — used by sellHint().
		lastLatest = latest; lastM5 = m5; lastH1 = h1;
		JsonObject d24 = null;
		try { d24 = new JsonParser().parse(httpGet(API + "/24h")).getAsJsonObject().getAsJsonObject("data"); }
		catch (Exception ignored) { /* optional liquidity gate */ }
		Map<Integer, Double> t90s = loadT90(cfg.useTrends());

		long bankroll = Math.max(1, bankrollGp);
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
			int hiInst = q.get("high").getAsInt(), loInst = q.get("low").getAsInt();
			if (loInst <= 0) continue;
			// newest quote timestamp — guard BOTH times (either can be json-null)
			boolean hasHi = q.has("highTime") && !q.get("highTime").isJsonNull();
			boolean hasLo = q.has("lowTime") && !q.get("lowTime").isJsonNull();
			long newest;
			if (hasHi && hasLo) newest = Math.min(q.get("highTime").getAsLong(), q.get("lowTime").getAsLong());
			else if (hasHi) newest = q.get("highTime").getAsLong();
			else if (hasLo) newest = q.get("lowTime").getAsLong();
			else newest = now;
			long age = now - newest;
			if (age > 3600) continue;

			// REALISTIC fill prices. latest.high is the last INSTANT-BUY print (a buyer
			// crossing the spread up) — placing a sell there often won't fill. Guard both
			// legs with the 5m volume-weighted average so the SELL price is one buyers
			// actually pay, and the BUY price one sellers actually accept.
			int hi = hiInst, lo = loInst;
			// prefer the 5m VWAP; fall back to the 1h VWAP so an illiquid item with no 5m
			// print doesn't revert to a lone, up-to-1h-stale instant price.
			JsonObject w5 = (m5 != null && m5.has(e.getKey())) ? m5.getAsJsonObject(e.getKey()) : null;
			JsonObject guard = w5;
			if (guard == null && h1.has(e.getKey())) guard = h1.getAsJsonObject(e.getKey());
			int guardLow = 0;
			if (guard != null)
			{
				if (guard.has("avgHighPrice") && !guard.get("avgHighPrice").isJsonNull())
					hi = Math.min(hiInst, guard.get("avgHighPrice").getAsInt());
				if (guard.has("avgLowPrice") && !guard.get("avgLowPrice").isJsonNull())
				{
					guardLow = guard.get("avgLowPrice").getAsInt();
					lo = Math.max(loInst, guardLow);
				}
			}
			if (hi <= lo) continue;   // no realistic spread once guarded
			// DUMP signal: the instant-sell price is well below the recent norm = someone's
			// dumping, so you can buy cheap right now (a dip; per the swing research these revert).
			boolean dumping = guardLow > 0 && loInst < guardLow * 0.95;

			int instant = netMargin(lo, hi, meta.exempt);

			JsonObject w1 = h1.has(e.getKey()) ? h1.getAsJsonObject(e.getKey()) : null;
			Integer hourly = null;
			int vh = 0, vl = 0;   // side-specific hourly volumes (high = instant-buys, low = instant-sells)
			if (w1 != null && !w1.get("avgHighPrice").isJsonNull() && !w1.get("avgLowPrice").isJsonNull())
			{
				hourly = netMargin(w1.get("avgLowPrice").getAsInt(), w1.get("avgHighPrice").getAsInt(), meta.exempt);
				vh = w1.get("highPriceVolume").getAsInt(); vl = w1.get("lowPriceVolume").getAsInt();
			}
			int vol1 = Math.min(vh, vl);
			if (vol1 < cfg.minVol1h()) continue;

			// margin blend + stability — mirrors the web's stability()/margin logic exactly
			// (stability returns the haircut 0.5 when there's no hourly to corroborate).
			int margin;
			double stab;
			if (hourly == null) { margin = (int) Math.floor(instant * 0.5); stab = 0.5; }
			else
			{
				double sc = Math.max(Math.max(Math.abs(instant), Math.abs(hourly)), 1);
				double agree = Math.max(0, 1 - Math.abs(instant - hourly) / sc);
				margin = agree >= 0.7 ? Math.round((instant + hourly) / 2f) : Math.min(instant, hourly);
				stab = agree;
			}
			if (margin < cfg.minMargin()) continue;

			// FILLABLE prices: a resting offer at the touch (buy=lo / sell=hi) sits at the BACK
			// of the queue and often never fills — the "it won't sell for the listed price" bug.
			// Undercut one tick each side and rank on THAT margin, what a flip actually earns.
			int tkB = tickSize(lo), tkS = tickSize(hi);
			int bidComp = lo + tkB;
			int askComp = Math.max(bidComp + 1, hi - tkS);
			// ORDER-FLOW IMBALANCE: if instant-buys outnumber instant-sells (rho>0) the item is
			// being bid up — competitors overcut your buy, so you realistically fill it HIGHER;
			// sell pressure (rho<0) drags your realistic sell fill LOWER. (Cont/Kukanov/Stoikov.)
			double rho = (vh + vl) > 0 ? (double) (vh - vl) / (vh + vl) : 0;
			double kappa = 0.4;
			if (rho > 0) bidComp += (int) Math.round(rho * kappa * (hi - lo));
			else if (rho < 0) askComp -= (int) Math.round(-rho * kappa * (hi - lo));
			askComp = Math.max(bidComp + 1, askComp);
			int haircutDelta = instant - margin;                         // keep the corroboration pessimism
			int marginComp = (askComp - saleTax(askComp, meta.exempt) - bidComp) - haircutDelta;
			if (marginComp < Math.max(1, cfg.minMargin())) continue;     // no achievable margin after undercut

			// 24h liquidity gate + flow forecast shrinkage (web: minVol24 + flowFcast)
			JsonObject w24 = (d24 != null && d24.has(e.getKey())) ? d24.getAsJsonObject(e.getKey()) : null;
			int vol24 = 0;
			if (w24 != null && !w24.get("highPriceVolume").isJsonNull() && !w24.get("lowPriceVolume").isJsonNull())
				vol24 = Math.min(w24.get("highPriceVolume").getAsInt(), w24.get("lowPriceVolume").getAsInt());
			if (d24 != null && vol24 < MIN_VOL24) continue;
			Double hourly24 = vol24 > 0 ? vol24 / 24.0 : null;
			double flowFcast = hourly24 != null ? (vol1 + hourly24) / 2.0 : vol1;

			int limit = meta.limit;
			Integer rem = remaining.get(id);   // units still buyable this 4h window (null = full)
			if (rem != null)
			{
				if (rem <= 0) continue;         // limit already spent — don't recommend it
				limit = Math.min(limit, rem);
			}
			long afford = perItemCap / bidComp;
			long through = (long) (PART * flowFcast * cycleH);
			int qty = (int) Math.max(0, Math.min(limit, Math.min(afford, through)));
			if (qty <= 0) continue;

			// FILL PROBABILITY (Poisson): expected units arriving on the SLOWER side in one 4h
			// cycle vs the qty you'd buy — how likely the round-trip actually completes.
			double expUnits = PART * Math.min(vh, vl) * cycleH;
			double zf = (expUnits - qty) / Math.sqrt(Math.max(1, expUnits));
			double fillProb = 1.0 / (1.0 + Math.exp(-1.702 * zf));   // logistic approx of the normal CDF
			boolean wontFill = Math.min(vh, vl) < 50 || fillProb < 0.15;   // too little counter-flow

			// intra-hour trend (falling-knife) penalty — matches the web's trendPen
			double midNow = (lo + hi) / 2.0;
			Double mid1h = null;
			if (w1 != null && !w1.get("avgHighPrice").isJsonNull() && !w1.get("avgLowPrice").isJsonNull())
				mid1h = (w1.get("avgLowPrice").getAsInt() + w1.get("avgHighPrice").getAsInt()) / 2.0;
			double trendPen = (mid1h != null && (midNow - mid1h) / mid1h < -0.03) ? 0.8 : 1.0;

			double roi = (double) marginComp / bidComp;
			// adverse-selection guard: a margin far wider than its 24h norm is usually a
			// transient spike that collapses before you sell — demote it (web scoreAll spikePen).
			double spikePen = 1.0;
			if (w24 != null && !w24.get("avgHighPrice").isJsonNull() && !w24.get("avgLowPrice").isJsonNull())
			{
				int m24 = netMargin(w24.get("avgLowPrice").getAsInt(), w24.get("avgHighPrice").getAsInt(), meta.exempt);
				if (m24 > 0 && marginComp > 2.5 * m24) spikePen = 0.7;
			}
			// confidence = quality * stability * intra-hour-trend * spike-guard (web scoreAll conf)
			double fresh = Math.exp(-age / TAU_S);
			double volS = Math.sqrt(Math.min(1.0, vol1 / VOL_SAT));
			double quality = Math.sqrt(fresh * volS);
			double conf = Math.max(0, Math.min(1, quality * stab * trendPen * spikePen));
			Double t90 = t90s.get(id);
			double pen = trendPenalty(t90);   // long-term death-spiral, applied to expGph (like web applyTrends)

			Flip f = new Flip();
			f.id = id; f.name = meta.name; f.buy = bidComp; f.sell = askComp;
			f.tax = saleTax(askComp, meta.exempt); f.margin = marginComp; f.quantity = qty; f.limit = limit;
			// SIDE-SPECIFIC fill time with flow shrinkage: BUY fills against low-side volume,
			// SELL against high-side volume; the two legs are sequential.
			double shrink = (hourly24 != null && vol1 > 0) ? flowFcast / vol1 : 1.0;
			double sellersFc = vl * shrink, buyersFc = vh * shrink;
			double buyFillH = sellersFc > 0 ? f.quantity / (PART * sellersFc) : 999.0;
			double sellFillH = buyersFc > 0 ? f.quantity / (PART * buyersFc) : 999.0;
			double fillH = Math.min(999.0, buyFillH + sellFillH);
			double effCycleH = Math.max(fillH, cycleH);   // floor at the 4h buy-limit reset
			f.roi = roi; f.fillHours = fillH;
			f.gph = (double) marginComp * qty / effCycleH;
			f.expGph = f.gph * conf * pen;   // short-term confidence x long-term trend penalty
			f.confidence = conf * pen; f.t90 = t90; f.decliner = pen < 1.0; f.dumping = dumping;
			f.fillProb = fillProb; f.wontFill = wontFill;
			// WHY this pick ranks where it does — the honest, per-pick rationale no paid black box shows
			int fp = (int) Math.round(fillProb * 100);
			if (wontFill) f.why = "Thin market — may sit unfilled (little counter-flow)";
			else if (pen < 1.0) f.why = "Margin is real but it's in a long-term decline — risky hold";
			else if (dumping) f.why = "Dip: cheap vs its recent norm, ~" + fp + "% fill — buy-the-dip";
			else if (stab < 0.7) f.why = "Wide instant spread the 1h avg doesn't confirm — treated cautiously";
			else if (fillProb >= 0.7) f.why = "Solid: ~" + fp + "% fill, spread corroborated by the 1h average";
			else f.why = "OK: ~" + fp + "% fill — decent margin, watch the fill time";
			out.add(f);
		}
		// same tiebreak order as the web: expGph desc, confidence desc, name asc
		out.sort(Comparator.<Flip>comparingDouble(x -> -x.expGph)
			.thenComparing(Comparator.comparingDouble((Flip x) -> -x.confidence))
			.thenComparing(x -> x.name));
		int rows = Math.max(5, cfg.rows());
		return out.size() > rows ? new ArrayList<>(out.subList(0, rows)) : out;
	}
}
