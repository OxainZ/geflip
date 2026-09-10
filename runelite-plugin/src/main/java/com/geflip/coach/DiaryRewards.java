package com.geflip.coach;

/**
 * Achievement-diary rewards, keyed diary + tier.
 *
 * The coach already READ diary progress from varbits, but only reported it - "Ardougne: Hard"
 * is a scoreboard, not advice. The reward is the entire reason to do the next tier, so it
 * belongs next to the progress.
 *
 * Scraped from each wiki diary page's per-tier Rewards section (api.php action=parse), NOT
 * written from memory. This is the FIRST reward the wiki lists for that tier, which is
 * usually the headline item but is not a curated "best" pick - Wilderness sword 3 really is
 * listed as "identical stats to a mithril sword" before its useful perks. Accurate over tidy.
 */
final class DiaryRewards
{
	private DiaryRewards() {}

	/** {diary, tier, item, headline benefit} - tier index matches CoachPlugin.TIER. */
	static final String[][] REWARDS = {
		{"Ardougne", "Easy", "Ardougne cloak 1", "Unlimited teleports to the Ardougne Monastery"},
		{"Ardougne", "Medium", "Ardougne cloak 2", "Three daily teleports to the farming patch at the Ardougne farm"},
		{"Ardougne", "Hard", "Ardougne cloak 3", "Five daily teleports to the farming patch at the Ardougne farm"},
		{"Ardougne", "Elite", "Ardougne cloak 4", "Unlimited teleports to the farming patch at the Ardougne farm"},
		{"Desert", "Easy", "Desert amulet 1", "No benefits aside from cosmetic purposes"},
		{"Desert", "Medium", "Desert amulet 2", "One daily teleport to Nardah"},
		{"Desert", "Hard", "Desert amulet 3", "No new benefits"},
		{"Desert", "Elite", "Desert amulet 4", "Unlimited teleports to Nardah, closer to the Elidinis Statuette "},
		{"Falador", "Easy", "Falador shield 1", "Can restore 25% of prayer points once a day"},
		{"Falador", "Medium", "Falador shield 2", "Can restore 50% of prayer points once a day"},
		{"Falador", "Hard", "Falador shield 3", "Can restore full prayer points once a day"},
		{"Falador", "Elite", "Falador shield 4", "Can restore full prayer points twice a day"},
		{"Fremennik", "Easy", "Fremennik sea boots 1", "One daily teleport to the Rellekka marketplace"},
		{"Fremennik", "Medium", "Fremennik sea boots 2", "Three daily teleports to the Rellekka marketplace"},
		{"Fremennik", "Hard", "Fremennik sea boots 3", "Five daily teleports to the Rellekka marketplace"},
		{"Fremennik", "Elite", "Fremennik sea boots 4", "Unlimited teleports to the Rellekka marketplace"},
		{"Kandarin", "Easy", "Kandarin headgear 1", "Acts as a light source when worn or held in the inventory"},
		{"Kandarin", "Medium", "Kandarin headgear 2", "No new benefits"},
		{"Kandarin", "Hard", "Kandarin headgear 3", "One daily teleport to Sherlock"},
		{"Kandarin", "Elite", "Kandarin headgear 4", "Unlimited teleports to Sherlock"},
		{"Karamja", "Easy", "Karamja gloves 1", "While worn, Brimhaven—Ardougne and Musa Point—Port Sarim boat tr"},
		{"Karamja", "Medium", "Karamja gloves 2", "While worn, 10% additional Agility experience from all obstacles"},
		{"Karamja", "Hard", "Karamja gloves 3", "Unlimited teleports to the underground portion of the Shilo Vill"},
		{"Karamja", "Elite", "Karamja gloves 4", "Unlimited teleports to Duradel/Kuradal"},
		{"Kourend", "Easy", "Rada's blessing 1", "Three daily teleports to the Kourend Woodland"},
		{"Kourend", "Medium", "Rada's blessing 2", "Five daily teleports to the Kourend Woodland"},
		{"Kourend", "Hard", "Rada's blessing 3", "Unlimited teleports to the Kourend Woodland"},
		{"Kourend", "Elite", "Rada's blessing 4", "Unlimited teleports to the top of Mount Karuulm"},
		{"Lumbridge", "Easy", "Explorer's ring 1", "Can restore 50% of run energy twice a day"},
		{"Lumbridge", "Medium", "Explorer's ring 2", "Can restore 50% of run energy three times a day"},
		{"Lumbridge", "Hard", "Explorer's ring 3", "Can restore 50% of run energy four times a day"},
		{"Lumbridge", "Elite", "Explorer's ring 4", "Can restore full run energy three times a day"},
		{"Morytania", "Easy", "Morytania legs 1", "Two daily teleports to the Pool of Slime beneath the Ectofuntus"},
		{"Morytania", "Medium", "Morytania legs 2", "Five daily teleports to the Pool of Slime beneath the Ectofuntus"},
		{"Morytania", "Hard", "Morytania legs 3", "Unlimited teleports to Burgh de Rott"},
		{"Morytania", "Elite", "Morytania legs 4", "Prevents ghasts from turning your food into rotten food when wor"},
		{"Varrock", "Easy", "Varrock armour 1", "While worn, 10% chance of mining double clay, limestone, guardia"},
		{"Varrock", "Medium", "Varrock armour 2", "While worn, 10% chance of mining double ores up to and including"},
		{"Varrock", "Hard", "Varrock armour 3", "Can be worn in place of a chef's hat to access the Cooks' Guild"},
		{"Varrock", "Elite", "Varrock armour 4", "Can be worn in place of a prospector jacket for clue steps and t"},
		{"Western", "Easy", "Western banner 1", "Depicts a chompy bird"},
		{"Western", "Medium", "Western banner 2", "Depicts King Awowogei"},
		{"Western", "Hard", "Western banner 3", "Depicts a gnome child"},
		{"Western", "Elite", "Western banner 4", "Depicts an elven pattern"},
		{"Wilderness", "Easy", "Wilderness sword 1", "Always slashes webs successfully"},
		{"Wilderness", "Medium", "Wilderness sword 2", "Identical stats to a steel sword"},
		{"Wilderness", "Hard", "Wilderness sword 3", "Identical stats to a mithril sword (only weighs negligibly more)"},
		{"Wilderness", "Elite", "Wilderness sword 4", "Identical stats to an adamant sword (aside from weighing negligi"},
	};

	/** Item + benefit for a diary tier, or null when unknown. */
	static String[] of(String diary, String tier)
	{
		for (String[] r : REWARDS)
			if (r[0].equals(diary) && r[1].equals(tier)) return new String[]{ r[2], r[3] };
		return null;
	}
}
