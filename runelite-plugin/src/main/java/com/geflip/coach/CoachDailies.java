package com.geflip.coach;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * The compounding daily/passive plays that separate efficient players from everyone else — the biggest
 * real leak in most accounts isn't slow grinding, it's SKIPPING these (wiki/efficiency meta). Info only
 * (read-only): the Coach reminds you + gates each on your actual quest/level unlocks. RuneLite's built-in
 * Timetracking owns the exact cooldown timers; this is the "don't forget these every session" checklist.
 */
final class CoachDailies
{
	private CoachDailies() {}

	private static String gate(CoachState st, Quest q, String name) { return st.finished(q) ? "" : "  [unlock: " + name + "]"; }

	static List<String> lines(CoachState st)
	{
		List<String> o = new ArrayList<>();
		o.add("*DAILIES — the compounding plays most players skip");
		o.add("Farm run — herbs ~80m, trees daily · ~0.5-1M gp + 100k+ Farming xp/day  → Farm tab");
		o.add("Bird house run (Fossil Island) — ~4.6k Hunter xp/hr + nests, ~2 min" + gate(st, Quest.BONE_VOYAGE, "Bone Voyage"));
		o.add("Hespori (Farming Guild) — Farming xp + bottomless bucket / seeds" + (st.level(Skill.FARMING) >= 65 ? "" : "  [needs 65 Farming]"));
		o.add("Managing Miscellania — passive resources daily, 1-2 min" + gate(st, Quest.THRONE_OF_MISCELLANIA, "Throne of Miscellania")
			+ (st.finished(Quest.ROYAL_TROUBLE) ? "" : " · do Royal Trouble for +50%"));
		o.add("Tears of Guthix — WEEKLY · free xp into your lowest skill" + gate(st, Quest.TEARS_OF_GUTHIX, "Tears of Guthix"));
		o.add("Daily Battlestaves (Zaff, Varrock) — ~7k gp/day + Crafting xp if made");
		o.add("Daily herb boxes (NMZ) — up to ~1M gp/day if you have the points");
		o.add("Guardians of the Rift / Wintertodt — best Runecraft / Firemaking + profit (on-demand)");
		o.add("");
		o.add("EDGE HABITS: quest-first (XP lamps + permanent unlocks), do each Achievement Diary tier the");
		o.add("  moment you qualify, train multi-skill methods (barb fishing = Fish+Str+Agi), and fund the");
		o.add("  fast methods with gp. Advanced: tick manipulation (2-3× rates) — high effort, opt-in.");
		o.add("");
		return o;
	}
}
