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
	// colourblind-safe (IBM palette): blue vs orange survives red-green CVD; magenta is
	// distinct from both. Never blue-vs-green (the deuteranopia/protanopia confusion).
	private static final Color MAGE_COL = new Color(0x64, 0x8F, 0xFF);   // blue
	private static final Color RANGE_COL = new Color(0xFE, 0x61, 0x00);  // orange
	private static final Color HEAL_COL = new Color(0xDC, 0x26, 0x7F);   // magenta

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
		// 1) highlight every healer as it comes (independent of the prayer state)
		for (NPC h : plugin.healerNpcs()) drawHealer(g, h);

		JadPrayerPlugin.Attack a = plugin.attack;
		// test mode: no live Jad → draw a sample centre-screen so you can confirm it works
		if (a == null && config.testShow())
		{
			int s = Math.max(1, config.scale());
			int w = (plugin.mageSprite != null ? plugin.mageSprite.getWidth() : 34) * s;
			int h = (plugin.mageSprite != null ? plugin.mageSprite.getHeight() : 34) * s;
			int cx = client.getViewportXOffset() + client.getViewportWidth() / 2 - w / 2;
			int cy = client.getViewportYOffset() + client.getViewportHeight() / 2 - h / 2;
			drawBadge(g, plugin.mageSprite, "PRAY MAGE (test)", MAGE_COL, cx, cy, w, h);
			return null;
		}
		if (a == null) return null;

		boolean mage = a == JadPrayerPlugin.Attack.MAGE;
		BufferedImage icon = mage ? plugin.mageSprite : plugin.rangeSprite;
		Color col = mage ? MAGE_COL : RANGE_COL;
		String label = mage ? "PRAY MAGE" : "PRAY RANGE";
		int s = Math.max(1, config.scale());
		int w = (icon != null ? icon.getWidth() : 34) * s;
		int h = (icon != null ? icon.getHeight() : 34) * s;
		int ticks = plugin.ticksToHit();   // -1 if none in flight

		// 2) PRIMARY: the actual protection-prayer SPRITE above Jad's head — just the icon,
		// no coloured box or label. The sprite changing (magic <-> missiles) is the signal.
		if (config.overHead() && plugin.activeJad != null)
		{
			NPC jad = plugin.activeJad;
			LocalPoint lp = jad.getLocalLocation();
			if (lp != null)
			{
				net.runelite.api.Point p = Perspective.localToCanvas(
					client, lp, client.getPlane(), jad.getLogicalHeight() + 60);
				if (p != null)
				{
					int x = p.getX() - w / 2, y = p.getY() - h;
					if (icon != null)
					{
						// soft black drop-shadow so the icon reads on any background
						g.setColor(new Color(0, 0, 0, 120));
						g.fillOval(x - 3, y - 3, w + 6, h + 6);
						g.drawImage(icon, x, y, w, h, null);
					}
					else drawBadge(g, icon, label, col, x, y, w, h);   // fallback if sprite not loaded yet
					if (ticks >= 0) drawTicks(g, ticks, Color.WHITE, x + w / 2, y - 8);
				}
			}
		}

		// optional secondary centre-screen indicator (off by default)
		if (config.center())
		{
			int cx = client.getViewportXOffset() + client.getViewportWidth() / 2 - w / 2;
			int cy = client.getViewportYOffset() + client.getViewportHeight() / 2 - h / 2;
			drawBadge(g, icon, label, col, cx, cy, w, h);
			if (ticks >= 0) drawTicks(g, ticks, col, cx + w / 2, cy - 8);
		}
		return null;
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
