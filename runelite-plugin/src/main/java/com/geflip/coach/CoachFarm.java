package com.geflip.coach;

import java.util.ArrayList;
import java.util.List;

/**
 * Farming-run guide, keyed to your Farming level: what to plant in each patch type right now (the
 * highest-value seed you can use), where those patches are + how to teleport, and the run tips that
 * matter (compost, disease, yield gear). Pure data → lines; the panel only shows it when you enable
 * the toggle, so it stays out of the way until you want it.
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

	private static String bare(Object[][] tbl, int lvl)   // the seed you'd plant at this level
	{
		String best = "—";
		for (Object[] row : tbl) if (lvl >= (int) row[1]) best = (String) row[0];
		return best;
	}

	// approximate grow times (minutes): herbs ~80m, trees ~6h, fruit trees ~16h.
	static final int HERB_MIN = 80, TREE_MIN = 360, FRUIT_MIN = 960;

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

	/** A step-by-step combined herb + tree + fruit-tree run for the player's level: what to bring,
	 *  then a numbered route (teleport → patch → do), then the tips. Repeatable each cycle — every
	 *  stop is the same loop: harvest → clear → ultracompost → plant → pay to protect. */
	static List<String> plan(int farmingLevel, int elapsedMin)
	{
		String herb = bare(HERBS, farmingLevel), tree = bare(TREES, farmingLevel),
			fruit = bare(FRUIT, farmingLevel), bush = bare(BUSH, farmingLevel);
		// Farming Guild is TIERED: 45 = bush/flower/allotment/cactus only; 65 = herb + tree;
		// 85 = fruit tree. The valuable herb/tree stop isn't available until 65.
		boolean guildBush = farmingLevel >= 45, guildHerbTree = farmingLevel >= 65, guildFruit = farmingLevel >= 85;
		List<String> o = new ArrayList<>();

		o.add("FARM RUN — Farming " + farmingLevel + " (herbs + trees + fruit trees)");
		o.add("Herb=" + herb + " · Tree=" + tree + " · Fruit=" + fruit + " · Bush=" + bush);
		o.add("");
		o.addAll(readiness(elapsedMin));
		o.add("");
		o.add("BRING:");
		o.add("• " + (guildHerbTree ? 6 : 5) + "x " + herb + " (herb) seeds");
		o.add("• 5x " + tree + " saplings  +  4x " + fruit + " saplings");
		o.add("• Ultracompost (or a Bottomless bucket) — one per herb/allotment patch");
		o.add("• Rake (skip if you have any diary that clears weeds), spade, seed dibber, secateurs");
		o.add("• Protection pay: coins + baskets of fruit / veg for the tree & fruit-tree gardeners");
		o.add("• GEAR: Magic secateurs (+10% herb yield), full Farmer's outfit (XP), Ectophial/teleports");
		o.add("");
		o.add("TELEPORTS to carry: Ardougne cloak, Camelot tab, Explorer's ring, Ring of wealth,");
		o.add("  Xeric's talisman, Varrock tab, Home tele" + (guildHerbTree ? ", Skills necklace" : "")
			+ ", Spirit tree / Royal seed pod, Ectophial (Morytania), Icy basalt (Weiss), Quetzal whistle (Varlamore).");
		o.add("");
		o.add("STEPS — each stop: HARVEST → clear → ULTRACOMPOST → PLANT → PAY to protect:");
		int n = 1;
		o.add(n++ + ") Home tele → LUMBRIDGE: tree patch (" + tree + ").");
		o.add(n++ + ") Varrock tab → VARROCK palace: tree patch.");
		o.add(n++ + ") Explorer's ring → FALADOR: herb patch (" + herb + "). Then Ring of wealth → FALADOR PARK: tree patch.");
		o.add(n++ + ") Camelot tab → CATHERBY: herb + fruit tree + allotment.");
		o.add(n++ + ") Ardougne cloak → ARDOUGNE: herb + allotment + flower.");
		o.add(n++ + ") Xeric's talisman (Glade) → run S to HOSIDIUS: herb + allotment.");
		if (guildHerbTree)
			o.add(n++ + ") Skills necklace → FARMING GUILD: herb + tree" + (guildFruit ? " + fruit tree" : "") + " (needs 65 Farm).");
		o.add(n++ + ") Spirit tree / Royal seed pod → GNOME STRONGHOLD: fruit tree + tree.");
		o.add(n++ + ") Spirit tree → TREE GNOME VILLAGE: fruit tree.");
		o.add(n++ + ") Ectophial → CANIFIS (Morytania): herb — needs Nature Spirit (disease-free w/ Morytania Hard diary).");
		o.add(n++ + ") Digsite pendant → FOSSIL ISLAND: herb — needs Bone Voyage.");
		o.add(n++ + ") Icy basalt → WEISS: herb (disease-free) — needs Making Friends with My Arm.");
		o.add(n++ + ") Quetzal whistle → CIVITAS ILLA FORTIS (Varlamore): herb — needs Varlamore access.");
		o.add("");
		if (!guildBush) o.add("LOCKED: Farming Guild low tier (45 Farm = bush/allotments).");
		else if (!guildHerbTree) o.add("NEXT UNLOCK: 65 Farming = Farming Guild herb + tree patches (a big one-stop).");
		o.add("SKIP any stop you can't reach yet (Weiss/Varlamore/gnome = quest/access gated) — the rest still pays.");
		o.add("TIPS: ultracompost + Ardougne Medium diary = disease-free herbs. Resurrect Crops (78 Magic,");
		o.add("  Arceuus) revives a dead herb. NEVER skip paying a tree/fruit gardener — a dead sapling is a big loss.");
		o.add("CADENCE: herbs ~80 min, trees/fruit ~ once a day — so do the herb loop often, trees on the daily pass.");
		return o;
	}
}
