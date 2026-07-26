package com.geflip;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * geflip — ranks GE flips by gp/hour in a side panel and tracks your own fills for a
 * live session P&L. READ-ONLY by construction: it reads offer events and shows
 * suggestions; it never sends input or places/collects an offer (that would be
 * macroing = a ban). A scaffold to build + test against the live client — see README.
 */
@Slf4j
@PluginDescriptor(
	name = "Geflip",
	description = "Ranks GE flips by gp/hour (tax, limits, liquidity, long-term trend); tracks your fills.",
	tags = {"flipping", "grand", "exchange", "ge", "money", "merchanting"}
)
public class GeflipPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientToolbar clientToolbar;
	@Inject private GeflipConfig config;
	@Inject private ScheduledExecutorService executor;
	@Inject private net.runelite.client.Notifier notifier;
	@Inject private net.runelite.client.callback.ClientThread clientThread;
	@Inject private net.runelite.client.config.ConfigManager configManager;

	// held items we've already crash-alerted on, so we don't spam (re-armed on recovery)
	private final java.util.Set<Integer> dumpAlerted = java.util.concurrent.ConcurrentHashMap.newKeySet();
	// your manual cost corrections (id -> real avg cost) for holdings the plugin mis-costed
	// because it didn't see every buy (mobile / offline). Persisted.
	private final java.util.Map<Integer, Long> costOverride = new java.util.concurrent.ConcurrentHashMap<>();

	// live coins you actually have (inventory + bank when opened); −1 = unknown yet
	private volatile long liveGp = -1;
	// snapshots of what you actually hold (item id -> qty), taken on the client thread so the
	// off-thread holdings reconcile never touches live containers. null = not read yet.
	private volatile java.util.Map<Integer, Integer> invCounts;
	private volatile java.util.Map<Integer, Integer> bankCounts;

	private final GeflipScanner scanner = new GeflipScanner();
	private GeflipPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> refresh;

	// honest flip P&L: recomputed by matching buys->sells (never a drifting running total)
	private volatile GeflipLedger ledger = new GeflipLedger();
	// persisted fill log, so history survives restarts and accumulates across sessions
	private final java.io.File fillsFile =
		new java.io.File(net.runelite.client.RuneLite.RUNELITE_DIR, "geflip/fills.json");

	// local bridge: serves the UI + these live fills to the web app on your network
	private GeflipServer bridge;
	private final java.util.List<Fill> fills = new java.util.concurrent.CopyOnWriteArrayList<>();
	// the 8 GE slots as the client last reported them — your live open offers
	private final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
	// last completed-offer signature per slot, so a login-replayed BOUGHT/SOLD isn't
	// logged twice; cleared when the slot empties. Persisted with the fills.
	private final String[] slotSig = new String[8];
	// when the CURRENT offer in each slot was first seen (ms), to flag stale unfilled offers
	private final long[] slotSince = new long[8];
	private final String[] slotKey = new String[8];
	private int tickCounter;   // paces the periodic offer-age refresh
	// per-item 4h buy-limit window: id -> [windowStartMs, unitsBoughtThisWindow]
	private final java.util.Map<Integer, long[]> buyWindows = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long BUY_WINDOW_MS = 4 * 60 * 60 * 1000L;
	// the last ranked flips, shared so the web app/phone shows exactly what the panel shows
	private volatile java.util.List<GeflipScanner.Flip> lastFlips = java.util.Collections.emptyList();
	// immutable snapshot of your GE offers, built ONLY on the client thread and published
	// here so the bridge/cloud (other threads) never touch live client objects or slots[]
	private volatile java.util.List<Offer> offerSnapshot = java.util.Collections.emptyList();

	/** One real GE fill. Carries the item ID; the web app resolves the name from /mapping. */
	static final class Fill
	{
		final int id; final String side; final int price, qty, tax; final long ts;
		Fill(int id, String side, int price, int qty, int tax, long ts)
		{ this.id = id; this.side = side; this.price = price; this.qty = qty; this.tax = tax; this.ts = ts; }
	}
	static final class Session
	{
		final long realized, deployed, kept; final int flips, held;
		Session(long realized, long deployed, long kept, int flips, int held)
		{ this.realized = realized; this.deployed = deployed; this.kept = kept; this.flips = flips; this.held = held; }
	}

	/** One live GE slot (an open/finished offer). Progress = qtySold / qtyTotal. */
	static final class Offer
	{
		final int slot, id; final String state; final int price, qtySold, qtyTotal; final long spent;
		String name; long ageSec; boolean stale;
		Offer(int slot, int id, String state, int price, int qtySold, int qtyTotal, long spent)
		{ this.slot = slot; this.id = id; this.state = state; this.price = price;
		  this.qtySold = qtySold; this.qtyTotal = qtyTotal; this.spent = spent; }
	}

	static final class State
	{
		final boolean ok = true; final long ts = System.currentTimeMillis() / 1000;
		Session session; java.util.List<Fill> fills; java.util.List<GeflipScanner.Flip> flips;
		java.util.List<Offer> offers;
	}

	/** Units still buyable this 4h window per item (only items with an active window). */
	private java.util.Map<Integer, Integer> remainingLimits()
	{
		java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
		long nowMs = System.currentTimeMillis();
		for (java.util.Map.Entry<Integer, long[]> en : buyWindows.entrySet())
		{
			long[] w = en.getValue();
			if (nowMs - w[0] >= BUY_WINDOW_MS) continue;   // window expired → full limit, no cap
			int lim = scanner.limitFor(en.getKey());
			if (lim <= 0) continue;                        // unknown limit → don't cap
			out.put(en.getKey(), (int) Math.max(0, lim - w[1]));
		}
		return out;
	}

	/** Minutes until an item's 4h buy window resets (−1 if none active). */
	int limitResetMins(int id)
	{
		long[] w = buyWindows.get(id);
		if (w == null) return -1;
		long left = BUY_WINDOW_MS - (System.currentTimeMillis() - w[0]);
		return left <= 0 ? -1 : (int) (left / 60000);
	}

	/** Lower-cased set of the "not a flip" item names from config. */
	private java.util.Set<String> excludeLowered()
	{
		java.util.Set<String> out = new java.util.HashSet<>();
		String s = config.excludeItems();
		if (s != null)
			for (String part : s.split(","))
			{
				String t = part.trim().toLowerCase();
				if (!t.isEmpty()) out.add(t);
			}
		return out;
	}

	/** An item you're holding (bought, not yet sold) + where to list it to sell. */
	static final class Hold
	{
		final int id; final String name; final int qty; final long avgCost; final int sellHint; final boolean exempt;
		Hold(int id, String name, int qty, long avgCost, int sellHint, boolean exempt)
		{ this.id = id; this.name = name; this.qty = qty; this.avgCost = avgCost; this.sellHint = sellHint; this.exempt = exempt; }
	}

	/**
	 * Everything you're holding, biggest first, with the current recommended sell price.
	 * RECONCILED against what you actually possess: an item you no longer have (inventory +
	 * bank read, none left, not in a GE slot) is dropped automatically; a partial sale caps
	 * the shown qty. When the bank hasn't been opened we can't be sure, so we keep it (the ✓
	 * button is the manual fallback there).
	 */
	private java.util.List<Hold> buildHoldings()
	{
		boolean invKnown = invCounts != null;      // logged in & inventory read
		boolean bankKnown = bankCounts != null;    // bank opened this session
		java.util.List<Hold> out = new java.util.ArrayList<>();
		for (java.util.Map.Entry<Integer, long[]> e : ledger.holdings.entrySet())
		{
			int id = e.getKey();
			int qty = (int) e.getValue()[0];
			if (qty <= 0) continue;
			// cost per unit: the ledger already folded in any manual correction, so this is
			// consistent with the session "held" total
			long avg = e.getValue()[1] / e.getValue()[0];
			// subtract what you've ALREADY LISTED for sale — that's not "to sell" anymore.
			// (only actively-SELLING units; a CANCELLED sell is back in your hands.)
			qty -= listedForSaleQty(id);
			if (qty <= 0) continue;
			if (invKnown)
			{
				int have = possessed(id);
				if (have <= 0)
				{
					// nothing in hand: keep only if it's an uncollected buy or the bank is unread
					if (!inActiveBuy(id) && bankKnown) continue;   // provably no longer held
				}
				else if (have < qty) qty = have;   // some sold — show only what's actually in hand
			}
			String nm = scanner.nameFor(id);
			out.add(new Hold(id, nm != null ? nm : "#" + id, qty, avg, scanner.sellHint(id), scanner.isExempt(id)));
		}
		out.sort((a, b) -> Long.compare((long) b.qty * b.avgCost, (long) a.qty * a.avgCost));
		return out;
	}

	/**
	 * Mark a held item as sold — records the sale the plugin missed (sold on mobile / while
	 * closed / a missed event) at the current market estimate, so it FIFO-matches the open
	 * lots and drops off "To sell". Runs on the client thread (button click).
	 */
	void clearHolding(int id)
	{
		// don't mark something still LISTED on the GE — the real SOLD event will book it, and a
		// synthetic sell now would double-count when it fills.
		if (inActiveGe(id))
		{
			if (panel != null) panel.setStatus("still listed on the GE — let it fill / collect first");
			return;
		}
		// do the mutation on the client thread so the slot arrays aren't cloned mid-write
		clientThread.invoke(() ->
		{
			long[] h = ledger.holdings.get(id);
			if (h == null || h[0] <= 0) return;
			int qty = (int) h[0];
			int price = scanner.sellHint(id);
			if (price <= 0) price = (int) (h[1] / Math.max(1, h[0]));   // no quote → book flat at cost
			int tax = GeflipScanner.saleTax(price, scanner.isExempt(id)) * qty;
			fills.add(new Fill(id, "SELL", price, qty, tax, System.currentTimeMillis() / 1000));
			if (fills.size() > 3000) fills.remove(0);
			java.util.List<Fill> snap = new java.util.ArrayList<>(fills);
			String[] sig = slotSig.clone(), key = slotKey.clone(); long[] since = slotSince.clone();
			java.util.Map<Integer, long[]> win = deepCopyWindows();
			executor.submit(() -> { saveFills(snap, sig, key, since, win); recompute(); });
		});
	}

	private java.util.Map<Integer, long[]> deepCopyWindows()
	{
		java.util.Map<Integer, long[]> m = new java.util.HashMap<>();
		for (java.util.Map.Entry<Integer, long[]> e : buyWindows.entrySet()) m.put(e.getKey(), e.getValue().clone());
		return m;
	}

	/**
	 * Crash guard: if an item you're HOLDING has fallen below your buy price (net of tax),
	 * push one notification so you can cut it before it drops further. Re-arms once it recovers.
	 */
	private void checkDumps()
	{
		if (!config.dumpAlerts()) return;
		java.util.Set<Integer> stillDown = new java.util.HashSet<>();
		for (java.util.Map.Entry<Integer, long[]> e : ledger.holdings.entrySet())
		{
			int id = e.getKey();
			// don't alert on something you provably no longer hold (sold on mobile etc.)
			if (invCounts != null && bankCounts != null && possessed(id) <= 0 && !inActiveGe(id)) continue;
			long cost = e.getValue()[0] > 0 ? e.getValue()[1] / e.getValue()[0] : 0;
			int sell = scanner.sellHint(id);
			if (sell <= 0 || cost <= 0) continue;
			long net = sell - GeflipScanner.saleTax(sell, scanner.isExempt(id));
			if (net < cost * 0.90)   // you're 10%+ underwater on a held item — it crashed
			{
				stillDown.add(id);
				if (dumpAlerted.add(id))   // first time we've seen this crash
				{
					String nm = scanner.nameFor(id);
					notifier.notify("Geflip: " + (nm != null ? nm : "#" + id) + " crashed to ~"
						+ gpn(net) + " (you paid ~" + gpn(cost) + ") — consider cutting.");
				}
			}
		}
		dumpAlerted.retainAll(stillDown);   // re-arm items that recovered
	}

	/** Look up the recommended buy/sell for ANY item by name — for the panel's price-check box. */
	String priceCheck(String name)
	{
		if (name == null || name.trim().isEmpty()) return "type an item name";
		int id = scanner.idForName(name);
		if (id < 0) return "\"" + name.trim() + "\" not found — check spelling / rescan";
		String nm = scanner.nameFor(id);
		int buy = scanner.buyHint(id), sell = scanner.sellHint(id);
		if (buy <= 0 && sell <= 0) return (nm != null ? nm : name) + ": no live price — hit Rescan first";
		StringBuilder s = new StringBuilder(nm != null ? nm : name).append(": ");
		if (buy > 0) s.append("buy ~").append(gpn(buy)).append("   ");
		if (sell > 0)
		{
			int net = sell - GeflipScanner.saleTax(sell, scanner.isExempt(id));
			s.append("sell ~").append(gpn(sell)).append(" (net ").append(gpn(net)).append(")");
		}
		return s.toString();
	}

	private static String gpn(long v) { return String.format("%,d", v); }

	/** Set your true average cost for a held item (fixes a mis-captured cost). cost<=0 clears it. */
	void setCost(int id, long cost)
	{
		if (cost > 0) costOverride.put(id, cost);
		else costOverride.remove(id);
		// PERSIST it (recompute alone never saves) — snapshot the arrays on the client thread
		clientThread.invoke(() ->
		{
			java.util.List<Fill> snap = new java.util.ArrayList<>(fills);
			String[] sig = slotSig.clone(), key = slotKey.clone(); long[] since = slotSince.clone();
			java.util.Map<Integer, long[]> win = deepCopyWindows();
			executor.submit(() -> { saveFills(snap, sig, key, since, win); recompute(); });
		});
	}

	/** Mark an item as PERSONAL USE (not a flip): adds it to the exclude list so it leaves
	 *  "To sell" and stays out of your flip P&L. */
	void markPersonalUse(int id)
	{
		String nm = scanner.nameFor(id);
		if (nm == null) return;
		String cur = config.excludeItems() == null ? "" : config.excludeItems().trim();
		// don't double-add
		for (String p : cur.split(",")) if (p.trim().equalsIgnoreCase(nm)) { recompute(); return; }
		String next = cur.isEmpty() ? nm : cur + ", " + nm;
		configManager.setConfiguration("geflip", "excludeItems", next);
		if (panel != null) panel.setStatus("kept \"" + nm + "\" — personal use, hidden from flips");
		recompute();
	}

	/** Rebuild the flip ledger from the raw fills + current exclude list, and refresh the panel. */
	private void recompute()
	{
		java.util.Set<Integer> excluded = scanner.idsForNames(excludeLowered());
		ledger = GeflipLedger.compute(fills, excluded, costOverride);
		if (panel != null) { panel.setSession(ledger); panel.setHoldings(buildHoldings()); }
	}

	/** On-disk shape: the fill log, per-slot dedup markers, offer-age clocks, buy windows. */
	private static final class Persist
	{
		java.util.List<Fill> fills; String[] slotSig, slotKey; long[] slotSince;
		java.util.Map<Integer, long[]> buyWindows;
		java.util.Map<Integer, Long> costOverride;
	}

	private void loadFills()
	{
		try
		{
			if (!fillsFile.isFile()) return;
			String json = new String(java.nio.file.Files.readAllBytes(fillsFile.toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
			Persist p = new com.google.gson.Gson().fromJson(json, Persist.class);
			if (p == null) return;
			if (p.fills != null) for (Fill f : p.fills) if (f != null) fills.add(f);
			if (p.slotSig != null)
				for (int i = 0; i < slotSig.length && i < p.slotSig.length; i++) slotSig[i] = p.slotSig[i];
			if (p.slotKey != null)
				for (int i = 0; i < slotKey.length && i < p.slotKey.length; i++) slotKey[i] = p.slotKey[i];
			if (p.slotSince != null)
				for (int i = 0; i < slotSince.length && i < p.slotSince.length; i++) slotSince[i] = p.slotSince[i];
			if (p.buyWindows != null) buyWindows.putAll(p.buyWindows);
			if (p.costOverride != null) costOverride.putAll(p.costOverride);
		}
		catch (Exception e) { log.debug("geflip: could not load fills history", e); }
	}

	/** Persist a client-thread SNAPSHOT (never the live arrays/list) to avoid a data race. */
	private synchronized void saveFills(java.util.List<Fill> fillSnap, String[] sig, String[] key, long[] since,
		java.util.Map<Integer, long[]> windows)
	{
		try
		{
			java.io.File dir = fillsFile.getParentFile();
			if (dir != null && !dir.isDirectory()) dir.mkdirs();
			Persist p = new Persist();
			// keep the log bounded — the most recent 3000 fills
			p.fills = fillSnap.size() > 3000
				? new java.util.ArrayList<>(fillSnap.subList(fillSnap.size() - 3000, fillSnap.size()))
				: fillSnap;
			p.slotSig = sig;
			p.slotKey = key;
			p.slotSince = since;
			p.buyWindows = windows;
			p.costOverride = new java.util.HashMap<>(costOverride);
			java.nio.file.Files.write(fillsFile.toPath(),
				new com.google.gson.Gson().toJson(p).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		catch (Exception e) { log.debug("geflip: could not save fills history", e); }
	}

	/** Snapshot the 8 GE slots into DTOs, skipping empty ones and naming each item. */
	private java.util.List<Offer> buildOffers()
	{
		java.util.List<Offer> out = new java.util.ArrayList<>();
		for (int i = 0; i < slots.length; i++)
		{
			GrandExchangeOffer o = slots[i];
			if (o == null) continue;
			GrandExchangeOfferState st = o.getState();
			if (st == null || st == GrandExchangeOfferState.EMPTY) continue;
			Offer of = new Offer(i, o.getItemId(), st.name(), o.getPrice(),
				o.getQuantitySold(), o.getTotalQuantity(), o.getSpent());
			of.name = scanner.nameFor(o.getItemId());
			// age + staleness: an in-progress offer that hasn't filled after staleHours
			// has almost certainly been priced out — reprice it instead of waiting days
			boolean inProgress = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING;
			if (slotSince[i] > 0) of.ageSec = (System.currentTimeMillis() - slotSince[i]) / 1000;
			of.stale = inProgress && o.getQuantitySold() < o.getTotalQuantity()
				&& of.ageSec >= Math.max(1, config.staleHours()) * 3600L;
			out.add(of);
		}
		return out;
	}

	private State buildState()
	{
		State s = new State();
		GeflipLedger l = ledger;
		s.session = new Session(l.realizedFlip, l.inventoryCost, l.keptNet, l.matchedUnits, l.openUnits);
		s.fills = fills;
		s.flips = lastFlips;      // the phone/web shows the same ranked flips the panel does
		s.offers = offerSnapshot; // client-thread snapshot — never touch live client objects here
		return s;
	}

	/**
	 * Push our slice (fills + session) to the cloud sync Worker. The Worker shallow-
	 * merges, so we never clobber the web app's `config`. Best-effort, off the client
	 * thread (called from the scan executor). READ-ONLY toward the game.
	 */
	private void cloudPush()
	{
		String url = config.cloudUrl(), id = config.cloudId();
		if (url == null || url.isEmpty() || id == null || id.length() < 16) return;
		try
		{
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.add("fills", new com.google.gson.Gson().toJsonTree(fills));
			o.add("flips", new com.google.gson.Gson().toJsonTree(lastFlips));
			o.add("offers", new com.google.gson.Gson().toJsonTree(offerSnapshot));
			GeflipLedger l = ledger;
			com.google.gson.JsonObject sess = new com.google.gson.JsonObject();
			sess.addProperty("realized", l.realizedFlip);
			sess.addProperty("deployed", l.inventoryCost);
			sess.addProperty("kept", l.keptNet);
			sess.addProperty("flips", l.matchedUnits);
			sess.addProperty("held", l.openUnits);
			sess.addProperty("roundTrips", l.flips);           // your record: completed flips
			sess.addProperty("winPct", (int) Math.round(l.winRate() * 100));
			sess.addProperty("holdH", l.avgHoldHours());
			sess.addProperty("perDay", l.realizedPerDay());    // realized flip profit / day
			o.add("session", sess);
			o.addProperty("updated", System.currentTimeMillis() / 1000);
			byte[] body = o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

			String full = url.replaceAll("/+$", "") + "/?id="
				+ java.net.URLEncoder.encode(id, "UTF-8");
			java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(full).openConnection();
			try
			{
				c.setRequestMethod("PUT");
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				c.setConnectTimeout(10000);
				c.setReadTimeout(15000);
				try (java.io.OutputStream os = c.getOutputStream()) { os.write(body); }
				int code = c.getResponseCode();
				if (code >= 300) log.debug("geflip cloud push http {}", code);
			}
			finally { c.disconnect(); }   // always release, even on exception
		}
		catch (Exception e) { log.debug("geflip cloud push failed", e); }
	}

	@Provides
	GeflipConfig provideConfig(net.runelite.client.config.ConfigManager cm)
	{
		return cm.getConfig(GeflipConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new GeflipPanel(this::triggerScan, this::clearHolding, this::priceCheck,
			(id, cost) -> setCost(id, cost), this::markPersonalUse);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/geflip_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Geflip")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		loadFills();     // restore the persisted fill history
		recompute();     // show flip P&L from it immediately (reflows once mapping loads)
		scheduleRefresh();
		triggerScan();

		if (config.bridgeEnabled())
		{
			try
			{
				bridge = new GeflipServer(config.bridgePort(), config.bridgeToken())
					.withState(this::buildState)
					.onConfigPost(body -> log.debug("bridge config: {}", body));
				bridge.start();
				log.info("geflip bridge on port {} — open http://<pc-ip>:{} on your phone",
					config.bridgePort(), config.bridgePort());
			}
			catch (Exception e) { log.warn("geflip bridge failed to start", e); }
		}
	}

	@Override
	protected void shutDown()
	{
		if (refresh != null) refresh.cancel(true);
		if (bridge != null) { bridge.stop(); bridge = null; }
		clientToolbar.removeNavigation(navButton);
		panel = null;
	}

	private void scheduleRefresh()
	{
		if (refresh != null) refresh.cancel(false);
		int s = Math.max(60, config.refreshSec());
		refresh = executor.scheduleWithFixedDelay(this::triggerScan, s, s, TimeUnit.SECONDS);
	}

	/** Off-thread scan → push rows to the panel. Wiki API is polite once/2min. */
	private void triggerScan()
	{
		executor.submit(() ->
		{
			GeflipPanel p = panel;    // may be nulled by shutDown() while this runs
			try
			{
				if (p != null) p.setStatus("scanning…");
				long bank = bankrollGp();
				java.util.List<GeflipScanner.Flip> flips = scanner.scan(config, remainingLimits(), bank);
				for (GeflipScanner.Flip f : flips) f.resetMins = limitResetMins(f.id);   // buy-limit timer
				if (p != null) p.setBankroll(bank, config.autoBankroll() && liveGp >= 0);
				lastFlips = flips;                       // share with the bridge/cloud
				if (p != null) { p.setFlips(flips); p.setStatus(flips.size() + " flips · " + timeNow()); }
				if (p != null) p.setDecants(scanner.scanDecants(config));   // decanting opportunities
				recompute();   // mapping is loaded now → exclude list resolves, P&L reflows
			}
			catch (Exception e)
			{
				log.warn("geflip scan failed", e);
				if (p != null) p.setStatus("scan failed — check connection");
			}
			checkDumps();  // warn if anything you HOLD has crashed below your buy
			cloudPush();   // push fills/session to the cloud store (no-op if not configured)
		});
	}

	/**
	 * Real fills → session P&L. This is the read-only heart of the RuneLite tie-in:
	 * the client hands us every offer state change; we account BOUGHT spend and SOLD
	 * proceeds net of the 2% tax. No input is ever sent back.
	 */
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged ev)
	{
		GrandExchangeOffer o = ev.getOffer();
		if (o == null) return;
		int slot = ev.getSlot();
		GrandExchangeOfferState st = o.getState();
		// mirror EVERY slot change so the panel/web knows your live open offers, not just fills
		if (slot >= 0 && slot < slots.length) slots[slot] = o;
		// stamp when THIS offer began (item+price+total), so we can age it. A new/changed
		// offer resets the clock; an empty slot clears it. Login-replay keeps the same key.
		if (slot >= 0 && slot < slotSince.length)
		{
			if (st == GrandExchangeOfferState.EMPTY) { slotSince[slot] = 0; slotKey[slot] = null; }
			else
			{
				String key = o.getItemId() + ":" + o.getPrice() + ":" + o.getTotalQuantity();
				if (!key.equals(slotKey[slot])) { slotKey[slot] = key; slotSince[slot] = System.currentTimeMillis(); }
			}
		}
		// build the offer snapshot HERE (client thread) and publish it for the panel + bridge/cloud
		java.util.List<Offer> snap = buildOffers();
		offerSnapshot = snap;
		if (panel != null) panel.setOffers(snap);

		// a collected/empty slot clears its dedup marker so the NEXT offer in that slot is
		// recorded even if it looks identical. (Cancels are NOT cleared here — we record
		// their filled portion below, then EMPTY clears the marker on collection.)
		if (slot >= 0 && slot < slotSig.length && st == GrandExchangeOfferState.EMPTY)
		{
			slotSig[slot] = null;
		}

		// Record the FILLED portion of any offer that's done moving gp: a full buy/sell,
		// OR a cancelled offer that partially filled first (else those units become phantom
		// profit when you later sell them — they'd have no cost basis in the ledger).
		boolean buy = st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.CANCELLED_BUY;
		boolean sell = st == GrandExchangeOfferState.SOLD || st == GrandExchangeOfferState.CANCELLED_SELL;
		if (!buy && !sell) return;

		long spent = o.getSpent();          // gp moved so far on this offer
		int qty = o.getQuantitySold();      // filled units
		if (qty <= 0) return;               // a cancel with nothing filled — nothing to record
		int unit = (int) (spent / qty);

		// dedup: a completed/cancelled offer re-fires with identical numbers on login replay
		String sig = st.name() + ":" + o.getItemId() + ":" + qty + ":" + spent;
		if (slot >= 0 && slot < slotSig.length)
		{
			if (sig.equals(slotSig[slot])) return;   // already recorded this one
			slotSig[slot] = sig;
		}

		long now = System.currentTimeMillis() / 1000;
		if (buy)
		{
			fills.add(new Fill(o.getItemId(), "BUY", unit, qty, 0, now));
			// advance the item's rolling 4h buy-limit window
			long nowMs = System.currentTimeMillis();
			long[] w = buyWindows.get(o.getItemId());
			// replace-on-write (never mutate a shared array in place — it's read off-thread)
			if (w == null || nowMs - w[0] >= BUY_WINDOW_MS) buyWindows.put(o.getItemId(), new long[]{ nowMs, qty });
			else buyWindows.put(o.getItemId(), new long[]{ w[0], w[1] + qty });
		}
		else // sell — record the 2% tax the GE takes on the sale (respecting tax-exempt items)
		{
			int tax = GeflipScanner.saleTax(unit, scanner.isExempt(o.getItemId())) * qty;
			fills.add(new Fill(o.getItemId(), "SELL", unit, qty, tax, now));
		}
		if (fills.size() > 3000) fills.remove(0);   // keep it bounded
		// snapshot the mutable state HERE (client thread), then persist + re-match off-thread
		// so the executor never serialises arrays/list the client thread is mutating.
		java.util.List<Fill> fillSnap = new java.util.ArrayList<>(fills);
		String[] sigSnap = slotSig.clone(), keySnap = slotKey.clone();
		long[] sinceSnap = slotSince.clone();
		java.util.Map<Integer, long[]> winSnap = deepCopyWindows();
		executor.submit(() -> { saveFills(fillSnap, sigSnap, keySnap, sinceSnap, winSnap); recompute(); });
	}

	/**
	 * Rebuild the offer snapshot on a clock (every ~6s) so an offer's age — and the stale
	 * flag — advances even when NO GE event fires (a priced-out offer that never fills emits
	 * no further events). Runs on the client thread, so reading live slots[] is safe.
	 */
	/** Track what you actually hold — inventory always, bank when it's been opened. Client thread. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged ev)
	{
		int id = ev.getContainerId();
		if (id == InventoryID.INVENTORY.getId()) invCounts = countMap(client.getItemContainer(InventoryID.INVENTORY));
		else if (id == InventoryID.BANK.getId()) bankCounts = countMap(client.getItemContainer(InventoryID.BANK));
		else return;
		long gp = 0; boolean known = false;
		if (invCounts != null) { gp += invCounts.getOrDefault(ItemID.COINS_995, 0); known = true; }
		if (bankCounts != null) { gp += bankCounts.getOrDefault(ItemID.COINS_995, 0); known = true; }
		if (known) liveGp = gp;
	}

	private static java.util.Map<Integer, Integer> countMap(ItemContainer c)
	{
		java.util.Map<Integer, Integer> m = new java.util.HashMap<>();
		if (c == null) return m;
		for (Item it : c.getItems())
		{
			if (it == null || it.getId() < 0) continue;
			m.merge(it.getId(), it.getQuantity(), Integer::sum);
		}
		return m;
	}

	/** How many of an item you actually possess (inventory + bank when opened). */
	private int possessed(int id)
	{
		int n = 0;
		java.util.Map<Integer, Integer> inv = invCounts, bank = bankCounts;
		if (inv != null) n += inv.getOrDefault(id, 0);
		if (bank != null) n += bank.getOrDefault(id, 0);
		return n;
	}

	/** Is this item currently sitting in one of your GE slots (in flight)? */
	private boolean inActiveGe(int id)
	{
		for (Offer o : offerSnapshot) if (o.id == id) return true;
		return false;
	}

	/** Are you currently BUYING this item (uncollected buy — still yours to sell later)? */
	private boolean inActiveBuy(int id)
	{
		for (Offer o : offerSnapshot) if (o.id == id && o.state != null && o.state.contains("BUY")) return true;
		return false;
	}

	/** Units of an item you've ALREADY LISTED for sale (actively SELLING, unfilled remainder).
	 *  A CANCELLED sell is NOT counted — those units are back in your hands to re-sell. */
	private int listedForSaleQty(int id)
	{
		int n = 0;
		for (Offer o : offerSnapshot)
			if (o.id == id && "SELLING".equals(o.state)) n += Math.max(0, o.qtyTotal - o.qtySold);
		return n;
	}

	/** The bankroll to size flips with: your real coins if auto is on and known, else the config. */
	private long bankrollGp()
	{
		if (config.autoBankroll() && liveGp >= 0) return Math.max(1, liveGp);
		return Math.max(1L, config.bankrollM() * 1_000_000L);
	}

	@Subscribe
	public void onGameTick(net.runelite.api.events.GameTick ev)
	{
		if (++tickCounter % 10 != 0) return;   // ~6s at 600ms/tick
		java.util.List<Offer> snap = buildOffers();
		offerSnapshot = snap;
		if (panel != null) panel.setOffers(snap);
	}

	private static String timeNow()
	{
		return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
	}
}
