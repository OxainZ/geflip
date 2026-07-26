package com.geflip.coach;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs the coach engine against "Foo fighta"'s real hiscores + known state (blowpipe owned, RFD not
 * started, 225 QP) and locks the advice it should produce — proof the goal graph behaves.
 */
public class CoachEngineTest
{
	private static CoachState fooFighta()
	{
		Map<Skill, Integer> lv = new EnumMap<>(Skill.class);
		lv.put(Skill.ATTACK, 75); lv.put(Skill.STRENGTH, 71); lv.put(Skill.DEFENCE, 70); lv.put(Skill.HITPOINTS, 78);
		lv.put(Skill.RANGED, 84); lv.put(Skill.PRAYER, 63); lv.put(Skill.MAGIC, 69); lv.put(Skill.SLAYER, 48);
		lv.put(Skill.HERBLORE, 53); lv.put(Skill.AGILITY, 53); lv.put(Skill.FARMING, 37); lv.put(Skill.CONSTRUCTION, 40);
		lv.put(Skill.HUNTER, 33); lv.put(Skill.CRAFTING, 51); lv.put(Skill.MINING, 52); lv.put(Skill.SMITHING, 50);
		lv.put(Skill.THIEVING, 45); lv.put(Skill.WOODCUTTING, 48); lv.put(Skill.FIREMAKING, 52); lv.put(Skill.FLETCHING, 33);
		lv.put(Skill.FISHING, 34); lv.put(Skill.RUNECRAFT, 38); lv.put(Skill.COOKING, 70);

		Map<Quest, QuestState> q = new EnumMap<>(Quest.class);
		for (Quest qq : CoachGoals.KEY_QUESTS) q.put(qq, QuestState.NOT_STARTED);
		q.put(Quest.REGICIDE, QuestState.FINISHED);           // assume access to Zul-Andra
		q.put(Quest.DRAGON_SLAYER_I, QuestState.FINISHED);

		Set<Integer> owned = new HashSet<>();
		owned.add(CoachGoals.FIRE_CAPE);
		owned.add(CoachGoals.BLOWPIPE_CHARGED);               // "i have toxic blow pipe"

		int cb = CoachState.combat(75, 71, 70, 78, 84, 63, 69);
		return new CoachState(lv, 225, q, owned, true, 40_000_000L, cb, true);
	}

	@Test
	public void combatLevelMatchesHiscores()
	{
		assertEquals(92, CoachState.combat(75, 71, 70, 78, 84, 63, 69));
	}

	@Test
	public void producesSaneAdvice()
	{
		CoachState s = fooFighta();
		List<CoachEngine.Scored> all = CoachEngine.evaluate(s);
		Map<String, CoachEngine.Scored> by = new java.util.HashMap<>();
		for (CoachEngine.Scored sc : all) by.put(sc.goal.name, sc);

		// Void: has every stat → READY now.
		assertEquals(CoachEngine.Status.READY, by.get("Void ranged set").status);
		// Zulrah: blowpipe + Regicide + 84 ranged → READY now.
		assertEquals(CoachEngine.Status.READY, by.get("Zulrah (money boss)").status);
		// Dizana's: gated like a hard PvM challenge (Ranged 90/Def 80/Prayer 74 + quest) → BLOCKED
		// for a mid-game account, NOT "almost" (the audit's key correction).
		assertEquals(CoachEngine.Status.BLOCKED, by.get("Dizana's quiver (BiS ranged cape)").status);
		// Barrows gloves: real Agility req is 48 (account has 53) → phantom Agility gap GONE; only the
		// RFD + Monkey Madness I quests remain → ALMOST, no Agility gap.
		assertEquals(CoachEngine.Status.ALMOST, by.get("Barrows gloves").status);
		assertTrue(by.get("Barrows gloves").gaps.stream().noneMatch(g -> g.contains("Agility")));
		// Piety: prayer 63→70 is the only gap → ALMOST.
		assertEquals(CoachEngine.Status.ALMOST, by.get("Piety (melee prayer)").status);
		// DS2: many skill gaps → BLOCKED, and QP is NOT among them (225 >= 200).
		CoachEngine.Scored ds2 = by.get("Dragon Slayer 2 (-> Vorkath)");
		assertEquals(CoachEngine.Status.BLOCKED, ds2.status);
		assertTrue(ds2.gaps.stream().noneMatch(g -> g.startsWith("QP")));
		assertTrue(ds2.gaps.stream().anyMatch(g -> g.contains("Construction")));

		// THE FIX: a single huge gap is NOT "almost". Infernal (endgame) and +39-slayer Kraken
		// must be BLOCKED, never ALMOST.
		assertEquals(CoachEngine.Status.BLOCKED, by.get("Infernal cape").status);
		assertEquals(CoachEngine.Status.BLOCKED, by.get("Kraken (slayer boss)").status);
		// The headline list must lead with something actionable, and never surface Infernal near the top.
		List<CoachEngine.Scored> next = CoachEngine.doNext(all);
		assertTrue(next.get(0).status == CoachEngine.Status.READY);
		assertTrue(next.stream().noneMatch(sc -> sc.goal.name.equals("Infernal cape")));

		// Quest recommender knows what's done: RFD is NOT finished here → it must appear.
		assertTrue(CoachEngine.quests(s).stream().anyMatch(qs -> qs.rec.q == net.runelite.api.Quest.RECIPE_FOR_DISASTER));
	}
}
