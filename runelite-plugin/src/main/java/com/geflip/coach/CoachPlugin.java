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
	// PvM progress from the OSRS hiscores (boss KCs, collection log, clues)
	private volatile net.runelite.client.hiscore.HiscoreResult hiscore;
	private volatile String hiscoreName;
	private volatile long lastHiscoreMs = 0;

	@Provides
	CoachConfig provideConfig(net.runelite.client.config.ConfigManager cm) { return cm.getConfig(CoachConfig.class); }

	@Override
	protected void startUp()
	{
		panel = new CoachPanel(this::rescan, this::ask, this::buildContext, this::markFarmRun);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/coach_icon.png");
		navButton = NavigationButton.builder().tooltip("Geflip Coach").icon(icon).priority(8).panel(panel).build();
		clientToolbar.addNavigation(navButton);
		int s = Math.max(10, config.refreshSec());
		refresh = executor.scheduleWithFixedDelay(this::rescan, 3, s, TimeUnit.SECONDS);
		executor.scheduleWithFixedDelay(this::fetchPrices, 0, 600, TimeUnit.SECONDS);   // net-worth prices
	}

	@Override
	protected void shutDown()
	{
		if (refresh != null) refresh.cancel(true);
		clientToolbar.removeNavigation(navButton);
		panel = null;
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
			p.setSummary("combat " + st.combatLevel + " · " + st.qp + " QP"
				+ (st.wealth >= 0 ? " · " + CoachGoals.gp(st.wealth) + " net" : st.coins >= 0 ? " · " + CoachGoals.gp(st.coins) + " gp" : "")
				+ (ca != null ? " · CA " + ca : "")
				+ (st.bankKnown ? "" : " · (open bank for full net worth)"));
			p.setSessionStats(sessionStats(st));
			refreshHiscore();
			p.setNext(CoachEngine.doNext(all));
			p.setGoals(all, questLines(st), pvmLines(), diaryLines());
			p.setBlocked(CoachEngine.blocked(all));
			p.setFarm(config.farmingHelper() ? CoachFarm.plan(st.level(Skill.FARMING), farmElapsedMin()) : null);
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
			for (String g : ready) if (!lastReady.contains(g)) notifier.notify("Geflip Coach: \"" + g + "\" is now available!");
			for (String q : qReady) if (!lastQuestReady.contains(q)) notifier.notify("Geflip Coach: you can now start \"" + q + "\"");
		}
		lastReady.clear(); lastReady.addAll(ready);
		lastQuestReady.clear(); lastQuestReady.addAll(qReady);
		primed = true;
	}

	/** Live session rates — XP/hr overall and gp/hr (net-worth delta). Baseline snaps on first read. */
	private String sessionStats(CoachState st)
	{
		long now = System.currentTimeMillis(), xp = totalXp();
		if (sessStartMs == 0) { sessStartMs = now; sessStartXp = xp; sessStartWealth = st.wealth; return "session: tracking…"; }
		if (sessStartWealth < 0 && st.wealth >= 0) sessStartWealth = st.wealth;   // baseline once prices load
		double hrs = (now - sessStartMs) / 3_600_000.0;
		if (hrs < 1.0 / 60) return "session: warming up…";
		long xpH = Math.round((xp - sessStartXp) / hrs);
		String s = "session: " + CoachGoals.gp(xpH) + " xp/hr";
		if (st.wealth >= 0 && sessStartWealth >= 0)
		{
			long gpH = Math.round((st.wealth - sessStartWealth) / hrs);
			s += " · " + (gpH >= 0 ? "+" : "-") + CoachGoals.gp(Math.abs(gpH)) + " gp/hr";
		}
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
		try { long last = Long.parseLong(v.trim()); return last > 0 ? (int) ((System.currentTimeMillis() - last) / 60000) : -1; }
		catch (NumberFormatException e) { return -1; }
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
		b.append("\n\nQUESTS (").append(done).append(" of ").append(st.quests.size()).append(" tracked done). High-value not-done:\n");
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
		final String url = config.askUrl().trim();
		if (url.isEmpty())
		{
			// no endpoint → answer from the coach's own engine instead of a dead message
			if (panel != null) panel.setAskResult(localAnswer(question));
			return;
		}
		executor.submit(() ->
		{
			try
			{
				String reply = callLlm(url, config.askKey().trim(), config.askModel().trim(), ctx, question);
				if (panel != null) panel.setAskResult(reply);
			}
			catch (Exception e)
			{
				if (panel != null) panel.setAskResult("Ask failed: " + e.getMessage()
					+ "\n\nTip: leave the endpoint blank and use Copy-context → paste into Claude.");
			}
		});
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
