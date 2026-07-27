package com.geflip.coach;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * Farming-run guide, keyed to YOUR account: what to plant in each patch type right now (the highest
 * seed you meet), and — the important part — the BEST route for what you've actually unlocked. Every
 * patch carries its real access gate (quest and/or Farming level, verified vs the wiki 2026-07), so a
 * patch you can't reach is moved to a "locked" list with exactly what unlocks it, instead of sending
 * you somewhere you can't go. Pure data → lines; shown only when you enable the toggle.
 *
 * Access facts baked in (post-Jan-2024): Kourend/Hosidius FAVOUR was removed — Hosidius patches and
 * Farming-Guild entry need NO favour, only the guild's Farming-level tiers (45/65/85). Fossil Island
 * has NO herb patch. Troll Stronghold's herb patch needs My Arm's Big Adventure (not Eadgar's Ruse).
 */
final class CoachFarm
{
	private CoachFarm() {}

	// {seed name, farming level} — highest one you meet is the pick; the next one is your goal.
	private static final Object[][] HERBS = {
		{"Guam", 9}, {"Marrentill", 14}, {"Tarromin", 19}, {"Harralander", 26}, {"Ranarr", 32},
		{"Toadflax", 38}, {"Irit", 44}, {"Avantoe", 50}, {"Kwuarm", 56}, {"Snapdragon", 62},
		{"Cadantine", 67}, {"Lantadyme", 73}, {"Dwarf weed", 79}, {"Torstol", 85} };
	private static final Object[][] TREES = {
		{"Oak", 15}, {"Willow", 30}, {"Maple", 45}, {"Yew", 60}, {"Magic", 75} };
	private static final Object[][] FRUIT = {
		{"Apple", 27}, {"Banana", 33}, {"Orange", 39}, {"Curry", 42}, {"Pineapple", 51},
		{"Papaya", 57}, {"Palm", 68}, {"Dragonfruit", 81} };
	private static final Object[][] BUSH = {
		{"Redberry", 10}, {"Cadava", 22}, {"Dwellberry", 36}, {"Jangerberry", 48},
		{"Whiteberry", 59}, {"Poison ivy", 70} };

	/** One farm patch: where it is + how to teleport, and its access gate. gate==null means always
	 *  reachable; otherwise reqLabel says what unlocks it (a quest name or "NN Farming"). */
	private static final class Patch
	{
		final String loc, tele, reqLabel;
		final Predicate<CoachState> gate;
		Patch(String loc, String tele, String reqLabel, Predicate<CoachState> gate)
		{ this.loc = loc; this.tele = tele; this.reqLabel = reqLabel; this.gate = gate; }
		boolean open(CoachState st) { return gate == null || gate.test(st); }
	}
	private static Patch free(String loc, String tele) { return new Patch(loc, tele, null, null); }
	private static Patch lvl(String loc, String tele, int f) { return new Patch(loc, tele, f + " Farming", st -> st.level(Skill.FARMING) >= f); }
	private static Patch quest(String loc, String tele, String name, Quest q) { return new Patch(loc, tele, name, st -> st.finished(q)); }

	static final String[] TYPES = { "Herb", "Tree", "Fruit", "Flower", "Bush", "All" };

	private static final Patch[] HERB_PATCHES = {
		free("Falador farm", "Explorer's ring (cabbage tele) → run N to Elstan"),
		free("Catherby", "Catherby teleport tab (or Camelot tele → run E)"),
		free("Ardougne", "Ardougne cloak 2+ (farm tele) → Kragen"),
		free("Morytania (Ectofuntus)", "Ectophial → run W to Lyra"),
		free("Hosidius", "Xeric's talisman → Xeric's Glade → run SW (no favour needed)"),
		lvl("Farming Guild (W wing)", "Skills necklace → Farming Guild", 65),
		quest("Troll Stronghold (roof)", "Stony basalt / Trollheim teleport", "My Arm's Big Adventure", Quest.MY_ARMS_BIG_ADVENTURE),
		quest("Weiss", "Icy basalt (lands by the patch)", "Making Friends with My Arm", Quest.MAKING_FRIENDS_WITH_MY_ARM),
		quest("Harmony Island", "Harmony Island Teleport (Arceuus) → run S", "The Great Brain Robbery", Quest.THE_GREAT_BRAIN_ROBBERY),
	};
	private static final Patch[] TREE_PATCHES = {
		free("Lumbridge", "Home / Lumbridge teleport → W of the castle (Fayeth)"),
		free("Varrock", "Varrock teleport → run W to Gertrude's (Treznor)"),
		free("Falador Park", "Falador teleport / Ring of wealth → the park (Heskel)"),
		free("Taverley", "Games necklace → Burthorpe → run to Alain"),
		free("Gnome Stronghold", "Spirit tree / Royal seed pod (Prissy Scilla)"),
		lvl("Farming Guild (W wing)", "Skills necklace → Farming Guild", 65),
	};
	private static final Patch[] FRUIT_PATCHES = {
		free("Gnome Stronghold", "Spirit tree / Royal seed pod (Bolongo)"),
		free("Tree Gnome Village", "Fairy ring CIQ (quest-free)"),
		free("Catherby", "Catherby teleport tab → E beach (Ellena)"),
		free("Brimhaven", "House teleport set to Brimhaven / charter ship (Garth)"),
		new Patch("Lletya", "Teleport crystal", "Regicide + Mourning's End Pt I (started)",
			st -> st.started(Quest.MOURNINGS_END_PART_I)),
		lvl("Farming Guild (N wing)", "Skills necklace → Farming Guild", 85),
	};
	private static final Patch[] FLOWER_PATCHES = {   // flowers + allotments share these sites
		free("Falador farm", "Explorer's ring (cabbage tele)"),
		free("Catherby", "Catherby teleport tab / Camelot tele"),
		free("Ardougne", "Ardougne cloak 2+"),
		free("Morytania (Ectofuntus)", "Ectophial → run W"),
		free("Hosidius", "Xeric's talisman → Xeric's Glade (no favour)"),
		free("Ortus Farm (Varlamore)", "Quetzal whistle"),
		lvl("Farming Guild (E wing)", "Skills necklace → Farming Guild", 45),
	};
	private static final Patch[] BUSH_PATCHES = {
		free("Champions' Guild", "Combat bracelet → Champions' Guild (or Varrock tele → run S), Dreven"),
		free("Rimmington", "House teleport set to Rimmington (Taria)"),
		free("Ardougne", "Ardougne cloak 1+ → Monastery (Torrell), or fairy ring DJP"),
		quest("Etceteria", "Fairy ring CIP → run over (Rhazien)", "The Fremennik Trials", Quest.THE_FREMENNIK_TRIALS),
		lvl("Farming Guild (E wing)", "Skills necklace → Farming Guild", 45),
	};

	private static final int FLOWER_MIN = 20, BUSH_MIN = 320;
	static final int HERB_MIN = 80, TREE_MIN = 360, FRUIT_MIN = 960;

	private static String bare(Object[][] tbl, int lvl)   // the seed you'd plant at this level
	{
		String best = "—";
		for (Object[] row : tbl) if (lvl >= (int) row[1]) best = (String) row[0];
		return best;
	}

	private static String eta(int elapsedMin, int cycle, String label)
	{
		if (elapsedMin < 0) return "  " + label + ": —";
		int left = cycle - elapsedMin;
		return "  " + label + ": " + (left <= 0 ? "READY ✓" : "in ~" + (left >= 60 ? (left / 60) + "h" + (left % 60 != 0 ? (left % 60) + "m" : "") : left + "m"));
	}

	/** Readiness header for the current run cycle, given minutes since your last "mark run done"
	 *  (−1 if never logged). A cycle tracker — RuneLite's Timetracking reads exact per-patch state. */
	static List<String> readiness(int elapsedMin)
	{
		List<String> o = new ArrayList<>();
		if (elapsedMin < 0) { o.add("No run logged yet — plant, then hit 'Mark farm run done'."); return o; }
		o.add("Since last run: ~" + (elapsedMin >= 60 ? (elapsedMin / 60) + "h" + (elapsedMin % 60) + "m" : elapsedMin + "m"));
		o.add(eta(elapsedMin, HERB_MIN, "Herbs").trim());
		o.add(eta(elapsedMin, TREE_MIN, "Trees").trim());
		o.add(eta(elapsedMin, FRUIT_MIN, "Fruit trees").trim());
		return o;
	}

	/** Split a patch set into the route you can do now vs what's locked, and append both. Returns the
	 *  number of OPEN stops (so callers can note when a run is empty). */
	private static int appendRoute(List<String> o, Patch[] patches, CoachState st)
	{
		List<Patch> open = new ArrayList<>(), locked = new ArrayList<>();
		for (Patch p : patches) (p.open(st) ? open : locked).add(p);
		int n = 1;
		for (Patch p : open) o.add("  " + n++ + ") " + p.loc + " — " + p.tele);
		if (!locked.isEmpty())
		{
			o.add("  LOCKED (unlock to add stops):");
			for (Patch p : locked) o.add("    ✖ " + p.loc + " — unlock: " + p.reqLabel);
		}
		return open.size();
	}

	/** A PRESET run for one crop type (Herb / Tree / Fruit / Flower / Bush), or the combined "All",
	 *  routed to what YOUR account has unlocked. */
	static List<String> run(String type, CoachState st, int elapsedMin)
	{
		if (type == null || type.equals("All")) return plan(st, elapsedMin);
		int level = st.level(Skill.FARMING);
		String seed; int cycle; Patch[] patches; String bring;
		switch (type)
		{
			case "Tree":
				seed = bare(TREES, level); cycle = TREE_MIN; patches = TREE_PATCHES;
				bring = "one " + seed + " sapling per open stop + coins/produce to pay each gardener"; break;
			case "Fruit":
				seed = bare(FRUIT, level); cycle = FRUIT_MIN; patches = FRUIT_PATCHES;
				bring = "one " + seed + " sapling per open stop + baskets of fruit to pay each gardener"; break;
			case "Flower":
				seed = "Marigolds (protect allotments) or Limpwurt/Rosemary"; cycle = FLOWER_MIN; patches = FLOWER_PATCHES;
				bring = "flower seeds (+ allotment seeds if doing allotments)"; break;
			case "Bush":
				seed = bare(BUSH, level); cycle = BUSH_MIN; patches = BUSH_PATCHES;
				bring = "one " + seed + " seed per open stop + ultracompost + coins to pay gardeners"; break;
			default: // Herb
				seed = bare(HERBS, level); cycle = HERB_MIN; patches = HERB_PATCHES;
				bring = "one " + seed + " seed per open stop + ultracompost (or bottomless bucket) each"; break;
		}
		List<String> o = new ArrayList<>();
		o.add(type.toUpperCase() + " RUN — plant " + seed + "  (Farming " + level + ")");
		o.add(eta(elapsedMin, cycle, type).trim());
		o.add("");
		o.add("BRING: " + bring);
		o.add("KIT: spade, seed dibber, rake (skip w/ a diary), secateurs" + ("Herb".equals(type) ? ", Magic secateurs (+10% yield)" : "") + ", Farmer's outfit, the teleports below.");
		o.add("");
		o.add("ROUTE (best for what you've unlocked) — each stop: HARVEST → clear → "
			+ ("Tree".equals(type) || "Fruit".equals(type) ? "PLANT → PAY the gardener to protect" : "ULTRACOMPOST → PLANT") + ":");
		int open = appendRoute(o, patches, st);
		o.add("");
		o.add("GETTING THERE: each stop shows its teleport — take it, then set the patch as your");
		o.add("  shortest-path target for a drawn walking route the last few tiles.");
		o.add(open == 0 ? "No stops unlocked yet — see LOCKED above." : "Mark the run done to start the cycle timer.");
		if ("Herb".equals(type)) o.add("TIP: ultracompost + Ardougne Medium diary = disease-free herbs; Resurrect Crops (78 Mag) revives a dead one.");
		if ("Tree".equals(type) || "Fruit".equals(type)) o.add("TIP: NEVER skip paying a gardener — a dead sapling is a big loss. Trees can't be cured, only protected.");
		return o;
	}

	/** The combined daily pass — herb + tree + fruit-tree, each routed to your unlocks. */
	static List<String> plan(CoachState st, int elapsedMin)
	{
		int level = st.level(Skill.FARMING);
		String herb = bare(HERBS, level), tree = bare(TREES, level), fruit = bare(FRUIT, level);
		List<String> o = new ArrayList<>();

		o.add("FARM RUN — Farming " + level + " (herbs + trees + fruit trees)");
		o.add("Herb=" + herb + " · Tree=" + tree + " · Fruit=" + fruit);
		o.add("");
		o.addAll(readiness(elapsedMin));
		o.add("");
		o.add("BRING:");
		o.add("• " + herb + " (herb) seeds — one per open herb stop below");
		o.add("• " + tree + " saplings (tree) + " + fruit + " saplings (fruit) — one per open stop");
		o.add("• Ultracompost (or Bottomless bucket) per herb/allotment patch");
		o.add("• Rake (skip w/ any diary), spade, seed dibber, secateurs; pay: coins + baskets to protect trees");
		o.add("• GEAR: Magic secateurs (+10% herb yield), full Farmer's outfit (XP)");
		o.add("");
		o.add("HERB stops:");
		appendRoute(o, HERB_PATCHES, st);
		o.add("");
		o.add("TREE stops:");
		appendRoute(o, TREE_PATCHES, st);
		o.add("");
		o.add("FRUIT-TREE stops:");
		appendRoute(o, FRUIT_PATCHES, st);
		o.add("");
		o.add("Order geographically (Falador+park, Catherby herb+fruit, Ardougne, Hosidius, guild if unlocked,");
		o.add("  then the spirit-tree gnome patches) to save hops. Mark the run done to start the timer.");
		o.add("CADENCE: herbs ~80 min, trees/fruit ~once a day — herb loop often, trees on the daily pass.");
		return o;
	}
}
