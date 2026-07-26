package com.geflip.coach;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * The curated knowledge base: OSRS progression goals with their EXACT requirements, tuned toward a
 * combat/PvM progression path. Each goal knows how to report its own gaps against a live
 * {@link CoachState}. This is the honest core — it's a heuristic recommender over a hand-checked
 * requirement graph, not an omniscient optimiser: it nails the high-value unlocks and tells you
 * precisely what's gating each one, which is what "what do I do next" actually needs.
 *
 * Requirements are functional: a Req returns null when satisfied, else a short gap string
 * ("Magic +6", "quest: Regicide", "QP +40"). Add goals by appending to GOALS.
 */
final class CoachGoals
{
	private CoachGoals() {}

	/** An unmet requirement: a short human label + a WEIGHT (roughly "how far", so the engine can
	 *  tell a +1 gap from a +47 gap — a single huge gap is NOT "almost done"). */
	static final class Gap
	{
		final String text; final int weight;
		Gap(String text, int weight) { this.text = text; this.weight = weight; }
	}

	/** A single requirement. Returns null if met, else a Gap. */
	interface Req { Gap gap(CoachState s); }

	static final class Goal
	{
		final String name, note; final int impact; final String effort; final List<Req> reqs;
		Goal(String name, int impact, String effort, String note, Req... reqs)
		{ this.name = name; this.impact = impact; this.effort = effort; this.note = note; this.reqs = Arrays.asList(reqs); }
	}

	// --- requirement factories (weight ≈ levels/effort remaining) --------------
	static Req skill(Skill sk, int lvl) { return s -> s.level(sk) >= lvl ? null : new Gap(cap(sk.name()) + " +" + (lvl - s.level(sk)), lvl - s.level(sk)); }
	static Req quest(Quest q)           { return s -> s.finished(q) ? null : new Gap("quest: " + pretty(q), 6); }
	static Req questStarted(Quest q)    { return s -> s.started(q) ? null : new Gap("start: " + pretty(q), 4); }
	static Req qp(int n)                { return s -> s.qp >= n ? null : new Gap("QP +" + (n - s.qp), Math.min(20, n - s.qp)); }
	static Req coins(long n)            { return s -> (s.coins < 0 || s.coins >= n) ? null : new Gap("need " + gp(n) + " gp", 5); }
	/** Item you should own; while the bank is unread we can't see banked items, so it stays a gap
	 *  until you open your bank once (honest — better than a false "ready"). */
	static Req item(String label, int... ids)
	{
		return s -> { for (int id : ids) if (s.owns(id)) return null;
			return new Gap(s.bankKnown ? "need " + label : "need " + label + " (open bank to confirm)", 6); };
	}

	// --- key items the plugin scans equipment/inventory/bank for --------------
	static final int FIRE_CAPE = 6570, INFERNAL_CAPE = 21295;
	static final int BLOWPIPE_CHARGED = 12926, BLOWPIPE_EMPTY = 12924;
	static final int BARROWS_GLOVES = 7462;
	static final int FURY = 6585, ANGUISH = 19547, OCCULT = 12002, ASSEMBLER = 22109;
	static final int[] KEY_ITEMS = { FIRE_CAPE, INFERNAL_CAPE, BLOWPIPE_CHARGED, BLOWPIPE_EMPTY,
		BARROWS_GLOVES, FURY, ANGUISH, OCCULT, ASSEMBLER };

	// --- quests whose state the plugin reads (goals reference these) ----------
	static final Quest[] KEY_QUESTS = {
		Quest.RECIPE_FOR_DISASTER, Quest.KINGS_RANSOM, Quest.DESERT_TREASURE_I, Quest.REGICIDE,
		Quest.DRAGON_SLAYER_I, Quest.DRAGON_SLAYER_II, Quest.MONKEY_MADNESS_I, Quest.MONKEY_MADNESS_II,
		Quest.SONG_OF_THE_ELVES, Quest.CHILDREN_OF_THE_SUN, Quest.BONE_VOYAGE, Quest.CLIENT_OF_KOUREND,
		Quest.LUNAR_DIPLOMACY,
	};

