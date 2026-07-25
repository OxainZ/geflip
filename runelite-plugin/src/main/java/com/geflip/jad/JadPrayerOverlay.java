package com.geflip.jad;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the "pray this now" indicator as a prayer symbol ABOVE JAD'S HEAD, and outlines
 * every healer as it spawns. Colours are colourblind-safe (blue mage / orange range —
 * never blue-vs-green; magenta healers), and the type is double-encoded with text so it
 * reads without colour. Pure drawing — no input.
 */
public class JadPrayerOverlay extends Overlay
{
	// colourblind-safe (IBM palette): blue/orange/purple are all distinguishable under
	// red-green CVD; magenta for healers. Type is also double-encoded with the sprite + label.
	private static final Color MAGE_COL = new Color(0x64, 0x8F, 0xFF);   // blue
	private static final Color RANGE_COL = new Color(0xFE, 0x61, 0x00);  // orange
	private static final Color MELEE_COL = new Color(0x78, 0x5E, 0xF0);  // purple
	private static final Color HEAL_COL = new Color(0xDC, 0x26, 0x7F);   // magenta

	private static Color colorFor(JadPrayerPlugin.Attack a)
	{
		return a == JadPrayerPlugin.Attack.MAGE ? MAGE_COL : a == JadPrayerPlugin.Attack.RANGE ? RANGE_COL : MELEE_COL;
	}
	private static String labelFor(JadPrayerPlugin.Attack a)
	{
		return a == JadPrayerPlugin.Attack.MAGE ? "PRAY MAGE" : a == JadPrayerPlugin.Attack.RANGE ? "PRAY RANGE" : "PRAY MELEE";
	}

	private final Client client;
	private final JadPrayerPlugin plugin;
	private final JadPrayerConfig config;

	@Inject
	JadPrayerOverlay(Client client, JadPrayerPlugin plugin, JadPrayerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		// 1) highlight every healer as it comes (skip the list copy when none are up)
		if (plugin.healersUp) for (NPC h : plugin.healerNpcs()) drawHealer(g, h);

		// 2) TEST: a fixed, clearly-labelled corner sample — NOT over any monster (so it can
		// never look like a real read on the wrong creature).
		if (config.testShow()) drawTestSample(g);

		int s = Math.max(1, config.scale());

		// 3) the correct prayer sprite over EACH monster (fixed-style on spawn; Jad live).
		if (config.overHead() && plugin.hasTargets())
		{
			for (java.util.Map.Entry<NPC, JadPrayerPlugin.Attack> en : plugin.targets().entrySet())
			{
				NPC npc = en.getKey();
				JadPrayerPlugin.Attack a = en.getValue();
				BufferedImage icon = plugin.spriteFor(a);
				Color col = colorFor(a);
				int w = (icon != null ? icon.getWidth() : 34) * s;
				int h = (icon != null ? icon.getHeight() : 34) * s;
				boolean isJad = npc == plugin.activeJad;   // only Jad gets the countdown + flash
				int ticks = isJad ? plugin.ticksToHit() : -1;
				drawOverHead(g, npc, icon, col, labelFor(a), w, h, ticks, isJad && plugin.flashing());
			}
		}
		return null;
	}

	/** Fixed corner sample so you can confirm the plugin is live and sized right. */
	private void drawTestSample(Graphics2D g)
	{
		int s = Math.max(1, config.scale());
		BufferedImage icon = plugin.mageSprite;
		int w = (icon != null ? icon.getWidth() : 34) * s, h = (icon != null ? icon.getHeight() : 34) * s;
		int x = client.getViewportXOffset() + 10, y = client.getViewportYOffset() + 30;
		if (icon != null)
		{
			g.setColor(new Color(0, 0, 0, 120));
			g.fillOval(x - 3, y - 3, w + 6, h + 6);
			g.drawImage(icon, x, y, w, h, null);
		}
		else drawBadge(g, icon, "TEST", MAGE_COL, x, y, w, h);
		g.setFont(g.getFont().deriveFont(Font.BOLD, 13f));
		g.setColor(Color.BLACK); g.drawString("TEST sample — turn off before your run", x + 1, y + h + 15);
		g.setColor(Color.WHITE); g.drawString("TEST sample — turn off before your run", x, y + h + 14);
	}

