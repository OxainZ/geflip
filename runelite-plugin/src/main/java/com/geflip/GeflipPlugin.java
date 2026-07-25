package com.geflip;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
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
	@Inject private ClientToolbar clientToolbar;
	@Inject private GeflipConfig config;
	@Inject private ScheduledExecutorService executor;

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

	/** Rebuild the flip ledger from the raw fills + current exclude list, and refresh the panel. */
	private void recompute()
	{
		java.util.Set<Integer> excluded = scanner.idsForNames(excludeLowered());
		ledger = GeflipLedger.compute(fills, excluded);
		if (panel != null) panel.setSession(ledger);
	}

	/** On-disk shape: the fill log, per-slot dedup markers, offer-age clocks, buy windows. */
	private static final class Persist
	{
		java.util.List<Fill> fills; String[] slotSig, slotKey; long[] slotSince;
		java.util.Map<Integer, long[]> buyWindows;
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
		panel = new GeflipPanel(this::triggerScan);
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
				java.util.List<GeflipScanner.Flip> flips = scanner.scan(config, remainingLimits());
				for (GeflipScanner.Flip f : flips) f.resetMins = limitResetMins(f.id);   // buy-limit timer
				lastFlips = flips;                       // share with the bridge/cloud
				if (p != null) { p.setFlips(flips); p.setStatus(flips.size() + " flips · " + timeNow()); }
				recompute();   // mapping is loaded now → exclude list resolves, P&L reflows
			}
			catch (Exception e)
			{
				log.warn("geflip scan failed", e);
				if (p != null) p.setStatus("scan failed — check connection");
			}
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
			if (w == null || nowMs - w[0] >= BUY_WINDOW_MS) buyWindows.put(o.getItemId(), new long[]{ nowMs, qty });
			else w[1] += qty;
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
		java.util.Map<Integer, long[]> winSnap = new java.util.HashMap<>(buyWindows);
		executor.submit(() -> { saveFills(fillSnap, sigSnap, keySnap, sinceSnap, winSnap); recompute(); });
	}

	/**
	 * Rebuild the offer snapshot on a clock (every ~6s) so an offer's age — and the stale
	 * flag — advances even when NO GE event fires (a priced-out offer that never fills emits
	 * no further events). Runs on the client thread, so reading live slots[] is safe.
	 */
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