	// --- the goal graph -------------------------------------------------------
	static final List<Goal> GOALS = new ArrayList<>();
	static
	{
		// --- cheap, huge-value unlocks -----------------------------------------
		GOALS.add(new Goal("Barrows gloves", 5, "medium", "Recipe for Disaster — near-BiS gloves, permanent, cheap. The last subquest (King Awowogei) needs ~65 Agility (boostable to 70 with a Summer pie).",
			quest(Quest.RECIPE_FOR_DISASTER), skill(Skill.COOKING, 70), skill(Skill.AGILITY, 65)));
		GOALS.add(new Goal("Void ranged set", 3, "quick", "Pest Control — cheap strong ranged armour; great for Zulrah/slayer while you save for better.",
			skill(Skill.RANGED, 42), skill(Skill.DEFENCE, 42), skill(Skill.HITPOINTS, 42), skill(Skill.PRAYER, 22)));
		GOALS.add(new Goal("Ancient Magicks (Barrage)", 4, "medium", "Desert Treasure I — unlocks Ice Barrage/Burst for AoE slayer (huge XP + money at nechs/dust devils).",
			quest(Quest.DESERT_TREASURE_I), skill(Skill.MAGIC, 50)));

		// --- prayers (DPS multipliers) -----------------------------------------
		GOALS.add(new Goal("Piety (melee prayer)", 4, "medium", "70 Prayer + 70 Defence + King's Ransom. Big melee boost for fang/slayer tasks.",
			skill(Skill.PRAYER, 70), skill(Skill.DEFENCE, 70), quest(Quest.KINGS_RANSOM)));
		GOALS.add(new Goal("Rigour (ranged prayer)", 5, "long", "74 Prayer + a Dexterous prayer scroll (CoX drop or buy). THE ranged DPS prayer — your top upgrade as a ranger.",
			skill(Skill.PRAYER, 74)));
		GOALS.add(new Goal("Augury (magic prayer)", 3, "long", "77 Prayer + an Arcane prayer scroll. Magic version of Rigour.",
			skill(Skill.PRAYER, 77)));

		// --- money bosses ------------------------------------------------------
		GOALS.add(new Goal("Zulrah (money boss)", 5, "medium", "~2-3.5M/hr. Needs a blowpipe + partial Regicide to reach Zul-Andra. Drops serp helm, blowpipe parts, onyx.",
			item("Toxic blowpipe", BLOWPIPE_CHARGED, BLOWPIPE_EMPTY), questStarted(Quest.REGICIDE), skill(Skill.RANGED, 75)));
		GOALS.add(new Goal("Trident of the swamp", 3, "quick", "75 Magic to wield — your Zulrah mage weapon AND it clears Dragon Slayer 2's magic gate. Two birds.",
			skill(Skill.MAGIC, 75)));
		GOALS.add(new Goal("Dragon Slayer 2 -> Vorkath", 5, "long", "Vorkath is ~3M/hr. Your 225 QP already clears the 200 QP gate — only skills remain.",
			qp(200), skill(Skill.MAGIC, 75), skill(Skill.SMITHING, 70), skill(Skill.MINING, 68),
			skill(Skill.AGILITY, 62), skill(Skill.THIEVING, 60), skill(Skill.CONSTRUCTION, 60),
			skill(Skill.HUNTER, 50), skill(Skill.HERBLORE, 50),
			quest(Quest.DRAGON_SLAYER_I), quest(Quest.BONE_VOYAGE), quest(Quest.CLIENT_OF_KOUREND)));

		// --- slayer engine -----------------------------------------------------
		GOALS.add(new Goal("Slayer helmet (imbued)", 5, "medium", "55 Slayer + unlock via Slayer points. Works for ranged too — with your blowpipe it shreds tasks.",
			skill(Skill.SLAYER, 55)));
		GOALS.add(new Goal("Kraken (slayer boss)", 3, "medium", "87 Slayer — easy AFK-ish money + trident/tentacle.", skill(Skill.SLAYER, 87)));
		GOALS.add(new Goal("Cerberus (primordial etc.)", 4, "long", "91 Slayer — drops the crystals for BiS boots.", skill(Skill.SLAYER, 91)));
		GOALS.add(new Goal("Alchemical Hydra", 4, "long", "95 Slayer — great money + hydra leather (ferocious gloves).", skill(Skill.SLAYER, 95)));

		// --- ranged endgame cape + armour --------------------------------------
		GOALS.add(new Goal("Dizana's quiver (BiS ranged cape)", 5, "long", "Fortis Colosseum. Only needs 75 Ranged (you have 84) + Children of the Sun. 10% extra-arrow = ~10% free DPS. Your real fire-cape successor.",
			skill(Skill.RANGED, 75), quest(Quest.CHILDREN_OF_THE_SUN)));
		GOALS.add(new Goal("Amulet of anguish", 3, "medium", "Ranged BiS neck — big step up from fury for a ranger.",
			item("Amulet of anguish", ANGUISH)));
		GOALS.add(new Goal("Infernal cape", 4, "grind", "The Inferno — ENDGAME. One of the hardest solo challenges in the game; expect near-max stats, BiS gear, Rigour, and many attempts. Not a near-term goal.",
			skill(Skill.RANGED, 92), skill(Skill.MAGIC, 90), skill(Skill.DEFENCE, 82), skill(Skill.HITPOINTS, 90),
			skill(Skill.PRAYER, 77)));

		// --- long-term gateways -------------------------------------------------
		GOALS.add(new Goal("Monkey Madness 2", 3, "medium", "Unlocks demonic gorillas (zenyte drops) + is a prereq for later content.",
			quest(Quest.MONKEY_MADNESS_I), skill(Skill.SLAYER, 69), skill(Skill.CRAFTING, 70),
			skill(Skill.HUNTER, 60), skill(Skill.AGILITY, 55), skill(Skill.THIEVING, 55), skill(Skill.FIREMAKING, 60)));
		GOALS.add(new Goal("Song of the Elves -> Gauntlet", 5, "grind", "Unlocks Prifddinas + The Gauntlet (crystal armour/bow — excellent for a ranger) + Zalcano. Needs 70 in ~10 skills.",
			skill(Skill.AGILITY, 70), skill(Skill.CONSTRUCTION, 70), skill(Skill.FARMING, 70), skill(Skill.HERBLORE, 70),
			skill(Skill.HUNTER, 70), skill(Skill.MINING, 70), skill(Skill.SMITHING, 70), skill(Skill.THIEVING, 70),
			skill(Skill.WOODCUTTING, 70), skill(Skill.CRAFTING, 70), skill(Skill.MAGIC, 70)));
	}

