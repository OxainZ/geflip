package com.geflip;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("geflip")
public interface GeflipConfig extends Config
{
	@ConfigItem(
		keyName = "bankroll",
		name = "Bankroll (m)",
		description = "Gold you have to deploy, in millions. Drives quantity sizing.",
		position = 1
	)
	default int bankrollM() { return 50; }

	@ConfigItem(
		keyName = "members",
		name = "Members items",
		description = "Include members-only items. Turn off on an F2P world/account.",
		position = 2
	)
	default boolean members() { return true; }

	@Range(min = 1, max = 500)
	@ConfigItem(
		keyName = "minVol1h",
		name = "Min 1h volume",
		description = "Skip items thinner than this in the last hour (both legs).",
		position = 3
	)
	default int minVol1h() { return 20; }

	@ConfigItem(
		keyName = "minMargin",
		name = "Min margin (gp)",
		description = "Skip anything with a net-of-tax margin below this.",
		position = 4
	)
	default int minMargin() { return 1; }

	@Range(min = 5, max = 50)
	@ConfigItem(
		keyName = "rows",
		name = "Rows shown",
		description = "How many top flips to list in the panel.",
		position = 5
	)
	default int rows() { return 20; }

	@ConfigItem(
		keyName = "useTrends",
		name = "Death-spiral filter",
		description = "Haircut items in a steep long-term decline, using geflip's data/trends.json.",
		position = 6
	)
	default boolean useTrends() { return true; }

	@Range(min = 60, max = 900)
	@ConfigItem(
		keyName = "refreshSec",
		name = "Auto-refresh (s)",
		description = "How often to re-pull prices and re-rank. Be kind to the wiki API.",
		position = 7
	)
	default int refreshSec() { return 120; }
}
