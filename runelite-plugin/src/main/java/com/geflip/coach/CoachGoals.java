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
	// completion items (all verified vs ItemID 1.12.33) — owning one means the goal is DONE
	static final int AVAS_ACC = 10499, VOID_HELM = 11664, VOID_TOP = 8839, VOID_ETOP = 13072;
	static final int SLAYER_HELM = 11864, SLAYER_HELM_I = 11865, TRIDENT_SWAMP = 12899, TRIDENT_SWAMP_E = 22292;
	static final int RUNE_POUCH = 12791, RUNE_POUCH2 = 23650, BOOK_OF_DEAD = 25818;
	// only the IMBUED helm (i) + its recolours count — the plain 11864 is NOT imbued, so it must not
	// mark the "imbued" goal done.
	private static final int[] SLAYER_HELMS = { SLAYER_HELM_I, 19641, 19645, 19649, 21266, 21890, 23075, 24444, 25900, 25906, 25912 };

	/** goal name -> item ids that mean "you already have this / it's DONE". */
	static final java.util.Map<String, int[]> DONE_IF_OWN = new java.util.HashMap<>();
	static
	{
		DONE_IF_OWN.put("Ava's accumulator (ranged QoL)", new int[]{ AVAS_ACC, 23609, ASSEMBLER });
		DONE_IF_OWN.put("Void ranged set", new int[]{ VOID_HELM });   // ranger helm is the ranged-defining piece (top 8839 is shared with melee/mage)
		DONE_IF_OWN.put("Barrows gloves", new int[]{ BARROWS_GLOVES, 23593 });
		DONE_IF_OWN.put("Occult necklace", new int[]{ OCCULT });
		DONE_IF_OWN.put("Amulet of anguish", new int[]{ ANGUISH });
		DONE_IF_OWN.put("Trident of the swamp", new int[]{ TRIDENT_SWAMP, TRIDENT_SWAMP_E });
		DONE_IF_OWN.put("Rune pouch", new int[]{ RUNE_POUCH, RUNE_POUCH2 });
		DONE_IF_OWN.put("Book of the Dead (thralls)", new int[]{ BOOK_OF_DEAD });
		DONE_IF_OWN.put("Slayer helmet (imbued)", SLAYER_HELMS);
		DONE_IF_OWN.put("Infernal cape", new int[]{ INFERNAL_CAPE });
	}

	// every id the plugin scans equipment/inventory/bank for = key items + all completion items
	static final int[] KEY_ITEMS;
	static
	{
		java.util.Set<Integer> s = new java.util.LinkedHashSet<>();
		for (int id : new int[]{ FIRE_CAPE, INFERNAL_CAPE, BLOWPIPE_CHARGED, BLOWPIPE_EMPTY, BARROWS_GLOVES, FURY, ANGUISH, OCCULT, ASSEMBLER }) s.add(id);
		for (int[] ids : DONE_IF_OWN.values()) for (int id : ids) s.add(id);
		KEY_ITEMS = new int[s.size()];
		int i = 0; for (int id : s) KEY_ITEMS[i++] = id;
	}

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
		GOALS.add(new Goal("Barrows gloves", 5, "medium", "Recipe for Disaster — near-BiS gloves, permanent, cheap. Last subquest (Freeing King Awowogei) needs 48 Agility + Monkey Madness I; RFD also wants Cooking 70.",
			quest(Quest.RECIPE_FOR_DISASTER), quest(Quest.MONKEY_MADNESS_I), skill(Skill.COOKING, 70), skill(Skill.AGILITY, 48)));
		GOALS.add(new Goal("Void ranged set", 3, "quick", "Pest Control — cheap strong ranged armour; great for Zulrah/slayer while you save for better.",
			skill(Skill.RANGED, 42), skill(Skill.ATTACK, 42), skill(Skill.STRENGTH, 42), skill(Skill.DEFENCE, 42),
			skill(Skill.HITPOINTS, 42), skill(Skill.MAGIC, 42), skill(Skill.PRAYER, 22)));
		GOALS.add(new Goal("Ancient Magicks (Barrage)", 4, "medium", "Desert Treasure I — unlocks Ice Barrage/Burst for AoE slayer (huge XP + money at nechs/dust devils).",
			quest(Quest.DESERT_TREASURE_I), skill(Skill.MAGIC, 50)));

		// --- prayers (DPS multipliers) -----------------------------------------
		GOALS.add(new Goal("Piety (melee prayer)", 4, "medium", "70 Prayer + 70 Defence + King's Ransom. Big melee boost for fang/slayer tasks.",
			skill(Skill.PRAYER, 70), skill(Skill.DEFENCE, 70), quest(Quest.KINGS_RANSOM)));
		GOALS.add(new Goal("Rigour (ranged prayer)", 5, "long", "74 Prayer + 70 Defence + a Dexterous prayer scroll (CoX drop, or buy for ~24M). THE ranged DPS prayer — your top upgrade as a ranger.",
			skill(Skill.PRAYER, 74), skill(Skill.DEFENCE, 70), coins(24_000_000)));
		GOALS.add(new Goal("Augury (magic prayer)", 3, "long", "77 Prayer + 70 Defence + an Arcane prayer scroll (~15M). Magic version of Rigour.",
			skill(Skill.PRAYER, 77), skill(Skill.DEFENCE, 70), coins(15_000_000)));

		// --- money bosses ------------------------------------------------------
		GOALS.add(new Goal("Zulrah (money boss)", 5, "medium", "~2-3.5M/hr. Needs a blowpipe + Regicide done (reaches Zul-Andra via Port Tyras). Drops serp helm, blowpipe parts, onyx.",
			item("Toxic blowpipe", BLOWPIPE_CHARGED, BLOWPIPE_EMPTY), quest(Quest.REGICIDE), skill(Skill.RANGED, 75)));
		GOALS.add(new Goal("Trident of the swamp", 3, "quick", "78 Magic to wield (seas trident is 75) — your Zulrah mage weapon; also clears DS2's 75-Magic gate. Two birds.",
			skill(Skill.MAGIC, 78)));
		GOALS.add(new Goal("Occult necklace", 3, "medium", "Best magic-damage neck — your Zulrah mage switch. From Smoke devils or buy.",
			item("Occult necklace", OCCULT)));
		GOALS.add(new Goal("Ava's accumulator (ranged QoL)", 4, "quick", "Animal Magnetism — returns your ammo + range bonus. Upgrade to the Assembler after DS2.",
			quest(Quest.ANIMAL_MAGNETISM), skill(Skill.RANGED, 50)));
		GOALS.add(new Goal("Dragon Slayer 2 (-> Vorkath)", 5, "long", "Completing DS2 unlocks Vorkath (~2.5-3.5M/hr). Needs 200 QP + a long quest chain and these skills.",
			qp(200), skill(Skill.MAGIC, 75), skill(Skill.SMITHING, 70), skill(Skill.MINING, 68), skill(Skill.CRAFTING, 62),
			skill(Skill.AGILITY, 60), skill(Skill.THIEVING, 60), skill(Skill.CONSTRUCTION, 50), skill(Skill.HITPOINTS, 50),
			quest(Quest.DRAGON_SLAYER_I), quest(Quest.LEGENDS_QUEST), quest(Quest.DREAM_MENTOR),
			quest(Quest.A_TAIL_OF_TWO_CATS), quest(Quest.ANIMAL_MAGNETISM), quest(Quest.GHOSTS_AHOY),
			quest(Quest.BONE_VOYAGE), quest(Quest.CLIENT_OF_KOUREND)));
		GOALS.add(new Goal("Salve amulet (ei) — Vorkath BiS", 3, "medium", "Haunted Mine + Lair of Tarn Razorlor make salve (e); then IMBUE it (800k NMZ pts / 320 Soul Wars zeal / Scroll of Imbuing) for the (ei) ranged+magic version. +20% dmg/acc vs undead.",
			quest(Quest.HAUNTED_MINE), quest(Quest.LAIR_OF_TARN_RAZORLOR)));

		// --- slayer engine -----------------------------------------------------
		GOALS.add(new Goal("Slayer helmet (imbued)", 5, "medium", "55 Crafting + the 'Malevolent masquerade' unlock (400 Slayer pts) to assemble; no Slayer LEVEL needed. Works for ranged too — with your blowpipe it shreds tasks.",
			skill(Skill.CRAFTING, 55)));
		GOALS.add(new Goal("Kraken (slayer boss)", 3, "medium", "87 Slayer — easy AFK-ish money + trident/tentacle.", skill(Skill.SLAYER, 87)));
		GOALS.add(new Goal("Cerberus (primordial etc.)", 4, "long", "91 Slayer — drops the crystals for BiS boots.", skill(Skill.SLAYER, 91)));
		GOALS.add(new Goal("Alchemical Hydra", 4, "long", "95 Slayer — great money + hydra leather (ferocious gloves).", skill(Skill.SLAYER, 95)));

		// --- ranged endgame cape + armour --------------------------------------
		GOALS.add(new Goal("Dizana's quiver (BiS ranged cape)", 5, "grind", "Fortis Colosseum wave 12 (Sol Heredit) — a HARD solo PvM fight on par with the Inferno, NOT a stat unlock. Needs strong gear, Rigour and practice + Children of the Sun.",
			quest(Quest.CHILDREN_OF_THE_SUN), skill(Skill.RANGED, 90), skill(Skill.DEFENCE, 80), skill(Skill.PRAYER, 74)));
		GOALS.add(new Goal("Amulet of anguish", 3, "medium", "Ranged BiS neck — big step up from fury for a ranger.",
			item("Amulet of anguish", ANGUISH)));
		GOALS.add(new Goal("Infernal cape", 4, "grind", "The Inferno — ENDGAME. One of the hardest solo challenges in the game; expect near-max stats, BiS gear, Rigour, and many attempts. Not a near-term goal.",
			skill(Skill.RANGED, 92), skill(Skill.MAGIC, 90), skill(Skill.DEFENCE, 82), skill(Skill.HITPOINTS, 90),
			skill(Skill.PRAYER, 77)));

		// --- QoL unlocks + raids -----------------------------------------------
		GOALS.add(new Goal("Fairy rings (travel QoL)", 4, "medium", "Fairytale II (partial) — the game's best teleport network. Huge time-saver for slayer/farming/bossing.",
			quest(Quest.FAIRYTALE_II__CURE_A_QUEEN)));
		GOALS.add(new Goal("Rune pouch", 4, "medium", "Buy for 750 Slayer reward points (or Mage Training Arena points / LMS). NOT from Enter the Abyss. Carry 3-4 rune types — needed to barrage/alch on the go."));
		GOALS.add(new Goal("Book of the Dead (thralls)", 4, "long", "A Kingdom Divided — undead thralls are a big FREE DPS boost across almost all PvM.",
			quest(Quest.A_KINGDOM_DIVIDED)));
		GOALS.add(new Goal("Barrows (ranged gear + money)", 3, "quick", "Priest in Peril for Morytania access — Karil's ranged gear + steady GP, no combat gate.",
			quest(Quest.PRIEST_IN_PERIL)));
		GOALS.add(new Goal("Tombs of Amascut (ToA)", 4, "grind", "Beneath Cursed Sands — a SCALABLE raid, ranged-friendly; you pick the difficulty. Great loot.",
			quest(Quest.BENEATH_CURSED_SANDS), skill(Skill.RANGED, 80), skill(Skill.PRAYER, 74)));
		GOALS.add(new Goal("Armadyl (Kree'arra, GWD)", 3, "long", "70 Ranged (+ crossbow & mithril grapple for the shortcut) — armadyl armour + crossbow for a ranger. Team, or solo with good gear.",
			skill(Skill.RANGED, 70)));

		// --- long-term gateways -------------------------------------------------
		GOALS.add(new Goal("Monkey Madness 2", 3, "medium", "Unlocks demonic gorillas (zenyte drops) + is a prereq for later content.",
			quest(Quest.MONKEY_MADNESS_I), quest(Quest.ENLIGHTENED_JOURNEY), quest(Quest.THE_EYES_OF_GLOUPHRIE),
			quest(Quest.WATCHTOWER), quest(Quest.TROLL_STRONGHOLD), skill(Skill.SLAYER, 69), skill(Skill.CRAFTING, 70),
			skill(Skill.HUNTER, 60), skill(Skill.AGILITY, 55), skill(Skill.THIEVING, 55), skill(Skill.FIREMAKING, 60)));
		GOALS.add(new Goal("Song of the Elves -> Gauntlet", 5, "grind", "Unlocks Prifddinas + The Gauntlet (crystal armour/bow — excellent for a ranger) + Zalcano. Needs 70 in 8 skills + Mourning's End Part II chain.",
			skill(Skill.AGILITY, 70), skill(Skill.CONSTRUCTION, 70), skill(Skill.FARMING, 70), skill(Skill.HERBLORE, 70),
			skill(Skill.HUNTER, 70), skill(Skill.MINING, 70), skill(Skill.SMITHING, 70), skill(Skill.WOODCUTTING, 70),
			quest(Quest.MOURNINGS_END_PART_II), quest(Quest.MAKING_HISTORY), quest(Quest.DRUIDIC_RITUAL)));
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
			skill(Skill.THIEVING, 53), skill(Skill.FIREMAKING, 50), skill(Skill.MAGIC, 50), skill(Skill.SLAYER, 10),
			quest(Quest.THE_DIG_SITE), quest(Quest.TEMPLE_OF_IKOV), quest(Quest.THE_TOURIST_TRAP),
			quest(Quest.TROLL_STRONGHOLD), quest(Quest.PRIEST_IN_PERIL), quest(Quest.WATERFALL_QUEST)));
		QUESTS.add(new QuestRec(Quest.MONKEY_MADNESS_I, "Dragon scimitar + opens MM2 and the RFD monkey subquest.",
			quest(Quest.THE_GRAND_TREE), quest(Quest.TREE_GNOME_VILLAGE)));
		QUESTS.add(new QuestRec(Quest.REGICIDE, "Access to Zul-Andra (Zulrah) + elf lands.",
			skill(Skill.CRAFTING, 10), skill(Skill.AGILITY, 56), quest(Quest.UNDERGROUND_PASS)));
		QUESTS.add(new QuestRec(Quest.RECIPE_FOR_DISASTER, "Barrows gloves (best cheap gloves). Long chain: broad skills + 175 QP.",
			qp(175), skill(Skill.COOKING, 70), skill(Skill.MAGIC, 59), skill(Skill.FISHING, 53), skill(Skill.THIEVING, 53),
			skill(Skill.MINING, 50), skill(Skill.FIREMAKING, 50), skill(Skill.AGILITY, 48), skill(Skill.RANGED, 40),
			skill(Skill.SMITHING, 40), skill(Skill.CRAFTING, 40), skill(Skill.WOODCUTTING, 36), skill(Skill.HERBLORE, 25), skill(Skill.FLETCHING, 10),
			quest(Quest.COOKS_ASSISTANT), quest(Quest.FISHING_CONTEST), quest(Quest.GOBLIN_DIPLOMACY), quest(Quest.BIG_CHOMPY_BIRD_HUNTING),
			quest(Quest.MURDER_MYSTERY), quest(Quest.NATURE_SPIRIT), quest(Quest.WITCHS_HOUSE), quest(Quest.GERTRUDES_CAT),
			quest(Quest.SHADOW_OF_THE_STORM), quest(Quest.LEGENDS_QUEST), quest(Quest.MONKEY_MADNESS_I),
			quest(Quest.DESERT_TREASURE_I), quest(Quest.HORROR_FROM_THE_DEEP)));
		QUESTS.add(new QuestRec(Quest.CHILDREN_OF_THE_SUN, "Short — unlocks Fortis Colosseum (Dizana's quiver comes from beating wave 12, a hard fight)."));
		QUESTS.add(new QuestRec(Quest.LUNAR_DIPLOMACY, "Lunar spellbook (NPC Contact, Humidify, Vengeance later).",
			skill(Skill.MAGIC, 65), skill(Skill.CRAFTING, 61), skill(Skill.MINING, 60), skill(Skill.WOODCUTTING, 55),
			skill(Skill.FIREMAKING, 49), skill(Skill.DEFENCE, 40), skill(Skill.HERBLORE, 5),
			quest(Quest.THE_FREMENNIK_TRIALS), quest(Quest.LOST_CITY), quest(Quest.RUNE_MYSTERIES), quest(Quest.SHILO_VILLAGE)));
		QUESTS.add(new QuestRec(Quest.MONKEY_MADNESS_II, "Demonic gorillas (zenyte drops).",
			quest(Quest.MONKEY_MADNESS_I), quest(Quest.ENLIGHTENED_JOURNEY), quest(Quest.THE_EYES_OF_GLOUPHRIE),
			quest(Quest.WATCHTOWER), quest(Quest.TROLL_STRONGHOLD), skill(Skill.SLAYER, 69), skill(Skill.CRAFTING, 70),
			skill(Skill.HUNTER, 60), skill(Skill.AGILITY, 55), skill(Skill.THIEVING, 55), skill(Skill.FIREMAKING, 60)));
		QUESTS.add(new QuestRec(Quest.DRAGON_SLAYER_II, "Vorkath (~2.5-3.5M/hr). Long quest chain + these skills.",
			qp(200), skill(Skill.MAGIC, 75), skill(Skill.SMITHING, 70), skill(Skill.MINING, 68), skill(Skill.CRAFTING, 62),
			skill(Skill.AGILITY, 60), skill(Skill.THIEVING, 60), skill(Skill.CONSTRUCTION, 50), skill(Skill.HITPOINTS, 50),
			quest(Quest.LEGENDS_QUEST), quest(Quest.DREAM_MENTOR), quest(Quest.A_TAIL_OF_TWO_CATS),
			quest(Quest.ANIMAL_MAGNETISM), quest(Quest.GHOSTS_AHOY), quest(Quest.BONE_VOYAGE), quest(Quest.CLIENT_OF_KOUREND)));
		QUESTS.add(new QuestRec(Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE, "Ancient rings + BiS unlocks.",
			skill(Skill.MAGIC, 75), skill(Skill.FIREMAKING, 75), skill(Skill.THIEVING, 70), skill(Skill.HERBLORE, 62),
			skill(Skill.RUNECRAFT, 60), skill(Skill.CONSTRUCTION, 60),
			quest(Quest.DESERT_TREASURE_I), quest(Quest.SECRETS_OF_THE_NORTH), quest(Quest.ENAKHRAS_LAMENT),
			quest(Quest.TEMPLE_OF_THE_EYE), quest(Quest.THE_GARDEN_OF_DEATH), quest(Quest.BELOW_ICE_MOUNTAIN),
			quest(Quest.HIS_FAITHFUL_SERVANTS)));
		QUESTS.add(new QuestRec(Quest.SONG_OF_THE_ELVES, "Prifddinas + The Gauntlet (crystal gear) + Zalcano.",
			skill(Skill.AGILITY, 70), skill(Skill.CONSTRUCTION, 70), skill(Skill.FARMING, 70), skill(Skill.HERBLORE, 70),
			skill(Skill.HUNTER, 70), skill(Skill.MINING, 70), skill(Skill.SMITHING, 70), skill(Skill.THIEVING, 70),
			skill(Skill.WOODCUTTING, 70),
			quest(Quest.MOURNINGS_END_PART_II), quest(Quest.MAKING_HISTORY), quest(Quest.DRUIDIC_RITUAL)));
		// QoL / prerequisite quests worth surfacing
		QUESTS.add(new QuestRec(Quest.NATURE_SPIRIT, "Morytania access (→ Barrows, Ectophial herb patch).",
			quest(Quest.PRIEST_IN_PERIL), quest(Quest.THE_RESTLESS_GHOST)));
		QUESTS.add(new QuestRec(Quest.FAIRYTALE_I__GROWING_PAINS, "Step 1 toward fairy rings (the best teleport network).",
			quest(Quest.LOST_CITY), quest(Quest.NATURE_SPIRIT)));
		QUESTS.add(new QuestRec(Quest.FAIRYTALE_II__CURE_A_QUEEN, "Unlocks FAIRY RINGS (partial completion). Top-tier travel QoL.",
			skill(Skill.HERBLORE, 57), skill(Skill.FARMING, 49), skill(Skill.THIEVING, 40), quest(Quest.FAIRYTALE_I__GROWING_PAINS)));
		QUESTS.add(new QuestRec(Quest.A_KINGDOM_DIVIDED, "Book of the Dead — undead thralls (free DPS everywhere).",
			skill(Skill.AGILITY, 54), skill(Skill.THIEVING, 52), skill(Skill.WOODCUTTING, 52), skill(Skill.HERBLORE, 50),
			skill(Skill.MINING, 42), skill(Skill.CRAFTING, 38), skill(Skill.MAGIC, 35),
			quest(Quest.THE_DEPTHS_OF_DESPAIR), quest(Quest.THE_QUEEN_OF_THIEVES), quest(Quest.THE_ASCENT_OF_ARCEUUS),
			quest(Quest.THE_FORSAKEN_TOWER), quest(Quest.TALE_OF_THE_RIGHTEOUS), quest(Quest.CLIENT_OF_KOUREND), quest(Quest.X_MARKS_THE_SPOT)));
		QUESTS.add(new QuestRec(Quest.BENEATH_CURSED_SANDS, "Unlocks Tombs of Amascut (scalable ranged-friendly raid).",
			skill(Skill.AGILITY, 62), skill(Skill.CRAFTING, 55), skill(Skill.FIREMAKING, 55),
			quest(Quest.CONTACT), quest(Quest.PRINCE_ALI_RESCUE), quest(Quest.ICTHLARINS_LITTLE_HELPER), quest(Quest.GERTRUDES_CAT)));
	}

	// --- "HOW TO" data: concrete training methods + goal setups (the walkthrough layer) ------
	// best mid-game training method per skill (the Coach walks you through the HOW for non-quests).
	static final java.util.Map<Skill, String> METHOD = new java.util.EnumMap<>(Skill.class);
	static
	{
		METHOD.put(Skill.PRAYER, "dragon bones on a gilded altar (2 lit burners) — ~252 xp/bone");
		METHOD.put(Skill.MAGIC, "cheap: superheat/enchant jewellery; FAST: Ice Burst/Barrage on Slayer tasks (nechs/dust devils) — doubles as Slayer");
		METHOD.put(Skill.SLAYER, "tasks from Nieve/Steve, blowpipe everything; Barrage burst-able tasks for the fastest xp");
		METHOD.put(Skill.CRAFTING, "green→blue→red→black d'hide bodies, or battlestaves (Varrock Elite), or cut gems");
		METHOD.put(Skill.SMITHING, "Blast Furnace: gold bars w/ goldsmith gauntlets (xp) or cannonballs (cash)");
		METHOD.put(Skill.MINING, "Motherlode Mine (AFK) or iron power-mining for speed");
		METHOD.put(Skill.AGILITY, "rooftops (Seers' > Ardougne) or Hallowed Sepulchre (fast + GP)");
		METHOD.put(Skill.THIEVING, "Ardougne Knights (55+) — best xp/gp; or Pyramid Plunder");
		METHOD.put(Skill.HUNTER, "birdhouse runs (passive, do on farm runs) + red chinchompas for active xp");
		METHOD.put(Skill.CONSTRUCTION, "oak larders → mahogany tables/oak dungeon doors — butler + planks, buy your way up");
		METHOD.put(Skill.HERBLORE, "make the best unf→finished potion you can (buy herbs + secondaries off the GE)");
		METHOD.put(Skill.FARMING, "daily tree + herb + fruit-tree runs — see the Farm tab");
		METHOD.put(Skill.RUNECRAFT, "Guardians of the Rift (GOTR) — xp + useful rewards");
		METHOD.put(Skill.FIREMAKING, "Wintertodt (also gives supplies/loot) — best all-round");
		METHOD.put(Skill.WOODCUTTING, "teak trees (2-tick) or redwoods; Forestry events help");
		METHOD.put(Skill.FISHING, "barbarian fishing (Fishing+Agility+Strength) or minnows→sharks");
		METHOD.put(Skill.COOKING, "1-tick karambwans, or wines for cheap fast xp");
		METHOD.put(Skill.FLETCHING, "buy bows → string them, or dart-tips → darts");
		METHOD.put(Skill.ATTACK, "train on Slayer tasks / Nightmare Zone with your best weapon");
		METHOD.put(Skill.STRENGTH, "train on Slayer tasks / NMZ (aggressive) with your best weapon");
		METHOD.put(Skill.DEFENCE, "controlled/defensive on Slayer tasks, or NMZ");
	}

	// concrete setup/how for the non-quest goals the Coach recommends (Zulrah rotation, scroll, etc.)
	static final java.util.Map<String, String> HOW = new java.util.HashMap<>();
	static
	{
		HOW.put("Zulrah (money boss)", "WHERE: Zul-Andra (fairy ring CKR then run S, or the Zul-Andra teleport scroll). BRING: blowpipe + dragon darts, trident once 75 Mag, anti-venom+, prayer/super restores. Turn on RuneLite's Zulrah plugin and learn the fixed rotation.");
		HOW.put("Rigour (ranged prayer)", "GET TO 74 Prayer (see Prayer training), then READ a Dexterous prayer scroll — a Chambers of Xeric drop, or buy one on the GE (~24M).");
		HOW.put("Augury (magic prayer)", "GET TO 77 Prayer, then read an Arcane prayer scroll (~15M on the GE).");
		HOW.put("Slayer helmet (imbued)", "NEED 55 Slayer + 55 Crafting. From ANY Slayer master: unlock 'Malevolent masquerade' (400 pts), buy the black mask + the 5 headgear parts, then combine them. Imbue at Nightmare Zone (1250 pts) or Soul Wars.");
		HOW.put("Ava's accumulator (ranged QoL)", "DO the Animal Magnetism quest — START by talking to Ava at Draynor Manor (Ernest the Chicken area). After the quest, talk to her for the accumulator (needs 50 Ranged). Upgrade to the Assembler after DS2 + a Vorkath head.");
		HOW.put("Barrows gloves", "DO Monkey Madness I, then finish ALL Recipe for Disaster subquests → buy the gloves from the Culinaromancer's chest under Lumbridge Castle. Use Quest Helper for each subquest's steps.");
		HOW.put("Barrows (ranged gear + money)", "WHERE: the Barrows mounds NE of Canifis (fairy ring BKR, or the Barrows minigame teleport). Priest in Peril unlocks Morytania. Dig into each brother's mound, kill all 6, then loot the chest in the tunnels.");
		HOW.put("Occult necklace", "Just BUY it on the GE (~300-500k) — far easier than grinding Thermonuclear smoke devils (93 Slayer).");
		HOW.put("Trident of the swamp", "NEED 75 Magic to wield. Assemble from Zulrah drops (magic fang + an uncharged toxic trident), or buy the charged swamp trident on the GE.");
		HOW.put("Void ranged set", "WHERE: Void Knights' Outpost — talk to the Squire on the docks in Port Sarim to sail there. Play Pest Control (take the HARD boat), spend ~30-40 points on the ranged top, legs, gloves + a helm.");
		HOW.put("Amulet of anguish", "Buy it on the GE, or craft from a zenyte (demonic gorillas after MM2, or ToA). Big ranged-neck upgrade over the fury.");
		HOW.put("Rune pouch", "DO the 'Enter the Abyss' miniquest — talk to the Mage of Zamorak (he wanders the ruins just NE of Edgeville, edge of the Wild). ~5 min, no requirements. (Or buy it with 750 Slayer points.)");
		HOW.put("Ancient Magicks (Barrage)", "DO Desert Treasure I (use Quest Helper). Then switch to the Ancient spellbook at the altar in the pyramid north of the Bandit Camp (or a POH altar).");
		HOW.put("Fairy rings (travel QoL)", "DO Fairytale II up to the point you re-attune the rings (partial completion is enough) — use Quest Helper. Then any fairy ring lets you code-hop the map.");
		HOW.put("Book of the Dead (thralls)", "DO 'A Kingdom Divided' (use Quest Helper), then read the Book of the Dead. Cast Resurrect Thrall from the Arceuus spellbook (needs the book equipped/owned).");
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
