package com.geflip.coach;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("geflipcoach")
public interface CoachConfig extends Config
{
	@ConfigItem(keyName = "refreshSec", name = "Refresh (sec)",
		description = "How often to re-read your account and re-rank goals.", position = 1)
	default int refreshSec() { return 20; }

	@ConfigItem(keyName = "farmingHelper", name = "Farming run helper",
		description = "Show a Farm tab: what to plant in each patch for your level, where the patches "
			+ "are + how to teleport. Off by default — turn it on only when you're doing a farm run.",
		position = 2)
	default boolean farmingHelper() { return false; }

	@ConfigItem(keyName = "unlockAlerts", name = "Goal-unlock alerts",
		description = "Ping me the moment a goal becomes available — e.g. the instant you hit 55 Slayer "
			+ "for the slayer helm, or finish a quest that opens the next one. Proactive, not just a panel.",
		position = 3)
	default boolean unlockAlerts() { return true; }

	@ConfigItem(keyName = "focusGoal", name = "Focus goal (Path tab)",
		description = "Type a goal name (e.g. 'Vorkath' or 'Dizana') to see the full ordered path to it — "
			+ "every skill to train and quest to do, in order, with live ETAs. Blank = auto-pick your "
			+ "highest-impact blocked goal.",
		position = 4)
	default String focusGoal() { return ""; }

	@ConfigItem(keyName = "webhookUrl", name = "Discord webhook (alerts)",
		description = "Optional: a Discord webhook URL. Goal unlocks + big milestones get pushed here so "
			+ "you see them on your phone even with the game closed. Blank = off.",
		position = 5)
	default String webhookUrl() { return ""; }

	@ConfigSection(name = "Phone sync", description = "Read your Coach on your phone over your own wifi.",
		position = 8, closedByDefault = true)
	String phone = "phone";

	@ConfigItem(keyName = "phoneSync", name = "Serve Coach to phone", section = phone, position = 8,
		description = "Start a tiny local web page (on this PC, your wifi only) that shows your live Coach. "
			+ "Open http://<this-pc-ip>:<port>/?t=<token> in your phone browser. READ-ONLY toward the game.")
	default boolean phoneSync() { return false; }

	@ConfigItem(keyName = "phonePort", name = "Phone sync port", section = phone, position = 9,
		description = "Port for the phone page (default 7778). Must differ from the flipper's bridge port.")
	default int phonePort() { return 7778; }

	@ConfigItem(keyName = "phoneToken", name = "Phone sync token", section = phone, position = 10, secret = true,
		description = "A password appended as ?t=… so only you can read it. Blank = anyone on your wifi can.")
	default String phoneToken() { return ""; }

	@ConfigSection(name = "Ask (LLM coach)", description = "Optional: ask a live-context question about your account.",
		position = 11, closedByDefault = true)
	String ask = "ask";

	@ConfigItem(keyName = "askUrl", name = "LLM endpoint URL", section = ask, position = 11,
		description = "An OpenAI/Anthropic-compatible chat-completions URL. Leave blank to use the "
			+ "Copy-context button instead (paste into Claude yourself — zero setup).")
	default String askUrl() { return ""; }

	@ConfigItem(keyName = "askKey", name = "API key", section = ask, position = 12, secret = true,
		description = "Bearer token for the endpoint above. Stored in your RuneLite profile.")
	default String askKey() { return ""; }

	@ConfigItem(keyName = "askModel", name = "Model", section = ask, position = 13,
		description = "Model id to send (endpoint-dependent).")
	default String askModel() { return "claude-sonnet-4-5"; }
}
