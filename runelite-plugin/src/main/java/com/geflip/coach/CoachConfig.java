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

	@ConfigSection(name = "Ask (LLM coach)", description = "Optional: ask a live-context question about your account.",
		position = 10, closedByDefault = true)
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
