package com.geflip.coach;

import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;

/**
 * An immutable snapshot of the account, taken on the client thread and handed to the (thread-safe)
 * goal engine + panel. Everything the coach reasons about lives here — levels, quest points, the
 * states of the quests our goals care about, key items owned, coins, and combat level. Skills,
 * QP, quests and diaries are readable any time you're logged in; item ownership is best-effort
 * (a banked item is invisible until you open the bank once — same limitation the flipper has).
 */
final class CoachState
{
	final Map<Skill, Integer> levels;      // real (un-boosted) level per skill
	final int qp;                          // quest points
	final Map<Quest, QuestState> quests;   // only the quests our goals reference
	final Set<Integer> ownedItemIds;       // key items seen in equipment + inventory + bank
	final boolean bankKnown;               // has the bank been opened this session? (else item gaps are soft)
	final long coins;                      // gp in inventory + bank (−1 = unknown)
	final int combatLevel;
	final boolean loggedIn;

	CoachState(Map<Skill, Integer> levels, int qp, Map<Quest, QuestState> quests,
		Set<Integer> ownedItemIds, boolean bankKnown, long coins, int combatLevel, boolean loggedIn)
	{
		this.levels = levels; this.qp = qp; this.quests = quests; this.ownedItemIds = ownedItemIds;
		this.bankKnown = bankKnown; this.coins = coins; this.combatLevel = combatLevel; this.loggedIn = loggedIn;
	}

	int level(Skill s) { Integer v = levels.get(s); return v != null ? v : 1; }
	boolean finished(Quest q) { return quests.get(q) == QuestState.FINISHED; }
	boolean started(Quest q) { QuestState st = quests.get(q); return st == QuestState.IN_PROGRESS || st == QuestState.FINISHED; }
	boolean owns(int id) { return ownedItemIds.contains(id); }

	/** Standard OSRS combat level from the snapshot's real levels. */
	static int combat(int att, int str, int def, int hp, int ranged, int prayer, int magic)
	{
		double base = 0.25 * (def + hp + Math.floor(prayer / 2.0));
		double melee = 0.325 * (att + str);
		double range = 0.325 * Math.floor(ranged * 1.5);
		double mage = 0.325 * Math.floor(magic * 1.5);
		return (int) Math.floor(base + Math.max(melee, Math.max(range, mage)));
	}
}
