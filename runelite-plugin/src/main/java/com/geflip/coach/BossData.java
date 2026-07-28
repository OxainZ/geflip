package com.geflip.coach;

import com.geflip.coach.CoachDps.Boss;

/**
 * Curated boss defensive stats for the DPS calculator, transcribed from oldschool.runescape.wiki
 * "Combat stats" tables (deep-research pass). Split out so the data is one file.
 * Fields: defence level, then defensive bonuses (Stab, Slash, Crush, Ranged, Magic).
 */
final class BossData
{
	private BossData() {}

	// Boss(name, defLvl, dStab, dSlash, dCrush, dRange, dMagic, note) — wiki combat-stats, post-Jun-2025.
	static void load(java.util.List<Boss> b)
	{
		b.add(new Boss("Vorkath",            214,  26, 108, 108,  26, 240, "melee stab / ranged; high magic def"));
		b.add(new Boss("Zulrah",             300,   0,   0,   0,  50, -45, "green→mage, blue→range (per-phase resist)"));
		b.add(new Boss("Kraken",               1,   0,   0,   0, 300, 130, "magic (trident); huge ranged def"));
		b.add(new Boss("Giant Mole",         200,  60,  80, 100,  60,  80, "stab or ranged (lowest def)"));
		b.add(new Boss("Sarachnis",          150,  60,  40,  10, 300, 150, "crush (very low crush def)"));
		b.add(new Boss("Alchemical Hydra",   100,  75, 150, 150,  45, 150, "ranged (lowest def); 75% reduction per phase until vent"));
		b.add(new Boss("Demonic gorilla",    200,   0,   0,   0,   0,  20, "near-zero def; switches prayer — alternate styles"));
		b.add(new Boss("Corporeal Beast",    310,  25, 200, 100, 230, 150, "stab (corpbane weapon only) or crush"));
		b.add(new Boss("General Graardor",   250,  90,  90,  90,  90, 298, "any melee/ranged; avoid magic"));
		b.add(new Boss("K'ril Tsutsaroth",   270,  70,  80,  80,  80,  80, "stab lowest"));
		b.add(new Boss("Commander Zilyana",  300, 100, 100, 100,  75, 100, "ranged lowest"));
		b.add(new Boss("Kree'arra",          260, 180, 180, 180, 200, 200, "ranged only"));
		b.add(new Boss("The Nightmare",      150, 120, 180,  40, 600, 600, "crush (crush def +40)"));
		b.add(new Boss("Callisto",           225, 150, 130, 125,  50,   0, "magic def 0 → magic viable; crush/ranged too"));
		b.add(new Boss("Vet'ion",            395, 201, 200, -10, 270, 250, "crush (crush def −10); undead → Salve"));
		b.add(new Boss("Venenatis",          321, 100, 100,  10, 150, 300, "crush (crush def +10)"));
		b.add(new Boss("TzTok-Jad",          480,   0,   0,   0,   0,   0, "all def 0 → any style"));
		b.add(new Boss("Dagannoth Rex",      255, 255, 255, 255, 255,  10, "magic (magic def +10)"));
		b.add(new Boss("Dagannoth Prime",    255, 255, 255, 255,  10, 255, "ranged (ranged def +10)"));
		b.add(new Boss("Dagannoth Supreme",  128,  10,  10,  10, 550, 255, "melee (melee def +10)"));
		b.add(new Boss("Barrows brother",    100, 220, 230, 220, 220,   0, "magic (wind spells); melee ~high def"));
	}
}
