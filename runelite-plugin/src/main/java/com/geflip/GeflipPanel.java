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
	private final JLabel combined = new JLabel(" ");   // real earn rate = top slots summed
	private final JLabel bankLabel = new JLabel(" ");  // the bankroll being used (your coins)
	private final JLabel session = new JLabel("session: —");
	private final JLabel calib = new JLabel(" ");      // your ACTUAL results (win% / hold)
	private final JLabel legend = new JLabel(" ");     // what the row symbols mean
	private final JPanel rows = new JPanel();          // Flips tab
	private final JPanel dipsRows = new JPanel();      // Dips tab (🔥 items cheap vs their norm)
	private final JPanel offersBox = new JPanel();     // "Your GE" — live open offers (You tab)
	private final JPanel holdBox = new JPanel();       // "To sell" — items you hold + sell price (You tab)
	private final java.awt.CardLayout cards = new java.awt.CardLayout();
	private final JPanel cardPanel = new JPanel(cards);
	private final JButton tabFlips = new JButton("Flips");
	private final JButton tabDips = new JButton("Dips");
	private final JButton tabYou = new JButton("You");
	private final java.util.function.IntConsumer onClearHold;

	GeflipPanel(Runnable onRefresh, java.util.function.IntConsumer onClearHold)
	{
		this.onClearHold = onClearHold;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		session.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		calib.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		calib.setFont(FontManager.getRunescapeSmallFont());
		legend.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		legend.setFont(FontManager.getRunescapeSmallFont());
		legend.setText("<html><span style='color:#999'>gp/h · buy→sell +margin ×qty · ~fill · ↻limit · %fill · 🔥dip · ⚠falling · ⏳low</span></html>");

		// --- top: rescan + status + legend ---
		JButton refresh = new JButton("Rescan");
		refresh.addActionListener(e -> onRefresh.run());
		refresh.setAlignmentX(Component.LEFT_ALIGNMENT);
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		legend.setAlignmentX(Component.LEFT_ALIGNMENT);
		combined.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		combined.setAlignmentX(Component.LEFT_ALIGNMENT);
		bankLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		bankLabel.setFont(FontManager.getRunescapeSmallFont());
		bankLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.add(refresh);
		top.add(status);
		top.add(combined);
		top.add(bankLabel);
		top.add(legend);

		// --- tab bar: Flips | Dips | You ---
		JPanel tabBar = new JPanel(new GridLayout(1, 3, 3, 0));
		tabBar.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
		tabBar.add(tabFlips); tabBar.add(tabDips); tabBar.add(tabYou);
		tabFlips.addActionListener(e -> showCard("flips"));
		tabDips.addActionListener(e -> showCard("dips"));
		tabYou.addActionListener(e -> showCard("you"));

		JPanel north = new JPanel(new BorderLayout());
		north.add(top, BorderLayout.NORTH);
		north.add(tabBar, BorderLayout.SOUTH);
		add(north, BorderLayout.NORTH);

		// --- cards ---
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		dipsRows.setLayout(new BoxLayout(dipsRows, BoxLayout.Y_AXIS));
		offersBox.setLayout(new BoxLayout(offersBox, BoxLayout.Y_AXIS));
		holdBox.setLayout(new BoxLayout(holdBox, BoxLayout.Y_AXIS));

		// "You" card = session P&L + your record + what to sell + live GE offers
		JPanel youBox = new JPanel();
		youBox.setLayout(new BoxLayout(youBox, BoxLayout.Y_AXIS));
		session.setAlignmentX(Component.LEFT_ALIGNMENT);
		calib.setAlignmentX(Component.LEFT_ALIGNMENT);
		offersBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		holdBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		youBox.add(session);
		youBox.add(calib);
		youBox.add(holdBox);
		youBox.add(offersBox);

		cardPanel.add(scrollOf(rows), "flips");
		cardPanel.add(scrollOf(dipsRows), "dips");
		cardPanel.add(scrollOf(youBox), "you");
		add(cardPanel, BorderLayout.CENTER);
		showCard("flips");
	}

	private static JScrollPane scrollOf(JPanel content)
	{
		JScrollPane s = new JScrollPane(content);
		s.setBorder(null);
		s.getVerticalScrollBar().setUnitIncrement(16);
		return s;
	}

	private void showCard(String name)
	{
		cards.show(cardPanel, name);
		// highlight the active tab
		tabFlips.setForeground("flips".equals(name) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabDips.setForeground("dips".equals(name) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabYou.setForeground("you".equals(name) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
	}

	void setStatus(String s) { SwingUtilities.invokeLater(() -> status.setText(s)); }

	/** Show the bankroll the scan is sizing to (your real coins when auto is on). */
	void setBankroll(long gp, boolean auto)
	{
		SwingUtilities.invokeLater(() ->
			bankLabel.setText("bankroll: " + gp(gp) + (auto ? " (your coins)" : " (manual)")));
	}

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

			// CALIBRATION: your ACTUAL track record, so you can trust (or discount) the gp/h estimates
			if (l.flips > 0)
			{
				double h = l.avgHoldHours();
				String hold = h >= 24 ? String.format("%.1fd", h / 24) : h >= 1 ? Math.round(h) + "h" : Math.max(1, (int) Math.round(h * 60)) + "m";
				long perDay = l.realizedPerDay();
				String rate = perDay != 0 ? " · " + gp(perDay) + "/day" : "";
				calib.setText("record: " + gp(l.realizedFlip) + " over " + l.flips + " flips · "
					+ Math.round(l.winRate() * 100) + "% win · ~" + hold + " hold" + rate);
				calib.setForeground(l.realizedFlip >= 0 ? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.PROGRESS_ERROR_COLOR);
				calib.setToolTipText("Your REAL results from completed round-trips: total realized profit, flip count, "
					+ "win-rate, average hold, and realized gp/day over the "
					+ (l.spanDays() >= 1 ? String.format("%.1f", l.spanDays()) + " days" : "time")
					+ " your log spans. This is the honest profitability number — use it over any estimate.");
			}
			else calib.setText("record: no completed flips yet — it fills in as you flip");
		});
	}

	/** Render "To sell" — items you're holding (bought, not yet sold) + where to list them. */
	void setHoldings(List<GeflipPlugin.Hold> holds)
	{
		SwingUtilities.invokeLater(() ->
		{
			holdBox.removeAll();
			if (holds != null && !holds.isEmpty())
			{
				JLabel hdr = new JLabel("To sell");
				hdr.setForeground(ColorScheme.BRAND_ORANGE);
				hdr.setBorder(BorderFactory.createEmptyBorder(6, 1, 2, 1));
				hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
				holdBox.add(hdr);
				for (GeflipPlugin.Hold h : holds) holdBox.add(holdRow(h));
			}
			holdBox.revalidate();
			holdBox.repaint();
		});
	}

	private JPanel holdRow(GeflipPlugin.Hold h)
	{
		JPanel p = new JPanel(new BorderLayout(0, 1));
		p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel name = new JLabel(h.name + "  ×" + h.qty);
		name.setForeground(ColorScheme.TEXT_COLOR);
		String line2;
		if (h.sellHint > 0)
		{
			long net = h.sellHint - (h.sellHint < 50 ? 0 : Math.min((long) (h.sellHint * 0.02), 5_000_000));
			boolean profit = net >= h.avgCost;
			line2 = "cost " + gp(h.avgCost) + "  →  sell @ " + gp(h.sellHint)
				+ "  (" + (profit ? "+" : "") + gp(net - h.avgCost) + "/ea)";
			JLabel sub = new JLabel(line2);
			sub.setFont(FontManager.getRunescapeSmallFont());
			sub.setForeground(profit ? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.PROGRESS_ERROR_COLOR);
			p.add(sub, BorderLayout.SOUTH);
			p.setToolTipText("You hold " + h.qty + " at ~" + gp(h.avgCost) + " each. List a sell at ~"
				+ gp(h.sellHint) + " to fill; " + (profit ? "that's a profit." : "that's a LOSS — decide cut vs hold."));
		}
		else
		{
			JLabel sub = new JLabel("cost " + gp(h.avgCost) + "  ·  no live price");
			sub.setFont(FontManager.getRunescapeSmallFont());
			sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			p.add(sub, BorderLayout.SOUTH);
		}
		p.add(name, BorderLayout.CENTER);
		// "✓" = I already sold this (plugin missed it — mobile/offline). Records the sale + clears it.
		JButton sold = new JButton("✓");
		sold.setToolTipText("Mark as sold — use if you sold it and it's still listed here "
			+ "(e.g. sold on mobile). Records the sale at the current market price and clears it.");
		sold.setMargin(new java.awt.Insets(0, 4, 0, 4));
		sold.setFont(FontManager.getRunescapeSmallFont());
		sold.addActionListener(e -> { if (onClearHold != null) onClearHold.accept(h.id); });
		p.add(sold, BorderLayout.EAST);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
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
		JLabel l = new JLabel((o.stale ? "⚠ " : buy ? "▼ " : "▲ ") + nm + "  @" + gp(o.price));
		l.setForeground(o.stale ? ColorScheme.PROGRESS_ERROR_COLOR
			: buy ? ColorScheme.PROGRESS_INPROGRESS_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);
		String tail = o.qtySold + "/" + o.qtyTotal
			+ (done ? " ✓" : cancelled ? " ✕" : o.stale ? " stale" : "");
		JLabel r = new JLabel(tail);
		r.setForeground(done ? ColorScheme.PROGRESS_COMPLETE_COLOR
			: o.stale ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		r.setHorizontalAlignment(JLabel.RIGHT);
		r.setFont(FontManager.getRunescapeSmallFont());
		if (o.stale)
		{
			String age = o.ageSec >= 3600 ? "~" + (o.ageSec / 3600) + "h" : "~" + Math.max(1, o.ageSec / 60) + "m";
			l.setToolTipText("Unfilled for " + age + " — the price likely moved. Reprice it.");
		}
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
			dipsRows.removeAll();
			int dips = 0;
			double top8 = 0; int slots = 0;
			for (GeflipScanner.Flip f : flips)
			{
				rows.add(rowFor(f));
				if (slots < 8) { top8 += f.expGph; slots++; }        // real rate = your 8 slots combined
				if (f.dumping) { dipsRows.add(rowFor(f)); dips++; }   // 🔥 cheap vs its recent norm
			}
			combined.setText(slots > 0 ? "≈ " + gp((long) top8) + "/hr across " + slots + " slots" : " ");
			combined.setToolTipText("Your realistic earn rate = the top " + slots + " flips run at once "
				+ "(one per GE slot). Per-item gp/h looks small; the 8-slot total is the real number. "
				+ "Raise your Bankroll in config to unlock bigger per-slot flips.");
			if (dips == 0)
			{
				JLabel none = new JLabel("no dips right now — nothing's trading below its norm");
				none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
				dipsRows.add(none);
			}
			tabDips.setText(dips > 0 ? "Dips (" + dips + ")" : "Dips");
			rows.revalidate(); rows.repaint();
			dipsRows.revalidate(); dipsRows.repaint();
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
		String tag = (f.dumping ? "🔥 " : "") + (f.decliner ? "⚠ " : "");
		JLabel name = new JLabel(tag + f.name);
		name.setForeground(f.decliner ? ColorScheme.PROGRESS_ERROR_COLOR
			: f.dumping ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
		JLabel gph = new JLabel(gp((long) f.expGph) + "/h");
		// colour the headline by fill confidence — the honest signal: a fat gp/h in orange/red
		// means it probably won't fill. Green = reliable, orange = so-so, red = thin.
		gph.setForeground(f.wontFill || f.fillProb < 0.4 ? ColorScheme.PROGRESS_ERROR_COLOR
			: f.fillProb < 0.7 ? ColorScheme.PROGRESS_INPROGRESS_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);
		gph.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER);
		top.add(gph, BorderLayout.EAST);

		// --- line 2: buy -> sell, margin, qty, and the ESTIMATED FILL TIME so the
		// gp/h rate isn't a mystery (a "~2d" item won't earn its hourly rate soon) ---
		String ft = fillTxt(f.fillHours);
		String reset = f.resetMins > 0 ? "   ↻" + (f.resetMins >= 60 ? (f.resetMins / 60) + "h" : f.resetMins + "m") : "";
		String fill = f.wontFill ? "   ⏳low" : "   " + Math.round(f.fillProb * 100) + "% fill";
		JLabel sub = new JLabel(gp(f.buy) + " → " + gp(f.sell)
			+ "   +" + gp(f.margin) + "   ×" + f.quantity + (ft.isEmpty() ? "" : "   " + ft) + reset + fill);
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

		// full plain-English explanation of this row on hover
		StringBuilder tip = new StringBuilder("<html><b>").append(f.name).append("</b><br>");
		if (f.why != null && !f.why.isEmpty()) tip.append("<i>").append(f.why).append("</i><br>");
		tip.append("Buy at ").append(gp(f.buy)).append(", sell at ").append(gp(f.sell))
			.append(" → ").append(gp(f.margin)).append(" profit each after tax<br>");
		tip.append("Buy up to ").append(f.quantity).append(" (bankroll/limit/volume capped)<br>");
		tip.append("Est. ").append(gp((long) f.expGph)).append("/hr — profit ÷ how long both offers take to fill<br>");
		if (f.fillHours < 900) tip.append("Fills in ~").append(fillTxt(f.fillHours).replace("~", "")).append("<br>");
		tip.append(f.wontFill ? "⏳ Low counter-flow — expect slow/failed fills<br>"
			: Math.round(f.fillProb * 100) + "% chance the round-trip completes within 4h<br>");
		if (f.resetMins > 0) tip.append("↻ your 4h buy limit resets in ~")
			.append(f.resetMins >= 60 ? (f.resetMins / 60) + "h" : f.resetMins + "m").append("<br>");
		if (f.dumping) tip.append("🔥 cheap right now vs its recent norm — a dip<br>");
		if (f.decliner) tip.append("⚠ in a long-term decline — risky<br>");
		if (f.t90 != null) tip.append("90-day trend ").append(pct(f.t90)).append("<br>");
		tip.append("<i>Click to copy the name for the GE search</i></html>");
		name.setToolTipText(tip.toString());

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
