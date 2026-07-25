package com.geflip.jad;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * Shows the correct overhead prayer over EACH monster you fight in the Fight Caves /
 * Inferno. Fixed-style monsters (Tz-Kih melee, Tok-Xil range, Ket-Zek mage, …) get their
 * prayer the moment they spawn; Jad SWITCHES between magic / ranged / melee, so its icon
 * updates the instant its attack animation changes. Healers are outlined separately.
 *
 * READ-ONLY by construction: it only draws indicators. It never clicks a prayer or sends
 * input — auto-praying would be macroing.
 *
 * RULES NOTE: Jagex's Third-Party Client Guidelines classify prayer-switching indicators /
 * attack prediction as disallowed (why official RuneLite has no Fight Cave plugin and the
 * hub won't host one). This is a private/local build; use at your own risk.
 */
@Slf4j
@PluginDescriptor(
	name = "Jad Prayer Helper",
	description = "Shows the overhead prayer to use for each Fight Cave / Inferno monster (Jad switches live). Indicator only.",
	tags = {"jad", "fight", "cave", "inferno", "prayer", "tzhaar", "pvm", "flick"}
)
public class JadPrayerPlugin extends Plugin
{
	// Jad attack animations — VERIFIED against RuneLite/OpenOSRS AnimationID.java.
	private static final int TZTOK_JAD_MAGE = 2656, TZTOK_JAD_RANGE = 2652, TZTOK_JAD_MELEE = 2655;
	private static final int JALTOK_JAD_MAGE = 7592, JALTOK_JAD_RANGE = 7593, JALTOK_JAD_MELEE = 7590;
	// reaction window (ticks): prayer must be up by animation-tick + this many
	private static final int TZTOK_JAD_HIT_TICKS = 2, JALTOK_JAD_HIT_TICKS = 3;

	public enum Attack { MAGE, RANGE, MELEE }

	// Fixed-style Fight Cave wave monsters: NPC id -> the prayer to use against it.
	private static final Map<Integer, Attack> STYLE = new HashMap<>();
	static
	{
		for (int id : new int[]{2189, 2190, 3116, 3117}) STYLE.put(id, Attack.MELEE);       // Tz-Kih (drains prayer)
		for (int id : new int[]{2191, 2192, 3118, 3119, 3120}) STYLE.put(id, Attack.MELEE); // Tz-Kek (+ split)
		for (int id : new int[]{2193, 2194, 3121, 3122}) STYLE.put(id, Attack.RANGE);       // Tok-Xil
		for (int id : new int[]{3123, 3124}) STYLE.put(id, Attack.MELEE);                   // Yt-MejKot (heals)
		for (int id : new int[]{3125, 3126}) STYLE.put(id, Attack.MAGE);                    // Ket-Zek
	}

	@Inject private OverlayManager overlayManager;
	@Inject private SpriteManager spriteManager;
	@Inject private JadPrayerConfig config;
	@Inject private JadPrayerOverlay overlay;

	private final Set<NPC> healers = new HashSet<>();
	// every combat monster we can advise on -> the prayer to use against it right now
	private final Map<NPC, Attack> targets = new ConcurrentHashMap<>();
	public volatile NPC activeJad;       // the Jad whose latest attack drives the tick countdown/flash
	public volatile long switchedAtMs;   // when Jad last changed attack (for the flash)
	public volatile int hitTicks;        // ticks until Jad's current attack lands (0 = landed)
	public volatile boolean healersUp;

	public BufferedImage mageSprite, rangeSprite, meleeSprite;

	@Provides
	JadPrayerConfig provideConfig(ConfigManager cm) { return cm.getConfig(JadPrayerConfig.class); }

	@Override
	protected void startUp()
	{
		spriteManager.getSpriteAsync(SpriteID.PRAYER_PROTECT_FROM_MAGIC, 0, img -> mageSprite = img);
		spriteManager.getSpriteAsync(SpriteID.PRAYER_PROTECT_FROM_MISSILES, 0, img -> rangeSprite = img);
		spriteManager.getSpriteAsync(SpriteID.PRAYER_PROTECT_FROM_MELEE, 0, img -> meleeSprite = img);
		overlayManager.add(overlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		targets.clear();
		healers.clear();
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
		NPC n = e.getNpc();
		if (isHealer(n)) { healers.add(n); healersUp = true; return; }
		if (isJad(n)) { activeJad = n; return; }   // Jad's style is unknown until its first attack animation
		Attack st = STYLE.get(n.getId());
		if (st != null) targets.put(n, st);        // fixed-style wave monster — show its prayer immediately
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned e)
	{
		NPC n = e.getNpc();
		targets.remove(n);
		if (n == activeJad) { activeJad = null; hitTicks = 0; }
		if (healers.remove(n)) healersUp = !healers.isEmpty();
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (hitTicks > 0) hitTicks--;
	}

	/** Ticks until Jad's current attack lands (-1 once landed / none) — for the countdown. */
	public int ticksToHit()
	{
		return (activeJad != null && hitTicks > 0) ? hitTicks : -1;
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged e)
	{
		if (!(e.getActor() instanceof NPC)) return;
		NPC npc = (NPC) e.getActor();
		if (!isJad(npc)) return;   // wave monsters are fixed-style (handled on spawn); only Jad switches
		int anim = npc.getAnimation();
		if (config.debug()) log.info("Jad id={} animation={}", npc.getId(), anim);

		Attack a = null;
		boolean inferno = false;
		if (anim == TZTOK_JAD_MAGE) a = Attack.MAGE;
		else if (anim == TZTOK_JAD_RANGE) a = Attack.RANGE;
		else if (anim == TZTOK_JAD_MELEE) a = Attack.MELEE;
		else if (anim == JALTOK_JAD_MAGE) { a = Attack.MAGE; inferno = true; }
		else if (anim == JALTOK_JAD_RANGE) { a = Attack.RANGE; inferno = true; }
		else if (anim == JALTOK_JAD_MELEE) { a = Attack.MELEE; inferno = true; }
		if (a != null)
		{
			targets.put(npc, a);            // update THIS Jad's prayer instantly on the switch
			activeJad = npc;
			switchedAtMs = System.currentTimeMillis();
			hitTicks = inferno ? JALTOK_JAD_HIT_TICKS : TZTOK_JAD_HIT_TICKS;
		}
	}

	/** The prayer sprite for an attack type (null until sprites finish loading). */
	public BufferedImage spriteFor(Attack a)
	{
		return a == Attack.MAGE ? mageSprite : a == Attack.RANGE ? rangeSprite : meleeSprite;
	}

	/** Snapshot: every monster we're advising on right now -> its prayer. */
	public Map<NPC, Attack> targets() { return new HashMap<>(targets); }
	public boolean hasTargets() { return !targets.isEmpty(); }

	/** Snapshot of the live healers, for the overlay to outline each one. */
	public java.util.List<NPC> healerNpcs() { return new java.util.ArrayList<>(healers); }

	/** True briefly after Jad switches attack — drives the flash on Jad's icon. */
	public boolean flashing()
	{
		return config.flash() && System.currentTimeMillis() - switchedAtMs < 700;
	}
}
