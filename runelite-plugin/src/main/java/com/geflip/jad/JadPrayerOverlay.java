package com.geflip.jad;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
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
 * Draws the "pray this now" indicator: a big prayer icon in the centre of the screen
 * (impossible to miss during Jad) and/or a small one over Jad's head. Colour + flash
 * key off the plugin's current attack read. Pure drawing — no input.
 */
public class JadPrayerOverlay extends Overlay
{
	private static final Color MAGE_COL = new Color(0x3B, 0x7B, 0xFF);   // blue
	private static final Color RANGE_COL = new Color(0x2F, 0xD0, 0x6A);  // green

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

	private static final Color HEAL_COL = new Color(0xFF, 0x9A, 0x1F);   // orange

	@Override
	public Dimension render(Graphics2D g)
	{
		if (plugin.healersUp) drawHealerBanner(g);

		JadPrayerPlugin.Attack a = plugin.attack;
		// test mode: with no live Jad, draw a sample so you can confirm/position the indicator
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
		int iw = icon != null ? icon.getWidth() : 34;
		int ih = icon != null ? icon.getHeight() : 34;
		int ticks = plugin.ticksToHit();   // -1 if no attack in flight

		if (config.center())
		{
			int s = Math.max(1, config.scale());
			int w = iw * s, h = ih * s;
			int cx = client.getViewportXOffset() + client.getViewportWidth() / 2 - w / 2;
			int cy = client.getViewportYOffset() + client.getViewportHeight() / 2 - h / 2;
			drawBadge(g, icon, label, col, cx, cy, w, h);
			if (ticks >= 0) drawTicks(g, ticks, col, cx + w / 2, cy - 8);
		}

		if (config.overHead() && plugin.activeJad != null)
		{
			NPC jad = plugin.activeJad;
			LocalPoint lp = jad.getLocalLocation();
			if (lp != null)
			{
				net.runelite.api.Point p = Perspective.localToCanvas(
					client, lp, client.getPlane(), jad.getLogicalHeight() + 40);
				if (p != null) drawBadge(g, icon, null, col, p.getX() - iw / 2, p.getY() - ih, iw, ih);
			}
		}
		return null;
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

	/** Banner reminding you to re-aggro the healers and hold your prayer. */
	private void drawHealerBanner(Graphics2D g)
	{
		String msg = "HEALERS — re-aggro & keep praying";
		g.setFont(g.getFont().deriveFont(Font.BOLD, 18f));
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(msg);
		int cx = client.getViewportXOffset() + client.getViewportWidth() / 2;
		int y = client.getViewportYOffset() + 60;
		g.setColor(new Color(0, 0, 0, 150));
		g.fillRect(cx - w / 2 - 10, y - fm.getAscent() - 6, w + 20, fm.getHeight() + 10);
		g.setColor(Color.BLACK);
		g.drawString(msg, cx - w / 2 + 1, y + 1);
		g.setColor(HEAL_COL);
		g.drawString(msg, cx - w / 2, y);
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
