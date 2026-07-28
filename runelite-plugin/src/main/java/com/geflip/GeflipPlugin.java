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
	// items you're watching (persisted) + which we've already "cheap now" alerted on
	private final java.util.Set<Integer> watchlist = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final java.util.Set<Integer> watchAlerted = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final java.util.Set<Integer> sellAlerted = java.util.concurrent.ConcurrentHashMap.newKeySet();   // reprice nudges
	private volatile int lastCheckedId = -1;   // last item you priced (for the ☆ watch button)

	// live coins you actually have (inventory + bank when opened); −1 = unknown yet
	private volatile long liveGp = -1;
	// snapshots of what you actually hold (item id -> qty), taken on the client thread so the
	// off-thread holdings reconcile never touches live containers. null = not read yet.
	private volatile java.util.Map<Integer, Integer> invCounts;
	private volatile java.util.Set<Integer> excludedIds = java.util.Collections.emptySet();   // personal-use ids
	private final java.util.concurrent.atomic.AtomicBoolean scanning = new java.util.concurrent.atomic.AtomicBoolean(false);
	private final MarketClock marketClock = new MarketClock();   // hour-of-week activity logger
	private volatile java.util.concurrent.ScheduledFuture<?> persistDebounce;   // coalesces fill-event saves
	private volatile int pendingSoldId = -1;   // a sell seen during the debounce window (for the flip alert)
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
	// per-slot INCREMENTAL fill high-water mark: how much of the CURRENT offer in each slot has
	// already been booked as fills (cumulative units + gp). Each offer event reports CUMULATIVE
	// progress, so the delta since this mark is the newly-filled portion — that's how a PARTIAL
	// sell/buy shows up in P&L and To-sell the instant it fills, not only when the offer completes.
	// Persisted with the fills so a restart / login-replay re-books nothing and misses nothing
	// (GE offers keep filling while you're logged out).
	private final int[] slotBookedQty = new int[8];
	private final long[] slotBookedSpent = new long[8];
	// one-time upgrade guard: an old save lacks the fill marks. Until each slot is seeded once,
	// treat an in-slot offer that matches the saved key as already-booked (its fills are on disk).
	private volatile boolean upgradeMode = false;
	private final boolean[] slotSeeded = new boolean[8];
	// flip-complete alerts: the sell intent + item id are threaded through recompute(fromSell,soldId)
	// (NOT a shared flag), so only a real sell can fire. Baseline re-set on every recompute.
	private volatile long lastRealizedNotified = 0;
	private volatile boolean notifyPrimed = false;
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
		String name; long ageSec; boolean stale; int sellHint;   // recommended sell price for this item (live)
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

	/** A watched item with its live buy/sell + whether it's cheap right now. */
	static final class Watch
	{
		final int id; final String name; final int buy, sell; final boolean cheap;
		Watch(int id, String name, int buy, int sell, boolean cheap)
		{ this.id = id; this.name = name; this.buy = buy; this.sell = sell; this.cheap = cheap; }
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
		// OPT-IN: also surface tradeable items sitting in your INVENTORY that the flip ledger never tracked
		// you buying (drops, older buys, a bailed flip). OFF by default because it also catches PvM
		// supplies/gear you carry (potions, brews, bolts, boots) — turn on "Show bag items in To-sell" only
		// if you want everything in your bag listed. Tracked flip holdings always show regardless.
		java.util.Map<Integer, Integer> inv = invCounts;
		if (inv != null && config.sellBagItems())
		{
			java.util.Set<Integer> shown = new java.util.HashSet<>();
			for (Hold h : out) shown.add(h.id);
			for (java.util.Map.Entry<Integer, Integer> ie : inv.entrySet())
			{
				int id = ie.getKey();
				if (id == ItemID.COINS_995 || shown.contains(id) || excludedIds.contains(id)) continue;
				String nm = scanner.nameFor(id);
				if (nm == null) continue;                              // not on the GE mapping ⇒ can't sell it there
				int qty = ie.getValue();   // inventory count ALREADY excludes units listed on the GE (listing
				if (qty <= 0) continue;    // moves them out of your bag) — don't subtract listedForSaleQty again
				out.add(new Hold(id, nm, qty, -1, scanner.sellHint(id), scanner.isExempt(id)));   // −1 = cost untracked
			}
		}
		out.sort((a, b) -> Long.compare((long) b.qty * Math.max(0, b.avgCost), (long) a.qty * Math.max(0, a.avgCost)));
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
			pruneFills();
			java.util.List<Fill> snap = new java.util.ArrayList<>(fills);
			String[] sig = slotSig.clone(), key = slotKey.clone(); long[] since = slotSince.clone();
			int[] bk = slotBookedQty.clone(); long[] bks = slotBookedSpent.clone();
			java.util.Map<Integer, long[]> win = deepCopyWindows();
			executor.submit(() -> { saveFills(snap, sig, key, since, win, bk, bks); recompute(); });
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

	/** #3 — SELL-side reprice nudge (every other alert is buy-side). Pings once when a resting sell has
	 *  gone stale (unfilled past your stale-hours) OR is now listed above what buyers are actually paying,
	 *  so capital isn't stuck in a mispriced sell. Re-arms when the offer is repriced/fills/cancels. */
	private void checkSells()
	{
		if (!config.sellAlerts()) return;
		java.util.Set<Integer> stillStuck = new java.util.HashSet<>();
		for (Offer o : offerSnapshot)
		{
			if (o == null || !"SELLING".equals(o.state)) continue;   // active, not-yet-filled sells only
			int hint = scanner.sellHint(o.id);
			if (hint <= 0) continue;
			boolean mispriced = o.price > hint + GeflipScanner.tickSize(hint);   // above what buyers pay now
			if (o.stale || mispriced)
			{
				stillStuck.add(o.id);
				if (sellAlerted.add(o.id))
				{
					String nm = scanner.nameFor(o.id);
					String why = mispriced
						? "listed at " + gpn(o.price) + " but buyers are at ~" + gpn(hint) + " — reprice down to fill"
						: "unfilled for " + fmtDur(o.ageSec) + " — the price moved, reprice it";
					notifier.notify("Geflip sell: " + (nm != null ? nm : "#" + o.id) + " " + why);
				}
			}
		}
		sellAlerted.retainAll(stillStuck);   // re-arm once it's repriced / filled / cancelled
	}

	/** Look up the recommended buy/sell for ANY item by name — for the panel's price-check box. */
	String priceCheck(String name)
	{
		if (name == null || name.trim().isEmpty()) return "type an item name";
		int id = scanner.idForName(name);
		if (id < 0) return "\"" + name.trim() + "\" not found — check spelling / rescan";
		lastCheckedId = id;   // remember for the ☆ watch button
		String nm = scanner.nameFor(id);
		int buy = scanner.buyHint(id), sell = scanner.sellHint(id);
		if (buy <= 0 && sell <= 0) return (nm != null ? nm : name) + ": no live price — hit Rescan first";
		String head = nm != null ? nm : name;
		// only one side quoted — can't compute a margin, just show what we have
		if (buy <= 0 || sell <= 0)
		{
			if (sell <= 0) return head + ": buy ~" + gpn(buy) + " (no sell quote yet)";
			int net1 = sell - GeflipScanner.saleTax(sell, scanner.isExempt(id));
			return head + ": sell ~" + gpn(sell) + " (net " + gpn(net1) + ", no buy quote yet)";
		}
		// both sides quoted → the answer is the MARGIN. buyHint overcuts a tick and sellHint
		// undercuts a tick to actually fill, so on a razor-tight item buy can exceed sell — that's
		// not a bug, it means there's no flip. Lead with the per-item profit so it's unambiguous.
		int net = sell - GeflipScanner.saleTax(sell, scanner.isExempt(id));
		int margin = net - buy;   // real gp/item if you buy at buy and sell at sell, after the 2% tax
		String line = head + ": buy ~" + gpn(buy) + " → sell ~" + gpn(sell) + " (net " + gpn(net) + ")";
		if (margin > 0) return line + "  = +" + gpn(margin) + "/ea";
		return line + "  = " + gpn(margin) + "/ea  ✗ no margin (spread too tight)";
	}

	private static String gpn(long v) { return String.format("%,d", v); }

	/** Compact human duration from seconds. Uses "min"/"h" NOT "m" — in this app "m" means
	 *  MILLIONS of gp, so "25m hold" read as 25M; "25min" can't be misread. */
	private static String fmtDur(double sec)
	{
		if (sec < 90) return Math.round(sec) + "s";
		if (sec < 5400) return Math.round(sec / 60.0) + "min";
		return String.format("%.1fh", sec / 3600.0);
	}

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
			int[] bk = slotBookedQty.clone(); long[] bks = slotBookedSpent.clone();
			java.util.Map<Integer, long[]> win = deepCopyWindows();
			executor.submit(() -> { saveFills(snap, sig, key, since, win, bk, bks); recompute(); });
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
		for (String p : cur.split(",")) if (p.trim().equalsIgnoreCase(nm)) { recomputeAsync(); return; }
		String next = cur.isEmpty() ? nm : cur + ", " + nm;
		configManager.setConfiguration("geflip", "excludeItems", next);
		if (panel != null) panel.setStatus("kept \"" + nm + "\" — personal use, hidden from flips");
		recomputeAsync();
	}

	/** Add/remove the last price-checked item from your watchlist (☆ button). */
	void toggleWatchLast()
	{
		int id = lastCheckedId;
		if (id <= 0) { if (panel != null) panel.setStatus("price-check an item first, then ☆ to watch it"); return; }
		if (!watchlist.remove(id)) watchlist.add(id);
		persist();
		recomputeAsync();
	}

	/** Remove an item from the watchlist (✕ on a watch row). */
	void unwatch(int id) { watchlist.remove(id); persist(); recomputeAsync(); }

	/** Run recompute() off the EDT (these are Swing button callbacks) so the ledger replay + the
	 *  volatile ledger write happen on the executor, consistent with the scan/fill paths. */
	private void recomputeAsync() { executor.submit(() -> recompute()); }

	/** Live buy/sell for each watched item, cheap ones first. */
	private java.util.List<Watch> buildWatch()
	{
		java.util.List<Watch> out = new java.util.ArrayList<>();
		for (int id : watchlist)
		{
			String nm = scanner.nameFor(id);
			out.add(new Watch(id, nm != null ? nm : "#" + id, scanner.buyHint(id), scanner.sellHint(id), scanner.isCheap(id)));
		}
		out.sort((a, b) -> Boolean.compare(b.cheap, a.cheap));
		return out;
	}

	/** Ping once when a watched item goes cheap (a buy window); re-arm when it recovers. */
	private void checkWatch()
	{
		java.util.Set<Integer> cheapNow = new java.util.HashSet<>();
		for (int id : watchlist)
		{
			if (!scanner.isCheap(id)) continue;
			cheapNow.add(id);
			if (watchAlerted.add(id))
			{
				String nm = scanner.nameFor(id);
				int buy = scanner.buyHint(id);
				notifier.notify("Geflip watch: " + (nm != null ? nm : "#" + id) + " is cheap now — buy ~" + gpn(buy));
			}
		}
		watchAlerted.retainAll(cheapNow);
	}

	/** Wipe the realized-P&L journal: fill log + all slot marks + cost corrections + buy windows,
	 *  then persist the empty state. KEEPS your watchlist and exclude list. Ongoing GE offers are
	 *  seeded to "now" so their already-filled units aren't dumped back in — you start counting
	 *  fresh from this moment. Runs on the client thread (needs the live slots). */
	void resetJournal()
	{
		clientThread.invoke(() ->
		{
			// MUST be able to read the live offers to seed ongoing ones — otherwise a wipe persisted
			// while logged out (null offers) leaves in-flight offers unseeded, and on next login their
			// already-filled units get re-booked as "new", re-adding exactly what we wiped. Refuse.
			GrandExchangeOffer[] live = client.getGrandExchangeOffers();
			if (live == null)
			{
				if (panel != null) panel.setStatus("log in first, then reset — can't read your live offers");
				return;
			}
			fills.clear();
			costOverride.clear();
			buyWindows.clear();
			java.util.Arrays.fill(slotSig, null);
			java.util.Arrays.fill(slotKey, null);
			java.util.Arrays.fill(slotSince, 0L);
			java.util.Arrays.fill(slotBookedQty, 0);
			java.util.Arrays.fill(slotBookedSpent, 0L);
			java.util.Arrays.fill(slotSeeded, true);   // manual reset — never re-seed from a stale save
			upgradeMode = false;
			// seed marks from the CURRENT live offers so an in-progress offer tracks from now, not
			// re-booking the portion that filled before the reset.
			for (int i = 0; i < live.length && i < slots.length; i++)
			{
				GrandExchangeOffer o = live[i];
				if (o == null || o.getState() == GrandExchangeOfferState.EMPTY) continue;
				slotKey[i] = o.getItemId() + ":" + o.getPrice() + ":" + o.getTotalQuantity();
				slotBookedQty[i] = o.getQuantitySold();
				slotBookedSpent[i] = o.getSpent();
				slotSince[i] = System.currentTimeMillis();
			}
			java.util.List<Fill> snap = new java.util.ArrayList<>(fills);
			String[] sig = slotSig.clone(), key = slotKey.clone(); long[] since = slotSince.clone();
			int[] bk = slotBookedQty.clone(); long[] bks = slotBookedSpent.clone();
			java.util.Map<Integer, long[]> win = deepCopyWindows();
			executor.submit(() -> { saveFills(snap, sig, key, since, win, bk, bks); recompute(); });
		});
		if (panel != null) panel.setStatus("journal reset — P&L cleared");
	}

	/** Persist the fill log + markers off-thread (snapshots taken on the client thread). */
	private void persist()
	{
		clientThread.invoke(() ->
		{
			java.util.List<Fill> snap = new java.util.ArrayList<>(fills);
			String[] sig = slotSig.clone(), key = slotKey.clone(); long[] since = slotSince.clone();
			int[] bk = slotBookedQty.clone(); long[] bks = slotBookedSpent.clone();
			java.util.Map<Integer, long[]> win = deepCopyWindows();
			executor.submit(() -> saveFills(snap, sig, key, since, win, bk, bks));
		});
	}

	/** #1 — proven winners (≥3 flips, net positive) that AREN'T in the current ranked list, each with the
	 *  reason it's suppressed right now (thin volume / margin below your min / falling / spike / stale).
	 *  Turns "my winner went quiet" from a black box into an explanation. */
	private java.util.List<String> suppressedWinners(java.util.List<GeflipScanner.Flip> shown)
	{
		java.util.Set<Integer> shownIds = new java.util.HashSet<>();
		for (GeflipScanner.Flip f : shown) shownIds.add(f.id);
		java.util.List<java.util.Map.Entry<Integer, long[]>> winners = new java.util.ArrayList<>();
		for (java.util.Map.Entry<Integer, long[]> e : ledger.byItem.entrySet())
			if (e.getValue()[0] > 0 && e.getValue()[1] >= 3) winners.add(e);   // net profit > 0, ≥3 flips
		winners.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
		java.util.List<String> out = new java.util.ArrayList<>();
		for (java.util.Map.Entry<Integer, long[]> e : winners)
		{
			if (out.size() >= 8) break;
			int id = e.getKey();
			if (shownIds.contains(id)) continue;   // it IS showing — nothing to explain
			String why = scanner.whyNotShowing(id, config);
			if (why == null) continue;
			String nm = scanner.nameFor(id);
			out.add((nm != null ? nm : "#" + id) + " — " + why);
		}
		return out;
	}

	/** Cross-reference: price the account shopping list the COACH published (farm seeds/supplies) against
	 *  live GE quotes, so buying your progression is one glance in the flipper. Empty if the Coach isn't
	 *  running / farming helper is off. */
	private java.util.List<String> buildAccountNeeds()
	{
		java.util.List<GeflipShared.Need> needs = GeflipShared.needs();
		if (needs.isEmpty()) return java.util.Collections.emptyList();
		java.util.List<String> out = new java.util.ArrayList<>();
		for (GeflipShared.Need n : needs)
		{
			java.util.Set<Integer> ids = scanner.idsForNames(java.util.Collections.singleton(n.item.toLowerCase()));
			Integer id = ids.isEmpty() ? null : ids.iterator().next();
			if (id == null) { out.add(n.item + " ×" + n.qty + "  — price n/a  · " + n.reason); continue; }
			int buy = scanner.buyHint(id);
			boolean cheap = scanner.isCheap(id);
			out.add(n.item + " ×" + n.qty + "  — buy ~" + gpn(buy) + " ea" + (cheap ? "  🔥cheap" : "") + "  · " + n.reason);
		}
		return out;
	}

	/** Your TRUSTED STABLE: items you consistently profit on (≥5 completed flips, ≥60% win-rate, net
	 *  positive) — the reliable core to flip repeatedly, vs the scanner's opportunistic satellite. */
	private java.util.List<String> stable()
	{
		java.util.List<java.util.Map.Entry<Integer, long[]>> items = new java.util.ArrayList<>(ledger.byItem.entrySet());
		items.removeIf(e -> { long[] v = e.getValue(); return !(v[1] >= 5 && v[0] > 0 && (double) v[2] / v[1] >= 0.6); });
		items.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
		java.util.List<String> out = new java.util.ArrayList<>();
		for (int i = 0; i < items.size() && i < 10; i++)
		{
			java.util.Map.Entry<Integer, long[]> e = items.get(i);
			long[] v = e.getValue();
			String nm = scanner.nameFor(e.getKey());
			out.add((nm != null ? nm : "#" + e.getKey()) + " — " + (int) Math.round(100.0 * v[2] / v[1]) + "% win · " + gpn(v[0]) + " total");
		}
		return out;
	}

	/** Your items ranked by realized profit — the journal analytics ("what actually pays me"). */
	private java.util.List<String> topItems()
	{
		java.util.List<java.util.Map.Entry<Integer, long[]>> items = new java.util.ArrayList<>(ledger.byItem.entrySet());
		items.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
		java.util.List<String> lines = new java.util.ArrayList<>();
		// show EVERY item you've flipped (sorted by realized profit — winners top, losers bottom), not
		// just the top few: the whole point of the journal is to see the full ledger, and it scrolls.
		for (int i = 0; i < items.size(); i++)
		{
			java.util.Map.Entry<Integer, long[]> e = items.get(i);
			String nm = scanner.nameFor(e.getKey());
			long[] v = e.getValue();
			long profit = v[0]; long flips = v[1];
			long wins = v.length > 2 ? v[2] : 0;
			long holdSec = v.length > 3 ? v[3] : 0;
			long units = v.length > 4 ? v[4] : 0;
			int winPct = flips > 0 ? (int) Math.round(100.0 * wins / flips) : 0;
			String hold = units > 0 ? fmtDur(holdSec / (double) units) : "?";
			lines.add((nm != null ? nm : "#" + e.getKey()) + ": "
				+ (profit >= 0 ? "+" : "") + gpn(profit)
				+ " (" + flips + " flips, " + winPct + "% win, ~" + hold + " hold)");
		}
		return lines;
	}

	private void recompute() { recompute(false, -1); }

	/** Rebuild the flip ledger from the raw fills + current exclude list, and refresh the panel.
	 *  The flip-complete alert fires ONLY when fromSell is true (the sell-fill path passes the sold
	 *  item id) — so an exclude edit, rescan, or journal reset can never mis-fire it. We ALWAYS
	 *  re-baseline lastRealizedNotified afterwards, so the next sell's delta is exactly its profit
	 *  regardless of what changed realizedFlip in between (reset to 0, exclude move, etc.). */
	private void recompute(boolean fromSell, int soldId)
	{
		java.util.Set<Integer> excluded = scanner.idsForNames(excludeLowered());
		excludedIds = excluded;   // shared with buildHoldings so personal-use items don't show as "to sell"
		ledger = GeflipLedger.compute(fills, excluded, costOverride);
		if (notifyPrimed && fromSell && config.flipAlerts() && ledger.realizedFlip != lastRealizedNotified)
		{
			long delta = ledger.realizedFlip - lastRealizedNotified;
			String nm = scanner.nameFor(soldId);
			notifier.notify("Geflip: sold " + (nm != null ? nm : "item")
				+ " → " + (delta >= 0 ? "+" : "") + gpn(delta) + " flip profit");
		}
		lastRealizedNotified = ledger.realizedFlip;   // re-baseline on EVERY recompute (sell or not)
		notifyPrimed = true;
		if (panel != null)
		{
			panel.setSession(ledger); panel.setHoldings(buildHoldings());
			panel.setTopItems(topItems()); panel.setWatch(buildWatch());
			// capital-utilization meter: stock held + gp tied up in pending buys, vs bankroll
			long working = ledger.inventoryCost;
			int slotsUsed = 0;
			for (Offer o : offerSnapshot)
			{
				slotsUsed++;
				if ("BUYING".equals(o.state)) working += (long) o.price * Math.max(0, o.qtyTotal - o.qtySold);
			}
			panel.setCapital(working, bankrollGp(), slotsUsed);
		}
	}

	/** On-disk shape: the fill log, per-slot dedup markers, offer-age clocks, buy windows. */
	private static final class Persist
	{
		java.util.List<Fill> fills; String[] slotSig, slotKey; long[] slotSince;
		int[] slotBookedQty; long[] slotBookedSpent;   // incremental-fill high-water marks
		java.util.Map<Integer, long[]> buyWindows;
		java.util.Map<Integer, Long> costOverride;
		java.util.List<Integer> watchlist;
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
			if (p.slotBookedQty != null)
				for (int i = 0; i < slotBookedQty.length && i < p.slotBookedQty.length; i++) slotBookedQty[i] = p.slotBookedQty[i];
			if (p.slotBookedSpent != null)
				for (int i = 0; i < slotBookedSpent.length && i < p.slotBookedSpent.length; i++) slotBookedSpent[i] = p.slotBookedSpent[i];
			// no marks in the save = a pre-mark version wrote it → enter one-time upgrade mode so the
			// first sight of each still-open offer seeds (not re-books) its already-recorded fills.
			upgradeMode = (p.slotBookedQty == null || p.slotBookedSpent == null) && p.fills != null && !p.fills.isEmpty();
			if (p.buyWindows != null) buyWindows.putAll(p.buyWindows);
			if (p.costOverride != null) costOverride.putAll(p.costOverride);
			if (p.watchlist != null) watchlist.addAll(p.watchlist);
		}
		catch (Exception e) { log.debug("geflip: could not load fills history", e); }
	}

	/** Persist a client-thread SNAPSHOT (never the live arrays/list) to avoid a data race. */
	private synchronized void saveFills(java.util.List<Fill> fillSnap, String[] sig, String[] key, long[] since,
		java.util.Map<Integer, long[]> windows, int[] booked, long[] bookedSpent)
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
			p.slotBookedQty = booked;
			p.slotBookedSpent = bookedSpent;
			p.buyWindows = windows;
			p.costOverride = new java.util.HashMap<>(costOverride);
			p.watchlist = new java.util.ArrayList<>(watchlist);
			// atomic write: a crash mid-write (this PC shuts down nightly) must never truncate the P&L journal.
			byte[] data = new com.google.gson.Gson().toJson(p).getBytes(java.nio.charset.StandardCharsets.UTF_8);
			java.nio.file.Path dest = fillsFile.toPath();
			java.nio.file.Path tmp = dest.resolveSibling(fillsFile.getName() + ".tmp");
			java.nio.file.Files.write(tmp, data);
			try
			{
				java.nio.file.Files.move(tmp, dest,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			catch (java.nio.file.AtomicMoveNotSupportedException amnse)   // some filesystems don't support atomic move
			{
				java.nio.file.Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
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
			of.sellHint = scanner.sellHint(o.getItemId());   // recommended sell price, shown on the row
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
			// cap the pushed fills to the last 3000 (matches the disk cap) so a marathon session doesn't
			// serialize + PUT a huge payload every scan
			java.util.List<Fill> fillsOut = fills.size() > 3000
				? new java.util.ArrayList<>(fills.subList(fills.size() - 3000, fills.size())) : fills;
			o.add("fills", new com.google.gson.Gson().toJsonTree(fillsOut));
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
		marketClock.loadCsv(configManager.getConfiguration("geflip", "hourlyVol"));   // resume the activity log
		panel = new GeflipPanel(this::triggerScan, this::clearHolding, this::priceCheck,
			(id, cost) -> setCost(id, cost), this::markPersonalUse, this::toggleWatchLast, this::unwatch,
			this::resetJournal);
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
		// reconcile against the live GE slots now, in case the plugin was enabled mid-session
		// (RuneLite only replays offer events on login) — the truest fill source we can read.
		clientThread.invoke(this::reconcileGeSlots);
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
		if (persistDebounce != null) persistDebounce.cancel(false);   // a pending batch is re-booked by reconcile on next login
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
			// don't let scans pile up: the fixed-delay scheduler fires every refreshSec regardless of how
			// long the previous scan took, and a slow/rate-limited wiki can make the timeseries pass run
			// long — overlapping scans would amplify the load into a feedback loop. Skip if one's running.
			if (!scanning.compareAndSet(false, true)) return;
			GeflipPanel p = panel;    // may be nulled by shutDown() while this runs
			try
			{
				if (p != null) p.setStatus("scanning…");
				long bank = bankrollGp();
				// pass your realised per-item journal so the scan personalises confidence (#2)
				java.util.List<GeflipScanner.Flip> flips = scanner.scan(config, remainingLimits(), bank, ledger.byItem);
				for (GeflipScanner.Flip f : flips) f.resetMins = limitResetMins(f.id);   // buy-limit timer
				if (p != null) p.setBankroll(bank, config.autoBankroll() && liveGp >= 0);
				lastFlips = flips;                       // share with the bridge/cloud
				// hour-of-week logger: record the current global market activity, persist, and surface it
				int bin = MarketClock.hourOfWeekUtc();
				long volIdx = scanner.globalVolumeIndex();
				if (volIdx > 0) { marketClock.record(bin, volIdx); configManager.setConfiguration("geflip", "hourlyVol", marketClock.toCsv()); }
				int act = marketClock.activityPct(bin);
				String actStr = act >= 0 ? "  · mkt " + act + "% of peak" + (act >= 80 ? " (fast fills)" : act <= 40 ? " (slow)" : "") : "";
				if (p != null) { p.setFlips(flips); p.setStatus(flips.size() + " finds · " + timeNow() + actStr); }
				if (p != null) p.setSuppressedWinners(suppressedWinners(flips));   // #1: proven winners not showing + why
				if (p != null) p.setStable(stable());                             // your consistent-winner stable
				if (p != null) p.setAccountNeeds(buildAccountNeeds());            // cross-ref: what the Coach says your account needs
				if (p != null) p.setDecants(scanner.scanDecants(config));   // decanting opportunities
				if (p != null) p.setSets(scanner.scanSets(config));         // set-exchange arbitrage
				java.util.List<GeflipScanner.Alch> alchs = scanner.scanAlch(config);
				if (p != null) p.setAlch(alchs);                            // high-alch edge (tax-free)
				if (p != null) p.setProcessing(scanner.scanProcessing(config));   // processing arbitrage edges
				if (p != null) p.setRepairs(scanner.scanRepairs(config));         // Barrows-repair arbitrage
				if (p != null) p.setMovers(scanner.scanAnomalies(config));        // front-run / crash anomaly detector
				publishRoutes(flips, alchs);   // hand the Coach's unified GP/hr router our top passive GE routes
				recompute();   // mapping is loaded now → exclude list resolves, P&L reflows
			}
			catch (Exception e)
			{
				log.warn("geflip scan failed", e);
				if (p != null) p.setStatus("scan failed — check connection");
			}
			finally { scanning.set(false); }
			checkDumps();  // warn if anything you HOLD has crashed below your buy
			checkWatch();  // ping when a watched item goes cheap
			checkSells();  // #3: nudge when a resting sell is stale / priced above the market
			cloudPush();   // push fills/session to the cloud store (no-op if not configured)
		});
	}

	/** Publish our top passive GE money routes to the shared bridge so the Coach's unified GP/hr router can
	 *  rank them alongside its boss/skilling routes. Flips already carry gp/h; high-alch gp/h = profit ×
	 *  a sustainable cast rate (min of alch speed ~1200/hr and what the 4h buy limit allows per hour). */
	private void publishRoutes(java.util.List<GeflipScanner.Flip> flips, java.util.List<GeflipScanner.Alch> alchs)
	{
		java.util.List<GeflipShared.Route> routes = new java.util.ArrayList<>();
		if (flips != null)
			for (int i = 0; i < flips.size() && i < 3; i++)   // top 3 flips by rank
			{
				GeflipScanner.Flip f = flips.get(i);
				if (f.expGph > 0) routes.add(new GeflipShared.Route("Flip: " + f.name, (long) f.expGph, "flip"));
			}
		if (alchs != null && !alchs.isEmpty())
		{
			GeflipScanner.Alch a = alchs.get(0);   // best high-alch
			// ~1100/hr SUSTAINED (1200 is the perfect-play ceiling — 5-tick recast; realistic is lower with
			// banking/misclicks). Buy-limit also bounds it (limit/4 per hour).
			double rate = Math.min(1100.0, a.limit > 0 ? a.limit / 4.0 : 1100.0);
			long gpHr = (long) (a.profit * rate);
			if (gpHr > 0) routes.add(new GeflipShared.Route("High-alch: " + a.name, gpHr, "alch"));
		}
		GeflipShared.setFlipRoutes(routes);
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
		if (slot >= 0 && slot < slots.length) slots[slot] = o;   // mirror the live slot

		boolean booked = bookOfferProgress(slot, o);   // book any newly-filled units (partials too)

		// publish the offer snapshot + keep To-sell in step (a listing/cancel with no fill still
		// changes what's "to sell" — buildHoldings() subtracts what's actively listed).
		java.util.List<Offer> snap = buildOffers();
		offerSnapshot = snap;
		if (panel != null) panel.setOffers(snap);
		refreshHoldingsView();

		if (booked)
		{
			GrandExchangeOfferState s = o.getState();
			boolean sell = s == GrandExchangeOfferState.SELLING || s == GrandExchangeOfferState.SOLD
				|| s == GrandExchangeOfferState.CANCELLED_SELL;
			schedulePersist(sell ? o.getItemId() : -1);   // sell → may alert; buy → re-baseline only
		}
	}

	/** Coalesce a burst of fill events into ONE save+recompute. A big offer fills in many small
	 *  increments → many events/min; without this each did an O(n) fill-list copy + full ledger re-sort
	 *  + full disk rewrite. Debounced ~1.2s: the offer/To-sell view still updates per event (above),
	 *  only the heavy persist+P&L reflow is coalesced. A sell in the window is remembered so the
	 *  flip-complete alert still fires. persistAndRecompute snapshots on the client thread as before. */
	private void schedulePersist(int soldId)
	{
		if (soldId >= 0) pendingSoldId = soldId;
		java.util.concurrent.ScheduledFuture<?> pd = persistDebounce;
		if (pd != null && !pd.isDone()) return;   // one already pending → coalesce into it
		persistDebounce = executor.schedule(() ->
		{
			int sid = pendingSoldId; pendingSoldId = -1;
			clientThread.invoke(() -> persistAndRecompute(sid));
		}, 1200, TimeUnit.MILLISECONDS);
	}

	/**
	 * Reconcile against the client's LIVE GE slots — the truest available fill source. RuneLite
	 * only replays offer events on game login, not when this plugin is enabled mid-session, so
	 * without this a completed/partly-filled offer during a disabled window would be missed.
	 * This reads all 8 real slots and books any unbooked filled units; the per-slot high-water
	 * marks make it fully idempotent (re-running books nothing new). Client thread only.
	 */
	private void reconcileGeSlots()
	{
		GrandExchangeOffer[] live = client.getGrandExchangeOffers();
		if (live == null) return;
		boolean any = false;
		for (int i = 0; i < live.length && i < slots.length; i++)
		{
			GrandExchangeOffer o = live[i];
			if (o == null) continue;
			slots[i] = o;
			any |= bookOfferProgress(i, o);
		}
		java.util.List<Offer> snap = buildOffers();
		offerSnapshot = snap;
		if (panel != null) panel.setOffers(snap);
		refreshHoldingsView();
		if (any) persistAndRecompute();
	}

	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged ev)
	{
		// on login the client is about to replay slot states; reconcile catches anything the
		// event stream doesn't (plugin enabled mid-session, offers filled while logged out).
		if (ev.getGameState() == net.runelite.api.GameState.LOGGED_IN)
			clientThread.invoke(this::reconcileGeSlots);
	}

	/**
	 * Book the newly-filled portion of the offer now in `slot` (the delta since a per-slot
	 * high-water mark). quantitySold/spent are CUMULATIVE per offer, so booking the delta:
	 *   • captures PARTIAL buys/sells the instant units fill ("sold a little" moves P&L + To-sell),
	 *   • is idempotent on login-replay / reconcile (same numbers ⇒ zero delta),
	 *   • books a cancelled offer's filled tail before EMPTY clears the slot.
	 * Also maintains the age clock (slotSince) and resets the mark when a NEW offer takes the slot.
	 * Returns true iff a fill was recorded. MUST run on the client thread.
	 */
	private boolean bookOfferProgress(int slot, GrandExchangeOffer o)
	{
		if (o == null || slot < 0 || slot >= slotBookedQty.length) return false;
		GrandExchangeOfferState st = o.getState();
		if (st == GrandExchangeOfferState.EMPTY)
		{
			// clearing the mark MUST be persisted — otherwise a restart (you shut the PC down
			// nightly) restores the old key+mark, and re-buying the SAME item at the SAME price
			// matches that key so delta stays ≤0 and the whole new offer books nothing.
			boolean hadState = slotKey[slot] != null || slotBookedQty[slot] != 0 || slotBookedSpent[slot] != 0;
			slotSince[slot] = 0; slotKey[slot] = null;
			slotBookedQty[slot] = 0; slotBookedSpent[slot] = 0;
			if (slot < slotSig.length) slotSig[slot] = null;
			return hadState;   // return true so the caller persists the cleared slot
		}
		String key = o.getItemId() + ":" + o.getPrice() + ":" + o.getTotalQuantity();
		// ONE-TIME UPGRADE: an old save has no fill marks (they load as 0). A slot still holding the
		// SAME offer as at last save already has its filled units in `fills`, so seed the mark to the
		// current cumulative — don't re-book them (that was the double-count bug). A different offer
		// (key mismatch) is new and books normally through the reset below.
		if (upgradeMode && !slotSeeded[slot])
		{
			// First sight of this slot after an upgrade: treat whatever has filled so far as ALREADY
			// accounted (an old save booked completed offers to `fills`; re-booking them would double).
			// Seed UNCONDITIONALLY — a save from before slotKey existed loads key=null, so a key match
			// can't be relied on. You place fresh offers after login (first sight at qty≈0), so this
			// loses nothing real; it only suppresses re-booking a pre-upgrade completed offer.
			slotSeeded[slot] = true;
			slotKey[slot] = key; slotSince[slot] = System.currentTimeMillis();
			slotBookedQty[slot] = o.getQuantitySold(); slotBookedSpent[slot] = o.getSpent();
			return true;   // persist the seeded mark; book nothing this pass
		}
		// a genuinely NEW offer in this slot → reset the age clock + fill mark (a matching key on
		// login-replay is the SAME offer, so we keep the mark and don't re-book what we already had)
		if (!key.equals(slotKey[slot]))
		{
			slotKey[slot] = key; slotSince[slot] = System.currentTimeMillis();
			slotBookedQty[slot] = 0; slotBookedSpent[slot] = 0;
		}
		boolean isBuy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
			|| st == GrandExchangeOfferState.CANCELLED_BUY;
		boolean isSell = st == GrandExchangeOfferState.SELLING || st == GrandExchangeOfferState.SOLD
			|| st == GrandExchangeOfferState.CANCELLED_SELL;
		if (!isBuy && !isSell) return false;

		int soldNow = o.getQuantitySold();
		long spentNow = o.getSpent();
		int deltaQty = soldNow - slotBookedQty[slot];
		long deltaSpent = Math.max(0, spentNow - slotBookedSpent[slot]);
		if (deltaQty <= 0) return false;   // no NEW units filled since last seen (gate on qty alone so a
		                                    // lagging spend can't drop the units permanently)
		int unit = (int) Math.round((double) deltaSpent / deltaQty);   // avg price of the new units (rounded; ≤½gp/event basis drift)
		slotBookedQty[slot] = soldNow; slotBookedSpent[slot] = spentNow;

		long now = System.currentTimeMillis() / 1000;
		if (isBuy)
		{
			fills.add(new Fill(o.getItemId(), "BUY", unit, deltaQty, 0, now));
			long nowMs = System.currentTimeMillis();
			long[] w = buyWindows.get(o.getItemId());
			if (w == null || nowMs - w[0] >= BUY_WINDOW_MS) buyWindows.put(o.getItemId(), new long[]{ nowMs, deltaQty });
			else buyWindows.put(o.getItemId(), new long[]{ w[0], w[1] + deltaQty });
		}
		else
		{
			int tax = GeflipScanner.saleTax(unit, scanner.isExempt(o.getItemId())) * deltaQty;
			fills.add(new Fill(o.getItemId(), "SELL", unit, deltaQty, tax, now));
		}
		pruneFills();
		return true;
	}

	// In-memory fill cap. Kept modest: the on-disk cap is 3000 (saveFills), so a reload baselines to
	// ≤3000 and a much larger in-memory cap only accumulates during one uninterrupted marathon session
	// while adding CopyOnWriteArrayList O(N²) append cost. 8000 gives session headroom above the reload
	// baseline without the 25k tail. (Truly permanent per-item journal retention would need a separate
	// persisted journal — noted as a future improvement, not worth the P&L-path risk here.)
	private static final int FILL_CAP = 8000;

	/** Keep the fill log bounded WITHOUT corrupting P&L. A blind remove(0) could drop a BUY whose
	 *  SELL is still in the log → that sell becomes an unmatched "pure-profit" phantom (overstated
	 *  P&L) and the held lot vanishes. Instead, once over the cap, drop only items with a FLAT net
	 *  position (fully bought AND sold) — their buys and sells leave together, so nothing is
	 *  orphaned. Open positions are always kept; if everything is open we let the log grow rather
	 *  than risk corruption. removeIf on the CopyOnWriteArrayList is an atomic array swap. Client thread. */
	private void pruneFills()
	{
		if (fills.size() <= FILL_CAP) return;
		java.util.Map<Integer, Integer> net = new java.util.HashMap<>();
		for (Fill f : fills) net.merge(f.id, "BUY".equals(f.side) ? f.qty : -f.qty, Integer::sum);
		fills.removeIf(f -> net.getOrDefault(f.id, 0) == 0);
	}

	/** Snapshot the fill/slot state on the CLIENT thread, then persist + re-match off-thread
	 *  (so the executor never serialises arrays/list the client thread is mutating). */
	private void persistAndRecompute() { persistAndRecompute(-1); }

	/** soldId >= 0 => this booking was a SELL of that item → the recompute may fire a flip-complete
	 *  alert. -1 (buys, reconcile) => re-baseline only, never alert. */
	private void persistAndRecompute(int soldId)
	{
		java.util.List<Fill> fillSnap = new java.util.ArrayList<>(fills);
		String[] sigSnap = slotSig.clone(), keySnap = slotKey.clone();
		long[] sinceSnap = slotSince.clone();
		int[] bookedSnap = slotBookedQty.clone(); long[] bookedSpentSnap = slotBookedSpent.clone();
		java.util.Map<Integer, long[]> winSnap = deepCopyWindows();
		executor.submit(() -> { saveFills(fillSnap, sigSnap, keySnap, sinceSnap, winSnap, bookedSnap, bookedSpentSnap); recompute(soldId >= 0, soldId); });
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
		refreshHoldingsView();   // keep To-sell in step with partial fills / newly-listed units
	}

	/** Rebuild the To-sell list from the CURRENT ledger + offer snapshot, WITHOUT re-matching the
	 *  fill log. Use when only what's listed on the GE changed (a listing / partial fill / cancel),
	 *  so the sold-off or now-listed portion drops off To-sell immediately instead of on next scan. */
	private void refreshHoldingsView()
	{
		if (panel != null) panel.setHoldings(buildHoldings());
	}

	private static String timeNow()
	{
		return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
	}
}
