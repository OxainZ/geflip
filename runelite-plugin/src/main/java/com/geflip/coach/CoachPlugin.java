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

	private CoachPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> refresh;
	private volatile CoachState lastState;

	@Provides
	CoachConfig provideConfig(net.runelite.client.config.ConfigManager cm) { return cm.getConfig(CoachConfig.class); }

	@Override
	protected void startUp()
	{
		panel = new CoachPanel(this::rescan, this::ask, this::buildContext);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/coach_icon.png");
		navButton = NavigationButton.builder().tooltip("Geflip Coach").icon(icon).priority(8).panel(panel).build();
		clientToolbar.addNavigation(navButton);
		int s = Math.max(10, config.refreshSec());
		refresh = executor.scheduleWithFixedDelay(this::rescan, 3, s, TimeUnit.SECONDS);
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
			p.setSummary("combat " + st.combatLevel + " · " + st.qp + " QP"
				+ (st.coins >= 0 ? " · " + CoachGoals.gp(st.coins) + " gp" : "")
				+ (st.bankKnown ? "" : " · (open bank for gear)"));
			p.setNext(CoachEngine.doNext(all));
			p.setGoals(all, questLines(st), diaryLines());
			p.setBlocked(CoachEngine.blocked(all));
		});
	}

	// --- account snapshot (client thread) ------------------------------------
	private CoachState buildState()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
			return new CoachState(new EnumMap<>(Skill.class), 0, new EnumMap<>(Quest.class),
				new HashSet<>(), false, -1, 0, false);

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
		long coins = 0;
		coins += scan(InventoryID.EQUIPMENT, keyIds, owned);
		coins += scan(InventoryID.INVENTORY, keyIds, owned);
		boolean bankKnown = client.getItemContainer(InventoryID.BANK) != null;
		coins += scan(InventoryID.BANK, keyIds, owned);

		int combat = CoachState.combat(get(levels, Skill.ATTACK), get(levels, Skill.STRENGTH),
			get(levels, Skill.DEFENCE), get(levels, Skill.HITPOINTS), get(levels, Skill.RANGED),
			get(levels, Skill.PRAYER), get(levels, Skill.MAGIC));
		return new CoachState(levels, qp, quests, owned, bankKnown, coins, combat, true);
	}

	/** Scan a container: record any KEY_ITEMS present into `owned`, and return coins (id 995) found. */
	private long scan(InventoryID which, Set<Integer> keyIds, Set<Integer> owned)
	{
		ItemContainer c = client.getItemContainer(which);
		if (c == null) return 0;
		long coins = 0;
		for (Item it : c.getItems())
		{
			if (it == null) continue;
			int id = it.getId();
			if (id == 995) coins += it.getQuantity();
			else if (keyIds.contains(id)) owned.add(id);
		}
		return coins;
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