	// --- curated high-value quests (with their BINDING requirements) ----------
	static final class QuestRec
	{
		final Quest q; final String note; final List<Req> reqs;
		QuestRec(Quest q, String note, Req... reqs) { this.q = q; this.note = note; this.reqs = Arrays.asList(reqs); }
	}
	static final List<QuestRec> QUESTS = new ArrayList<>();
	static
	{
		QUESTS.add(new QuestRec(Quest.DESERT_TREASURE_I, "Ancient Magicks (Ice Barrage/Burst) — turbo-charges Slayer.",
			skill(Skill.FIREMAKING, 50), skill(Skill.MAGIC, 50), skill(Skill.SLAYER, 10)));
		QUESTS.add(new QuestRec(Quest.MONKEY_MADNESS_I, "Dragon scimitar + opens MM2 and the RFD monkey subquest."));
		QUESTS.add(new QuestRec(Quest.REGICIDE, "Access to Zul-Andra (Zulrah) + elf lands."));
		QUESTS.add(new QuestRec(Quest.RECIPE_FOR_DISASTER, "Barrows gloves. NOTE: the King Awowogei subquest needs ~70 Agility (boostable).",
			skill(Skill.COOKING, 70), skill(Skill.AGILITY, 65)));
		QUESTS.add(new QuestRec(Quest.CHILDREN_OF_THE_SUN, "Short — unlocks Fortis Colosseum → Dizana's quiver."));
		QUESTS.add(new QuestRec(Quest.LUNAR_DIPLOMACY, "Lunar spellbook (NPC Contact, Humidify, Vengeance later).",
			skill(Skill.MAGIC, 65), skill(Skill.HERBLORE, 60), skill(Skill.CRAFTING, 55)));
		QUESTS.add(new QuestRec(Quest.MONKEY_MADNESS_II, "Demonic gorillas (zenyte drops).",
			quest(Quest.MONKEY_MADNESS_I), skill(Skill.SLAYER, 69), skill(Skill.CRAFTING, 70),
			skill(Skill.HUNTER, 60), skill(Skill.AGILITY, 55), skill(Skill.THIEVING, 55), skill(Skill.FIREMAKING, 60)));
		QUESTS.add(new QuestRec(Quest.DRAGON_SLAYER_II, "Vorkath (~3M/hr).",
			qp(200), skill(Skill.MAGIC, 75), skill(Skill.SMITHING, 70), skill(Skill.MINING, 68),
			skill(Skill.AGILITY, 62), skill(Skill.THIEVING, 60), skill(Skill.CONSTRUCTION, 60),
			skill(Skill.HUNTER, 50), skill(Skill.HERBLORE, 50)));
		QUESTS.add(new QuestRec(Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE, "Ancient rings + BiS unlocks.",
			skill(Skill.MAGIC, 75), skill(Skill.FIREMAKING, 90), skill(Skill.MINING, 62), skill(Skill.HERBLORE, 60),
			skill(Skill.RUNECRAFT, 60), skill(Skill.CONSTRUCTION, 55), skill(Skill.AGILITY, 50), skill(Skill.THIEVING, 50)));
		QUESTS.add(new QuestRec(Quest.SONG_OF_THE_ELVES, "Prifddinas + The Gauntlet (crystal gear) + Zalcano.",
			skill(Skill.AGILITY, 70), skill(Skill.CONSTRUCTION, 70), skill(Skill.FARMING, 70), skill(Skill.HERBLORE, 70),
			skill(Skill.HUNTER, 70), skill(Skill.MINING, 70), skill(Skill.SMITHING, 70), skill(Skill.THIEVING, 70),
			skill(Skill.WOODCUTTING, 70), skill(Skill.CRAFTING, 70)));
	}

	// --- helpers --------------------------------------------------------------
	static String pretty(Quest q)
	{
		String[] w = q.name().replace("__", ": ").replace('_', ' ').toLowerCase().split(" ");
		StringBuilder b = new StringBuilder();
		for (String x : w) { if (x.isEmpty()) continue; b.append(Character.toUpperCase(x.charAt(0))).append(x.substring(1)).append(' '); }
		return b.toString().trim();
	}
	static String cap(String s)
	{
		s = s.toLowerCase();
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
	static String gp(long v)
	{
		if (v >= 1_000_000) return (v % 1_000_000 == 0 ? v / 1_000_000 : Math.round(v / 100_000.0) / 10.0) + "m";
		if (v >= 1000) return (v % 1000 == 0 ? v / 1000 : Math.round(v / 100.0) / 10.0) + "k";
		return String.valueOf(v);
	}
}
