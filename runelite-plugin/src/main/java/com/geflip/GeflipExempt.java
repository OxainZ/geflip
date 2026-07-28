package com.geflip;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * GE tax-exempt items — the full current list (45 items) from the OSRS Wiki
 * "Items exempt from Grand Exchange tax" category: bonds + starter tools, plus the May-2025 additions
 * (early-game ammo, mind rune, energy potions, the 13 low-tier foods, 8 teleport tablets, and the two
 * charged travel jewellery). A stale list here silently understates margin AND corrupts realized P&L on
 * high-volume staples (lobsters, arrows, teleports), so it's kept complete.
 *
 * Names are stored WITHOUT a charge/dose suffix; {@link #isExempt} strips a trailing "(n)" before matching
 * so every dose/charge variant that trades on the GE — Energy potion(1..4), Games necklace(8),
 * Ring of dueling(8) — resolves to its exempt base. Match is by lowercased mapping name.
 */
final class GeflipExempt
{
	static final Set<String> EXEMPT = new HashSet<>(Arrays.asList(
		// bond + starter tools
		"old school bond", "chisel", "gardening trowel", "glassblowing pipe", "hammer", "needle",
		"pestle and mortar", "rake", "saw", "secateurs", "seed dibber", "shears", "spade", "watering can",
		// early-game ammo + rune
		"bronze arrow", "iron arrow", "steel arrow", "bronze dart", "iron dart", "steel dart", "mind rune",
		// energy potion (all doses, via suffix strip)
		"energy potion",
		// low-tier foods
		"lobster", "salmon", "tuna", "shrimps", "bread", "cake", "cooked chicken", "cooked meat",
		"herring", "mackerel", "meat pie", "pike", "bass",
		// teleport tablets
		"varrock teleport", "lumbridge teleport", "falador teleport", "camelot teleport", "ardougne teleport",
		"kourend castle teleport", "civitas illa fortis teleport", "teleport to house",
		// charged travel jewellery (only the full-charge version trades; via suffix strip)
		"games necklace", "ring of dueling"));

	/** True if this GE mapping name is tax-exempt. Strips a trailing charge/dose suffix like "(8)" or "(4)". */
	static boolean isExempt(String name)
	{
		if (name == null) return false;
		String n = name.trim().toLowerCase().replaceFirst("\\s*\\(\\d+\\)$", "");
		return EXEMPT.contains(n);
	}

	private GeflipExempt() {}
}
