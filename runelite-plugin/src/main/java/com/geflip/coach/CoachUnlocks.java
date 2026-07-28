package com.geflip.coach;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.runelite.api.Quest;

/**
 * Permanent-unlock advisor — the "do this once, save forever" plays the efficiency research flagged as the
 * #2 leverage (after dailies). A curated set of high-value unlocks gated on YOUR quest state; the Coach
 * surfaces only the ones you DON'T have yet, with why they matter. Info-only (read-only).
 */
final class CoachUnlocks
{
	private CoachUnlocks() {}

	private static final class U
	{
		final String name, perk; final Predicate<CoachState> got;
		U(String name, String perk, Predicate<CoachState> got) { this.name = name; this.perk = perk; this.got = got; }
	}

	private static final U[] UNLOCKS = {
		new U("Fairy rings", "the big teleport network — the single biggest travel time-saver", st -> st.started(Quest.FAIRYTALE_II__CURE_A_QUEEN)),
		new U("Spirit tree network", "fast hops to gnome hubs + your farm patches", st -> st.finished(Quest.TREE_GNOME_VILLAGE) && st.finished(Quest.THE_GRAND_TREE)),
		new U("Barrows gloves", "best cheap gloves-slot in the game (Recipe for Disaster)", st -> st.finished(Quest.RECIPE_FOR_DISASTER)),
		new U("Ava's device", "auto-collects your ranged ammo (Animal Magnetism)", st -> st.finished(Quest.ANIMAL_MAGNETISM)),
		new U("Lunar spellbook", "Humidify, NPC Contact, teleports, superglass make", st -> st.finished(Quest.LUNAR_DIPLOMACY)),
		new U("Ancient Magicks", "ice barrage/burst — top Magic + Slayer xp", st -> st.finished(Quest.DESERT_TREASURE_I)),
		new U("Dragon Slayer II", "Vorkath (~3-5M/hr), 15k combat lamps, Myths' Guild", st -> st.finished(Quest.DRAGON_SLAYER_II)),
		new U("Maniacal monkeys (MM2)", "the best Hunter / Ranged chinning method", st -> st.finished(Quest.MONKEY_MADNESS_II)),
	};

	static List<String> lines(CoachState st)
	{
		List<U> todo = new ArrayList<>();
		for (U u : UNLOCKS) if (!u.got.test(st)) todo.add(u);
		List<String> o = new ArrayList<>();
		if (todo.isEmpty()) return o;   // you've got them all
		o.add("*🔓 UNLOCKS to grab (do once, save forever)");
		for (U u : todo) o.add(u.name + " — " + u.perk);
		o.add("Also: do each Achievement Diary tier the moment you qualify — permanent perks (teleports,");
		o.add("  better yields, drop rates). Quest-first, in an optimal order, front-loads all of this.");
		o.add("");
		return o;
	}
}
