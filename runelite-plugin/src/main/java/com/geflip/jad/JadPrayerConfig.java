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
		keyName = "overHead",
		name = "Symbols over monsters",
		description = "Pop the correct prayer symbol above each monster's head (Jad updates live).",
		position = 2
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
		keyName = "testShow",
		name = "Test — always show",
		description = "Draw a sample indicator even when no Jad is present, so you can confirm "
			+ "it works and position it. Turn OFF before your real run.",
		position = 8
	)
	default boolean testShow() { return false; }

	@ConfigItem(
		keyName = "debug",
		name = "Log Jad animations",
		description = "Print every Jad animation id to the log. Turn on ONLY if the indicator "
			+ "doesn't fire — then tell me the numbers and I'll fix the attack ids.",
		position = 9
	)
	default boolean debug() { return false; }
}
