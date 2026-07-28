package com.geflip.coach;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * Geflip Coach — reads your live account every refresh and tells you the highest-value next action.
 * A curated goal graph (skills + quests + QP + key items) is evaluated against your real state, so
 * "what do I do next" is answered concretely and updates as you play. Optional LLM "Ask" pipes your
 * live snapshot to a model for open-ended coaching. READ-ONLY toward the game — it only reads state.
 */
@Slf4j
@PluginDescriptor(
	name = "Geflip Coach",
	description = "Reads your account and tells you the best next step (goals, gaps, ranked actions, LLM coaching).",
	tags = {"progression", "goals", "slayer", "quest", "coach", "pvm", "efficiency"}
)
public class CoachPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientToolbar clientToolbar;
	@Inject private CoachConfig config;
	@Inject private ScheduledExecutorService executor;
	@Inject private ClientThread clientThread;
	@Inject private net.runelite.client.Notifier notifier;
	@Inject private net.runelite.client.config.ConfigManager configManager;
	@Inject private net.runelite.client.hiscore.HiscoreClient hiscoreClient;
	@Inject private net.runelite.client.game.ItemManager itemManager;

	private CoachPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> refresh;
	private volatile CoachState lastState;
	// what was READY / do-now last scan, so we can PING you the instant something new unlocks
	private final Set<String> lastReady = new HashSet<>();
	private final Set<String> lastQuestReady = new HashSet<>();
	private boolean primed = false;   // skip the very first scan so we don't alert your whole backlog
	// session efficiency: baseline XP/wealth/time snapped on the first logged-in read
	private long sessStartMs = 0, sessStartXp = 0, sessStartWealth = -1;
	private final Map<Skill, Long> startSkillXp = new EnumMap<>(Skill.class);   // per-skill baseline → xp/hr per skill
	// map "Magic" -> Skill.MAGIC so we can turn a gap string back into a skill for ETAs
	private static final Map<String, Skill> SKILL_BY_CAP = new java.util.HashMap<>();
	static { for (Skill sk : Skill.values()) if (sk != Skill.OVERALL) SKILL_BY_CAP.put(CoachGoals.cap(sk.name()), sk); }
	// PvM progress from the OSRS hiscores (boss KCs, collection log, clues)
	private volatile net.runelite.client.hiscore.HiscoreResult hiscore;
	private volatile String hiscoreName;
	private volatile long lastHiscoreMs = 0;
	// Wise Old Man efficiency metrics (EHP/EHB), fetched off-thread + cached
	private volatile CoachWom.Result wom;
	private volatile long lastWomMs = 0;
	private ScheduledFuture<?> priceRefresh;   // the net-worth price poller (cancelled on shutdown)
	private boolean wealthBaselined = false;   // session gp/hr baseline only once the bank is known
	private volatile String farmRunType = "Herb";   // which preset farm run the Farm tab shows
	private CoachServer phoneServer;   // the LAN phone bridge (started only when the toggle is on)
	private volatile CoachServer.Snapshot snapshot = new CoachServer.Snapshot();   // last state, served to phone

	@Provides
	CoachConfig provideConfig(net.runelite.client.config.ConfigManager cm) { return cm.getConfig(CoachConfig.class); }

	@Override
	protected void startUp()
	{
		panel = new CoachPanel(this::rescan, this::ask, this::buildContext, this::markFarmRun, this::guideTo, this::selectFarmRun, this::guideToFarm);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/coach_icon.png");
		navButton = NavigationButton.builder().tooltip("Geflip Coach").icon(icon).priority(8).panel(panel).build();
		clientToolbar.addNavigation(navButton);
		int s = Math.max(10, config.refreshSec());
		refresh = executor.scheduleWithFixedDelay(this::rescan, 3, s, TimeUnit.SECONDS);
		priceRefresh = executor.scheduleWithFixedDelay(this::fetchPrices, 0, 600, TimeUnit.SECONDS);   // net-worth prices
	}

	@Override
	protected void shutDown()
	{
		if (refresh != null) refresh.cancel(true);
		if (priceRefresh != null) priceRefresh.cancel(true);
		if (phoneServer != null) { try { phoneServer.stop(); } catch (Exception ignored) {} phoneServer = null; }
		clientThread.invoke(client::clearHintArrow);   // don't leave a stale guide arrow behind
		clientToolbar.removeNavigation(navButton);
		panel = null;
	}

	private int builtPort = -1;          // port/token the LIVE phoneServer was built with, so a runtime
	private String builtToken = "";      // change (esp. adding a token) rebuilds instead of serving stale

	/** Start/stop/REBUILD the phone bridge to match the toggle + port + token (cheap to call every
	 *  rescan). A failed bind (port already in use) just leaves it off rather than throwing into the
	 *  refresh loop. Critically: if you add a token expecting protection, the old open server is torn
	 *  down and rebuilt with it — it never keeps serving on the previous (possibly blank) token. */
	private void ensurePhoneServer()
	{
		boolean want = config.phoneSync();
		int port = config.phonePort();
		String token = config.phoneToken().trim();
		if (phoneServer != null && (!want || port != builtPort || !token.equals(builtToken)))
		{
			try { phoneServer.stop(); } catch (Exception ignored) {}
			phoneServer = null;
		}
		if (want && phoneServer == null)
		{
			try { phoneServer = new CoachServer(port, token).withState(() -> snapshot); phoneServer.start(); builtPort = port; builtToken = token; }
			catch (Exception e) { phoneServer = null; }
		}
	}

	/** Render scored goals to plain phone lines: "name — first gaps" (or "ready ✓"). */
	private static List<String> scoredLines(List<CoachEngine.Scored> rows)
	{
		List<String> o = new ArrayList<>();
		if (rows != null) for (CoachEngine.Scored s : rows)
		{
			String gaps = s.gaps.isEmpty() ? "ready ✓" : String.join(", ", s.gaps.subList(0, Math.min(3, s.gaps.size())));
			o.add(s.goal.name + " — " + gaps);
		}
		return o;
	}

	/** Read the account on the client thread, evaluate goals, push to the panel. */
	private void rescan()
	{
		clientThread.invoke(() ->
		{
			CoachState st = buildState();
			lastState = st;
			CoachPanel p = panel;
			if (p == null) return;
			if (!st.loggedIn) { p.setStatus("log in to read your account"); return; }
			List<CoachEngine.Scored> all = CoachEngine.evaluate(st);
			p.setStatus("read " + timeShort());
			String ca = caTier();
			int slayerPts = client.getVarbitValue(Varbits.SLAYER_POINTS);
			int streak = client.getVarbitValue(Varbits.SLAYER_TASK_STREAK);
			CoachWom.Result w = wom;
			String eff = w != null && w.tracked && (w.ehp > 0 || w.ehb > 0)
				? " · " + Math.round(w.ehp) + " EHP" + (w.ehb >= 1 ? "/" + Math.round(w.ehb) + " EHB" : "")
					+ (w.ttm > 0 ? " · " + Math.round(w.ttm) + "h to max" : "")
				: "";
			String summary = "combat " + st.combatLevel + " · " + st.qp + " QP"
				+ (st.wealth >= 0 ? " · " + CoachGoals.gp(st.wealth) + " net" : st.coins >= 0 ? " · " + CoachGoals.gp(st.coins) + " gp" : "")
				+ (ca != null ? " · CA " + ca : "")
				+ (slayerPts > 0 || streak > 0 ? " · Slayer " + slayerPts + "pt/" + streak + " streak" : "")
				+ eff
				+ (st.bankKnown ? "" : " · (open bank for full net worth)");
			String sess = sessionStats(st);
			List<CoachEngine.Scored> next = CoachEngine.doNext(all);
			List<String> path = criticalPath(st, all);   // reuse the already-computed goal graph (no 2nd evaluate)
			List<String> risk = riskLines();
			List<String> farmLines = null;
			if (config.farmingHelper())
			{
				farmLines = new ArrayList<>(pathTo99(st));                 // the 1→99 trainer, then the run
				farmLines.addAll(CoachFarm.run(farmRunType, st, farmElapsedMin()));
			}
			p.setFarmSteps(config.farmingHelper() ? farmSteps(st) : java.util.Collections.emptyList());
			p.setSkills(skillLines(st));   // the all-skills 1→99 trainer
			publishAccountNeeds(st);       // cross-reference: hand the flipper your account shopping list
			p.setSummary(summary);
			p.setSessionStats(sess);
			refreshHiscore();
			p.setNext(next);
			p.setGoals(all, questLines(st), pvmLines(), diaryLines());
			p.setPath(path);
			p.setRisk(risk);
			p.setBlocked(CoachEngine.blocked(all));
			p.setFarm(farmLines);
			// mirror the same data to the phone snapshot (served by CoachServer if enabled)
			CoachServer.Snapshot snap = new CoachServer.Snapshot();
			snap.updated = System.currentTimeMillis() / 1000;
			snap.status = "read " + timeShort();
			snap.summary = summary;
			snap.session = sess;
			snap.next = scoredLines(next);
			snap.path = path;
			snap.risk = risk;
			snap.farm = farmLines != null ? farmLines : java.util.Collections.emptyList();
			snap.goals = scoredLines(all);
			snapshot = snap;
			ensurePhoneServer();
			fireUnlockAlerts(all, st);
		});
	}

	// --- account snapshot (client thread) ------------------------------------
	private CoachState buildState()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
			return new CoachState(new EnumMap<>(Skill.class), 0, new EnumMap<>(Quest.class),
				new HashSet<>(), false, -1, -1, 0, false);

		Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
		for (Skill sk : Skill.values())
		{
			if (sk == Skill.OVERALL) continue;
			levels.put(sk, client.getRealSkillLevel(sk));
		}
		int qp = client.getVarpValue(VarPlayer.QUEST_POINTS);
		// read EVERY quest's state so the coach knows what you've already done (and never suggests it)
		Map<Quest, net.runelite.api.QuestState> quests = new EnumMap<>(Quest.class);
		for (Quest q : Quest.values())
		{
			try { quests.put(q, q.getState(client)); } catch (Exception ignored) { /* skip odd entries */ }
		}

		Set<Integer> keyIds = new HashSet<>();
		for (int id : CoachGoals.KEY_ITEMS) keyIds.add(id);
		Set<Integer> owned = new HashSet<>();
		long[] acc = new long[2];   // [0]=coins, [1]=GE value of everything (net worth)
		scan(InventoryID.EQUIPMENT, keyIds, owned, acc);
		scan(InventoryID.INVENTORY, keyIds, owned, acc);
		boolean bankKnown = client.getItemContainer(InventoryID.BANK) != null;
		scan(InventoryID.BANK, keyIds, owned, acc);
		long wealth = prices != null ? acc[1] : -1;   // −1 until the price table has loaded

		int combat = CoachState.combat(get(levels, Skill.ATTACK), get(levels, Skill.STRENGTH),
			get(levels, Skill.DEFENCE), get(levels, Skill.HITPOINTS), get(levels, Skill.RANGED),
			get(levels, Skill.PRAYER), get(levels, Skill.MAGIC));
		return new CoachState(levels, qp, quests, owned, bankKnown, acc[0], wealth, combat, true);
	}

	/** Scan a container: record KEY_ITEMS into `owned`, and accumulate acc[0]=coins, acc[1]=GE value
	 *  (qty × live wiki mid-price) of every item — that's the net-worth figure. */
	private void scan(InventoryID which, Set<Integer> keyIds, Set<Integer> owned, long[] acc)
	{
		ItemContainer c = client.getItemContainer(which);
		if (c == null) return;
		Map<Integer, Integer> px = prices;
		for (Item it : c.getItems())
		{
			if (it == null) continue;
			int id = it.getId(), qty = it.getQuantity();
			if (id == 995) { acc[0] += qty; acc[1] += qty; continue; }   // coins are worth 1 each
			if (keyIds.contains(id)) owned.add(id);
			if (px != null) { Integer p = px.get(id); if (p != null) acc[1] += (long) p * qty; }
		}
	}

	// --- live wiki price table (for net worth) -------------------------------
	private volatile Map<Integer, Integer> prices;   // item id -> mid price (avg of instant buy/sell)

	/** Pull the OSRS wiki real-time prices so we can value your whole bank. Polite: one call every
	 *  ~10 min, off the client thread, descriptive User-Agent per the wiki API rules. */
	private void fetchPrices()
	{
		try
		{
			HttpURLConnection c = (HttpURLConnection) new URL("https://prices.runescape.wiki/api/v1/osrs/latest").openConnection();
			c.setRequestProperty("User-Agent", "geflip-coach - net worth valuation");
			c.setConnectTimeout(15000); c.setReadTimeout(30000);
			String resp = new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
			JsonObject data = new JsonParser().parse(resp).getAsJsonObject().getAsJsonObject("data");
			Map<Integer, Integer> m = new java.util.HashMap<>(data.size() * 2);
			for (Map.Entry<String, com.google.gson.JsonElement> e : data.entrySet())
			{
				JsonObject o = e.getValue().getAsJsonObject();
				Integer hi = o.has("high") && !o.get("high").isJsonNull() ? o.get("high").getAsInt() : null;
				Integer lo = o.has("low") && !o.get("low").isJsonNull() ? o.get("low").getAsInt() : null;
				int mid = hi != null && lo != null ? (hi + lo) / 2 : hi != null ? hi : lo != null ? lo : 0;
				if (mid > 0) m.put(Integer.parseInt(e.getKey()), mid);
			}
			prices = m;
		}
		catch (Exception e) { log.debug("coach: price fetch failed", e); }
	}

	private static int get(Map<Skill, Integer> m, Skill s) { Integer v = m.get(s); return v != null ? v : 1; }

	// --- diaries (best-effort completion display) ----------------------------
	private static final Object[][] DIARIES = {
		{"Ardougne", Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE},
		{"Desert", Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE},
		{"Falador", Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE},
		{"Fremennik", Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE},
		{"Kandarin", Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE},
		{"Karamja", Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM, Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE},
		{"Kourend", Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE},
		{"Lumbridge", Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE},
		{"Morytania", Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE},
		{"Varrock", Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE},
		{"Western", Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE},
		{"Wilderness", Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE},
	};
	private static final String[] TIER = { "Easy", "Medium", "Hard", "Elite" };

	/** Curated high-value quests you HAVEN'T done, do-now first (reads every quest so it never lists
	 *  one you've finished). */
	private List<String> questLines(CoachState st)
	{
		List<String> out = new ArrayList<>();
		for (CoachEngine.QuestScored qs : CoachEngine.quests(st))
			out.add((qs.ready ? "✓ " : "○ ") + CoachGoals.pretty(qs.rec.q)
				+ (qs.ready ? " — do now" : " — " + String.join(", ", qs.gaps)));
		if (out.isEmpty()) out.add("all tracked quests done — nice");
		return out;
	}

	/** PROACTIVE: ping the moment a goal (or a curated quest) becomes newly available. Skips the
	 *  first scan so it doesn't dump your whole backlog at you on login. Runs on the client thread. */
	private void fireUnlockAlerts(List<CoachEngine.Scored> all, CoachState st)
	{
		Set<String> ready = new HashSet<>();
		for (CoachEngine.Scored sc : all) if (sc.status == CoachEngine.Status.READY) ready.add(sc.goal.name);
		Set<String> qReady = new HashSet<>();
		for (CoachEngine.QuestScored qs : CoachEngine.quests(st)) if (qs.ready) qReady.add(CoachGoals.pretty(qs.rec.q));

		if (primed && config.unlockAlerts())
		{
			for (String g : ready) if (!lastReady.contains(g)) { notifier.notify("Geflip Coach: \"" + g + "\" is now available!"); webhook("✅ Unlocked: **" + g + "**"); }
			for (String q : qReady) if (!lastQuestReady.contains(q)) { notifier.notify("Geflip Coach: you can now start \"" + q + "\""); webhook("📜 Quest available: **" + q + "**"); }
		}
		lastReady.clear(); lastReady.addAll(ready);
		lastQuestReady.clear(); lastQuestReady.addAll(qReady);
		primed = true;
	}

	/** Live session rates — XP/hr overall and gp/hr (net-worth delta). Baseline snaps on first read. */
	private String sessionStats(CoachState st)
	{
		long now = System.currentTimeMillis(), xp = totalXp();
		if (sessStartMs == 0)
		{
			sessStartMs = now; sessStartXp = xp;
			for (Skill sk : Skill.values()) if (sk != Skill.OVERALL) startSkillXp.put(sk, (long) client.getSkillExperience(sk));
		}
		// gp/hr baseline is snapped ONLY once the bank has been read — otherwise the baseline is just
		// carried gp, and opening the bank later fabricates a giant fake "+500m gp/hr".
		if (!wealthBaselined && st.bankKnown && st.wealth >= 0) { sessStartWealth = st.wealth; wealthBaselined = true; }
		double hrs = (now - sessStartMs) / 3_600_000.0;
		if (hrs < 1.0 / 60) return "session: warming up…";
		long xpH = Math.round((xp - sessStartXp) / hrs);
		String s = "session: " + CoachGoals.gp(xpH) + " xp/hr";
		if (wealthBaselined && st.bankKnown && st.wealth >= 0)
		{
			long gpH = Math.round((st.wealth - sessStartWealth) / hrs);
			s += " · " + (gpH >= 0 ? "+" : "-") + CoachGoals.gp(Math.abs(gpH)) + " gp/hr";
		}
		else s += " · gp/hr: open bank";
		return s;
	}

	private long totalXp()
	{
		long t = 0;
		for (Skill sk : Skill.values()) if (sk != Skill.OVERALL) t += client.getSkillExperience(sk);
		return t;
	}

	// --- PvM progress via the OSRS hiscores (boss KCs, collection log, clues) ----
	// key bosses to surface, in display order (label -> HiscoreSkill)
	private static final Object[][] PVM = {
		{"Collection log", net.runelite.client.hiscore.HiscoreSkill.COLLECTIONS_LOGGED},
		{"Clues (all)", net.runelite.client.hiscore.HiscoreSkill.CLUE_SCROLL_ALL},
		{"Zulrah", net.runelite.client.hiscore.HiscoreSkill.ZULRAH},
		{"Vorkath", net.runelite.client.hiscore.HiscoreSkill.VORKATH},
		{"TzTok-Jad", net.runelite.client.hiscore.HiscoreSkill.TZTOK_JAD},
		{"TzKal-Zuk (Inferno)", net.runelite.client.hiscore.HiscoreSkill.TZKAL_ZUK},
		{"Alchemical Hydra", net.runelite.client.hiscore.HiscoreSkill.ALCHEMICAL_HYDRA},
		{"Kraken", net.runelite.client.hiscore.HiscoreSkill.KRAKEN},
		{"Cerberus", net.runelite.client.hiscore.HiscoreSkill.CERBERUS},
		{"Barrows", net.runelite.client.hiscore.HiscoreSkill.BARROWS_CHESTS},
		{"Grotesque Guardians", net.runelite.client.hiscore.HiscoreSkill.GROTESQUE_GUARDIANS},
		{"Giant Mole", net.runelite.client.hiscore.HiscoreSkill.GIANT_MOLE},
		{"Chambers of Xeric", net.runelite.client.hiscore.HiscoreSkill.CHAMBERS_OF_XERIC},
		{"Theatre of Blood", net.runelite.client.hiscore.HiscoreSkill.THEATRE_OF_BLOOD},
		{"Tombs of Amascut", net.runelite.client.hiscore.HiscoreSkill.TOMBS_OF_AMASCUT},
		{"Phantom Muspah", net.runelite.client.hiscore.HiscoreSkill.PHANTOM_MUSPAH},
	};

	/** Look up the player's hiscores (KCs) when the name changes or every ~10 min. Off-thread. */
	private void refreshHiscore()
	{
		net.runelite.api.Player me = client.getLocalPlayer();
		String nm = me != null ? me.getName() : null;
		if (nm == null) return;
		long now = System.currentTimeMillis();
		if (nm.equals(hiscoreName) && now - lastHiscoreMs < 600_000) return;   // fresh enough
		hiscoreName = nm; lastHiscoreMs = now;
		try
		{
			hiscoreClient.lookupAsync(nm, net.runelite.client.hiscore.HiscoreEndpoint.NORMAL)
				.whenComplete((r, ex) -> { if (r != null) hiscore = r; });
		}
		catch (Exception e) { log.debug("coach: hiscore lookup failed", e); }
		// Wise Old Man EHP/EHB — blocking HTTP, so run it on the executor (never the client thread), cached 10 min.
		final String womName = nm;
		executor.submit(() ->
		{
			try
			{
				CoachWom.Result r = CoachWom.fetch(womName);
				if (r != null && !r.tracked) { CoachWom.track(womName); r = CoachWom.fetch(womName); }   // first-time: track then read
				if (r != null) { wom = r; lastWomMs = System.currentTimeMillis(); }
			}
			catch (Exception e) { log.debug("coach: WOM lookup failed", e); }
		});
	}

	/** Boss KCs + collection-log + clue totals from the hiscores (only entries you're ranked on). */
	private List<String> pvmLines()
	{
		net.runelite.client.hiscore.HiscoreResult h = hiscore;
		if (h == null) return java.util.Collections.emptyList();
		List<String> out = new ArrayList<>();
		for (Object[] row : PVM)
		{
			net.runelite.client.hiscore.Skill sk = h.getSkill((net.runelite.client.hiscore.HiscoreSkill) row[1]);
			if (sk == null) continue;
			int v = sk.getLevel();
			if (v > 0) out.add(row[0] + ": " + String.format("%,d", v));
		}
		return out;
	}

	// --- PvP risk / loot-keep + own stats (reads YOUR OWN state — rules-safe) ---
	/** What you'd lose if you died in the Wilderness right now: values every item you're wearing +
	 *  carrying, keeps your 3 most valuable (4 with Protect Item; 0/1 if skulled), and reports the
	 *  rest as risked — plus your live spec %, HP and prayer. All own-state; no opponent reads. */
	private List<String> riskLines()
	{
		// value EVERY worn + carried item via ItemManager (handles untradeable/charged gear that a raw
		// GE-price map misses — that was why worn stuff read as 0). name+price both from ItemManager.
		java.util.List<Object[]> items = new ArrayList<>();   // {name, value(long)}
		long total = 0; int untradeables = 0;
		for (InventoryID which : new InventoryID[]{ InventoryID.EQUIPMENT, InventoryID.INVENTORY })
		{
			ItemContainer c = client.getItemContainer(which);
			if (c == null) continue;
			for (Item it : c.getItems())
			{
				if (it == null) continue;   // bank/equipment array slots can be null — guard like every other container loop
				int id = it.getId(), qty = it.getQuantity();
				if (id < 0 || qty <= 0) continue;
				net.runelite.api.ItemComposition comp = itemManager.getItemComposition(id);
				String name = comp != null ? comp.getName() : "#" + id;
				if (name == null || name.equals("null")) continue;
				long v = id == 995 ? qty : (long) Math.max(0, itemManager.getItemPrice(id)) * qty;
				if (v <= 0) untradeables++;   // untradeable/charged — no GE value but still LOST on death
				items.add(new Object[]{ name + (qty > 1 ? " x" + qty : ""), v });
				total += v;
			}
		}
		if (items.isEmpty()) return java.util.Collections.singletonList("Nothing on you — log in / gear up.");
		items.sort((a, b) -> Long.compare((long) b[1], (long) a[1]));   // most valuable first

		net.runelite.api.Player me = client.getLocalPlayer();
		boolean skulled = me != null && me.getSkullIcon() != -1;
		boolean protectItem = client.getVarbitValue(Varbits.PRAYER_PROTECT_ITEM) > 0;
		int keepN = (skulled ? 0 : 3) + (protectItem ? 1 : 0);
		long kept = 0;
		for (int i = 0; i < keepN && i < items.size(); i++) kept += (long) items.get(i)[1];

		List<String> out = new ArrayList<>();
		out.add("RISK (if you die in the Wild now)");
		out.add("On you: " + CoachGoals.gp(total) + "  ·  " + (skulled ? "SKULLED" : "unskulled")
			+ (protectItem ? " + Protect Item" : ""));
		out.add("Keep " + keepN + ": " + CoachGoals.gp(kept) + "   ·   LOSE: " + CoachGoals.gp(total - kept));
		if (!skulled && !protectItem) out.add("(Protect Item prayer saves 1 more.)");
		out.add("");
		out.add("Risking (worn + carried, most valuable first):");
		int shown = 0;
		for (Object[] r : items)
		{
			if (shown++ >= 12) { out.add("  …+" + (items.size() - 12) + " more"); break; }
			long v = (long) r[1];
			out.add("  " + (shown <= keepN ? "🛡 " : "✖ ") + r[0] + " — " + (v > 0 ? CoachGoals.gp(v) : "untradeable"));
		}
		if (untradeables > 0) out.add("(🛡 = kept · ✖ = lost. Untradeables show no GE value but are still lost.)");
		out.add("");
		int spec = client.getVarpValue(net.runelite.api.VarPlayer.SPECIAL_ATTACK_PERCENT) / 10;
		out.add("Spec: " + spec + "%  ·  HP: " + client.getBoostedSkillLevel(Skill.HITPOINTS) + "/" + client.getRealSkillLevel(Skill.HITPOINTS)
			+ "  ·  Prayer: " + client.getBoostedSkillLevel(Skill.PRAYER) + "/" + client.getRealSkillLevel(Skill.PRAYER));
		// est. max hit from your EQUIPPED strength / ranged-str / magic-dmg bonuses (base: no prayer/style)
		int strB = 0, rstrB = 0; double mdmgB = 0;
		int aStab = 0, aSlash = 0, aCrush = 0, aRange = 0, weaponSpeed = 0;   // attack bonuses + weapon speed (for DPS)
		boolean twoH = false;   // a 2H weapon leaves the shield slot forced-empty — don't credit shield upgrades
		Map<Integer, Integer> slotStr = new java.util.HashMap<>();    // slot -> melee str bonus
		Map<Integer, Integer> slotRstr = new java.util.HashMap<>();   // slot -> ranged str bonus
		Map<Integer, Double> slotMdmg = new java.util.HashMap<>();    // slot -> magic dmg %
		ItemContainer eq = client.getItemContainer(InventoryID.EQUIPMENT);
		if (eq != null) for (Item it : eq.getItems())
		{
			if (it == null || it.getId() < 0) continue;
			net.runelite.client.game.ItemStats s = itemManager.getItemStats(it.getId());
			if (s == null || s.getEquipment() == null) continue;
			net.runelite.client.game.ItemEquipmentStats e = s.getEquipment();
			strB += e.getStr(); rstrB += e.getRstr(); mdmgB += e.getMdmg();
			aStab += e.getAstab(); aSlash += e.getAslash(); aCrush += e.getAcrush(); aRange += e.getArange();
			if (e.getSlot() == 3) { if (e.isTwoHanded()) twoH = true; if (e.getAspeed() > 0) weaponSpeed = e.getAspeed(); }   // slot 3 = weapon
			slotStr.merge(e.getSlot(), e.getStr(), Integer::sum);
			slotRstr.merge(e.getSlot(), e.getRstr(), Integer::sum);
			slotMdmg.merge(e.getSlot(), (double) e.getMdmg(), Double::sum);
		}
		int bStr = client.getBoostedSkillLevel(Skill.STRENGTH);
		int bRng = client.getBoostedSkillLevel(Skill.RANGED);
		int meleeMax = (int) (0.5 + (bStr + 8) * (strB + 64) / 640.0);
		int rangeMax = (int) (0.5 + (bRng + 8) * (rstrB + 64) / 640.0);
		out.add("Est. max hit — melee ~" + meleeMax + " · ranged ~" + rangeMax + "  (current levels + any potion; no prayer/combat style)");
		out.addAll(strengthUpgrades("Best melee upgrades (max-hit gain):", MELEE_UPGRADES, true, strB, bStr, meleeMax, slotStr, twoH));
		out.addAll(strengthUpgrades("Best ranged upgrades (max-hit gain):", RANGED_UPGRADES, false, rstrB, bRng, rangeMax, slotRstr, twoH));
		out.addAll(magicUpgrades(mdmgB, slotMdmg, twoH));
		out.addAll(dpsLines(client.getBoostedSkillLevel(Skill.ATTACK) + 8, aStab, aSlash, aCrush, meleeMax,
			bRng + 8, aRange, rangeMax, weaponSpeed > 0 ? weaponSpeed : 4));
		out.add("(Bank + banked coins are NOT at risk — only what's equipped/carried.)");
		return out;
	}

	/** Est. DPS vs each boss with your CURRENT gear (base — no prayer/combat style). Melee auto-picks the
	 *  best of stab/slash/crush against that boss's defence; ranged uses your ranged gear. Uses your equipped
	 *  weapon's attack speed for both (ranged Rapid saves a tick — real ranged DPS is a bit higher). */
	private List<String> dpsLines(int effAtk, int aStab, int aSlash, int aCrush, int meleeMax,
		int effRange, int aRange, int rangeMax, int speed)
	{
		List<String> out = new ArrayList<>();
		out.add("Est. DPS vs bosses (current gear, base — no prayer/style):");
		boolean anyMelee = meleeMax > 1 && (aStab > 0 || aSlash > 0 || aCrush > 0);
		boolean anyRange = rangeMax > 1 && aRange > 0;
		if (!anyMelee && !anyRange) { out.add("  (equip a weapon to estimate DPS)"); return out; }
		for (CoachDps.Boss b : CoachDps.BOSSES)
		{
			double melee = anyMelee ? Math.max(CoachDps.dps(effAtk, aStab, meleeMax, b.defLvl, b.dStab, speed),
				Math.max(CoachDps.dps(effAtk, aSlash, meleeMax, b.defLvl, b.dSlash, speed),
					CoachDps.dps(effAtk, aCrush, meleeMax, b.defLvl, b.dCrush, speed))) : 0;
			double ranged = anyRange ? CoachDps.dps(effRange, aRange, rangeMax, b.defLvl, b.dRange, speed) : 0;
			out.add("  " + b.name + ": " + (anyMelee ? "melee ~" + String.format("%.1f", melee) : "")
				+ (anyMelee && anyRange ? " · " : "") + (anyRange ? "ranged ~" + String.format("%.1f", ranged) : "") + " dps");
		}
		return out;
	}

	// curated high-value upgrades per style (ids verified vs ItemID 1.12.33). Stats + prices are read
	// LIVE from ItemManager, so nothing here is a hardcoded stat that could go stale.
	private static final int[] MELEE_UPGRADES = {
		19553,                // Amulet of torture (neck)
		21295, 6570,          // Infernal cape / Fire cape
		11832, 11834,         // Bandos chestplate / tassets
		24271,                // Neitiznot faceguard (helm)
		22981, 7462,          // Ferocious gloves / Barrows gloves
		25485, 11773,         // Ultor ring / Berserker (i)
		13239, 11840,         // Primordial boots / Dragon boots
		22322,                // Avernic defender (offhand)
	};
	private static final int[] RANGED_UPGRADES = {
		19547,                // Amulet of anguish (neck)
		22109,                // Ava's assembler (cape)
		11826, 11828, 11830,  // Armadyl helmet / chestplate / chainskirt
		26235, 7462,          // Zaryte vambraces / Barrows gloves
		11771, 13237,         // Archer's ring (i) / Pegasian boots
	};
	private static final int[] MAGIC_UPGRADES = {
		12002,                // Occult necklace (neck)
		19544,                // Tormented bracelet (gloves)
		21018, 21021, 21024,  // Ancestral hat / top / bottom
		21791, 21793, 21795,  // Imbued god capes (sara/guthix/zammy)
		13235,                // Eternal boots
		20714, 6889,          // Tome of fire / Mage's book (offhand)
	};

	/** Max-hit-gain finder for a strength-based style (melee=getStr, ranged=getRstr). For each candidate
	 *  it reports the max-hit gain from swapping it into its slot, ranked, with the live GE price. */
	private List<String> strengthUpgrades(String header, int[] ids, boolean melee,
		int curBonus, int boostedLvl, int curMax, Map<Integer, Integer> slotBonus, boolean twoH)
	{
		java.util.List<Object[]> ups = new ArrayList<>();   // {label, gain, price}
		for (int id : ids)
		{
			net.runelite.client.game.ItemStats s = itemManager.getItemStats(id);
			if (s == null || s.getEquipment() == null) continue;
			int slot = s.getEquipment().getSlot();
			if (twoH && slot == 5) continue;   // shield-slot upgrade unreachable while wielding a 2H weapon
			int cand = melee ? s.getEquipment().getStr() : s.getEquipment().getRstr();
			int newBonus = curBonus - slotBonus.getOrDefault(slot, 0) + cand;
			int newMax = (int) (0.5 + (boostedLvl + 8) * (newBonus + 64) / 640.0);
			int gain = newMax - curMax;
			if (gain <= 0) continue;   // already equal/better in that slot
			net.runelite.api.ItemComposition c = itemManager.getItemComposition(id);
			ups.add(new Object[]{ c != null ? c.getName() : "#" + id, gain, itemManager.getItemPrice(id) });
		}
		if (ups.isEmpty()) return java.util.Collections.emptyList();
		ups.sort((a, b) -> Integer.compare((int) b[1], (int) a[1]));   // biggest max-hit gain first
		List<String> out = new ArrayList<>();
		out.add(header);
		int n = 0;
		for (Object[] u : ups) { if (n++ >= 5) break; out.add("  " + u[0] + ": +" + u[1] + "  (~" + CoachGoals.gp((int) u[2]) + ")"); }
		return out;
	}

	/** Magic upgrades: ranked by the % magic-damage gain from swapping into their slot (magic max hit
	 *  scales with the spell, so gear is measured by its magic-damage bonus, matching the gear screen). */
	private List<String> magicUpgrades(double curMdmg, Map<Integer, Double> slotMdmg, boolean twoH)
	{
		java.util.List<Object[]> ups = new ArrayList<>();   // {label, gain%, price}
		for (int id : MAGIC_UPGRADES)
		{
			net.runelite.client.game.ItemStats s = itemManager.getItemStats(id);
			if (s == null || s.getEquipment() == null) continue;
			int slot = s.getEquipment().getSlot();
			if (twoH && slot == 5) continue;   // shield-slot (tome/book) unreachable while wielding a 2H staff
			double newMdmg = curMdmg - slotMdmg.getOrDefault(slot, 0.0) + s.getEquipment().getMdmg();
			double gain = newMdmg - curMdmg;
			if (gain <= 0.05) continue;   // already equal/better in that slot
			net.runelite.api.ItemComposition c = itemManager.getItemComposition(id);
			ups.add(new Object[]{ c != null ? c.getName() : "#" + id, gain, itemManager.getItemPrice(id) });
		}
		if (ups.isEmpty()) return java.util.Collections.emptyList();
		ups.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));
		List<String> out = new ArrayList<>();
		out.add("Best magic upgrades (magic-dmg gain):");
		int n = 0;
		for (Object[] u : ups) { if (n++ >= 5) break; out.add("  " + u[0] + ": +" + String.format("%.1f", (double) u[1]) + "%  (~" + CoachGoals.gp((int) u[2]) + ")"); }
		return out;
	}

	// --- critical-path planner (the "how do I actually get there" brain) ------
	/** Ordered plan to the focus goal: every skill to train (with a live ETA if you're training it)
	 *  and quest to do, prerequisites first. Focus = config.focusGoal (name match) or the
	 *  highest-impact blocked goal. Client thread (reads live skill XP). */
	private List<String> criticalPath(CoachState st, List<CoachEngine.Scored> all)
	{
		CoachGoals.Goal target = null;
		boolean userSet = false;
		String want = config.focusGoal().trim().toLowerCase();
		if (!want.isEmpty())
			for (CoachEngine.Scored sc : all) if (sc.goal.name.toLowerCase().contains(want)) { target = sc.goal; userSet = true; break; }
		if (target == null) { List<CoachEngine.Scored> b = CoachEngine.blocked(all); if (!b.isEmpty()) target = b.get(0).goal; }
		if (target == null) return java.util.Collections.emptyList();

		java.util.LinkedHashMap<String, Integer> skills = new java.util.LinkedHashMap<>();   // capName -> target level (max)
		java.util.LinkedHashSet<String> quests = new java.util.LinkedHashSet<>();             // pretty names, prereqs first
		java.util.LinkedHashSet<String> misc = new java.util.LinkedHashSet<>();
		collectPath(target.reqs, st, skills, quests, misc, 0);

		List<String> out = new ArrayList<>();
		int steps = skills.size() + quests.size() + misc.size();
		out.add(userSet ? "🎯 PLANNING: " + target.name + "  (" + steps + " step" + (steps == 1 ? "" : "s") + " left)"
			: "PATH TO " + target.name + "   (auto-picked — set 'Focus goal' in config to plan your own target)");
		if (steps == 0) { out.add("✓ ready now — go do it!"); return out; }
		for (Map.Entry<String, Integer> e : skills.entrySet())
		{
			Skill sk = SKILL_BY_CAP.get(e.getKey());
			int cur = sk != null ? st.level(sk) : 0;
			String eta = sk != null ? etaFor(sk, e.getValue()) : null;
			out.add("• Train " + e.getKey() + " " + cur + "→" + e.getValue() + (eta != null ? "  (~" + eta + ")" : ""));
		}
		for (String q : quests) out.add("• Quest: " + q);
		for (String m : misc) out.add("• " + m);

		// --- HOW TO (the walkthrough layer): methods + live costs + Quest Helper handoff ---
		out.add("");
		out.add("HOW TO:");
		String how = CoachGoals.HOW.get(target.name);
		if (how != null) out.add("  " + how);
		Map<Integer, Integer> px = prices;
		for (Map.Entry<String, Integer> e : skills.entrySet())
		{
			Skill sk = SKILL_BY_CAP.get(e.getKey());
			String m = sk != null ? CoachGoals.METHOD.get(sk) : null;
			if (m == null) continue;
			String line = "  " + e.getKey() + " → " + m;
			if (sk == Skill.PRAYER && px != null)   // concrete cost: dragon bones (id 536) to target
			{
				long xpNeed = xpForLevel(e.getValue()) - client.getSkillExperience(Skill.PRAYER);
				if (xpNeed > 0) { long bones = (long) Math.ceil(xpNeed / 252.0); Integer bp = px.get(536);
					line += "  (~" + bones + " bones" + (bp != null ? " ≈ " + CoachGoals.gp(bones * bp) : "") + ")"; }
			}
			out.add(line);
		}
		if (!quests.isEmpty())
			out.add("  Quests → install the Quest Helper plugin for turn-by-turn steps (the Coach picks WHICH & the order; Quest Helper shows HOW).");
		return out;
	}

	private void collectPath(List<CoachGoals.Req> reqs, CoachState st, Map<String, Integer> skills,
		Set<String> quests, Set<String> misc, int depth)
	{
		for (CoachGoals.Req r : reqs)
		{
			CoachGoals.Gap g = r.gap(st);
			if (g == null) continue;
			String t = g.text;
			int plus = t.lastIndexOf(" +");
			if (plus > 0 && SKILL_BY_CAP.containsKey(t.substring(0, plus)))   // "<Skill> +N" → train to cur+N
			{
				String cap = t.substring(0, plus);
				try { int delta = Integer.parseInt(t.substring(plus + 2).trim()); int tgt = st.level(SKILL_BY_CAP.get(cap)) + delta;
					skills.merge(cap, tgt, Math::max); } catch (NumberFormatException ignored) {}
			}
			else if (t.startsWith("quest: ") || t.startsWith("start: "))
			{
				String qn = t.substring(7).trim();
				if (depth < 2) { CoachGoals.QuestRec qr = findQuestRec(qn); if (qr != null) collectPath(qr.reqs, st, skills, quests, misc, depth + 1); }
				quests.add(qn);   // added AFTER its prereqs → correct order in the LinkedHashSet
			}
			else misc.add(t);   // QP / gp / item
		}
	}

	private static CoachGoals.QuestRec findQuestRec(String prettyName)
	{
		for (CoachGoals.QuestRec qr : CoachGoals.QUESTS) if (CoachGoals.pretty(qr.q).equals(prettyName)) return qr;
		return null;
	}

	/** XP to reach a skill level, then ETA at your live per-skill rate (null if not training it). */
	private static long xpForLevel(int lvl)
	{
		double xp = 0;
		for (int i = 1; i < lvl; i++) xp += Math.floor(i + 300 * Math.pow(2, i / 7.0));
		return (long) Math.floor(xp / 4);
	}

	private String etaFor(Skill sk, int targetLevel)
	{
		Long base = startSkillXp.get(sk);
		if (base == null || sessStartMs == 0) return null;
		double hrs = (System.currentTimeMillis() - sessStartMs) / 3_600_000.0;
		long cur = client.getSkillExperience(sk);
		if (hrs < 0.03 || cur <= base) return null;   // not (yet) training this skill
		double rate = (cur - base) / hrs;
		long need = xpForLevel(targetLevel) - cur;
		if (need <= 0 || rate <= 0) return null;
		double eta = need / rate;
		return eta < 1 ? Math.max(1, Math.round(eta * 60)) + "m" : String.format("%.1fh", eta);
	}

	private static String fmtDays(double d)
	{
		if (d < 1) return "<1 day";
		long days = Math.round(d);
		return days <= 60 ? days + " days" : days + " days (~" + Math.round(days / 30.0) + " months)";
	}

	/** The "Path to 99 Farming" trainer: your live level/XP + the optimal what-to-plant for your band,
	 *  the next milestone, a realistic days-to-99 ETA (daily-run rate, not the bursty session rate), and
	 *  the must-have efficiency items. Sourced from the wiki-verified 1→99 plan in CoachFarmPlan. */
	private List<String> pathTo99(CoachState st)
	{
		List<String> o = new ArrayList<>();
		int lvl = st.level(Skill.FARMING);
		o.add("PATH TO 99 FARMING");
		if (lvl >= 99) { o.add("99 Farming — done! The cape perk gives unlimited farming teleports."); o.add(""); return o; }
		long cur = client.getSkillExperience(Skill.FARMING);
		long xp99 = CoachFarmPlan.xpForLevel(99);
		o.add("Level " + lvl + " · " + CoachGoals.gp(Math.max(0, xp99 - cur)) + " XP to 99 ("
			+ (int) Math.min(100, 100.0 * cur / xp99) + "% there)");
		o.add("ETA to 99: ~" + fmtDays(CoachFarmPlan.daysTo99(cur)) + " of daily runs");
		o.add("");
		CoachFarmPlan.Band b = CoachFarmPlan.bandFor(lvl);
		o.add("DO NOW (level " + lvl + "):");
		o.add("• Trees: " + b.tree + "    • Fruit: " + b.fruit);
		o.add("• Herbs: " + b.herb + "    • Special: " + b.special);
		o.add("• ~" + b.xpDay + " on the daily circuit");
		if (b.note != null) o.add("• " + b.note);
		CoachFarmPlan.Band nx = CoachFarmPlan.nextBand(lvl);
		if (nx != null)
			o.add("NEXT: level " + nx.min + " → " + nx.unlock + "  (" + CoachGoals.gp(Math.max(0, CoachFarmPlan.xpForLevel(nx.min) - cur)) + " XP away)");
		o.add("MUST: " + CoachFarmPlan.efficiencyChecklist(lvl));
		o.add("");
		return o;
	}

	/** Cross-reference: publish what your account needs right now (the current farm run's seeds/saplings
	 *  + ultracompost) to the shared bridge, so the FLIPPER can price them live and help you buy your
	 *  progression. Cleared when the farming helper is off. */
	private void publishAccountNeeds(CoachState st)
	{
		if (!config.farmingHelper()) { com.geflip.GeflipShared.setNeeds(null); return; }
		int lvl = st.level(Skill.FARMING);
		String t = farmRunType == null ? "Herb" : farmRunType;
		List<com.geflip.GeflipShared.Need> needs = new ArrayList<>();
		boolean all = "All".equals(t);
		if (all || "Tree".equals(t))  { String s = CoachFarm.treeSaplingFor(lvl);  if (s != null) needs.add(new com.geflip.GeflipShared.Need(s, 6, "tree run")); }
		if (all || "Fruit".equals(t)) { String s = CoachFarm.fruitSaplingFor(lvl); if (s != null) needs.add(new com.geflip.GeflipShared.Need(s, 5, "fruit-tree run")); }
		if (all || "Herb".equals(t))
		{
			String s = CoachFarm.herbSeedFor(lvl);
			if (s != null) { needs.add(new com.geflip.GeflipShared.Need(s, 6, "herb run")); needs.add(new com.geflip.GeflipShared.Need("Ultracompost", 6, "herb run")); }
		}
		if ("Bush".equals(t)) { String s = CoachFarm.bushSeedFor(lvl); if (s != null) needs.add(new com.geflip.GeflipShared.Need(s, 5, "bush run")); }
		if ("Flower".equals(t)) needs.add(new com.geflip.GeflipShared.Need("Limpwurt seed", 5, "flower run"));
		com.geflip.GeflipShared.setNeeds(needs);
	}

	private static String skName(Skill sk) { String n = sk.name(); return n.charAt(0) + n.substring(1).toLowerCase(); }
	private static String fmtHrs(double h) { return h < 1 ? "<1h" : Math.round(h) + "h"; }

	/** The "Skills" trainer: every skill below 99, sorted by nearest-to-99 first, with your level, the
	 *  optimal method for your band, its XP/hr + cost tag, and a realistic active-hours ETA. Farming has
	 *  its own richer trainer in the Farm tab. Lines prefixed "*" are headers. */
	private List<String> skillLines(CoachState st)
	{
		List<String> o = new ArrayList<>(CoachDailies.lines(st));   // the compounding dailies most players skip
		o.addAll(CoachUnlocks.lines(st));                           // permanent unlocks you don't have yet
		o.add("*SKILLS — your road to 99 (nearest first)");
		java.util.List<Object[]> rows = new ArrayList<>();   // {skill, level, band, hours}
		for (Skill sk : Skill.values())
		{
			if (sk == Skill.OVERALL || sk == Skill.FARMING) continue;   // farming = its own Farm-tab trainer
			int lvl = st.level(sk);
			if (lvl >= 99) continue;
			CoachSkillPlan.Band b = CoachSkillPlan.bestBand(sk, lvl);
			if (b == null) continue;
			rows.add(new Object[]{ sk, lvl, b, CoachSkillPlan.hoursTo99(sk, client.getSkillExperience(sk)) });
		}
		rows.sort((a, c) ->
		{
			double ha = (double) a[3], hc = (double) c[3];   // passive/unknown (≤0) sink to the bottom
			return Double.compare(ha <= 0 ? Double.MAX_VALUE : ha, hc <= 0 ? Double.MAX_VALUE : hc);
		});
		for (Object[] r : rows)
		{
			Skill sk = (Skill) r[0]; int lvl = (int) r[1]; CoachSkillPlan.Band b = (CoachSkillPlan.Band) r[2]; double hrs = (double) r[3];
			String eta = b.xpHr <= 0 ? "passive (from combat)" : hrs > 0 ? "~" + fmtHrs(hrs) + " of training to 99" : "";
			o.add("*" + skName(sk) + " " + lvl + "   ·   " + eta);
			o.add("  " + b.method + (b.xpHr > 0 ? "  (~" + CoachGoals.gp(b.xpHr) + "/hr" + (b.cost.isEmpty() ? "" : " · " + b.cost) + ")" : ""));
		}
		if (rows.isEmpty()) o.add("  every trainable skill is 99 — maxed! 🎉");
		// money-maker advisor — gated by YOUR stats, ranked by gp/hr
		o.add("");
		o.add("*💰 BEST MONEY-MAKERS — every live route ranked (PvM · skilling · GE)");
		// UNIFIED GP/hr ROUTER: fuse the Coach's stat-gated PvM/skilling routes with the flipper's LIVE
		// GE routes (flips + high-alch, via the in-process bridge) into one ranked leaderboard — the thing
		// single-purpose tools can't do because they're a flipper OR a coach, never both.
		java.util.List<Object[]> routes = new ArrayList<>();   // {name, gpHr(long), note, kind}
		for (CoachMoney.M m : CoachMoney.eligible(st))
			routes.add(new Object[]{ m.name, (long) m.gpHr, m.note == null ? "" : m.note, "PvM/skill" });
		for (com.geflip.GeflipShared.Route r : com.geflip.GeflipShared.flipRoutes())
			if (r.gpHr > 0) routes.add(new Object[]{ r.label, r.gpHr, "", r.kind });
		routes.sort((x, y) -> Long.compare((Long) y[1], (Long) x[1]));   // highest gp/hr first (passive/varies sink to the end)
		for (Object[] r : routes)
		{
			long gph = (Long) r[1];
			o.add("*" + r[0] + "   ·   " + (gph > 0 ? "~" + CoachGoals.gp(gph) + "/hr" : "passive / varies") + "   [" + r[3] + "]");
			String note = (String) r[2];
			if (!note.isEmpty()) o.add("  " + note);
		}
		o.add("");
		o.add("Farming → see the Farm tab for the full path-to-99 + lead-through.");
		return o;
	}

	// Destinations I'm 100% sure of → an in-game hint arrow. Only certain tiles (no misdirection);
	// everything else is guided by the popup text + Quest Helper + your shortest-path plugin.
	private static final java.util.Map<String, int[]> GUIDE_DEST = new java.util.HashMap<>();
	static
	{
		int[] ge = { 3164, 3487, 0 };   // Grand Exchange centre
		GUIDE_DEST.put("Occult necklace", ge);
		GUIDE_DEST.put("Amulet of anguish", ge);
		GUIDE_DEST.put("Trident of the swamp", ge);
	}

	/** Clicked a goal → drop the in-game hint arrow toward its destination (when known for sure). */
	void guideTo(String goalName)
	{
		int[] d = GUIDE_DEST.get(goalName);
		if (d == null)
		{
			clientThread.invoke(client::clearHintArrow);   // no fixed spot → clear any old arrow, don't misdirect
			if (panel != null) panel.setStatus("see the popup — Quest Helper / shortest-path will route you");
			return;
		}
		clientThread.invoke(() -> client.setHintArrow(new net.runelite.api.coords.WorldPoint(d[0], d[1], d[2])));
		if (panel != null) panel.setStatus("→ hint arrow set to the Grand Exchange for " + goalName);
	}

	private volatile int[] farmArrow;   // last farm patch the arrow points at (toggle target)

	/** Tapped a farm stop → drop an in-world hint arrow ON that patch (tap the same stop again to clear).
	 *  Coords are wiki-verified within the hint-arrow tolerance; Troll Stronghold's is plane 1 (the roof). */
	void guideToFarm(CoachPanel.FarmStep s)
	{
		if (s == null) return;
		int[] tgt = { s.x, s.y, s.plane };
		if (farmArrow != null && farmArrow[0] == tgt[0] && farmArrow[1] == tgt[1] && farmArrow[2] == tgt[2])
		{
			farmArrow = null;
			clientThread.invoke(client::clearHintArrow);
			if (panel != null) panel.setStatus("cleared the farm arrow");
			return;
		}
		farmArrow = tgt;
		clientThread.invoke(() -> client.setHintArrow(new net.runelite.api.coords.WorldPoint(tgt[0], tgt[1], tgt[2])));
		if (panel != null) panel.setStatus("→ walk to " + s.loc + " (" + s.tele + ")");
	}

	/** Build the tappable lead-me-there steps for the current run type: every patch, gated to your
	 *  unlocks (locked ones show what unlocks them). Occupied state is left UNKNOWN on purpose — the
	 *  game only transmits a patch's live state while you're in its region, so a route-wide read would
	 *  be stale/wrong; RuneLite's Timetracking (which persists per-patch snapshots) owns ready-times. */
	private java.util.List<CoachPanel.FarmStep> farmSteps(CoachState st)
	{
		java.util.List<CoachPanel.FarmStep> out = new ArrayList<>();
		for (CoachFarm.Patch p : CoachFarm.patchesFor(farmRunType))
		{
			CoachPanel.FarmStep s = new CoachPanel.FarmStep();
			s.loc = p.loc; s.tele = p.tele; s.reqLabel = p.reqLabel;
			s.x = p.x; s.y = p.y; s.plane = p.plane;
			s.locked = !p.open(st);
			s.occupied = -1;
			out.add(s);
		}
		return out;
	}

	/** Pick which preset farm run the Farm tab shows (Herb/Tree/Fruit/Flower/Bush/All). */
	void selectFarmRun(String type) { farmRunType = type; rescan(); }

	/** Record "I just did a farm run" (persisted), so the Farm tab counts down to the next one. */
	void markFarmRun()
	{
		configManager.setConfiguration("geflipcoach", "lastFarmRunMs", System.currentTimeMillis());
		if (panel != null) panel.setStatus("farm run logged — timers reset");
		rescan();
	}

	/** Minutes since your last logged farm run (−1 if never). */
	private int farmElapsedMin()
	{
		String v = configManager.getConfiguration("geflipcoach", "lastFarmRunMs");
		if (v == null) return -1;
		try { long last = Long.parseLong(v.trim()); long mins = (System.currentTimeMillis() - last) / 60000; return last > 0 && mins >= 0 ? (int) mins : -1; }
		catch (NumberFormatException e) { return -1; }
	}

	/** Push a one-line message to the configured Discord webhook (off-thread, best-effort). So your
	 *  phone buzzes when a goal unlocks even with the game closed. */
	private void webhook(String msg)
	{
		final String url = config.webhookUrl().trim();
		if (url.isEmpty() || !url.startsWith("http")) return;
		executor.submit(() ->
		{
			try
			{
				HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
				c.setRequestMethod("POST");
				c.setConnectTimeout(10000); c.setReadTimeout(10000);
				c.setRequestProperty("Content-Type", "application/json");
				c.setDoOutput(true);
				JsonObject body = new JsonObject();
				body.addProperty("content", msg);
				try (OutputStream os = c.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
				c.getResponseCode();   // fire; ignore body
				c.disconnect();
			}
			catch (Exception e) { log.debug("coach: webhook failed", e); }
		});
	}

	private static final int[] CA_TIERS = {
		Varbits.COMBAT_ACHIEVEMENT_TIER_EASY, Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM,
		Varbits.COMBAT_ACHIEVEMENT_TIER_HARD, Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE,
		Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER, Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER };
	private static final String[] CA_NAMES = { "Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster" };

	/** Highest Combat Achievement tier completed (varbit == 2), or null if none. */
	private String caTier()
	{
		int highest = -1;
		for (int i = 0; i < CA_TIERS.length; i++) if (client.getVarbitValue(CA_TIERS[i]) >= 2) highest = i;
		return highest < 0 ? null : CA_NAMES[highest];
	}

	private List<String> diaryLines()
	{
		List<String> out = new ArrayList<>();
		for (Object[] d : DIARIES)
		{
			int highest = -1;
			for (int t = 0; t < 4; t++) if (client.getVarbitValue((Integer) d[t + 1]) > 0) highest = t;
			out.add(d[0] + ": " + (highest < 0 ? "—" : TIER[highest] + " ✓"));
		}
		return out;
	}

	// --- LLM Ask -------------------------------------------------------------
	/** The context prompt (used both by Copy-context and the endpoint call). */
	private String buildContext()
	{
		CoachState st = lastState;
		if (st == null || !st.loggedIn) return "Log in and rescan first so the coach can read your account.";
		StringBuilder b = new StringBuilder();
		b.append("You are an expert Old School RuneScape progression coach. Give specific, prioritised, "
			+ "correct advice for THIS account. Be concise and concrete.\n\n");
		b.append("ACCOUNT — combat ").append(st.combatLevel).append(", ").append(st.qp).append(" QP");
		if (st.coins >= 0) b.append(", ").append(CoachGoals.gp(st.coins)).append(" gp on hand");
		b.append(".\nLevels: ");
		for (Skill sk : Skill.values())
		{
			if (sk == Skill.OVERALL) continue;
			b.append(CoachGoals.cap(sk.name())).append(' ').append(st.level(sk)).append("  ");
		}
		b.append("\n\nCOACH PLAN (computed from a curated goal graph):\n");
		List<CoachEngine.Scored> all = CoachEngine.evaluate(st);
		b.append("Ready now: ");
		for (CoachEngine.Scored sc : CoachEngine.doNext(all)) if (sc.status == CoachEngine.Status.READY) b.append(sc.goal.name).append("; ");
		b.append("\nAlmost (small gaps): ");
		for (CoachEngine.Scored sc : all) if (sc.status == CoachEngine.Status.ALMOST) b.append(sc.goal.name).append(" [").append(String.join(", ", sc.gaps)).append("]; ");
		b.append("\nBlocked (long-term): ");
		for (CoachEngine.Scored sc : CoachEngine.blocked(all)) b.append(sc.goal.name).append(" [").append(String.join(", ", sc.gaps)).append("]; ");
		// QUESTS — the coach reads EVERY quest, so it knows what you've done and what's left that matters
		int done = 0; for (Quest q : st.quests.keySet()) if (st.finished(q)) done++;
		b.append("\n\nQUESTS: ").append(done).append(" done. High-value not-done:\n");
		for (CoachEngine.QuestScored qs : CoachEngine.quests(st))
			b.append("- ").append(CoachGoals.pretty(qs.rec.q)).append(qs.ready ? " — DO NOW" : " [" + String.join(", ", qs.gaps) + "]")
				.append(" (").append(qs.rec.note).append(")\n");
		List<String> pvm = pvmLines();
		if (!pvm.isEmpty()) b.append("\nPvM experience (kill counts): ").append(String.join(", ", pvm));
		return b.toString();
	}

	/** Deterministic answer from the coach's own brain — used when no LLM endpoint is set, so the Ask
	 *  box always gives real advice instead of a dead message. Routes on the question's intent. */
	private String localAnswer(String question)
	{
		CoachState st = lastState;
		if (st == null || !st.loggedIn) return "Log in and rescan first, then ask again.";
		String q = question.toLowerCase();
		List<CoachEngine.Scored> all = CoachEngine.evaluate(st);
		StringBuilder b = new StringBuilder();
		if (q.contains("quest"))
		{
			b.append("Next quests for your stats:\n");
			int n = 0;
			for (CoachEngine.QuestScored qs : CoachEngine.quests(st))
			{
				if (n++ >= 8) break;
				b.append(qs.ready ? "✓ DO NOW: " : "○ ").append(CoachGoals.pretty(qs.rec.q));
				if (!qs.ready) b.append(" — needs ").append(String.join(", ", qs.gaps));
				b.append("  · ").append(qs.rec.note).append('\n');
			}
			return b.toString();
		}
		if (q.contains("money") || q.contains("gp") || q.contains("boss") || q.contains("cash"))
		{
			b.append("Best money/bosses you can do NOW:\n");
			for (CoachEngine.Scored sc : CoachEngine.doNext(all))
				if (sc.status == CoachEngine.Status.READY) b.append("✓ ").append(sc.goal.name).append(" — ").append(sc.goal.note).append('\n');
			return b.length() == 0 ? "Nothing boss-ready yet — see the Next tab." : b.toString();
		}
		if (q.contains("train") || q.contains("skill") || q.contains("prayer") || q.contains("level") || q.contains("grind") || q.contains("next"))
		{
			b.append("Highest-leverage next steps:\n");
			int n = 0;
			for (CoachEngine.Scored sc : CoachEngine.doNext(all))
			{
				if (n++ >= 8) break;
				b.append(sc.status == CoachEngine.Status.READY ? "✓ " : "○ ").append(sc.goal.name);
				if (!sc.gaps.isEmpty()) b.append(" — ").append(String.join(", ", sc.gaps));
				b.append('\n');
			}
			return b.toString();
		}
		// default: the do-next plan
		b.append("Do next:\n");
		int n = 0;
		for (CoachEngine.Scored sc : CoachEngine.doNext(all))
		{
			if (n++ >= 8) break;
			b.append(sc.status == CoachEngine.Status.READY ? "✓ " : "○ ").append(sc.goal.name)
				.append(sc.gaps.isEmpty() ? " (ready)" : " — " + String.join(", ", sc.gaps)).append('\n');
		}
		b.append("\n(Set an LLM endpoint in Config → Ask, or use Copy-context → paste into Claude, for free-form coaching.)");
		return b.toString();
	}

	private void ask(String question)
	{
		final String ctx = buildContext();
		final String q = question;
		if (panel != null) panel.setAskResult("looking it up…");
		// EVERYTHING off the EDT: fetch live OSRS wiki knowledge for the question, then either ground
		// the LLM with it (endpoint set) or show the wiki + the coach's own plan (no endpoint). This is
		// how the coach "knows everything" — it pulls the current wiki on demand, never a stale copy.
		executor.submit(() ->
		{
			String wiki = wikiSummary(q);
			String url = config.askUrl().trim();
			if (!url.isEmpty())
			{
				try
				{
					String grounded = (wiki.isEmpty() ? "" : "OSRS WIKI (current, authoritative):\n" + wiki + "\n\n") + ctx;
					String reply = callLlm(url, config.askKey().trim(), config.askModel().trim(), grounded, q);
					if (panel != null) panel.setAskResult(reply);
				}
				catch (Exception e)
				{
					if (panel != null) panel.setAskResult((wiki.isEmpty() ? "" : "📖 " + wiki + "\n\n") + localAnswer(q)
						+ "\n\n(LLM endpoint failed: " + e.getMessage() + ")");
				}
			}
			else if (panel != null)
			{
				// no LLM: still answer with live wiki knowledge + the coach's computed plan
				panel.setAskResult((wiki.isEmpty() ? "" : "📖 " + wiki + "\n\n") + localAnswer(q));
			}
		});
	}

	/** Pull the current OSRS Wiki summary for a question — search → top page → plaintext extract.
	 *  Live + authoritative, so the coach answers from real game knowledge, never a bundled snapshot. */
	private String wikiSummary(String question)
	{
		try
		{
			// strip conversational filler so "how do i do zulrah" searches the topic ("zulrah"), not "how"
			String topic = question.toLowerCase().replaceAll(
				"\\b(how|to|do|i|get|got|the|a|an|what|whats|is|are|where|when|best|for|at|my|me|in|on|and|with|should|can|need|of|vs|good|way|it)\\b", " ")
				.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
			if (topic.isEmpty()) topic = question;
			String base = "https://oldschool.runescape.wiki/api.php?format=json&action=query";
			String search = base + "&list=search&srlimit=1&srsearch="
				+ java.net.URLEncoder.encode(topic, "UTF-8");
			JsonObject sj = new JsonParser().parse(httpGet(search)).getAsJsonObject();
			JsonArray hits = sj.getAsJsonObject("query").getAsJsonArray("search");
			if (hits.size() == 0) return "";
			String title = hits.get(0).getAsJsonObject().get("title").getAsString();
			String ext = base + "&prop=extracts&explaintext=1&exchars=1200&redirects=1&titles="
				+ java.net.URLEncoder.encode(title, "UTF-8");
			JsonObject ej = new JsonParser().parse(httpGet(ext)).getAsJsonObject();
			JsonObject pages = ej.getAsJsonObject("query").getAsJsonObject("pages");
			for (Map.Entry<String, com.google.gson.JsonElement> e : pages.entrySet())
			{
				JsonObject pg = e.getValue().getAsJsonObject();
				if (pg.has("extract") && !pg.get("extract").isJsonNull())
				{
					String x = pg.get("extract").getAsString().trim();
					return title + ": " + (x.length() > 1000 ? x.substring(0, 1000) + "…" : x);
				}
			}
			return "";
		}
		catch (Exception e) { log.debug("coach: wiki lookup failed", e); return ""; }
	}

	private static String httpGet(String url) throws Exception
	{
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestProperty("User-Agent", "geflip-coach - live wiki lookup");
		c.setConnectTimeout(12000); c.setReadTimeout(20000);
		return new String(readAll(c.getInputStream()), StandardCharsets.UTF_8);
	}

	/** Minimal OpenAI-compatible chat-completions POST; parses OpenAI or Anthropic response shapes. */
	private String callLlm(String url, String key, String model, String ctx, String question) throws Exception
	{
		boolean anthropic = url.contains("anthropic.com");
		JsonObject body = new JsonObject();
		JsonObject usr = new JsonObject(); usr.addProperty("role", "user"); usr.addProperty("content", question);
		JsonArray msgs = new JsonArray();
		if (anthropic)
		{
			// Anthropic Messages API: system is a top-level field, max_tokens required, x-api-key auth.
			body.addProperty("model", model.isEmpty() ? "claude-sonnet-4-5" : model);
			body.addProperty("max_tokens", 900);
			body.addProperty("system", ctx);
			msgs.add(usr);
			body.add("messages", msgs);
		}
		else
		{
			// OpenAI-compatible chat completions: system as a message, Bearer auth.
			JsonObject sys = new JsonObject(); sys.addProperty("role", "system"); sys.addProperty("content", ctx);
			msgs.add(sys); msgs.add(usr);
			body.addProperty("model", model.isEmpty() ? "gpt-4o-mini" : model);
			body.add("messages", msgs);
			body.addProperty("max_tokens", 900);
		}

		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setRequestMethod("POST");
		c.setConnectTimeout(15000); c.setReadTimeout(60000);
		c.setRequestProperty("Content-Type", "application/json");
		if (!key.isEmpty())
		{
			if (anthropic) { c.setRequestProperty("x-api-key", key); c.setRequestProperty("anthropic-version", "2023-06-01"); }
			else c.setRequestProperty("Authorization", "Bearer " + key);
		}
		c.setDoOutput(true);
		byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
		try (OutputStream os = c.getOutputStream()) { os.write(out); }
		int code = c.getResponseCode();
		java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
		String resp = new String(readAll(in), StandardCharsets.UTF_8);
		if (code >= 400) return "endpoint " + code + ": " + resp;
		try
		{
			JsonObject j = new JsonParser().parse(resp).getAsJsonObject();
			if (j.has("choices"))   // OpenAI
				return j.getAsJsonArray("choices").get(0).getAsJsonObject()
					.getAsJsonObject("message").get("content").getAsString();
			if (j.has("content"))   // Anthropic
				return j.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
		}
		catch (Exception ignored) { /* fall through to raw */ }
		return resp;
	}

	private static byte[] readAll(java.io.InputStream in) throws Exception
	{
		if (in == null) return new byte[0];
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[4096]; int n;
		while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
		return bo.toByteArray();
	}

	private static String timeShort()
	{
		java.time.LocalTime t = java.time.LocalTime.now();
		return String.format("%02d:%02d", t.getHour(), t.getMinute());
	}
}
