package com.geflip.jad;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("jadprayer")
public interface JadPrayerConfig extends Config
{
	@Range(min = 1, max = 6)
	@ConfigItem(
		keyName = "scale",
		name = "Indicator size",
		description = "How big the centre prayer icon is drawn (×the sprite size).",
		position = 1
	)
	default int scale() { return 3; }

	@ConfigItem(
		keyName = "center",
		name = "Big icon in centre",
		description = "Draw the large prayer icon in the middle of the screen — hardest to miss.",
		position = 2
	)
	default boolean center() { return true; }

	@ConfigItem(
		keyName = "overHead",
		name = "Icon over Jad",
		description = "Also draw a small prayer icon above Jad's head.",
		position = 3
	)
	default boolean overHead() { return true; }

	@ConfigItem(
		keyName = "flash",
		name = "Flash on switch",
		description = "Briefly flash the icon the instant Jad changes its attack.",
		position = 4
	)
	default boolean flash() { return true; }

	@ConfigItem(
		keyName = "debug",
		name = "Log Jad animations",
		description = "Print every Jad animation id to the log. Turn on ONLY if the indicator "
			+ "doesn't fire — then tell me the numbers and I'll fix the attack ids.",
		position = 9
	)
	default boolean debug() { return false; }
}
