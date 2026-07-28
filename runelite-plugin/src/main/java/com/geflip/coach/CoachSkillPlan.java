package com.geflip.coach;

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * The optimal 1→99 training method per skill, banded by level (wiki-sourced 2026 meta). Pure data →
 * the plugin composes it with your LIVE level/XP into a per-skill "best method now + ETA to 99". Mirror
 * of {@link CoachFarmPlan} but across skills; Farming keeps its richer dedicated planner. XP/hr figures
 * are the mainstream-efficient numbers (not tick-perfect ceilings), so ETAs are realistic, not fantasy.
 */
final class CoachSkillPlan
{
	private CoachSkillPlan() {}

	static final class Band
	{
		final int min, xpHr;
		final String method, cost, note;   // cost = short money tag: "profit" / "cheap" / "free" / "~18M" / "expensive"
		Band(int min, String method, int xpHr, String cost, String note)
		{ this.min = min; this.method = method; this.xpHr = xpHr; this.cost = cost; this.note = note; }
	}

	private static final Band[] EMPTY = {};
	private static final Map<Skill, Band[]> PLAN = new EnumMap<>(Skill.class);

	private static void put(Skill s, Band... bands) { PLAN.put(s, bands); }

	static
	{
		// Populated from the deep-research passes (combat / gathering / artisan / support). Filled below.
		SkillData.load();
	}

	/** Register a skill's bands (called by SkillData). */
	static void register(Skill s, Band... bands) { PLAN.put(s, bands); }

	static Band[] bands(Skill s) { return PLAN.getOrDefault(s, EMPTY); }

	/** The band whose range contains this level (last band with min ≤ level), or null if none. */
	static Band bestBand(Skill s, int level)
	{
		Band cur = null;
		for (Band b : bands(s)) { if (level >= b.min) cur = b; else break; }
		return cur;
	}

	/** The next band up (first with min > level), or null. */
	static Band nextBand(Skill s, int level)
	{
		for (Band b : bands(s)) if (b.min > level) return b;
		return null;
	}

	/** Realistic hours-to-99: sum, over each band still ahead, the XP left in it ÷ that band's XP/hr.
	 *  Returns -1 if we have no method data for the skill. */
	static double hoursTo99(Skill s, long curXp)
	{
		Band[] bs = bands(s);
		if (bs.length == 0) return -1;
		double hrs = 0; long xp99 = CoachFarmPlan.xpForLevel(99);
		for (int i = 0; i < bs.length; i++)
		{
			if (bs[i].xpHr <= 0) continue;
			long top = (i + 1 < bs.length) ? CoachFarmPlan.xpForLevel(bs[i + 1].min) : xp99;
			long lo = Math.max(curXp, CoachFarmPlan.xpForLevel(bs[i].min));
			if (top <= lo) continue;
			hrs += (top - lo) / (double) bs[i].xpHr;
		}
		return hrs;
	}
}
