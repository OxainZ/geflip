package com.geflip.coach;

import com.geflip.coach.CoachDps.Boss;

/**
 * Curated boss defensive stats for the DPS calculator, transcribed from oldschool.runescape.wiki
 * "Combat stats" tables (deep-research pass). Split out so the data is one file.
 * Fields: defence level, then defensive bonuses (Stab, Slash, Crush, Ranged, Magic), Hitpoints,
 * and three flags the DPS engine needs for conditional multipliers:
 *   dragon  = draconic → Dragon-hunter weapons apply (DHCB/DHL)
 *   undead  = Salve amulet applies
 *   slayer  = a standard slayer / boss-slayer assignment target → a Slayer helm (i) applies ON-TASK
 * HP values were wiki-verified 2026-07-29 (several differed from prior guesses).
 */
final class BossData
{
	private BossData() {}

	// Boss(name, defLvl, dStab, dSlash, dCrush, dRange, dMagic, hp, dragon, undead, slayer, note)
	static void load(java.util.List<Boss> b)
	{
		b.add(new Boss("Vorkath",            214,  26, 108, 108,  26, 240,  750, true,  true,  true,  "melee stab / ranged; high magic def · undead+dragon → Salve & DH weapons stack"));
		b.add(new Boss("Zulrah",             300,   0,   0,   0,  50, -45,  500, false, false, false, "green→mage, blue→range (per-phase resist)"));
		b.add(new Boss("Kraken",               1,   0,   0,   0, 300, 130,  255, false, false, true,  "magic (trident); huge ranged def · 87 Slayer boss"));
		b.add(new Boss("Giant Mole",         200,  60,  80, 100,  60,  80,  200, false, false, true,  "stab or ranged (lowest def) · boss-slayer task"));
		b.add(new Boss("Sarachnis",          150,  60,  40,  10, 300, 150,  400, false, false, true,  "crush (very low crush def) · boss-slayer task"));
		b.add(new Boss("Alchemical Hydra",   100,  75, 150, 150,  45, 150, 1100, true,  false, true,  "ranged (lowest def); 95 Slayer · draconic → DH weapons apply; 25% dmg reduction per phase until vent"));
		b.add(new Boss("Demonic gorilla",    200,   0,   0,   0,   0,  20,  380, false, false, false, "near-zero def; switches prayer — alternate styles"));
		b.add(new Boss("Corporeal Beast",    310,  25, 200, 100, 230, 150, 2000, false, false, false, "stab (corpbane weapon only) or crush"));
		b.add(new Boss("General Graardor",   250,  90,  90,  90,  90, 298,  255, false, false, false, "any melee/ranged; avoid magic"));
		b.add(new Boss("K'ril Tsutsaroth",   270,  70,  80,  80,  80,  80,  255, false, false, false, "stab lowest"));
		b.add(new Boss("Commander Zilyana",  300, 100, 100, 100,  75, 100,  255, false, false, false, "ranged lowest"));
		b.add(new Boss("Kree'arra",          260, 180, 180, 180, 200, 200,  255, false, false, false, "ranged only"));
		b.add(new Boss("The Nightmare",      150, 120, 180,  40, 600, 600, 2400, false, false, false, "crush (crush def +40) · HP shown is 5-player base; solo TTK is approximate"));
		b.add(new Boss("Callisto",           225, 150, 130, 125,  50,   0, 1000, false, false, true,  "magic def 0 → magic viable; crush/ranged too · Wildy boss task"));
		b.add(new Boss("Vet'ion",            395, 201, 200, -10, 270, 250,  510, false, true,  true,  "crush (crush def −10); undead → Salve · 2 phases ×255 HP · Wildy boss task"));
		b.add(new Boss("Venenatis",          321, 100, 100,  10, 150, 300,  850, false, false, true,  "crush (crush def +10) · Wildy boss task"));
		b.add(new Boss("TzTok-Jad",          480,   0,   0,   0,   0,   0,  250, false, false, false, "all def 0 → any style"));
		b.add(new Boss("Dagannoth Rex",      255, 255, 255, 255, 255,  10,  255, false, false, true,  "magic (magic def +10) · Dagannoth task"));
		b.add(new Boss("Dagannoth Prime",    255, 255, 255, 255,  10, 255,  255, false, false, true,  "ranged (ranged def +10) · Dagannoth task"));
		b.add(new Boss("Dagannoth Supreme",  128,  10,  10,  10, 550, 255,  255, false, false, true,  "melee (melee def +10) · Dagannoth task"));
		b.add(new Boss("Barrows brother",    100, 220, 230, 220, 220,   0,  100, false, true,  false, "magic (wind spells); melee ~high def · undead → Salve (HP ~100, varies by brother)"));
	}
}
