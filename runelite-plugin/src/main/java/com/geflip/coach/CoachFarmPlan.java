package com.geflip.coach;

/**
 * The optimal 1→99 Farming path, banded by level (wiki-sourced 2026 meta). Pure data → the plugin
 * composes it with your LIVE level/XP into a "Path to 99" trainer. Philosophy: daily TREE + FRUIT-TREE
 * runs are the XP backbone (huge XP per single check), herbs supplement for profit, hardwood + specials
 * (Hespori/Calquat/Celastrus/Redwood) bolt on, and Tithe Farm is the active grind for early-mid levels +
 * the farmer's-outfit unlock (+10.2% XP). Total XP 1→99 = 13,034,431.
 */
final class CoachFarmPlan
{
	private CoachFarmPlan() {}

	static final class Band
	{
		final int min, xpDayMid;   // xpDayMid = realistic XP/day on the daily circuit, for the ETA
		final String tree, fruit, herb, special, xpDay, unlock, note;
		Band(int min, String tree, String fruit, String herb, String special, String xpDay, int xpDayMid, String unlock, String note)
		{ this.min = min; this.tree = tree; this.fruit = fruit; this.herb = herb; this.special = special;
		  this.xpDay = xpDay; this.xpDayMid = xpDayMid; this.unlock = unlock; this.note = note; }
	}

	// sorted ascending by min level. tree/fruit = the sapling to plant in those patches at this band.
	static final Band[] BANDS = {
		new Band(1,  "—", "—", "Allotments (potato→sweetcorn)", "—", "~1–3k/day", 3_000, "start",
			"Rush the Farming quests — Fairytale I (Magic secateurs!), Forgettable Tale, Garden of Death, Garden of Tranquillity, My Arm's Big Adventure, Enlightened Journey ≈ 32.5k XP → ~level 33 instantly."),
		new Band(15, "Oak", "—", "Allotments", "—", "~3–8k/day", 6_000, "Oak trees (first trees)",
			"15 = your first tree patches. Plant an oak in every tree patch each day."),
		new Band(27, "Oak", "Apple", "Allotments", "—", "~8–15k/day", 12_000, "Apple fruit trees",
			"First fruit trees — the biggest early jump in XP per run. Always pay the farmer to protect."),
		new Band(33, "Willow", "Banana → Orange (39) → Curry (42)", "Ranarr (32)", "Teak (35, Fossil Island)", "~25–50k/day", 38_000, "Willow + Teak + Tithe",
			"First 'real' run. Grind Tithe Farm (34+) for the farmer's outfit (+10.2% XP) and Gricoller's can."),
		new Band(45, "Maple", "Pineapple (51)", "Toadflax (38) → Kwuarm (56)", "Teak", "~60–110k/day", 85_000, "Maple + Farming Guild",
			"Farming Guild beginner tier opens (45) — start using its patches. Ultracompost every herb/allotment."),
		new Band(57, "Maple", "Papaya (57)", "Kwuarm", "Mahogany (55, Fossil Island)", "~90–130k/day", 110_000, "Papaya + Mahogany",
			"Mahogany is a huge single-patch chunk. Papaya is a fine fruit-tree stop if avoiding Palm cost."),
		new Band(60, "Yew", "Papaya (Palm at 68)", "Snapdragon (62)", "Mahogany", "~110–160k/day", 135_000, "Yew trees",
			"Yew saplings in every tree patch now."),
		new Band(65, "Yew", "Palm (68)", "Snapdragon", "Hespori (65); Calquat (72 soon)", "~110–160k/day", 140_000, "Hespori + Guild intermediate",
			"Guild intermediate tier (herb/tree/Hespori/anima). Do the Hespori boss-harvest ~daily."),
		new Band(72, "Yew", "Palm", "Snapdragon", "Hespori, Calquat (72)", "~130–170k/day", 155_000, "Calquat",
			"Add a Calquat at Tai Bwo Wannai to the daily."),
		new Band(75, "Magic", "Palm", "Snapdragon", "Hespori, Calquat, Crystal (74, Song of the Elves)", "~180–220k/day", 200_000, "Magic trees",
			"Magic saplings — the endgame tree. Biggest per-tree XP."),
		new Band(81, "Magic", "Dragonfruit (81)", "Snapdragon", "Hespori, Calquat", "~215–240k/day", 227_000, "Dragonfruit (the classic run)",
			"The canonical run: 6 magic + 6 dragonfruit + calquat + celastrus ≈ 215k XP in ~5 min of clicking."),
		new Band(85, "Magic", "Dragonfruit", "Torstol (85)", "Celastrus (85, Guild advanced); Hespori, Calquat", "~230–250k/day", 240_000, "Celastrus + Guild advanced",
			"Guild advanced tier: fruit-tree, spirit-tree, celastrus, redwood patches."),
		new Band(90, "Magic", "Dragonfruit", "Torstol", "Redwood (90) + Celastrus + Hespori + Calquat", "~215–260k+/day", 245_000, "Redwood — the final push",
			"Full endgame circuit. Redwood is the single biggest per-check chunk (22,450 XP)."),
	};

	// standard OSRS xp-for-level (kept local so this class is self-contained for the ETA math).
	static long xpForLevel(int lvl)
	{
		double xp = 0;
		for (int i = 1; i < lvl; i++) xp += Math.floor(i + 300 * Math.pow(2, i / 7.0));
		return (long) Math.floor(xp / 4);
	}

	/** The band whose range contains this level (the last band with min ≤ level). */
	static Band bandFor(int level)
	{
		Band cur = BANDS[0];
		for (Band b : BANDS) if (level >= b.min) cur = b; else break;
		return cur;
	}

	/** The next band up (first with min > level), or null if you're in the final (90+) band. */
	static Band nextBand(int level)
	{
		for (Band b : BANDS) if (b.min > level) return b;
		return null;
	}

	/** Realistic days-to-99 doing the DAILY runs: sum, over each band still ahead of you, the XP left in
	 *  that band ÷ that band's daily rate. Accounts for the rate climbing as you level (unlike a flat
	 *  session rate, which is meaningless for bursty farming XP). */
	static double daysTo99(long curXp)
	{
		double days = 0; long xp99 = xpForLevel(99);
		for (int i = 0; i < BANDS.length; i++)
		{
			long bandTop = (i + 1 < BANDS.length) ? xpForLevel(BANDS[i + 1].min) : xp99;
			long lo = Math.max(curXp, xpForLevel(BANDS[i].min));
			if (bandTop <= lo) continue;                       // band already behind you
			days += (bandTop - lo) / (double) BANDS[i].xpDayMid;
		}
		return days;
	}

	/** Must-have efficiency items (the plugin adds live quest/level checks around these). */
	static String efficiencyChecklist(int level)
	{
		String s = "Magic secateurs (Fairytale I) +10% herb · Farmer's outfit (Tithe) +10.2% XP · "
			+ "ultracompost every herb/allotment · PAY every tree farmer to protect";
		return level >= 90 ? s + " · Bottomless compost bucket (Hespori drop)" : s;
	}
}
