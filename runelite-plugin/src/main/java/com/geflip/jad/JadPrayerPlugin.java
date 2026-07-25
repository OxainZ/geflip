package com.geflip.jad;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.SpriteID;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Shows which overhead prayer to use as Jad attacks, in the Fight Caves (TzTok-Jad)
 * and the Inferno (JalTok-Jad). It reacts to Jad's attack ANIMATION — the same tell a
 * player watches for — and flashes "PRAY MAGE" or "PRAY RANGE".
 *
 * READ-ONLY by construction: it only draws an indicator. It never clicks a prayer or
 * sends any input — auto-praying would be macroing. You still flick the prayer yourself.
 *
 * RULES NOTE: Jagex's Third-Party Client Guidelines classify "prayer switching indicators"
 * and "next-attack prediction" as disallowed — which is why official RuneLite removed its
 * Fight Cave plugin and the plugin-hub won't host one (the equivalent lives only in the
 * OpenOSRS fork). This is a private/local build; running a prayer-caller is against those
 * guidelines and is the user's own risk. Detection is animation-based (verified IDs).
 */
@Slf4j
@PluginDescriptor(
	name = "Jad Prayer Helper",
	description = "Flashes the overhead prayer to use as Jad attacks (Fight Caves / Inferno). Indicator only — never prays for you.",
	tags = {"jad", "fight", "cave", "inferno", "prayer", "tzhaar", "pvm", "flick"}
)
public class JadPrayerPlugin extends Plugin
{
	// Attack animations — VERIFIED against RuneLite/OpenOSRS AnimationID.java. Every real
	// plugin detects Jad's style from the animation (there is no reliable projectile tell).
	private static final int TZTOK_JAD_MAGE = 2656;
	private static final int TZTOK_JAD_RANGE = 2652;
	private static final int JALTOK_JAD_MAGE = 7592;
	private static final int JALTOK_JAD_RANGE = 7593;
	// Reaction window: the prayer must be up by animation-tick + this many ticks for the
	// hit to be blocked (matches the maintained community plugins). Counted down per tick.
	private static final int TZTOK_JAD_HIT_TICKS = 2;   // Fight Caves
	private static final int JALTOK_JAD_HIT_TICKS = 3;  // Inferno

	public enum Attack { MAGE, RANGE }

	@Inject private OverlayManager overlayManager;
	@Inject private SpriteManager spriteManager;
	@Inject private JadPrayerConfig config;
	@Inject private JadPrayerOverlay overlay;

	private final Set<NPC> jads = new HashSet<>();
	private final Set<NPC> healers = new HashSet<>();
	public volatile Attack attack;             // the prayer you should be on right now (null = none)
	public volatile NPC activeJad;             // a live Jad to draw over, if any
	public volatile long switchedAtMs;         // when the attack last changed (for the flash)
	public volatile int hitTicks;              // ticks until the current attack lands (0 = landed)
	public volatile boolean healersUp;         // Yt-HurKot / Jal-MejRah healers are alive

	public BufferedImage mageSprite, rangeSprite;

	@Provides
	JadPrayerConfig provideConfig(ConfigManager cm) { return cm.getConfig(JadPrayerConfig.class); }

	@Override
	protected void startUp()
	{
		spriteManager.getSpriteAsync(SpriteID.PRAYER_PROTECT_FROM_MAGIC, 0, img -> mageSprite = img);
		spriteManager.getSpriteAsync(SpriteID.PRAYER_PROTECT_FROM_MISSILES, 0, img -> rangeSprite = img);
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		jads.clear();
		healers.clear();
		attack = null;
		activeJad = null;
		hitTicks = 0;
		healersUp = false;
	}

	private static boolean isJad(NPC npc)
	{
		if (npc == null) return false;
		String n = npc.getName();
		return n != null && n.toLowerCase().contains("jad");
	}

	/** Yt-HurKot (Fight Caves) and Jal-MejRah (Inferno) are the healers. */
	private static boolean isHealer(NPC npc)
	{
		if (npc == null) return false;
		String n = npc.getName();
		if (n == null) return false;
		n = n.toLowerCase();
		return n.contains("hurkot") || n.contains("mejrah");
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned e)
	{
		if (isJad(e.getNpc())) { jads.add(e.getNpc()); activeJad = e.getNpc(); }
		else if (isHealer(e.getNpc())) { healers.add(e.getNpc()); healersUp = true; }
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		if (jads.remove(e.getNpc()))
		{
			if (jads.isEmpty()) { attack = null; activeJad = null; hitTicks = 0; }
			else if (e.getNpc() == activeJad) activeJad = jads.iterator().next();
		}
		if (healers.remove(e.getNpc())) healersUp = !healers.isEmpty();
	}

	// count the reaction window down each game tick (600ms); floor at 0
	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (hitTicks > 0) hitTicks--;
	}

	/** Ticks until the current attack lands (-1 once it's landed / no attack) — for the countdown. */
	public int ticksToHit()
	{
		return (attack != null && hitTicks > 0) ? hitTicks : -1;
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		if (!(e.getActor() instanceof NPC)) return;
		NPC npc = (NPC) e.getActor();
		if (!isJad(npc)) return;
		int anim = npc.getAnimation();
		if (config.debug()) log.info("Jad id={} animation={}", npc.getId(), anim);

		Attack a = null;
		boolean inferno = false;
		if (anim == TZTOK_JAD_MAGE) a = Attack.MAGE;
		else if (anim == TZTOK_JAD_RANGE) a = Attack.RANGE;
		else if (anim == JALTOK_JAD_MAGE) { a = Attack.MAGE; inferno = true; }
		else if (anim == JALTOK_JAD_RANGE) { a = Attack.RANGE; inferno = true; }
		if (a != null)
		{
			attack = a;
			activeJad = npc;
			switchedAtMs = System.currentTimeMillis();
			hitTicks = inferno ? JALTOK_JAD_HIT_TICKS : TZTOK_JAD_HIT_TICKS;   // reaction window
		}
	}

	/** True for a short window right after Jad switches attack — drives the flash. */
	public boolean flashing()
	{
		return config.flash() && System.currentTimeMillis() - switchedAtMs < 700;
	}
}
