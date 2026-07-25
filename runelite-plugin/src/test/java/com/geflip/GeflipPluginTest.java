package com.geflip;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * The dev launcher. RuneLite's client has no "sideloaded-plugins" folder — a custom
 * external plugin is registered in-process with ExternalPluginManager.loadBuiltin()
 * and then the normal client is started. Run via `gradlew run` (see build.gradle);
 * it launches the full RuneLite client with Geflip loaded, authenticating from
 * .runelite/credentials.properties (no --developer-mode needed with this path).
 */
public class GeflipPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(GeflipPlugin.class);
		RuneLite.main(args);
	}
}
