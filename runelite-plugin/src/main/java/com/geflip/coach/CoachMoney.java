package com.geflip.coach;

import java.util.function.Predicate;
import net.runelite.api.Skill;

/**
 * A curated money-maker advisor: well-known GP/hr methods gated by the requirements YOUR account meets
 * (skill levels + combat level), so it only ever recommends what you can actually do right now. GP/hr
 * figures are approximate (they move with prices) — treated as ranges, ranked, and clearly labelled.
 * Cross-references your live stats; ties to the flipper (GE flipping is the always-available baseline).
 */
final class CoachMoney
{
	private CoachMoney() {}

	static final class M
	{
		final String name; final int gpHr; final String note; final Predicate<CoachState> ok;
		M(String name, int gpHr, String note, Predicate<CoachState> ok)
		{ this.name = name; this.gpHr = gpHr; this.note = note; this.ok = ok; }
	}

	// gpHr = 0 → "passive/varies" (shown without an hourly number). Requirements are the ones I'm
	// confident on; the gp/hr is approximate. Ordered arbitrarily; the plugin ranks eligible ones.
	static final M[] METHODS = {
		new M("GE flipping — the Flips tab (scales with your bank)", 0, "steady, low-risk; grows with capital", st -> true),
		new M("Herb + tree farm runs (daily)", 0, "passive ~1-3M/day — see the Farm tab", st -> st.level(Skill.FARMING) >= 32),
		new M("Amethyst mining", 300_000, "very AFK; needs 92 Mining", st -> st.level(Skill.MINING) >= 92),
		new M("Anglerfish fishing", 300_000, "AFK profit; needs 82 Fishing", st -> st.level(Skill.FISHING) >= 82),
		new M("Tempoross (minigame)", 350_000, "loot + Fishing xp; needs 35 Fishing", st -> st.level(Skill.FISHING) >= 35),
		new M("Guardians of the Rift (GOTR)", 500_000, "profit + Runecraft xp + outfit; needs 27 RC", st -> st.level(Skill.RUNECRAFT) >= 27),
		new M("Wintertodt", 450_000, "loot crates + Firemaking xp; needs 50 FM", st -> st.level(Skill.FIREMAKING) >= 50),
		new M("Green dragons (Wilderness)", 700_000, "low-req; PvP risk; ~60+ combat", st -> st.combatLevel >= 60),
		new M("Making prayer potions", 800_000, "~5.6k/pot + Herblore xp; needs 38 Herblore", st -> st.level(Skill.HERBLORE) >= 38),
		new M("Barrows", 1_000_000, "mid PvM; needs decent gear; ~90+ combat", st -> st.combatLevel >= 90),
		new M("Combination runes (Magic Imbue)", 400_000, "RC method, NOT a GE flip: 1 pure essence + 1 element rune per rune at the opposite altar; Magic Imbue (82 Mag + Lunar Diplomacy) skips the talisman. ~23 RC for Lava.",
			st -> st.level(Skill.MAGIC) >= 82 && st.finished(net.runelite.api.Quest.LUNAR_DIPLOMACY) && st.level(Skill.RUNECRAFT) >= 23),
		new M("Blood-rune Runecrafting (Arceuus)", 1_000_000, "AFK-ish; needs 77 RC + Sins of the Father", st -> st.level(Skill.RUNECRAFT) >= 77),
		new M("Black chinchompa hunting (Wilderness)", 2_000_000, "high profit; PvP risk; needs 73 Hunter", st -> st.level(Skill.HUNTER) >= 73),
		new M("Rogues' Castle thieving (Wilderness)", 2_200_000, "fast; PvP risk; needs 84 Thieving", st -> st.level(Skill.THIEVING) >= 84),
		new M("Zulrah", 3_000_000, "learn the rotations; needs mid gear + fairy-ring/teleport access; ~90+ combat", st -> st.combatLevel >= 90),
		new M("Demonic gorillas (MM2)", 1_200_000, "zenyte shard drops; needs Monkey Madness II. Great for a ranger (chin/blowpipe).",
			st -> st.finished(net.runelite.api.Quest.MONKEY_MADNESS_II)),
		new M("Vorkath", 4_000_000, "needs Dragon Slayer II + strong gear; ~110+ combat. Blowpipe caps it — a Dragon Hunter Crossbow lifts the rate a lot.",
			st -> st.finished(net.runelite.api.Quest.DRAGON_SLAYER_II)),
		new M("Phantom Muspah", 2_500_000, "lower supply cost than Vorkath, ranged-friendly; needs Secrets of the North.",
			st -> st.finished(net.runelite.api.Quest.SECRETS_OF_THE_NORTH)),
		new M("Tombs of Amascut (low invocation)", 1_500_000, "scalable raid — a fire cape is enough to learn at low invo; needs Beneath Cursed Sands. Ranged-friendly.",
			st -> st.finished(net.runelite.api.Quest.BENEATH_CURSED_SANDS)),
		new M("Alchemical Hydra", 3_500_000, "high gp/hr + hydra leather; needs 95 Slayer (on-task).",
			st -> st.level(Skill.SLAYER) >= 95),
		new M("Corrupted Gauntlet", 1_000_000, "crystal armour + enhanced-weapon-seed shot; needs Song of the Elves. Solo, gear-independent (you get crystal gear inside).",
			st -> st.finished(net.runelite.api.Quest.SONG_OF_THE_ELVES)),
	};

	/** Eligible methods (requirements met), ranked by gp/hr descending; the passive ones (gpHr 0) sort
	 *  to the end so a concrete hourly earner leads. */
	static java.util.List<M> eligible(CoachState st)
	{
		java.util.List<M> out = new java.util.ArrayList<>();
		for (M m : METHODS) if (m.ok.test(st)) out.add(m);
		out.sort((a, b) -> Integer.compare(b.gpHr <= 0 ? -1 : b.gpHr, a.gpHr <= 0 ? -1 : a.gpHr));
		return out;
	}
}
