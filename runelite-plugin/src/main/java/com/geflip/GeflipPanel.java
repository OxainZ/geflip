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
	private final JLabel capitalLabel = new JLabel(" ");  // capital working vs idle + slots in use
	private final JLabel session = new JLabel("session: —");
	private final JLabel calib = new JLabel(" ");      // your ACTUAL results (win% / hold)
	private final JLabel legend = new JLabel(" ");     // what the row symbols mean
	private final JPanel rows = new JPanel();          // Flips tab
	private final JPanel dipsRows = new JPanel();      // Dips tab (🔥 items cheap vs their norm)
	private final JPanel decantRows = new JPanel();    // Decant tab (buy low-dose → sell (4))
	private final JPanel alchRows = new JPanel();       // Alch tab (buy < alch value, tax-free)
	private final JPanel setsRows = new JPanel();      // Sets tab (combine/split set exchange)
	private final JPanel offersBox = new JPanel();     // "Your GE" — live open offers (You tab)
	private final JPanel holdBox = new JPanel();       // "To sell" — items you hold + sell price (You tab)
	private final JPanel perfBox = new JPanel();       // "Best items" — realized per-item profit (You tab)
	private final JPanel suppressedBox = new JPanel(); // "Winners not showing" — proven items + why (You tab)
	private final JPanel accountBox = new JPanel();     // "For your account" — Coach shopping list priced (You tab)
	private final java.awt.CardLayout cards = new java.awt.CardLayout();
	private final JPanel cardPanel = new JPanel(cards);
	private final JButton tabFlips = new JButton("Flips");
	private final JButton tabDips = new JButton("Dips");
	private final JButton tabDecant = new JButton("Decant");
	private final JButton tabSets = new JButton("Sets");
	private final JButton tabAlch = new JButton("Alch");
	private final JButton tabYou = new JButton("You");
	private final javax.swing.JTextField priceInput = new javax.swing.JTextField();
	private final JLabel priceResult = new JLabel(" ");
	private final java.util.function.IntConsumer onClearHold;
	private final java.util.function.Function<String, String> onPriceCheck;
	private final java.util.function.ObjLongConsumer<Integer> onEditCost;
	private final java.util.function.IntConsumer onPersonalUse;
	private final Runnable onWatchLast;
	private final java.util.function.IntConsumer onUnwatch;
	private final Runnable onResetJournal;
	private final JPanel watchBox = new JPanel();      // "Watch" — pinned items + live prices (You tab)

	GeflipPanel(Runnable onRefresh, java.util.function.IntConsumer onClearHold,
		java.util.function.Function<String, String> onPriceCheck,
		java.util.function.ObjLongConsumer<Integer> onEditCost,
		java.util.function.IntConsumer onPersonalUse,
		Runnable onWatchLast, java.util.function.IntConsumer onUnwatch,
		Runnable onResetJournal)
	{
		this.onClearHold = onClearHold;
		this.onPriceCheck = onPriceCheck;
		this.onEditCost = onEditCost;
		this.onPersonalUse = onPersonalUse;
		this.onWatchLast = onWatchLast;
		this.onUnwatch = onUnwatch;
		this.onResetJournal = onResetJournal;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		session.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		calib.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		calib.setFont(FontManager.getRunescapeSmallFont());
		legend.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		legend.setFont(FontManager.getRunescapeSmallFont());
		legend.setText("<html><span style='color:#999'>gp/h · buy→sell +margin ×qty · ~fill · ↻limit · %fill · ★basket ×qty · ✓margin-verified · 🔥dip · ⚠decline · ⚡volatile · ⏳low</span></html>");
		legend.setToolTipText("<html><div width=340><b>How to read a row</b><br>"
			+ "<b>gp/h</b> — est. profit per hour (green=reliable fill, orange=so-so, red=thin).<br>"
			+ "<b>buy→sell +margin ×qty</b> — list a buy at the first, a sell at the second; profit each after tax × how many.<br>"
			+ "<b>~fill</b> — est. time both offers take to fill · <b>%fill</b> — chance the round-trip completes in 4h · <b>↻</b> buy-limit reset.<br>"
			+ "<b>★basket ×N</b> — put N of these in a GE slot (your cash + slots plan) · <b>✓</b> margin held up in the last 2h (timeseries-verified).<br>"
			+ "<b>🔥</b> cheap vs its norm (a dip) · <b>⚠</b> long-term decline · <b>⚡</b> volatile (can flip red) · <b>⏳</b> thin market.<br>"
			+ "Hover any row for its full plain-English reasoning + your own record on that item.<br><br>"
			+ "<b>Tabs:</b> Flips (all finds) · Dips (cheap-right-now) · Decant (potion arbitrage) · Sets (armour-set arbitrage) · You (your P&L, To-sell, journal).</div></html>");

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
		capitalLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		capitalLabel.setFont(FontManager.getRunescapeSmallFont());
		capitalLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
		// price-check: type ANY item to see its recommended buy/sell (works for anything,
		// not just holdings/scan) — fixes "I can't see what to sell X at".
		priceResult.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		priceResult.setFont(FontManager.getRunescapeSmallFont());
		priceResult.setAlignmentX(Component.LEFT_ALIGNMENT);
		priceInput.setToolTipText("Type any item name → its recommended buy/sell price");
		priceInput.putClientProperty("JTextField.placeholderText", "price-check any item…");
		priceInput.setMaximumSize(new Dimension(Integer.MAX_VALUE, priceInput.getPreferredSize().height));
		priceInput.setAlignmentX(Component.LEFT_ALIGNMENT);
		priceInput.addActionListener(e -> {
			if (onPriceCheck != null) priceResult.setText("<html>" + onPriceCheck.apply(priceInput.getText()) + "</html>");
		});
		// price-check input + a ☆ to add the checked item to your watchlist
		JButton watchBtn = mini("☆", "Watch the item you just priced — pings you when it goes cheap.");
		watchBtn.addActionListener(e -> { if (onWatchLast != null) onWatchLast.run(); });
		JPanel priceRow = new JPanel(new BorderLayout(4, 0));
		priceRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		priceRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, priceInput.getPreferredSize().height));
		priceRow.add(priceInput, BorderLayout.CENTER);
		priceRow.add(watchBtn, BorderLayout.EAST);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.add(refresh);
		top.add(status);
		top.add(combined);
		top.add(bankLabel);
		top.add(capitalLabel);
		top.add(priceRow);
		top.add(priceResult);
		top.add(legend);

		// --- tab bar: Flips | Dips | You ---
		JPanel tabBar = new JPanel(new GridLayout(2, 3, 2, 2));
		tabBar.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
		tabBar.add(tabFlips); tabBar.add(tabDips); tabBar.add(tabDecant); tabBar.add(tabSets); tabBar.add(tabAlch); tabBar.add(tabYou);
		for (JButton b : new JButton[]{tabFlips, tabDips, tabDecant, tabSets, tabAlch, tabYou})
			b.setMargin(new java.awt.Insets(2, 1, 2, 1));
		tabFlips.addActionListener(e -> showCard("flips"));
		tabDips.addActionListener(e -> showCard("dips"));
		tabDecant.addActionListener(e -> showCard("decant"));
		tabSets.addActionListener(e -> showCard("sets"));
		tabAlch.addActionListener(e -> showCard("alch"));
		tabYou.addActionListener(e -> showCard("you"));

		JPanel north = new JPanel(new BorderLayout());
		north.add(top, BorderLayout.NORTH);
		north.add(tabBar, BorderLayout.SOUTH);
		add(north, BorderLayout.NORTH);

		// --- cards ---
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		dipsRows.setLayout(new BoxLayout(dipsRows, BoxLayout.Y_AXIS));
		decantRows.setLayout(new BoxLayout(decantRows, BoxLayout.Y_AXIS));
		alchRows.setLayout(new BoxLayout(alchRows, BoxLayout.Y_AXIS));
		setsRows.setLayout(new BoxLayout(setsRows, BoxLayout.Y_AXIS));
		offersBox.setLayout(new BoxLayout(offersBox, BoxLayout.Y_AXIS));
		holdBox.setLayout(new BoxLayout(holdBox, BoxLayout.Y_AXIS));
		perfBox.setLayout(new BoxLayout(perfBox, BoxLayout.Y_AXIS));
		suppressedBox.setLayout(new BoxLayout(suppressedBox, BoxLayout.Y_AXIS));
		accountBox.setLayout(new BoxLayout(accountBox, BoxLayout.Y_AXIS));
		accountBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		suppressedBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		watchBox.setLayout(new BoxLayout(watchBox, BoxLayout.Y_AXIS));

		// "You" card = session P&L + your record + what to sell + best items + live GE offers
		JPanel youBox = new JPanel();
		youBox.setLayout(new BoxLayout(youBox, BoxLayout.Y_AXIS));
		session.setAlignmentX(Component.LEFT_ALIGNMENT);
		calib.setAlignmentX(Component.LEFT_ALIGNMENT);
		offersBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		holdBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		perfBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		watchBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		youBox.add(session);
		youBox.add(calib);
		youBox.add(accountBox);
		youBox.add(holdBox);
		youBox.add(watchBox);
		youBox.add(perfBox);
		youBox.add(suppressedBox);
		youBox.add(offersBox);
		// reset the realized-P&L journal (keeps watchlist + exclude list). Confirmed first — it wipes
		// your fill history, used when the numbers got polluted (e.g. a one-time upgrade double-book).
		JButton resetBtn = new JButton("reset journal");
		resetBtn.setFont(FontManager.getRunescapeSmallFont());
		resetBtn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		resetBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
		resetBtn.setToolTipText("Wipe realized P&L + fill history and start the stats clean. Your watchlist "
			+ "and \"not a flip\" exclude list are kept. This cannot be undone.");
		resetBtn.addActionListener(e -> {
			int r = javax.swing.JOptionPane.showConfirmDialog(this,
				"Wipe your flip journal (all recorded fills + realized P&L) and start clean?\n"
				+ "Your watchlist and exclude list are kept. This cannot be undone.",
				"Reset journal", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
			if (r == javax.swing.JOptionPane.YES_OPTION && onResetJournal != null) onResetJournal.run();
		});
		youBox.add(javax.swing.Box.createVerticalStrut(6));
		youBox.add(resetBtn);

		cardPanel.add(scrollOf(rows), "flips");
		cardPanel.add(scrollOf(dipsRows), "dips");
		cardPanel.add(scrollOf(decantRows), "decant");
		cardPanel.add(scrollOf(setsRows), "sets");
		cardPanel.add(scrollOf(alchRows), "alch");
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
		tabDecant.setForeground("decant".equals(name) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabSets.setForeground("sets".equals(name) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabAlch.setForeground("alch".equals(name) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabYou.setForeground("you".equals(name) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** Render the high-alch edge: items whose buy price + nature rune is below their alch value (tax-free). */
	void setAlch(List<GeflipScanner.Alch> alchs)
	{
		SwingUtilities.invokeLater(() ->
		{
			alchRows.removeAll();
			JLabel hint = new JLabel("<html><span style='color:#999'>buy < alch value → High Alch (55 Mag). NO GE tax — only the buy costs.</span></html>");
			hint.setFont(FontManager.getRunescapeSmallFont());
			hint.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			hint.setAlignmentX(Component.LEFT_ALIGNMENT);
			alchRows.add(hint);
			if (alchs == null || alchs.isEmpty())
			{
				JLabel none = new JLabel("no profitable alchs right now");
				none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				alchRows.add(none);
			}
			else for (GeflipScanner.Alch a : alchs) alchRows.add(alchRow(a));
			tabAlch.setText(alchs != null && !alchs.isEmpty() ? "Alch (" + alchs.size() + ")" : "Alch");
			alchRows.revalidate(); alchRows.repaint();
		});
	}

	private JPanel alchRow(GeflipScanner.Alch a)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel(a.name);
		name.setForeground(ColorScheme.TEXT_COLOR);
		JLabel prof = new JLabel("+" + gp(a.profit) + "/ea");
		prof.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prof.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER);
		top.add(prof, BorderLayout.EAST);
		JLabel sub = new JLabel("buy @" + gp(a.buy) + "  →  alch " + gp(a.alch) + "   ·   limit " + a.limit + "/4h  (~" + gp((long) a.profit * a.limit) + "/limit)");
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top); col.add(sub);
		p.add(col, BorderLayout.CENTER);
		name.setToolTipText("Buy at ~" + gp(a.buy) + ", High Alch for " + gp(a.alch) + " → +" + gp(a.profit)
			+ " each (after the nature rune, no GE tax). Buy limit " + a.limit + "/4h. Click to copy the name.");
		final String copyName = a.name;
		p.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override public void mouseClicked(java.awt.event.MouseEvent e)
			{
				java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(copyName);
				java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
				status.setText("copied \"" + copyName + "\"");
			}
		});
		return p;
	}

	void setStatus(String s) { SwingUtilities.invokeLater(() -> status.setText(s)); }

	/** Show the bankroll the scan is sizing to (your real coins when auto is on). */
	private volatile long bankrollGp = 0;   // last known bankroll, so the headline can cap by what you can fund

	void setBankroll(long gp, boolean auto)
	{
		bankrollGp = gp;
		SwingUtilities.invokeLater(() ->
			bankLabel.setText("bankroll: " + gp(gp) + (auto ? " (your coins)" : " (manual)")));
	}

	/** Capital-utilization meter: how much of the bankroll is WORKING (pending buys + held stock)
	 *  vs idle coins, and how many of the 8 GE slots are in use. Idle capital / idle slots are the
	 *  #1 throughput leak in flipping, so this makes them visible. */
	void setCapital(long working, long bankroll, int slotsUsed)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (bankroll <= 0) { capitalLabel.setText(" "); return; }
			int pct = (int) Math.round(100.0 * Math.min(working, bankroll) / bankroll);
			long idle = Math.max(0, bankroll - working);
			capitalLabel.setText("capital: " + gp(working) + " working (" + pct + "%) · "
				+ gp(idle) + " idle · " + slotsUsed + "/8 slots");
			// nudge orange when you're leaving a lot on the table (idle coins AND free slots)
			boolean leak = slotsUsed < 8 && pct < 60;
			capitalLabel.setForeground(leak ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
			capitalLabel.setToolTipText("Working = gp tied up in pending buys + stock you hold. Idle = coins "
				+ "sitting in your pocket. Slots = GE offers in use of 8. Idle capital and free slots are "
				+ "unearned gp/hr — fill them to raise your rate.");
		});
	}

	void setSession(GeflipLedger l)
	{
		SwingUtilities.invokeLater(() ->
		{
			String txt = "flip " + gp(l.realizedFlip);
			if (l.openUnits > 0) txt += " · held " + gp(l.inventoryCost);
			if (l.keptNet != 0) txt += " · kept " + gp(l.keptNet);
			if (l.unmatchedProceeds != 0) txt += " · sold stock " + gp(l.unmatchedProceeds);
			session.setText(txt);
			session.setForeground(l.realizedFlip >= 0
				? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.PROGRESS_ERROR_COLOR);
			session.setToolTipText("flip = profit from matched buy→sell round-trips (net of tax)   ·   "
				+ "held = cost of bought-but-unsold flip items   ·   "
				+ "kept = net spent on your \"not a flip\" items   ·   "
				+ "sold stock = proceeds from selling items the plugin never saw you buy (bank / "
				+ "pre-install / mobile) — NOT flip profit, there's no cost basis to measure a flip");

			// CALIBRATION: your ACTUAL track record, so you can trust (or discount) the gp/h estimates
			if (l.flips > 0)
			{
				double h = l.avgHoldHours();
				String hold = h >= 24 ? String.format("%.1fd", h / 24) : h >= 1 ? Math.round(h) + "h" : Math.max(1, (int) Math.round(h * 60)) + "min";
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

	/** Render "Watch" — pinned items with live buy/sell; cheap ones flagged. */
	void setWatch(List<GeflipPlugin.Watch> watches)
	{
		SwingUtilities.invokeLater(() ->
		{
			watchBox.removeAll();
			if (watches != null && !watches.isEmpty())
			{
				JLabel hdr = new JLabel("Watch");
				hdr.setForeground(ColorScheme.BRAND_ORANGE);
				hdr.setBorder(BorderFactory.createEmptyBorder(6, 1, 2, 1));
				hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
				watchBox.add(hdr);
				for (GeflipPlugin.Watch w : watches) watchBox.add(watchRow(w));
			}
			watchBox.revalidate();
			watchBox.repaint();
		});
	}

	private JPanel watchRow(GeflipPlugin.Watch w)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel name = new JLabel((w.cheap ? "🔥 " : "") + w.name);
		name.setForeground(w.cheap ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
		JLabel px = new JLabel((w.buy > 0 ? "buy " + gp(w.buy) : "") + (w.sell > 0 ? "  sell " + gp(w.sell) : ""));
		px.setFont(FontManager.getRunescapeSmallFont());
		px.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JButton rm = mini("✕", "Stop watching this item");
		rm.addActionListener(e -> { if (onUnwatch != null) onUnwatch.accept(w.id); });
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		name.setAlignmentX(Component.LEFT_ALIGNMENT); px.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(name); col.add(px);
		p.add(col, BorderLayout.CENTER);
		p.add(rm, BorderLayout.EAST);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** Render "Best items" — your realized profit per item (journal analytics). */
	void setTopItems(List<String> lines)
	{
		SwingUtilities.invokeLater(() ->
		{
			perfBox.removeAll();
			if (lines != null && !lines.isEmpty())
			{
				JLabel hdr = new JLabel("Best items (realized)");
				hdr.setForeground(ColorScheme.BRAND_ORANGE);
				hdr.setBorder(BorderFactory.createEmptyBorder(6, 1, 2, 1));
				hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
				perfBox.add(hdr);
				for (String s : lines)
				{
					JLabel l = new JLabel(s);
					l.setFont(FontManager.getRunescapeSmallFont());
					l.setForeground(s.contains(": +") ? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.PROGRESS_ERROR_COLOR);
					l.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
					l.setAlignmentX(Component.LEFT_ALIGNMENT);
					perfBox.add(l);
				}
			}
			perfBox.revalidate();
			perfBox.repaint();
		});
	}

	/** Render "For your account" — the shopping list the Coach published (farm seeds/supplies), priced
	 *  live so buying your progression is one glance. The cross-reference between the two plugins. */
	void setAccountNeeds(List<String> lines)
	{
		SwingUtilities.invokeLater(() ->
		{
			accountBox.removeAll();
			if (lines != null && !lines.isEmpty())
			{
				JLabel hdr = new JLabel("🎯 For your account (from Coach)");
				hdr.setForeground(ColorScheme.BRAND_ORANGE);
				hdr.setBorder(BorderFactory.createEmptyBorder(6, 1, 2, 1));
				hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
				hdr.setToolTipText("What your Coach says your account needs right now (farm-run seeds/supplies), "
					+ "priced live here so you can buy your progression efficiently. 🔥 = cheap vs its norm.");
				accountBox.add(hdr);
				for (String s : lines)
				{
					JLabel l = new JLabel(s);
					l.setFont(FontManager.getRunescapeSmallFont());
					l.setForeground(s.contains("🔥") ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
					l.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
					l.setAlignmentX(Component.LEFT_ALIGNMENT);
					accountBox.add(l);
				}
			}
			accountBox.revalidate();
			accountBox.repaint();
		});
	}

	/** Render "Your winners — not showing now" — proven items absent from the list + why (#1). */
	void setSuppressedWinners(List<String> lines)
	{
		SwingUtilities.invokeLater(() ->
		{
			suppressedBox.removeAll();
			if (lines != null && !lines.isEmpty())
			{
				JLabel hdr = new JLabel("Your winners — not flippable now");
				hdr.setForeground(ColorScheme.BRAND_ORANGE);
				hdr.setBorder(BorderFactory.createEmptyBorder(6, 1, 2, 1));
				hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
				hdr.setToolTipText("Items your journal shows you profit on that aren't in the current list — "
					+ "and the reason each is suppressed right now, so the list never looks arbitrary.");
				suppressedBox.add(hdr);
				for (String s : lines)
				{
					JLabel l = new JLabel(s);
					l.setFont(FontManager.getRunescapeSmallFont());
					l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					l.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
					l.setAlignmentX(Component.LEFT_ALIGNMENT);
					suppressedBox.add(l);
				}
			}
			suppressedBox.revalidate();
			suppressedBox.repaint();
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
		if (h.sellHint > 0 && h.avgCost < 0)
		{
			// untracked item (in your bag, no flip cost basis) — just tell you where to list it
			JLabel sub = new JLabel("sell @ " + gp(h.sellHint) + "   (no cost tracked)");
			sub.setFont(FontManager.getRunescapeSmallFont());
			sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			p.add(sub, BorderLayout.SOUTH);
			p.setToolTipText("You're holding " + h.qty + " — not from a tracked flip buy, so there's no cost basis. "
				+ "List a sell at ~" + gp(h.sellHint) + ". Use ✎ to set your real cost, or ⊘ to hide if it's personal.");
		}
		else if (h.sellHint > 0)
		{
			long tax = (h.exempt || h.sellHint < 50) ? 0 : Math.min((long) (h.sellHint * 0.02), 5_000_000);
			long net = h.sellHint - tax;
			boolean profit = net >= h.avgCost;
			boolean taxTrap = !profit && (h.sellHint - h.avgCost) >= 0;   // raw spread ok, tax eats it
			line2 = "cost " + gp(h.avgCost) + "  →  sell @ " + gp(h.sellHint)
				+ "  (" + (profit ? "+" : "") + gp(net - h.avgCost) + "/ea)" + (taxTrap ? "  ⚠tax" : "");
			JLabel sub = new JLabel(line2);
			sub.setFont(FontManager.getRunescapeSmallFont());
			sub.setForeground(profit ? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.PROGRESS_ERROR_COLOR);
			p.add(sub, BorderLayout.SOUTH);
			p.setToolTipText("You hold " + h.qty + " at ~" + gp(h.avgCost) + " each. List a sell at ~"
				+ gp(h.sellHint) + " to fill; "
				+ (taxTrap ? "raw spread is positive but the 2% tax (" + gp(tax) + ") eats it — this can only lose. Hold for a wider gap or cut."
					: profit ? "that's a profit." : "that's a LOSS — decide cut vs hold."));
		}
		else
		{
			JLabel sub = new JLabel("cost " + gp(h.avgCost) + "  ·  no live price");
			sub.setFont(FontManager.getRunescapeSmallFont());
			sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			p.add(sub, BorderLayout.SOUTH);
		}
		p.add(name, BorderLayout.CENTER);
		// row actions: ✓ sold · ✎ fix cost · ⊘ personal use
		JPanel actions = new JPanel(new GridLayout(1, 3, 2, 0));
		actions.setOpaque(false);
		JButton sold = mini("✓", "Mark as sold — records the sale (e.g. you sold it on mobile) and clears it.");
		sold.addActionListener(e -> { if (onClearHold != null) onClearHold.accept(h.id); });
		JButton edit = mini("✎", "Fix the cost — enter what you ACTUALLY paid per item if the number's wrong.");
		edit.addActionListener(e -> {
			String in = javax.swing.JOptionPane.showInputDialog(this, "Your real cost per " + h.name + " (gp):", h.avgCost);
			if (in != null && onEditCost != null)
			{
				try { onEditCost.accept(h.id, Long.parseLong(in.trim().replaceAll("[,_ ]", ""))); }
				catch (NumberFormatException ignored) { }
			}
		});
		JButton keep = mini("⊘", "Personal use — I don't flip this (e.g. Purple sweets). Hide it from To sell + flip P&L.");
		keep.addActionListener(e -> { if (onPersonalUse != null) onPersonalUse.accept(h.id); });
		actions.add(sold); actions.add(edit); actions.add(keep);
		p.add(actions, BorderLayout.EAST);
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
			// Headline = the realistic slate you can actually FUND and that will fill: fill the 8 slots
			// top-down, skip won't-fill rows, and stop once the committed capital (qty*buy) reaches your
			// bankroll — so the number isn't 8 flips each sized to 25% of your coins (a 200% fantasy).
			double top = 0; int slots = 0; long spent = 0; long bank = bankrollGp;
			for (GeflipScanner.Flip f : flips)
			{
				rows.add(rowFor(f));
				if (f.dumping) { dipsRows.add(rowFor(f)); dips++; }   // 🔥 cheap vs its recent norm
				if (slots >= 8 || f.wontFill) continue;
				long cost = (long) f.buy * f.quantity;
				if (bank > 0 && spent + cost > bank && slots > 0) continue;   // can't fund this one — skip it
				top += f.expGph; spent += cost; slots++;
			}
			combined.setText(slots > 0 ? "≈ " + gp((long) top) + "/hr across " + slots + " slots" : " ");
			combined.setToolTipText("Your realistic earn rate = the flips you can actually run at once — capped "
				+ "at your bankroll (" + gp(bank) + ") and skipping ones that likely won't fill. Raise your "
				+ "Bankroll/coins to fund bigger or more slots.");
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

	/** Render decanting opportunities: buy cheapest dose → decant to (4) at Bob Barter → sell. */
	void setDecants(List<GeflipScanner.Decant> decants)
	{
		SwingUtilities.invokeLater(() ->
		{
			decantRows.removeAll();
			JLabel hint = new JLabel("<html><span style='color:#999'>buy the cheap dose, decant to (4) free at Bob Barter, sell</span></html>");
			hint.setFont(FontManager.getRunescapeSmallFont());
			hint.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			hint.setAlignmentX(Component.LEFT_ALIGNMENT);
			decantRows.add(hint);
			if (decants == null || decants.isEmpty())
			{
				JLabel none = new JLabel("no profitable decants right now");
				none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				decantRows.add(none);
			}
			else for (GeflipScanner.Decant d : decants) decantRows.add(decantRow(d));
			tabDecant.setText(decants != null && !decants.isEmpty() ? "Decant (" + decants.size() + ")" : "Decant");
			decantRows.revalidate(); decantRows.repaint();
		});
	}

	/** Render set-exchange arbitrage (combine pieces↔set at the GE clerk, free). */
	void setSets(List<GeflipScanner.SetFlip> sets)
	{
		SwingUtilities.invokeLater(() ->
		{
			setsRows.removeAll();
			JLabel hint = new JLabel("<html><span style='color:#999'>combine/split at the GE clerk (free) for the spread</span></html>");
			hint.setFont(FontManager.getRunescapeSmallFont());
			hint.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
			hint.setAlignmentX(Component.LEFT_ALIGNMENT);
			setsRows.add(hint);
			if (sets == null || sets.isEmpty())
			{
				JLabel none = new JLabel("no profitable set flips right now");
				none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				setsRows.add(none);
			}
			else for (GeflipScanner.SetFlip s : sets) setsRows.add(setRow(s));
			tabSets.setText(sets != null && !sets.isEmpty() ? "Sets (" + sets.size() + ")" : "Sets");
			setsRows.revalidate(); setsRows.repaint();
		});
	}

	private JPanel setRow(GeflipScanner.SetFlip s)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel(s.name);
		name.setForeground(ColorScheme.TEXT_COLOR);
		JLabel prof = new JLabel("+" + gp(s.profit));
		prof.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prof.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER);
		top.add(prof, BorderLayout.EAST);
		JLabel sub = new JLabel(s.dir + "   (" + gp(s.buyTotal) + " → " + gp(s.sellNet) + ")");
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top); col.add(sub);
		p.add(col, BorderLayout.CENTER);
		name.setToolTipText(s.name + ": " + s.dir + " for +" + gp(s.profit) + " (net of tax). Combine/split is free at a GE clerk.");
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JPanel decantRow(GeflipScanner.Decant d)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel(d.name);
		name.setForeground(ColorScheme.TEXT_COLOR);
		JLabel prof = new JLabel("+" + gp(d.profitPer4) + "/ea");
		prof.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prof.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER);
		top.add(prof, BorderLayout.EAST);
		JLabel sub = new JLabel("buy " + d.buyLabel + " @" + gp(d.buyPrice) + "  →  sell (4) @" + gp(d.sell4));
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top); col.add(sub);
		p.add(col, BorderLayout.CENTER);
		name.setToolTipText("Buy " + d.buyLabel + " at ~" + gp(d.buyPrice) + ", decant to (4) free at Bob Barter, "
			+ "sell (4) at ~" + gp(d.sell4) + " → +" + gp(d.profitPer4) + " each after tax. Click to copy the name.");
		final String copyName = d.name;
		p.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override public void mouseClicked(java.awt.event.MouseEvent e)
			{
				java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(copyName);
				java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
				status.setText("copied \"" + copyName + "\"");
			}
		});
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JPanel rowFor(GeflipScanner.Flip f)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// --- line 1: item name (left, clips if long) + gp/hour headline (right) ---
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		String tag = (f.basketQty > 0 ? "★ " : "") + (f.dumping ? "🔥 " : "") + (f.decliner ? "⚠ " : "")
			+ (f.unstable ? "⚡ " : "") + (f.tsChecked && f.marginPersist >= 0.7 ? "✓ " : "");
		JLabel name = new JLabel(tag + f.name);
		name.setForeground(f.decliner ? ColorScheme.PROGRESS_ERROR_COLOR
			: f.unstable ? ColorScheme.PROGRESS_INPROGRESS_COLOR
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
		String bask = f.basketQty > 0 ? "   ★×" + f.basketQty : "";   // suggested slot size (#3)
		JLabel sub = new JLabel(gp(f.buy) + " → " + gp(f.sell)
			+ "   +" + gp(f.margin) + "×" + f.quantity + " = +" + gp((long) f.margin * f.quantity)
			+ (ft.isEmpty() ? "" : "   " + ft) + reset + fill + bask);
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

		// full plain-English explanation of this row on hover (escape name/why — this is an HTML label)
		StringBuilder tip = new StringBuilder("<html><b>").append(esc(f.name)).append("</b><br>");
		if (f.why != null && !f.why.isEmpty()) tip.append("<i>").append(esc(f.why)).append("</i><br>");
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
		// timeseries grounding (#1) + your own record (#2) + basket (#3)
		if (f.tsChecked) tip.append(f.marginPersist >= 0.7 ? "✓ margin held ~" : "margin present ~")
			.append((int) (f.marginPersist * 100)).append("% of the last 2h")
			.append(f.tsDir < -0.03 ? ", price falling ~" + (int) Math.round(-f.tsDir * 100) + "%" : "").append("<br>");
		if (f.personalized && f.yourWinRate >= 0)
		{
			tip.append("● your record here: ").append((int) Math.round(f.yourWinRate * 100)).append("% of past flips paid");
			if (f.yourMarginPer >= 0 || f.yourHoldH > 0)
				tip.append(" (you net ~").append(gp((long) f.yourMarginPer)).append("/ea")
					.append(f.yourHoldH > 0 ? ", ~" + fillTxt(f.yourHoldH).replace("~", "") + " hold" : "").append(")");
			tip.append("<br>");
		}
		if (f.basketQty > 0) tip.append("★ suggested basket: put ").append(f.basketQty).append(" of these in a GE slot<br>");
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

	private static JButton mini(String text, String tip)
	{
		JButton b = new JButton(text);
		b.setToolTipText(tip);
		b.setMargin(new java.awt.Insets(0, 2, 0, 2));
		b.setFont(FontManager.getRunescapeSmallFont());
		return b;
	}

	/** Escape the HTML-special chars so an item name (a stray &, or a future non-Jagex source)
	 *  can't break or inject into a Swing &lt;html&gt; label. */
	private static String esc(String s)
	{
		if (s == null) return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
