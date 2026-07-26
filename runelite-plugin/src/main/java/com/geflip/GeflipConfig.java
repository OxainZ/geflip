package com.geflip;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("geflip")
public interface GeflipConfig extends Config
{
	@ConfigItem(
		keyName = "autoBankroll",
		name = "Auto bankroll (my coins)",
		description = "Size flips from the coins you ACTUALLY have (inventory + bank when it's been "
			+ "opened this session), instead of the number below. Turn off to cap it manually.",
		position = 0
	)
	default boolean autoBankroll() { return true; }

	@ConfigItem(
		keyName = "bankroll",
		name = "Bankroll (m)",
		description = "Manual fallback, in millions — used only when Auto bankroll is off or your "
			+ "coins aren't readable yet. Drives quantity sizing.",
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
		description = "How many top flips to list in the panel — more = more opportunities to scan.",
		position = 5
	)
	default int rows() { return 30; }

	@ConfigItem(
		keyName = "dumpAlerts",
		name = "Crash alerts (held items)",
		description = "Notify when something you're HOLDING crashes below your buy price, so you "
			+ "can cut it before it falls further. Respects your RuneLite notification settings.",
		position = 6
	)
	default boolean dumpAlerts() { return true; }

	@ConfigItem(
		keyName = "useTrends",
		name = "Death-spiral filter",
		description = "Haircut items in a steep long-term decline, using geflip's data/trends.json.",
		position = 6
	)
	default boolean useTrends() { return true; }

	@ConfigItem(
		keyName = "hideFalling",
		name = "Hide falling items",
		description = "DON'T recommend items whose price is actively dropping this hour (>3%). These "
			+ "are the 'green now, red after you buy' traps — the margin looks real but the price "
			+ "keeps falling, so your sell ends up below your buy. Turn off to see them (flagged ⚠).",
		position = 61
	)
	default boolean hideFalling() { return true; }

	@ConfigItem(
		keyName = "hideSpikes",
		name = "Hide spike margins",
		description = "DON'T recommend a margin far wider than the item's own 24h norm (>3x). A spread "
			+ "that big is almost always a transient spike that collapses before you can sell it.",
		position = 62
	)
	default boolean hideSpikes() { return true; }

	@ConfigItem(
		keyName = "safeMode",
		name = "Safe mode",
		description = "Only show flips that are clean — hide anything flagged won't-fill (⏳), volatile "
			+ "(⚡) or in long-term decline (⚠). Fewer rows, but every one is a low-risk round-trip.",
		position = 63
	)
	default boolean safeMode() { return false; }

	@Range(min = 60, max = 900)
	@ConfigItem(
		keyName = "refreshSec",
		name = "Auto-refresh (s)",
		description = "How often to re-pull prices and re-rank. Be kind to the wiki API.",
		position = 7
	)
	default int refreshSec() { return 120; }

	@ConfigItem(
		keyName = "excludeItems",
		name = "Not-a-flip items",
		description = "Comma-separated item names you BUY to use, not to resell (e.g. "
			+ "\"Prayer potion(4), Nature rune, Cannonball\"). Their buys are kept out of "
			+ "your flip P&L so a bulk purchase you need doesn't look like a loss.",
		position = 8
	)
	default String excludeItems() { return ""; }

	@Range(min = 1, max = 48)
	@ConfigItem(
		keyName = "staleHours",
		name = "Stale offer (h)",
		description = "Flag an open buy/sell offer as stale once it's gone this many hours "
			+ "without filling — a sign the price moved and you should reprice.",
		position = 9
	)
	default int staleHours() { return 4; }

	@ConfigItem(
		keyName = "bridgeEnabled",
		name = "Local bridge (sync)",
		description = "Serve the geflip web UI + your live fills on your network. Open "
			+ "http://<this-pc-ip>:<port> on your phone (same wifi) for a synced, live UI.",
		position = 10
	)
	default boolean bridgeEnabled() { return false; }

	@Range(min = 1024, max = 65535)
	@ConfigItem(
		keyName = "bridgePort",
		name = "Bridge port",
		description = "TCP port for the local bridge.",
		position = 11
	)
	default int bridgePort() { return 7777; }

	@ConfigItem(
		keyName = "bridgeToken",
		name = "Bridge token",
		description = "Optional shared secret. If set, the phone URL needs ?t=<token>. "
			+ "Leave blank to allow anyone on your wifi (fine at home).",
		position = 12
	)
	default String bridgeToken() { return ""; }

	@ConfigItem(
		keyName = "cloudUrl",
		name = "Cloud sync URL",
		description = "Your geflip-sync Worker URL (blank = off). Pushes your fills so the "
			+ "web app syncs anywhere, even on mobile data.",
		position = 13
	)
	default String cloudUrl() { return ""; }

	@ConfigItem(
		keyName = "cloudId",
		name = "Cloud sync id",
		description = "The SAME sync-id you set in the web app. It's the only secret — keep it private.",
		position = 14
	)
	default String cloudId() { return ""; }
}