	/** Draw the prayer sprite above an NPC's head (drop-shadow + optional tick + flash ring). */
	private void drawOverHead(Graphics2D g, NPC npc, BufferedImage icon, Color col,
		String fallbackLabel, int w, int h, int ticks, boolean flash)
	{
		LocalPoint lp = npc.getLocalLocation();
		if (lp == null) return;
		net.runelite.api.Point p = Perspective.localToCanvas(
			client, lp, client.getPlane(), npc.getLogicalHeight() + 60);
		if (p == null) return;
		int x = p.getX() - w / 2, y = p.getY() - h;
		if (icon != null)
		{
			if (flash)   // Jad just switched — brief coloured ring to grab the eye (no colour-only reliance)
			{
				Stroke old = g.getStroke();
				g.setStroke(new BasicStroke(4f));
				g.setColor(col);
				g.drawOval(x - 4, y - 4, w + 8, h + 8);
				g.setStroke(old);
			}
			g.setColor(new Color(0, 0, 0, 120));   // soft shadow so it reads on any background
			g.fillOval(x - 3, y - 3, w + 6, h + 6);
			g.drawImage(icon, x, y, w, h, null);
		}
		else drawBadge(g, icon, fallbackLabel, col, x, y, w, h);   // fallback if sprite not loaded
		if (ticks >= 0) drawTicks(g, ticks, Color.WHITE, x + w / 2, y - 8);
	}

	/** Outline a healer (magenta hull + "HEAL" tag) so you can find and re-aggro them. */
	private void drawHealer(Graphics2D g, NPC npc)
	{
		if (npc == null) return;
		Shape hull = npc.getConvexHull();
		if (hull != null)
		{
			Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(2f));
			g.setColor(HEAL_COL);
			g.draw(hull);
			g.setColor(new Color(HEAL_COL.getRed(), HEAL_COL.getGreen(), HEAL_COL.getBlue(), 40));
			g.fill(hull);
			g.setStroke(old);
		}
		net.runelite.api.Point tp = npc.getCanvasTextLocation(g, "HEAL", npc.getLogicalHeight() + 20);
		if (tp != null)
		{
			g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
			g.setColor(Color.BLACK);
			g.drawString("HEAL", tp.getX() + 1, tp.getY() + 1);
			g.setColor(HEAL_COL);
			g.drawString("HEAL", tp.getX(), tp.getY());
		}
	}

	/** Big tick countdown to the hit, centred above the icon. */
	private void drawTicks(Graphics2D g, int ticks, Color col, int cx, int baselineY)
	{
		String t = String.valueOf(ticks);
		g.setFont(g.getFont().deriveFont(Font.BOLD, 28f));
		FontMetrics fm = g.getFontMetrics();
		int tx = cx - fm.stringWidth(t) / 2;
		g.setColor(Color.BLACK);
		g.drawString(t, tx + 2, baselineY + 2);
		g.setColor(col);
		g.drawString(t, tx, baselineY);
	}

	private void drawBadge(Graphics2D g, BufferedImage icon, String label, Color col,
		int x, int y, int w, int h)
	{
		boolean flash = plugin.flashing();
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(flash ? 5f : 3f));
		g.setColor(col);
		g.drawRect(x - 4, y - 4, w + 8, h + 8);
		if (flash)
		{
			g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 70));
			g.fillRect(x - 4, y - 4, w + 8, h + 8);
		}
		g.setStroke(old);

		if (icon != null) g.drawImage(icon, x, y, w, h, null);
		else { g.setColor(col); g.fillRect(x, y, w, h); }

		if (label != null)
		{
			g.setFont(g.getFont().deriveFont(Font.BOLD, 16f + w * 0.12f));
			FontMetrics fm = g.getFontMetrics();
			int tx = x + w / 2 - fm.stringWidth(label) / 2;
			int ty = y + h + fm.getHeight();
			g.setColor(Color.BLACK);
			g.drawString(label, tx + 2, ty + 2);
			g.setColor(col);
			g.drawString(label, tx, ty);
		}
	}
}
