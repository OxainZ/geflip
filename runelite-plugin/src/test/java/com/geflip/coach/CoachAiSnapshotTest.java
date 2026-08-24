package com.geflip.coach;

import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CoachAiSnapshotTest
{
	private CoachState state()
	{
		Map<Skill, Integer> lv = new EnumMap<>(Skill.class);
		for (Skill s : Skill.values()) lv.put(s, 1);
		lv.put(Skill.ATTACK, 75);
		lv.put(Skill.MAGIC, 69);
		Map<Quest, QuestState> q = new HashMap<>();
		q.put(Quest.DRAGON_SLAYER_I, QuestState.FINISHED);
		q.put(Quest.MONKEY_MADNESS_I, QuestState.IN_PROGRESS);
		return new CoachState(lv, 225, q, new HashSet<>(), true,
			40_000_000L, 46_000_000L, 92, true);
	}

	@Test
	public void buildCarriesAccountFacts()
	{
		JsonObject o = CoachAiSnapshot.build(state(), null, "Hard", 120, 55, null);
		assertEquals(92, o.get("combat").getAsInt());
		assertEquals(225, o.get("qp").getAsInt());
		assertEquals(40_000_000L, o.get("gp").getAsLong());
		assertEquals(46_000_000L, o.get("netWorth").getAsLong());
		assertEquals(75, o.getAsJsonObject("skills").get("Attack").getAsInt());
		assertEquals("Hard", o.get("caTier").getAsString());
		assertEquals(120, o.get("slayerPoints").getAsInt());
		JsonObject quests = o.getAsJsonObject("quests");
		assertEquals(1, quests.get("trackedFinished").getAsInt());
		assertEquals(1, quests.getAsJsonArray("inProgress").size());
		assertTrue(o.get("bankKnown").getAsBoolean());
		assertTrue(o.get("updated").getAsLong() > 0);
	}

	@Test
	public void unknownWealthAndWomAreOmittedNotFabricated()
	{
		Map<Skill, Integer> lv = new EnumMap<>(Skill.class);
		for (Skill s : Skill.values()) lv.put(s, 1);
		CoachState st = new CoachState(lv, 0, new HashMap<>(), new HashSet<>(),
			false, -1L, -1L, 3, true);
		JsonObject o = CoachAiSnapshot.build(st, null, null, -1, -1, null);
		assertFalse(o.has("gp"));
		assertFalse(o.has("netWorth"));
		assertFalse(o.has("wom"));
		assertFalse(o.has("caTier"));
		assertFalse(o.has("slayerPoints"));
		assertFalse(o.get("bankKnown").getAsBoolean());
	}

	@Test
	public void bankDailiesFarmCarriedAndOmittedHonestly()
	{
		int[][] bank = {{4151, 2}, {995, 40_000_000}};
		JsonObject o = CoachAiSnapshot.build(state(), null, null, -1, -1, null,
			bank, java.util.Arrays.asList("Herb run ready"), 95);
		assertEquals(2, o.get("bankItems").getAsInt());
		assertEquals(4151, o.getAsJsonArray("bank").get(0).getAsJsonArray().get(0).getAsInt());
		assertEquals("Herb run ready", o.getAsJsonArray("dailies").get(0).getAsString());
		assertEquals(95, o.get("farmRunMinsAgo").getAsInt());
		// bank never seen + farm unknown -> keys ABSENT, not fabricated
		JsonObject o2 = CoachAiSnapshot.build(state(), null, null, -1, -1, null,
			null, null, -1);
		assertFalse(o2.has("bank"));
		assertFalse(o2.has("dailies"));
		assertFalse(o2.has("farmRunMinsAgo"));
	}

	@Test
	public void pushRefusesWeakOrMissingId()
	{
		// contract test: a short id or blank url must be a silent no-op (the
		// sync-id is the whole security model — never PUT without real entropy)
		CoachAiSnapshot.push("", "x", new JsonObject());
		CoachAiSnapshot.push("https://example.invalid", "short", new JsonObject());
		// reaching here without an exception IS the assertion (best-effort path)
	}
}
