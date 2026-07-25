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
 * sends any input — auto-praying would be macroing (a ban). You still flick the prayer
 * yourself; this just makes the tell impossible to miss.
 */
@Slf4j
@PluginDescriptor(
	name = "Jad Prayer Helper",
	description = "Flashes the overhead prayer to use as Jad attacks (Fight Caves / Inferno). Indicator only — never prays for you.",
	tags = {"jad", "fight", "cave", "inferno", "prayer", "tzhaar", "pvm", "flick"}
)
public class JadPrayerPlugin extends Plugin
{
	// TzTok-Jad (Fight Caves) attack animations
	private static final int TZTOK_JAD_MAGE = 2656;
	private static final int TZTOK_JAD_RANGE = 2652;
	// JalTok-Jad (Inferno) attack animations (best-effort; verify with the debug toggle)
	private static final int JALTOK_JAD_MAGE = 7592;
	private static final int JALTOK_JAD_RANGE = 7593;

	public enum Attack { MAGE, RANGE }

	@Inject private OverlayManager overlayManager;
	@Inject private SpriteManager spriteManager;
	@Inject private JadPrayerConfig config;
	@Inject private JadPrayerOverlay overlay;

	private final Set<NPC> jads = new HashSet<>();
	public volatile Attack attack;         // the prayer you should be on right now (null = none)
	public volatile NPC activeJad;         // a live Jad to draw over, if any
	public volatile long switchedAtMs;     // when the attack last changed (for the flash)

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
		attack = null;
		activeJad = null;
	}

	private static boolean isJad(NPC npc)
	{
		if (npc == null) return false;
		String n = npc.getName();
		return n != null && n.toLowerCase().contains("jad");
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned e)
	{
		if (isJad(e.getNpc())) { jads.add(e.getNpc()); activeJad = e.getNpc(); }
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		jads.remove(e.getNpc());
		if (jads.isEmpty()) { attack = null; activeJad = null; }
		else if (e.getNpc() == activeJad) activeJad = jads.iterator().next();
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
		if (anim == TZTOK_JAD_MAGE || anim == JALTOK_JAD_MAGE) a = Attack.MAGE;
		else if (anim == TZTOK_JAD_RANGE || anim == JALTOK_JAD_RANGE) a = Attack.RANGE;
		if (a != null)
		{
			attack = a;
			activeJad = npc;
			switchedAtMs = System.currentTimeMillis();
		}
	}

	/** True for a short window right after Jad switches attack — drives the flash. */
	public boolean flashing()
	{
		return config.flash() && System.currentTimeMillis() - switchedAtMs < 700;
	}
}
