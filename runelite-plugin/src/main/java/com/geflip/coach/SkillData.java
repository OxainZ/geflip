package com.geflip.coach;

import net.runelite.api.Skill;
import com.geflip.coach.CoachSkillPlan.Band;

/**
 * The per-skill 1→99 method bands, transcribed from the deep-research passes (wiki-sourced 2026 meta).
 * XP/hr = the mainstream-efficient figure (realistic, not the tick-perfect ceiling); cost = a short
 * money tag so the trainer can flag gp sinks vs profit. Split out from CoachSkillPlan so data is one file.
 */
final class SkillData
{
	private SkillData() {}

	private static Band b(int min, String method, int xpHr, String cost, String note)
	{ return new Band(min, method, xpHr, cost, note); }

	static void load()
	{
		// ================= ARTISAN =================
		CoachSkillPlan.register(Skill.SMITHING,
			b(1,  "Quests (Knight's Sword, Giant Dwarf, RFD) → anvil iron", 40_000, "cheap", "~37k quest XP skips the worst grind"),
			b(40, "Blast Furnace — gold bars (goldsmith gauntlets)", 360_000, "~18M sink", "fastest; click-heavy. AFK+profit alt: steel cannonballs (~15k/hr)"));
		CoachSkillPlan.register(Skill.CRAFTING,
			b(1,  "Leather → cut sapphires/emeralds", 100_000, "cheap", "bridge to gems"),
			b(27, "Cut rubies → diamonds", 250_000, "small loss", "fast, cheap-ish gem cutting"),
			b(63, "Dragonhide bodies (green→blue→red→black)", 380_000, "expensive", "fastest; or glassblow ~100k/hr for cheap/AFK"));
		CoachSkillPlan.register(Skill.FLETCHING,
			b(1,  "Arrow shafts → oak shortbows", 30_000, "cheap", "bridge"),
			b(35, "Willow → maple longbows (cut + string)", 75_000, "profit", "semi-AFK, profitable"),
			b(70, "Yew longbows (cut + string)", 202_000, "profit", "very profitable, semi-AFK"),
			b(85, "Magic longbows (cut + string)", 247_000, "profit", "endgame default; darts = fastest but pricey"));
		CoachSkillPlan.register(Skill.CONSTRUCTION,
			b(1,  "Crude chairs → wooden bookcases", 40_000, "cheap", "bridge to 33"),
			b(33, "Oak larders (Kitchen) — hire the Demon Butler at 50!", 480_000, "~-95M budget", "cheapest efficient path; needs a servant"),
			b(52, "Mahogany tables (Dining room)", 900_000, "~150M sink", "fast; ~-14 gp/xp. Servant's money bag auto-pays"),
			b(77, "Gnome benches (Superior garden, mahogany)", 1_000_000, "~150-180M", "fastest; Construction is the priciest skill"));
		CoachSkillPlan.register(Skill.FIREMAKING,
			b(1,  "Burn normal→willow→maple logs / bonfires", 120_000, "cheap-ish", "bridge to Wintertodt at 50"),
			b(50, "Wintertodt (minigame)", 260_000, "PROFIT", "the meta: profitable + Pyromancer outfit (+2.5%) + WC xp, AFK-ish"));
		CoachSkillPlan.register(Skill.COOKING,
			b(1,  "Cook trout/salmon or start jugs of wine", 250_000, "cheap", "near-free bridge"),
			b(35, "Jugs of wine (fastest) OR fish on a range (AFK profit)", 480_000, "cheap/profit", "wine fastest; range fish lobster→anglerfish = AFK profit"));

		// ================= COMBAT =================
		// Attack/Strength/Defence share monsters + XP/hr — only the attack STYLE differs.
		CoachSkillPlan.register(Skill.ATTACK,
			b(1,  "Waterfall Quest → sand crabs (Accurate style)", 40_000, "free/cheap", "Waterfall = instant 30 Att+Str, no combat; then AFK crabs"),
			b(50, "Ammonite crabs / NMZ absorptions", 90_000, "cheap", "AFK; NMZ needs 5 quests + absorption pots"),
			b(70, "Nightmare Zone — Dharok's", 110_000, "moderate", "fastest AFK. Sulphur Naguas ~150k = faster active (48 Slayer)"));
		CoachSkillPlan.register(Skill.STRENGTH,
			b(1,  "Waterfall Quest → sand crabs (Aggressive style)", 40_000, "free/cheap", "instant 30 Str; then AFK crabs"),
			b(50, "Ammonite crabs / NMZ absorptions (Aggressive)", 90_000, "cheap", "AFK"),
			b(70, "NMZ — Dharok's (Aggressive)", 110_000, "moderate", "fastest AFK; Sulphur Naguas ~150k active"));
		CoachSkillPlan.register(Skill.DEFENCE,
			b(1,  "Sand crabs (Defensive style)", 40_000, "cheap", "AFK; set attack style to Defensive"),
			b(60, "NMZ (Defensive) / Sulphur Naguas", 100_000, "moderate", "AFK NMZ or active Naguas"));
		CoachSkillPlan.register(Skill.HITPOINTS,
			b(1,  "Trains passively from ALL combat — no standalone method", 0, "free", "rises automatically (~1.33 HP xp per damage); do your fastest combat"));
		CoachSkillPlan.register(Skill.RANGED,
			b(1,  "Sand crabs (darts) / cannon", 40_000, "cheap", "cannon expensive; crabs cheap + AFK"),
			b(45, "Chinning maniacal monkeys (MM2)", 400_000, "expensive", "FASTEST ranged xp; pricey chins. Cheaper AFK: Venator bow NMZ ~160k"),
			b(70, "Chinning (fastest) or Venator bow NMZ (AFK)", 500_000, "expensive", "black chins scale toward ~1M/hr; NMZ venator ~160k is far cheaper"));
		CoachSkillPlan.register(Skill.MAGIC,
			b(1,  "Splashing (AFK) or low/high alch", 40_000, "cheap", "splash = click every ~20 min, near-zero attention"),
			b(55, "High/tele-alch (profit); bursting monkeys unlocks at 62", 150_000, "profit/expensive", "alching profits now; Smoke Burst at 62, Ice Burst at 70"),
			b(62, "Smoke Burst maniacal monkeys (fastest xp)", 280_000, "expensive", "Ice Burst at 70, Ice Barrage at 94; stun-alch ~200k if you want profit"));
		CoachSkillPlan.register(Skill.PRAYER,
			b(1,  "Quests → then altar with bones", 30_000, "free", "quests get you to ~30 free"),
			b(30, "Chaos altar (dragon bones) = best value, or Gilded altar = fastest", 700_000, "big sink", "Chaos halves cost (PvP risk); Gilded needs 75 Con. Ensouled heads = profit but slow"),
			b(70, "Gilded/Chaos altar — superior dragon bones", 1_100_000, "big sink", "top rate; tens of M gp. Chaos altar ~half the cost"));

		// ================= SUPPORT =================
		CoachSkillPlan.register(Skill.HERBLORE,
			b(1,  "Quest (Druidic Ritual) → attack/antipoison potions", 80_000, "cheap", "Druidic Ritual unlocks Herblore"),
			b(38, "Prayer potions", 219_000, "PROFIT", "~+5.6k gp/pot — the money-maker band, do NOT skip"),
			b(45, "Super attack → super energy/strength", 250_000, "cheap", ""),
			b(63, "Super restore potions", 356_000, "moderate", ""),
			b(81, "Saradomin brews → super combats (90)", 450_000, "expensive", "fastest; ~100-200M to 99 but the profit bands recoup a lot"));
		CoachSkillPlan.register(Skill.RUNECRAFT,
			b(1,  "Quest (Temple of the Eye) → GOTR", 40_000, "cheap", "bridge into GOTR"),
			b(27, "Guardians of the Rift (GOTR) minigame", 55_000, "PROFIT", "the meta: AFK-ish, profit + Raiments outfit + colossal pouch"),
			b(77, "Blood runes (Arceuus, Dark Altar)", 42_000, "PROFIT", "AFK; ~0.5-1M gp/hr + bonus Mining/Crafting xp"),
			b(90, "Soul runes (Arceuus) — AFK profit; or Aether+runners = fastest", 44_000, "PROFIT", "buying xp via runners ~15M/hr if rushing"));
		CoachSkillPlan.register(Skill.AGILITY,
			b(1,  "Rooftops: Gnome → Draynor → Al Kharid → Varrock → Canifis", 13_000, "profit", "Marks of Grace (→ graceful outfit)"),
			b(50, "Falador rooftop, or Hallowed Sepulchre (52, the meta)", 45_000, "profit", "Sepulchre (Sins of the Father) = fastest AND profitable"),
			b(60, "Hallowed Sepulchre (F3+) / Seers' Village rooftop", 60_000, "profit", "Sepulchre beats rooftops from here"),
			b(80, "Hallowed Sepulchre F4-5 / Ardougne rooftop", 85_000, "profit", "Sepulchre F5 ~98k + best loot; Ardy rooftop ~70k"));
		CoachSkillPlan.register(Skill.THIEVING,
			b(1,  "Men → cakes → fruit stalls", 30_000, "profit", "bridge to blackjacking"),
			b(45, "Blackjack Bandits (Pollnivneach), or Ardy Knights (Hard Diary)", 100_000, "profit", "blackjack fastest; Knights = AFK-friendlier profit"),
			b(65, "Blackjack Menaphite Thugs (fastest), or Stealing Artefacts", 240_000, "profit", "Artefacts (~180k) is AFK-friendlier"),
			b(84, "Rogues' Castle chests (Wildy) — fastest + ~2.5M/hr", 285_000, "PROFIT", "PvP risk; or keep Menaphite blackjacking"));
		CoachSkillPlan.register(Skill.SLAYER,
			b(1,  "Turael/Spria (easy tasks + skip bad ones) → Mazchna", 15_000, "profit", "combat-based — master choice tracks your COMBAT level, not this number"),
			b(40, "Chaeldar (needs Lost City) — extend/cannon tasks", 30_000, "profit", "10 pts/task"),
			b(75, "Konar (brimstone keys) or Nieve (convenient)", 45_000, "PROFIT", "Konar = best drops; barrage AoE tasks push xp"),
			b(90, "Duradel (100 cmb + 50 Slayer) — barrage nechryael/dust devils", 60_000, "PROFIT", "efficiency king; among the most profitable skills"));

		// ================= GATHERING =================
		CoachSkillPlan.register(Skill.MINING,
			b(1,  "Copper/tin → iron powermining (drop-mine)", 45_000, "cheap", "3×3 triangle spots (Varrock/Al Kharid); cost-neutral"),
			b(45, "3-tick granite (fastest) or Motherlode Mine (AFK+profit)", 116_000, "cheap/profit", "3-tick ~116-132k click-heavy; MLM ~50k AFK + profit"),
			b(75, "3-tick granite / Blast Mine (profit) / Amethyst 92+ (AFK profit)", 125_000, "cheap/profit", "Blast Mine ~90k + profit; amethyst 92+ AFK profit"));
		CoachSkillPlan.register(Skill.FISHING,
			b(1,  "Shrimp → fly fishing trout/salmon", 40_000, "cheap", "fly rod + feathers; AFK or 3-tick"),
			b(58, "Barbarian fishing (with Otto) — 3-tick", 90_000, "cheap", "THE method; passive Agility + Strength xp. AFK ~50k / 3-tick ~110k"),
			b(82, "Barb fishing (fastest) or Anglerfish/Karambwan (AFK profit)", 110_000, "cheap/profit", "3-tick barb ~110k; anglerfish/karambwan = low-effort profit"));
		CoachSkillPlan.register(Skill.WOODCUTTING,
			b(1,  "Regular → oak trees", 40_000, "profit", "oak = AFK + small profit"),
			b(35, "Teak 2-tick (fastest) / AFK teak (profit)", 150_000, "cheap/profit", "2-tick teak ~150-215k click-heavy; AFK teak ~60-90k + profit"),
			b(65, "Teak 2-tick / Sulliuscep (best AFK ~100k)", 200_000, "cheap/profit", "sulliuscep 65+ AFK ~100k; redwood 90+ AFK profit"));
		CoachSkillPlan.register(Skill.HUNTER,
			b(1,  "Nat. History quiz → bird snaring → salamanders", 30_000, "cheap", "the quiz = instant ~1k xp skip"),
			b(47, "Maniacal monkeys (red chins, MM2) — AFK-ish deadfall", 80_000, "cheap", "needs Monkey Madness II"),
			b(73, "Black chinchompas (Wildy, profit) or Hunters' Rumours (safe)", 200_000, "PROFIT", "chins ~150-250k + 1.5-2.5M/hr (PvP risk); Rumours ~195-250k safe; herbiboar 80+ ~150k AFK + Herblore xp"));
	}
}
