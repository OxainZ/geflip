package com.geflip;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel: a ranked flip list + your live session P&L. Display only — tapping
 * a row copies the item name to the clipboard so you can paste it into the GE search
 * (the same "one-tap name" convenience the web app has). It never sends game input.
 */
class GeflipPanel extends PluginPanel
{
	private final JLabel status = new JLabel("idle");
	private final JLabel session = new JLabel("session: —");
	private final JPanel rows = new JPanel();
	private final JPanel offersBox = new JPanel();   // "Your GE" — live open offers

	GeflipPanel(Runnable onRefresh)
	{
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JPanel top = new JPanel(new BorderLayout());
		JButton refresh = new JButton("Rescan");
		refresh.addActionListener(e -> onRefresh.run());
		top.add(refresh, BorderLayout.NORTH);
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		session.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		JPanel meta = new JPanel(new GridLayout(2, 1));
		meta.add(status);
		meta.add(session);
		top.add(meta, BorderLayout.SOUTH);

		// north = controls/meta, then the live "Your GE" offers box beneath it
		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		offersBox.setLayout(new BoxLayout(offersBox, BoxLayout.Y_AXIS));
		offersBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		north.add(top);
		north.add(offersBox);
		add(north, BorderLayout.NORTH);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		JScrollPane scroll = new JScrollPane(rows);
		scroll.setBorder(null);
		add(scroll, BorderLayout.CENTER);
	}

	void setStatus(String s) { SwingUtilities.invokeLater(() -> status.setText(s)); }

	void setSession(GeflipLedger l)
	{
		SwingUtilities.invokeLater(() ->
		{
			String txt = "flip " + gp(l.realizedFlip);
			if (l.openUnits > 0) txt += " · held " + gp(l.inventoryCost);
			if (l.keptNet != 0) txt += " · kept " + gp(l.keptNet);
			session.setText(txt);
			session.setForeground(l.realizedFlip >= 0
				? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.PROGRESS_ERROR_COLOR);
			session.setToolTipText("flip = profit from matched buy→sell round-trips (net of tax)   ·   "
				+ "held = cost of bought-but-unsold flip items   ·   "
				+ "kept = net spent on your \"not a flip\" items");
		});
	}

	/** Render your live open GE offers (buying/selling with fill progress). */
	void setOffers(List<GeflipPlugin.Offer> offers)
	{
		SwingUtilities.invokeLater(() ->
		{
			offersBox.removeAll();
			if (offers != null && !offers.isEmpty())
			{
				JLabel hdr = new JLabel("Your GE");
				hdr.setForeground(ColorScheme.BRAND_ORANGE);
				hdr.setBorder(BorderFactory.createEmptyBorder(6, 1, 2, 1));
				hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
				offersBox.add(hdr);
				for (GeflipPlugin.Offer o : offers) offersBox.add(offerRow(o));
			}
			offersBox.revalidate();
			offersBox.repaint();
		});
	}

	private JPanel offerRow(GeflipPlugin.Offer o)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		boolean buy = o.state.contains("BUY");
		boolean done = o.state.equals("BOUGHT") || o.state.equals("SOLD");
		boolean cancelled = o.state.startsWith("CANCELLED");
		String nm = o.name != null ? o.name : ("#" + o.id);
		JLabel l = new JLabel((buy ? "▼ " : "▲ ") + nm + "  @" + gp(o.price));
		l.setForeground(buy ? ColorScheme.PROGRESS_INPROGRESS_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);
		JLabel r = new JLabel(o.qtySold + "/" + o.qtyTotal + (done ? " ✓" : cancelled ? " ✕" : ""));
		r.setForeground(done ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		r.setHorizontalAlignment(JLabel.RIGHT);
		r.setFont(FontManager.getRunescapeSmallFont());
		p.add(l, BorderLayout.CENTER);
		p.add(r, BorderLayout.EAST);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	void setFlips(List<GeflipScanner.Flip> flips)
	{
		SwingUtilities.invokeLater(() ->
		{
			rows.removeAll();
			for (GeflipScanner.Flip f : flips) rows.add(rowFor(f));
			rows.revalidate();
			rows.repaint();
		});
	}

	private JPanel rowFor(GeflipScanner.Flip f)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// --- line 1: item name (left, clips if long) + gp/hour headline (right) ---
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel((f.decliner ? "⚠ " : "") + f.name);
		name.setForeground(f.decliner ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.TEXT_COLOR);
		JLabel gph = new JLabel(gp((long) f.expGph) + "/h");
		gph.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		gph.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER);
		top.add(gph, BorderLayout.EAST);

		// --- line 2: buy -> sell, margin, qty, and the ESTIMATED FILL TIME so the
		// gp/h rate isn't a mystery (a "~2d" item won't earn its hourly rate soon) ---
		String ft = fillTxt(f.fillHours);
		JLabel sub = new JLabel(gp(f.buy) + " → " + gp(f.sell)
			+ "   +" + gp(f.margin) + "   ×" + f.quantity + (ft.isEmpty() ? "" : "   " + ft));
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());

		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top);
		col.add(sub);
		p.add(col, BorderLayout.CENTER);

		name.setToolTipText("Click to copy \"" + f.name + "\" for the GE search"
			+ (f.t90 != null ? "   ·   90d " + pct(f.t90) : ""));

		// click = copy the item name for the GE search (no game input)
		p.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override public void mouseClicked(java.awt.event.MouseEvent e)
			{
				java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(f.name);
				java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
				status.setText("copied \"" + f.name + "\"");
			}
		});
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private static String gp(long v)
	{
		long a = Math.abs(v);
		if (a >= 10_000_000) return sign(v) + (a / 1_000_000) + "m";
		if (a >= 1_000_000) return sign(v) + String.format("%.2fm", a / 1e6);
		if (a >= 1000) return sign(v) + String.format("%.1fk", a / 1e3);
		return v + "";
	}
	private static String sign(long v) { return v < 0 ? "-" : ""; }
	private static String pct(double v) { return String.format("%+.1f%%", v * 100); }
	private static String fillTxt(double h)
	{
		if (h >= 900 || Double.isNaN(h)) return "";
		if (h < 1) return "~" + Math.max(1, (int) Math.round(h * 60)) + "m";
		if (h < 24) return "~" + (h < 9.5 ? String.format("%.1f", h) : "" + (int) Math.round(h)) + "h";
		return "~" + String.format("%.1f", h / 24) + "d";
	}
}
