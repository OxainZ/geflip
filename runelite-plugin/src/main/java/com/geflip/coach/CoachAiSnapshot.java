package com.geflip.coach;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * The AI lane: serialize the coach's full account read into ONE JSON object
 * and push it to the same sync Worker the flipper already uses, under the
 * top-level key {@code account}. The Worker shallow-merges, so this never
 * clobbers the flipper's {@code fills}/{@code session} or the web app's
 * {@code config}. Anything holding the sync-id (a Claude session, the
 * Telegram bridge, curl) can then GET the blob and advise from live state.
 *
 * READ-ONLY toward the game, advice-only toward the player: this ships
 * account STATE out; nothing here (or anywhere in geflip) automates input.
 */
final class CoachAiSnapshot
{
	private CoachAiSnapshot() {}

	/** Pure builder — everything passed in is immutable or already snapshotted
	 * on the client thread, so this is safe to call from any thread. */
	static JsonObject build(CoachState st, List<CoachEngine.Scored> goals,
		String caTier, int slayerPoints, int slayerStreak, CoachWom.Result wom)
	{
		JsonObject o = new JsonObject();
		o.addProperty("updated", System.currentTimeMillis() / 1000);
		o.addProperty("loggedIn", st.loggedIn);
		o.addProperty("combat", st.combatLevel);
		o.addProperty("qp", st.qp);
		if (st.coins >= 0) o.addProperty("gp", st.coins);
		if (st.wealth >= 0) o.addProperty("netWorth", st.wealth);
		o.addProperty("bankKnown", st.bankKnown);

		JsonObject skills = new JsonObject();
		int total = 0;
		for (Map.Entry<Skill, Integer> e : st.levels.entrySet())
		{
			skills.addProperty(e.getKey().getName(), e.getValue());
			total += e.getValue();
		}
		o.add("skills", skills);
		o.addProperty("totalLevel", total);

		int done = 0;
		JsonArray inProgress = new JsonArray();
		for (Map.Entry<Quest, QuestState> e : st.quests.entrySet())
		{
			if (e.getValue() == QuestState.FINISHED) done++;
			else if (e.getValue() == QuestState.IN_PROGRESS) inProgress.add(e.getKey().getName());
		}
		JsonObject quests = new JsonObject();
		quests.addProperty("trackedFinished", done);
		quests.add("inProgress", inProgress);
		o.add("quests", quests);

		if (caTier != null) o.addProperty("caTier", caTier);
		if (slayerPoints >= 0) o.addProperty("slayerPoints", slayerPoints);
		if (slayerStreak >= 0) o.addProperty("slayerStreak", slayerStreak);

		if (goals != null)
		{
			JsonArray g = new JsonArray();
			int n = 0;
			for (CoachEngine.Scored s : goals)
			{
				if (n++ >= 12) break;                    // keep the blob small
				JsonObject row = new JsonObject();
				row.addProperty("goal", s.goal.name);
				row.addProperty("ready", s.gaps.isEmpty());
				row.add("gaps", new Gson().toJsonTree(
					s.gaps.subList(0, Math.min(4, s.gaps.size()))));
				g.add(row);
			}
			o.add("goals", g);
		}

		if (wom != null && wom.tracked)
		{
			JsonObject w = new JsonObject();
			w.addProperty("ehp", Math.round(wom.ehp));
			w.addProperty("ehb", Math.round(wom.ehb));
			if (wom.ttm > 0) w.addProperty("hoursToMax", Math.round(wom.ttm));
			o.add("wom", w);
		}
		return o;
	}

	/** PUT {"account": snapshot} to the Worker — the flipper's exact HTTP
	 * pattern (best-effort, bounded timeouts, always disconnect). Call OFF
	 * the client thread. */
	static void push(String url, String id, JsonObject account)
	{
		if (url == null || url.isEmpty() || id == null || id.length() < 16) return;
		try
		{
			JsonObject o = new JsonObject();
			o.add("account", account);
			byte[] body = o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			String full = url.replaceAll("/+$", "") + "/?id="
				+ java.net.URLEncoder.encode(id, "UTF-8");
			java.net.HttpURLConnection c =
				(java.net.HttpURLConnection) new java.net.URL(full).openConnection();
			try
			{
				c.setRequestMethod("PUT");
				c.setDoOutput(true);
				c.setRequestProperty("Content-Type", "application/json");
				c.setConnectTimeout(10000);
				c.setReadTimeout(15000);
				try (java.io.OutputStream os = c.getOutputStream()) { os.write(body); }
				c.getResponseCode();
			}
			finally { c.disconnect(); }
		}
		catch (Exception ignored) {}                     // best-effort, like cloudPush
	}
}
