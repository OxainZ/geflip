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
	private static final Object[][] SPECIAL = {
		{"Cactus", 55}, {"Calquat", 72}, {"Spirit tree", 83}, {"Celastrus", 85}, {"Redwood", 90} };

	private static String pick(Object[][] tbl, int lvl)
	{
		String best = null; int nextName = -1; String next = null;
		for (Object[] row : tbl)
		{
			int req = (int) row[1];
			if (lvl >= req) best = (String) row[0];
			else if (next == null) { next = (String) row[0]; nextName = req; }
		}
		if (best == null) return next != null ? "none yet (first is " + next + " @ " + nextName + ")" : "—";
		return best + (next != null ? "  (next: " + next + " @ " + nextName + ")" : "  (max tier)");
	}

	static List<String> plan(int farmingLevel)
	{
		List<String> out = new ArrayList<>();
		out.add("Your Farming: " + farmingLevel + ". Plant the highest tier you meet; rotate a run every ~80 min.");
		out.add("");
		out.add("HERBS → " + pick(HERBS, farmingLevel));
		out.add("  patches: Ardougne (Ardy cloak), Catherby (Camelot tele), Falador (Explorer's ring),");
		out.add("  Hosidius (Xeric's talisman), Farming Guild (Skills necklace, 45 Farm), Weiss/Troll (quests).");
		out.add("  ranarr/snapdragon/torstol = the money herbs; ALWAYS ultracompost + pay/protect.");
		out.add("");
		out.add("TREES → " + pick(TREES, farmingLevel));
		out.add("  patches: Lumbridge, Varrock, Falador, Taverley, Gnome Stronghold, Farming Guild.");
		out.add("  huge XP — plant the best you can, pay the farmer to protect, come back next run.");
		out.add("");
		out.add("FRUIT TREES → " + pick(FRUIT, farmingLevel));
		out.add("  patches: Gnome Stronghold, Tree Gnome Village, Brimhaven, Catherby, Lletya, Farming Guild.");
		out.add("  (use spirit tree / royal seed pod to hop between the gnome patches fast.)");
		out.add("");
		out.add("BUSHES → " + pick(BUSH, farmingLevel));
		out.add("  patches: Champions' Guild, Rimmington, Etceteria, Ardougne.");
		out.add("");
		out.add("SPECIAL → " + pick(SPECIAL, farmingLevel));
		out.add("  Hespori (anima cave, no level), Calquat (Tai Bwo Wannai), Cactus (Al Kharid), Redwood (Farming Guild).");
		out.add("");
		out.add("RUN KIT: ultracompost / bottomless bucket, Magic secateurs, full Farmer's outfit (yield+XP),");
		out.add("  teleport tablets for each patch, and Resurrect Crops (Arceuus) to save a dead herb.");
		out.add("DIARIES that help: Ardougne (disease-free herb), Falador, Kandarin, Kourend — do these when you can.");
		if (farmingLevel < 45) out.add("NEXT UNLOCK: 45 Farming opens the Farming Guild (mid tier) — a big convenience jump.");
		else if (farmingLevel < 65) out.add("NEXT UNLOCK: 65 Farming opens the Farming Guild high tier (herb+tree+fruit in one spot).");
		return out;
	}
}
