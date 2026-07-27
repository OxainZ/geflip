package com.geflip;

import com.google.gson.JsonArray;
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
	private static final int GROUND_N = 12;       // #1: max timeseries calls per scan (API politeness cap)

	/** Item metadata from /mapping, cached for the session (read cross-thread → volatile). */
	private volatile Map<Integer, Meta> mapping;
	private volatile long mappingAt = 0;

	/** #1 timeseries measurements cached per item: id -> {marginPersist, priceDir, fetchedMs}. Spares
	 *  the API from re-fetching the same top picks every scan. */
	private final Map<Integer, double[]> tsCache = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long TS_TTL_MS = 300_000L;   // 5 minutes

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
		boolean unstable;     // price swings exceed the margin — it may flip red before you sell
		double fillProb;      // P(the round-trip actually completes in a 4h cycle), 0..1
		boolean wontFill;     // too little counter-flow — expect slow/failed fills
		String why = "";      // one-line plain-English rationale (the transparency edge)
		// --- timeseries grounding (#1): did the margin actually persist recently? ---
		double marginPersist = -1;   // fraction of the last ~2h where a real margin existed (−1 = not checked)
		double tsDir;                // price direction over that window (e.g. −0.04 = fell 4%)
		boolean tsChecked;           // timeseries grounding ran for this pick
		// --- personalisation (#2): your own realised fills adjusted this pick ---
		boolean personalized;        // your fill history moved the confidence
		double yourWinRate = -1;     // your realised win-rate on this item (−1 = no history)
		double yourMarginPer = -1;   // your realised profit per unit on this item (−1 = no history)
		double yourHoldH = -1;       // your realised hold hours per unit (−1 = no history)
		// --- capital/slot basket (#3) ---
		int basketQty;               // suggested units if you put this in one of your GE slots (0 = not picked)
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

	/** #1 — for a proven-winner item that ISN'T in the current ranked list, the most likely REASON,
	 *  read from the last scan's cached quotes using the same gates scan() applies. So "my winner went
	 *  quiet" becomes "Magic logs — 24h volume below the gate" instead of an unexplained absence.
	 *  Returns null if we can't tell yet (no cached scan). */
	String whyNotShowing(int id, GeflipConfig cfg)
	{
		Map<Integer, Meta> map = mapping;
		if (map == null || lastLatest == null) return null;
		Meta m = map.get(id);
		if (m == null) return "not a GE-tradeable item";
		if (m.limit <= 0) return "no buy limit — not flippable";
		if (m.members && !cfg.members()) return "members item (members off in config)";
		String k = String.valueOf(id);
		if (!lastLatest.has(k)) return "no live price right now";
		JsonObject q = lastLatest.getAsJsonObject(k);
		if (q.get("high").isJsonNull() || q.get("low").isJsonNull()) return "no live buy/sell quote";
		int hi = q.get("high").getAsInt(), lo = q.get("low").getAsInt();
		if (lo <= 0 || hi <= lo) return "no spread right now";
		long now = System.currentTimeMillis() / 1000;
		boolean hasHi = q.has("highTime") && !q.get("highTime").isJsonNull();
		boolean hasLo = q.has("lowTime") && !q.get("lowTime").isJsonNull();
		long newest = hasHi && hasLo ? Math.min(q.get("highTime").getAsLong(), q.get("lowTime").getAsLong())
			: hasHi ? q.get("highTime").getAsLong() : hasLo ? q.get("lowTime").getAsLong() : now;
		if (now - newest > 3600) return "no trade in the last hour (stale quote)";
		int margin = netMargin(lo, hi, m.exempt);
		if (margin < Math.max(1, cfg.minMargin())) return "margin ~" + margin + "gp is below your " + cfg.minMargin() + "gp min";
		if (lastH1 != null && lastH1.has(k))
		{
			JsonObject w1 = lastH1.getAsJsonObject(k);
			if (!w1.get("highPriceVolume").isJsonNull() && !w1.get("lowPriceVolume").isJsonNull())
			{
				int vol1 = Math.min(w1.get("highPriceVolume").getAsInt(), w1.get("lowPriceVolume").getAsInt());
				if (vol1 < cfg.minVol1h()) return "1h volume thin (" + vol1 + " < your " + cfg.minVol1h() + " min)";
			}
			if (!w1.get("avgHighPrice").isJsonNull() && !w1.get("avgLowPrice").isJsonNull())
			{
				double mid1h = (w1.get("avgLowPrice").getAsInt() + w1.get("avgHighPrice").getAsInt()) / 2.0;
				double midNow = (lo + hi) / 2.0;
				if (mid1h > 0 && (midNow - mid1h) / mid1h < -0.03 && cfg.hideFalling()) return "price is falling this hour (hidden as a falling knife)";
			}
		}
		if (lastD24 != null && lastD24.has(k))
		{
			JsonObject w24 = lastD24.getAsJsonObject(k);
			if (!w24.get("highPriceVolume").isJsonNull() && !w24.get("lowPriceVolume").isJsonNull())
			{
				int vol24 = Math.min(w24.get("highPriceVolume").getAsInt(), w24.get("lowPriceVolume").getAsInt());
				if (vol24 < MIN_VOL24) return "24h volume below the " + MIN_VOL24 + " liquidity gate";
			}
			if (!w24.get("avgHighPrice").isJsonNull() && !w24.get("avgLowPrice").isJsonNull())
			{
				int m24 = netMargin(w24.get("avgLowPrice").getAsInt(), w24.get("avgHighPrice").getAsInt(), m.exempt);
				if (m24 > 0 && margin > 3.0 * m24 && cfg.hideSpikes()) return "margin looks like a transient spike (hidden)";
			}
		}
		return "just outside the top " + Math.max(5, cfg.rows()) + " right now";
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
	/** GET with one retry on a transient failure (a lone 429/5xx/timeout otherwise aborts the whole
	 *  scan — the audit's "no partial result" gap). Runs on the executor thread, so the brief backoff
	 *  sleep is off the EDT. */
	private static String httpGet(String url) throws Exception
	{
		Exception last = null;
		for (int attempt = 0; attempt < 2; attempt++)
		{
			if (attempt > 0) Thread.sleep(400L);   // brief backoff before the single retry
			try { return httpGetOnce(url); }
			catch (Exception e) { last = e; }
		}
		throw last;
	}

	private static String httpGetOnce(String url) throws Exception
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

	/** A decanting opportunity: buy the cheapest-per-dose form, decant to (4) at Bob Barter
	 *  (free/instant), sell the (4). Profit is net of the 2% tax on the (4) sale. */
	static final class Decant
	{
		String name;        // family, e.g. "Prayer potion"
		String buyLabel;    // e.g. "Prayer potion(3)"
		int buyId, buyPrice, buyDose;
		int sell4Id, sell4;
		long profitPer4;    // net gp per (4) made
	}

	private static final java.util.regex.Pattern DOSE = java.util.regex.Pattern.compile("^(.*)\\((\\d)\\)$");
	// (n)-suffixed items that AREN'T potions and can't be decanted at Bob Barter (charged jewellery,
	// teleport items, etc.). Matched against the base name so glory(1..4) etc. never show as decants.
	private static final java.util.regex.Pattern NOT_A_POTION = java.util.regex.Pattern.compile(
		"(?i)\\b(amulet|necklace|ring|bracelet|pendant|tiara|hat|slayer helmet|ring of|teleport)\\b");

	/** True only if the item's newest instant quote is fresh (≤1h old) — same staleness gate the
	 *  main scan uses. Sets and thinly-traded doses can print prices days stale; this rejects them
	 *  so a decant/set row is never built on an unrealisable, ancient quote. */
	private boolean quoteFresh(int id)
	{
		if (lastLatest == null) return false;
		String k = String.valueOf(id);
		if (!lastLatest.has(k)) return false;
		JsonObject q = lastLatest.getAsJsonObject(k);
		long now = System.currentTimeMillis() / 1000, newest = 0;
		if (q.has("highTime") && !q.get("highTime").isJsonNull()) newest = Math.max(newest, q.get("highTime").getAsLong());
		if (q.has("lowTime") && !q.get("lowTime").isJsonNull()) newest = Math.max(newest, q.get("lowTime").getAsLong());
		return newest > 0 && (now - newest) <= 3600;
	}

	/** Scan for profitable decants using the cached quotes from the last flip scan. */
	List<Decant> scanDecants(GeflipConfig cfg)
	{
		Map<Integer, Meta> map = mapping;
		if (map == null || lastLatest == null) return new ArrayList<>();
		// group dose variants by base name: base -> (dose -> meta)
		Map<String, Map<Integer, Meta>> fam = new HashMap<>();
		for (Meta m : map.values())
		{
			java.util.regex.Matcher mt = DOSE.matcher(m.name);
			if (!mt.matches()) continue;
			int dose;
			try { dose = Integer.parseInt(mt.group(2)); } catch (NumberFormatException e) { continue; }
			if (dose < 1 || dose > 4) continue;
			String base = mt.group(1).trim();
			if (NOT_A_POTION.matcher(base).find()) continue;   // charged jewellery isn't decantable
			fam.computeIfAbsent(base, k -> new HashMap<>()).put(dose, m);
		}
		List<Decant> out = new ArrayList<>();
		for (Map.Entry<String, Map<Integer, Meta>> e : fam.entrySet())
		{
			Meta four = e.getValue().get(4);
			if (four == null || (four.members && !cfg.members())) continue;
			if (!quoteFresh(four.id)) continue;              // don't sell (4) on a stale print
			int sell4 = sellHint(four.id);
			if (sell4 <= 0) continue;
			// cheapest per-dose across all FRESH variants
			double bestPerDose = Double.MAX_VALUE; Meta bestM = null; int bestBuy = 0, bestDose = 0;
			for (Map.Entry<Integer, Meta> d : e.getValue().entrySet())
			{
				if (!quoteFresh(d.getValue().id)) continue;
				int buy = buyHint(d.getValue().id);
				if (buy <= 0) continue;
				double perDose = (double) buy / d.getKey();
				if (perDose < bestPerDose) { bestPerDose = perDose; bestM = d.getValue(); bestBuy = buy; bestDose = d.getKey(); }
			}
			if (bestM == null || bestDose == 4) continue;   // need a cheaper sub-(4) source
			long profit4 = (sell4 - saleTax(sell4, four.exempt)) - Math.round(4 * bestPerDose);
			if (profit4 < Math.max(1, cfg.minMargin())) continue;
			Decant dc = new Decant();
			dc.name = e.getKey(); dc.buyLabel = bestM.name; dc.buyId = bestM.id; dc.buyPrice = bestBuy;
			dc.buyDose = bestDose; dc.sell4Id = four.id; dc.sell4 = sell4; dc.profitPer4 = profit4;
			out.add(dc);
		}
		out.sort(Comparator.<Decant>comparingLong(x -> -x.profitPer4));
		int rows = Math.max(5, cfg.rows());
		return out.size() > rows ? new ArrayList<>(out.subList(0, rows)) : out;
	}

	/** A set-exchange arbitrage: combine pieces->set or split set->pieces (free at the GE clerk). */
	static final class SetFlip
	{
		String name, dir;       // e.g. "Bandos armour set", "buy pieces → sell set"
		long buyTotal, sellNet; // cost of the buy side, net-of-tax proceeds of the sell side
		long profit;
	}

	// Sets defined by NAME (resolved to ids via the live mapping, so no hardcoded/guessable ids;
	// an unrecognised name just skips that set). Row = { setName, piece1, piece2, ... }.
	private static final String[][] SETS = {
		{"Ahrim's armour set", "Ahrim's hood", "Ahrim's robetop", "Ahrim's robeskirt", "Ahrim's staff"},
		{"Dharok's armour set", "Dharok's helm", "Dharok's platebody", "Dharok's platelegs", "Dharok's greataxe"},
		{"Guthan's armour set", "Guthan's helm", "Guthan's platebody", "Guthan's chainskirt", "Guthan's warspear"},
		{"Karil's armour set", "Karil's coif", "Karil's leathertop", "Karil's leatherskirt", "Karil's crossbow"},
		{"Torag's armour set", "Torag's helm", "Torag's platebody", "Torag's platelegs", "Torag's hammers"},
		{"Verac's armour set", "Verac's helm", "Verac's brassard", "Verac's plateskirt", "Verac's flail"},
		{"Armadyl armour set", "Armadyl helmet", "Armadyl chestplate", "Armadyl chainskirt"},
		{"Bandos armour set", "Bandos chestplate", "Bandos tassets", "Bandos boots"},
		{"Ancestral robes set", "Ancestral hat", "Ancestral robe top", "Ancestral robe bottom"},
		{"Justiciar armour set", "Justiciar faceguard", "Justiciar chestguard", "Justiciar legguards"},
		{"Inquisitor's armour set", "Inquisitor's great helm", "Inquisitor's hauberk", "Inquisitor's plateskirt"},
		{"Dagon'hai robes set", "Dagon'hai hat", "Dagon'hai robe top", "Dagon'hai robe bottom"},
	};

	/** Scan set-exchange arbitrage (combine vs split), using the cached quotes. */
	List<SetFlip> scanSets(GeflipConfig cfg)
	{
		if (mapping == null || lastLatest == null) return new ArrayList<>();
		List<SetFlip> out = new ArrayList<>();
		for (String[] s : SETS)
		{
			int setId = idForNameExact(s[0]);   // EXACT only — a contains-match here = bogus money math
			if (setId < 0 || !quoteFresh(setId)) continue;
			Meta setMeta = mapping.get(setId);
			if (setMeta != null && setMeta.members && !cfg.members()) continue;
			int[] pieces = new int[s.length - 1];
			boolean ok = true;
			for (int i = 1; i < s.length; i++)
			{
				int pid = idForNameExact(s[i]);
				if (pid < 0 || !quoteFresh(pid)) { ok = false; break; }
				pieces[i - 1] = pid;
			}
			if (!ok) continue;

			int setBuy = buyHint(setId), setSell = sellHint(setId);
			if (setBuy <= 0 || setSell <= 0) continue;

			// combine: BUY the pieces, sell the boxed set
			long piecesBuy = 0; boolean pb = true;
			for (int pid : pieces) { int b = buyHint(pid); if (b <= 0) { pb = false; break; } piecesBuy += b; }
			long combine = pb ? (setSell - saleTax(setSell, setMeta != null && setMeta.exempt)) - piecesBuy : Long.MIN_VALUE;

			// split: BUY the set, sell the pieces (tax on each piece)
			long piecesSellNet = 0; boolean ps = true;
			for (int pid : pieces)
			{
				int sp = sellHint(pid); if (sp <= 0) { ps = false; break; }
				Meta pm = mapping.get(pid);
				piecesSellNet += sp - saleTax(sp, pm != null && pm.exempt);
			}
			long split = ps ? piecesSellNet - setBuy : Long.MIN_VALUE;

			SetFlip f = new SetFlip();
			f.name = s[0];
			if (combine >= split && combine >= Math.max(1, cfg.minMargin()))
			{ f.dir = "buy pieces → sell set"; f.buyTotal = piecesBuy; f.sellNet = setSell - saleTax(setSell, setMeta != null && setMeta.exempt); f.profit = combine; }
			else if (split >= Math.max(1, cfg.minMargin()))
			{ f.dir = "buy set → sell pieces"; f.buyTotal = setBuy; f.sellNet = piecesSellNet; f.profit = split; }
			else continue;
			out.add(f);
		}
		out.sort(Comparator.<SetFlip>comparingLong(x -> -x.profit));
		return out;
	}

	/** Buy limit for an item (−1 if unknown / mapping not loaded). */
	int limitFor(int id)
	{
		Meta m = (mapping != null) ? mapping.get(id) : null;
		return m != null ? m.limit : -1;
	}

	// raw quotes from the last scan, so we can price any held item on demand
	private volatile JsonObject lastLatest, lastM5, lastH1, lastD24;

	/** True if the item is trading cheap right now vs its recent norm (a dip / good buy). */
	boolean isCheap(int id)
	{
		Integer lo = instant(id, "low");
		Integer avgLo = avgField(quoteSrc(id), "avgLowPrice");
		return lo != null && avgLo != null && avgLo > 0 && lo < avgLo * 0.95;
	}

	/** Find an item id by name (exact case-insensitive first, then contains). −1 if unknown.
	 *  The contains fallback is for loose user price-checks ONLY — never for money math, since
	 *  HashMap order makes it non-deterministic which partial match wins. */
	int idForName(String name)
	{
		int exact = idForNameExact(name);
		if (exact >= 0) return exact;
		if (mapping == null || name == null) return -1;
		String n = name.trim().toLowerCase();
		if (n.isEmpty()) return -1;
		for (Meta m : mapping.values()) if (m.name.toLowerCase().contains(n)) return m.id;
		return -1;
	}

	/** Exact case-insensitive name→id, or −1. Use this (never the contains fallback) whenever a
	 *  wrong resolution would produce real money numbers — e.g. the Sets scanner. */
	int idForNameExact(String name)
	{
		if (mapping == null || name == null) return -1;
		String n = name.trim().toLowerCase();
		if (n.isEmpty()) return -1;
		for (Meta m : mapping.values()) if (m.name.toLowerCase().equals(n)) return m.id;
		return -1;
	}

	/** The single best VWAP source for an item (5m preferred, else 1h) — so buy & sell hints
	 *  read the SAME window and can't invert by mixing 5m-low with 1h-high. */
	private JsonObject quoteSrc(int id)
	{
		String k = String.valueOf(id);
		if (lastM5 != null && lastM5.has(k)) return lastM5.getAsJsonObject(k);
		if (lastH1 != null && lastH1.has(k)) return lastH1.getAsJsonObject(k);
		return null;
	}

	private static Integer avgField(JsonObject g, String field)
	{
		return (g != null && g.has(field) && !g.get(field).isJsonNull()) ? g.get(field).getAsInt() : null;
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
		Integer buy = avgField(quoteSrc(id), "avgLowPrice");
		if (buy == null) buy = instant(id, "low");
		if (buy == null || buy <= 0) return -1;
		return buy + tickSize(buy);   // overcut a tick so a resting buy actually fills
	}

	/** Recommended price to LIST A SELL at (one tick under where buyers buy). Prefers the
	 *  recent VWAP so a stale instant print can't invert it; falls back to the instant high. */
	int sellHint(int id)
	{
		Integer sell = avgField(quoteSrc(id), "avgHighPrice");
		if (sell == null) sell = instant(id, "high");
		if (sell == null || sell <= 0) return -1;
		return sell - tickSize(sell);   // undercut a tick so a resting sell actually fills
	}

	List<Flip> scan(GeflipConfig cfg) throws Exception { return scan(cfg, java.util.Collections.emptyMap()); }
	List<Flip> scan(GeflipConfig cfg, java.util.Map<Integer, Integer> remaining) throws Exception
	{ return scan(cfg, remaining, cfg.bankrollM() * 1_000_000L); }
	List<Flip> scan(GeflipConfig cfg, java.util.Map<Integer, Integer> remaining, long bankrollGp) throws Exception
	{ return scan(cfg, remaining, bankrollGp, java.util.Collections.emptyMap()); }

	/**
	 * Full scan → ranked flips. `remaining` maps item id → units still buyable in the
	 * current 4h window (only for items you've partly/fully bought); an item at 0 is
	 * dropped, and quantity is capped by what's left — so it never recommends a flip you
	 * can't act on right now.
	 */
	/** @param perf your realised per-item journal (ledger.byItem): id → [profit, completedFlips,
	 *  winningFlips, holdSecSum, matchedUnits]. Used to personalise confidence (#2). */
	List<Flip> scan(GeflipConfig cfg, java.util.Map<Integer, Integer> remaining, long bankrollGp,
		java.util.Map<Integer, long[]> perf) throws Exception
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
		lastD24 = d24;   // cached for whyNotShowing()
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
			// PHANTOM-PRICE floor: a lone fat-finger instant-sell (e.g. 6gp on a 1.3k item) prints in
			// /latest but is UNBUYABLE — it filled and vanished. When neither 5m nor 1h has an
			// instant-sell VWAP, that phantom stands and fakes a huge margin. Floor the buy with the
			// 24h average (where the item truly trades) when the live low is <half of it; a real dip
			// (within ~half the 24h norm) still prices off the live quote.
			if (d24 != null && d24.has(e.getKey()))
			{
				JsonObject g24 = d24.getAsJsonObject(e.getKey());
				if (g24.has("avgLowPrice") && !g24.get("avgLowPrice").isJsonNull())
				{
					int low24 = g24.get("avgLowPrice").getAsInt();
					if (low24 > 0 && loInst < low24 / 2) lo = Math.max(lo, low24);
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
			// backstop for scans where 24h data didn't load: a real fillable flip never has the buy at
			// a tiny fraction of the sell. Buy < 15% of sell ⇒ the low is a phantom quote — drop it.
			if (bidComp > 0 && (long) bidComp * 100 < (long) askComp * 15) continue;

			// 24h liquidity gate + flow forecast shrinkage (web: minVol24 + flowFcast)
			JsonObject w24 = (d24 != null && d24.has(e.getKey())) ? d24.getAsJsonObject(e.getKey()) : null;
			int vol24 = 0;
			if (w24 != null && !w24.get("highPriceVolume").isJsonNull() && !w24.get("lowPriceVolume").isJsonNull())
				vol24 = Math.min(w24.get("highPriceVolume").getAsInt(), w24.get("lowPriceVolume").getAsInt());
			if (d24 != null && vol24 < MIN_VOL24) continue;
			Double hourly24 = vol24 > 0 ? vol24 / 24.0 : null;
			double flowFcast = hourly24 != null ? (vol1 + hourly24) / 2.0 : vol1;
			// flow-forecast shrinkage, computed ONCE and used for BOTH fill-probability and fill-time
			// (the web uses the shrunk forecast for both; the plugin used raw volume for fill-prob — a
			// parity drift that made the panel's %fill disagree with the phone). vl=instant-sells,
			// vh=instant-buys per hour.
			double shrink = (hourly24 != null && vol1 > 0) ? flowFcast / vol1 : 1.0;
			double sellersFc = vl * shrink, buyersFc = vh * shrink;

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
			// cycle vs the qty you'd buy — how likely the round-trip actually completes. Uses the
			// SHRUNK forecast (matches the web) so the panel's %fill agrees with the phone's.
			double expUnits = PART * Math.min(sellersFc, buyersFc) * cycleH;
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

			// FALLING-KNIFE REJECT: if the price is actively dropping this hour, the margin you see now
			// won't survive the round-trip — you buy green and sell red (the Larran's-key / dragon-metal
			// -sheet trap). Skip these entirely unless the user opts back in.
			boolean falling = mid1h != null && (midNow - mid1h) / mid1h < -0.03;
			if (falling && cfg.hideFalling()) continue;

			// adverse-selection guard: a margin far wider than its 24h norm is usually a transient
			// spike that collapses before you sell. >3x norm ⇒ reject (opt-out); 2.5-3x ⇒ demote.
			double spikePen = 1.0;
			boolean unstable = false;
			if (w24 != null && !w24.get("avgHighPrice").isJsonNull() && !w24.get("avgLowPrice").isJsonNull())
			{
				int aLo = w24.get("avgLowPrice").getAsInt(), aHi = w24.get("avgHighPrice").getAsInt();
				int m24 = netMargin(aLo, aHi, meta.exempt);
				if (m24 > 0 && marginComp > 3.0 * m24 && cfg.hideSpikes()) continue;   // illusory spike
				if (m24 > 0 && marginComp > 2.5 * m24) spikePen = 0.7;
				// VOLATILITY: measure the SWING across the 5m/1h/24h mids (not just the level drift,
				// which the falling-knife check already covers). Flag only a genuine wide swing — >5%
				// AND more than 2x the margin — so the ⚡ stays rare and meaningful, not on every row.
				double mid24 = (aLo + aHi) / 2.0;
				double m1 = mid1h != null ? mid1h : midNow;
				double swingLo = Math.min(midNow, Math.min(m1, mid24)), swingHi = Math.max(midNow, Math.max(m1, mid24));
				double swing = swingHi > 0 ? (swingHi - swingLo) / swingHi : 0;
				if (swing > 0.05 && swing > 2.0 * roi) unstable = true;
			}
			// confidence = quality * stability * intra-hour-trend * spike-guard (web scoreAll conf)
			double fresh = Math.exp(-age / TAU_S);
			double volS = Math.sqrt(Math.min(1.0, vol1 / VOL_SAT));
			double quality = Math.sqrt(fresh * volS);
			double conf = Math.max(0, Math.min(1, quality * stab * trendPen * spikePen));
			// PERSONALISATION (#2): once you've completed ≥3 flips on this item, weight it by YOUR realised
			// win-rate — proven winners up, items that kept going red down. The multiplier uses a Laplace-
			// smoothed rate ((wins+2)/(flips+4)) so a SMALL or CORRELATED sample (e.g. one buy dribble-sold
			// in pieces counts as several "flips") sits near neutral and only real volume moves it; the
			// factor is applied to expGph below (not clamped away), and we DISPLAY your honest raw rate.
			boolean personalized = false;
			double yourWr = -1, personalFactor = 1.0, yourMarginPer = -1, yourHoldH = -1, personalW = 0;
			long[] pi = perf.get(id);
			if (pi != null && pi[1] >= 3)
			{
				yourWr = pi[2] / (double) pi[1];                          // raw realised win-rate (for display)
				double shrunkWr = (pi[2] + 2.0) / (pi[1] + 4.0);         // smoothed toward 50% for small samples
				personalFactor = 0.7 + 0.5 * shrunkWr;                    // 0.7 (losers) .. 1.2 (proven winners)
				personalW = pi[1] / (pi[1] + 3.0);                        // trust in YOUR data, grows with sample size
				if (pi[4] > 0)   // matchedUnits > 0 → realised margin + hold per unit are meaningful
				{
					yourMarginPer = pi[0] / (double) pi[4];               // realised profit per unit (may be <0)
					yourHoldH = pi[3] / (double) pi[4] / 3600.0;          // realised hold hours per unit
					// REALISED-MARGIN haircut: if you consistently net LESS than the model predicts on this
					// item (undercut wars, slippage — or you lose on it), scale the rank toward your reality.
					if (marginComp > 0 && yourMarginPer < marginComp)
						personalFactor *= (1 - personalW) + personalW * Math.max(0.3, yourMarginPer / marginComp);
				}
				personalized = true;
			}
			Double t90 = t90s.get(id);
			double pen = trendPenalty(t90);   // long-term death-spiral, applied to expGph (like web applyTrends)

			Flip f = new Flip();
			f.id = id; f.name = meta.name; f.buy = bidComp; f.sell = askComp;
			f.tax = saleTax(askComp, meta.exempt); f.margin = marginComp; f.quantity = qty; f.limit = limit;
			// SIDE-SPECIFIC fill time (shrink/sellersFc/buyersFc computed above): BUY fills against
			// low-side volume, SELL against high-side volume; the two legs are sequential.
			double buyFillH = sellersFc > 0 ? f.quantity / (PART * sellersFc) : 999.0;
			double sellFillH = buyersFc > 0 ? f.quantity / (PART * buyersFc) : 999.0;
			double fillH = Math.min(999.0, buyFillH + sellFillH);
			// REALISED-HOLD blend: the Poisson fill time is optimistic; if YOUR history shows this item
			// takes longer to actually fill, blend your realised hold in (weighted by sample size) so the
			// gp/hr headline reflects your real fill speed, not the model's best case.
			if (personalized && yourHoldH > 0)
				fillH = Math.min(999.0, (1 - personalW) * fillH + personalW * yourHoldH);
			double effCycleH = Math.max(fillH, cycleH);   // floor at the 4h buy-limit reset
			f.roi = roi; f.fillHours = fillH;
			f.gph = (double) marginComp * qty / effCycleH;
			f.expGph = f.gph * conf * pen;   // short-term confidence x long-term trend penalty
			f.confidence = conf * pen; f.t90 = t90; f.decliner = pen < 1.0; f.dumping = dumping; f.unstable = unstable;
			f.fillProb = fillProb; f.wontFill = wontFill;
			f.personalized = personalized; f.yourWinRate = yourWr; f.yourMarginPer = yourMarginPer; f.yourHoldH = yourHoldH;
			// apply your-record weighting to the RANKING value (expGph isn't capped at 1, so a proven
			// winner actually rises); keep the displayed confidence a valid ≤1 probability.
			if (personalized) { f.expGph *= personalFactor; f.confidence = Math.min(1, f.confidence * personalFactor); }
			// WHY this pick ranks where it does — the honest, per-pick rationale no paid black box shows
			int fp = (int) Math.round(fillProb * 100);
			if (wontFill) f.why = "Thin market — may sit unfilled (little counter-flow)";
			else if (pen < 1.0) f.why = "Margin is real but it's in a long-term decline — risky hold";
			else if (unstable) f.why = "Volatile — price swings are bigger than the margin, it can flip red before you sell";
			else if (dumping) f.why = "Dip: cheap vs its recent norm, ~" + fp + "% fill — buy-the-dip";
			else if (stab < 0.7) f.why = "Wide instant spread the 1h avg doesn't confirm — treated cautiously";
			else if (fillProb >= 0.7) f.why = "Solid: ~" + fp + "% fill, spread corroborated by the 1h average";
			else f.why = "OK: ~" + fp + "% fill — decent margin, watch the fill time";
			// personalised note (your realised win-rate here), without hiding a live risk warning
			if (personalized && !f.wontFill && !f.unstable && !f.decliner)
			{
				int wr = (int) Math.round(yourWr * 100);
				if (yourWr >= 0.7) f.why = "Your winner (" + wr + "% of your flips here paid) — " + f.why;
				else if (yourWr <= 0.34) f.why = "Careful: only " + wr + "% of your past flips here paid — " + f.why;
			}
			// SAFE MODE: only clean flips — drop won't-fill / volatile / long-term-decline rows.
			if (cfg.safeMode() && (f.wontFill || f.unstable || f.decliner)) continue;
			out.add(f);
		}
		// rank by expGph WEIGHTED by fill probability, so a fat margin that probably won't fill
		// doesn't sit at #1 (the eye-catching-but-useless trap). Displayed gp/h is unchanged.
		Comparator<Flip> byValue = Comparator.<Flip>comparingDouble(x -> -x.expGph * (0.6 + 0.4 * x.fillProb))
			.thenComparing(Comparator.comparingDouble((Flip x) -> -x.confidence))
			.thenComparing(x -> x.name);
		out.sort(byValue);
		int rows = Math.max(5, cfg.rows());
		// TIMESERIES GROUNDING (#1): everything above uses aggregate snapshots; now pull the TOP few picks'
		// recent 5m history to confirm the margin ACTUALLY persisted (not a one-tick phantom) and isn't
		// quietly crashing. Bounded to the top GROUND_N (independent of the row count) to stay polite to
		// the wiki API, then we re-rank the WHOLE list so a demoted phantom drops and honest picks rise —
		// without dropping any shown rows.
		int groundN = Math.min(out.size(), GROUND_N);
		if (cfg.groundTimeseries() && groundN > 0) { groundWithTimeseries(out.subList(0, groundN), map); out.sort(byValue); }
		List<Flip> top = out.size() > rows ? new ArrayList<>(out.subList(0, rows)) : out;
		basket(top, bankroll, Math.max(1, cfg.geSlots()));   // #3: mark the capital/slot basket
		return top;
	}

	/** #1 — pull each pick's recent 5m timeseries and fold "did the margin actually exist lately, and
	 *  is it crashing" into confidence/expGph. Results are CACHED per item for TS_TTL_MS so repeated
	 *  scans of the same top picks don't re-hit the API. Left untouched (tsChecked stays false) if the
	 *  call fails and nothing is cached. */
	private void groundWithTimeseries(List<Flip> pool, Map<Integer, Meta> map)
	{
		long nowMs = System.currentTimeMillis();
		for (Flip f : pool)
		{
			double[] c = tsCache.get(f.id);   // {persist, dir, fetchedMs}
			if (c != null && nowMs - (long) c[2] < TS_TTL_MS) { applyTsResult(f, c[0], c[1]); continue; }
			try
			{
				Meta meta = map.get(f.id);
				boolean exempt = meta != null && meta.exempt;
				JsonArray data = new JsonParser().parse(httpGet(API + "/timeseries?timestep=5m&id=" + f.id))
					.getAsJsonObject().getAsJsonArray("data");
				int n = data.size();
				int from = Math.max(0, n - 24);   // last ~2h of 5m points
				int pos = 0, tot = 0; double firstMid = 0, lastMid = 0;
				for (int i = from; i < n; i++)
				{
					JsonObject p = data.get(i).getAsJsonObject();
					if (p.get("avgHighPrice").isJsonNull() || p.get("avgLowPrice").isJsonNull()) continue;
					int lo = p.get("avgLowPrice").getAsInt(), hi = p.get("avgHighPrice").getAsInt();
					if (lo <= 0 || hi <= 0) continue;
					tot++;
					if (netMargin(lo, hi, exempt) >= Math.max(1, f.margin * 0.5)) pos++;
					double mid = (lo + hi) / 2.0;
					if (firstMid == 0) firstMid = mid;
					lastMid = mid;
				}
				if (tot < 6) continue;   // too little history to judge — leave the pick as-is
				double persist = pos / (double) tot;
				double dir = firstMid > 0 ? (lastMid - firstMid) / firstMid : 0;
				tsCache.put(f.id, new double[]{ persist, dir, nowMs });
				applyTsResult(f, persist, dir);
			}
			catch (Exception ignored) { /* timeseries optional — keep the snapshot-based verdict */ }
		}
	}

	/** Fold a timeseries measurement (persistence + direction) into a pick's confidence/expGph + why.
	 *  Shared by the fresh-fetch and cache-hit paths. Applied once per scan to a fresh Flip, so there's
	 *  no double-application across scans (only the raw measurement is cached, not the applied state). */
	private void applyTsResult(Flip f, double persist, double dir)
	{
		f.marginPersist = persist; f.tsDir = dir; f.tsChecked = true;
		double tsConf = 0.4 + 0.6 * persist;          // 0.4 (never present) .. 1.0 (always present)
		if (dir < -0.03) tsConf *= 0.7;               // falling over the window
		f.confidence *= tsConf; f.expGph *= tsConf;
		// don't clobber a personalised "your winner/loser" note with a merely-reliable ts note
		if (persist < 0.3)
			f.why = "Margin present only ~" + (int) (persist * 100) + "% of the last 2h — likely a phantom";
		else if (dir < -0.03)
			f.why = "Price fell ~" + (int) Math.round(-dir * 100) + "% over 2h — the margin may not survive";
		else if (persist >= 0.7 && !f.personalized)
			f.why = "Reliable: the margin held ~" + (int) (persist * 100) + "% of the last 2h";
	}

	/** #3 — mark a capital/slot basket: greedily fill your free GE slots from the ranked list, sizing
	 *  each by what your cash and its buy limit allow, so you see WHAT to actually put in your slots. */
	void basket(List<Flip> ranked, long cashGp, int slots)
	{
		long cash = Math.max(0, cashGp);
		int used = 0;
		for (Flip f : ranked)
		{
			if (used >= slots || cash <= 0) break;
			if (f.wontFill || f.buy <= 0) continue;
			int q = (int) Math.min(f.quantity, cash / f.buy);
			if (q <= 0) continue;
			f.basketQty = q;
			cash -= (long) q * f.buy;
			used++;
		}
	}
}
