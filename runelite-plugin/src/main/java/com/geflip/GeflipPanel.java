package com.geflip;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.ProgressBar;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The side panel: a ranked flip list + your live session P&L. Display only — tapping
 * a row copies the item name to the clipboard so you can paste it into the GE search
 * (the same "one-tap name" convenience the web app has). It never sends game input.
 */
class GeflipPanel extends PluginPanel
{
	private final ItemManager itemManager;   // draws item sprites on every row (getImage().addTo(label))
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
	private final JPanel alchRows = new JPanel();       // Edges tab: high-alch (buy < alch value, tax-free)
	private final JPanel procRows = new JPanel();       // Edges tab: processing arbitrage (make → sell)
	private final JPanel repairRows = new JPanel();     // Edges tab: Barrows-repair arbitrage (buy broken → repair → sell)
	private final JPanel moverRows = new JPanel();      // Edges tab: abnormal price+volume movers
	private final JPanel setsRows = new JPanel();      // Sets tab (combine/split set exchange)
	private final JPanel offersBox = new JPanel();     // "Your GE" — live open offers (You tab)
	private final JPanel holdBox = new JPanel();       // "To sell" — items you hold + sell price (You tab)
	private final JPanel perfBox = new JPanel();       // "Best items" — realized per-item profit (You tab)
	private final JPanel suppressedBox = new JPanel(); // "Winners not showing" — proven items + why (You tab)
	private final JPanel stableBox = new JPanel();      // "Your stable" — consistent-winner items (You tab)
	private final JPanel accountBox = new JPanel();     // "For your account" — Coach shopping list priced (You tab)
	private final java.awt.CardLayout cards = new java.awt.CardLayout();
	private final JPanel cardPanel = new JPanel(cards);
	private final JButton tabFlips = new JButton("Flips");
	private final JButton tabDips = new JButton("Dips");
	private final JButton tabDecant = new JButton("Decant");
	private final JButton tabSets = new JButton("Sets");
	private final JButton tabAlch = new JButton("Edges");
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

	GeflipPanel(ItemManager itemManager, Runnable onRefresh, java.util.function.IntConsumer onClearHold,
		java.util.function.Function<String, String> onPriceCheck,
		java.util.function.ObjLongConsumer<Integer> onEditCost,
		java.util.function.IntConsumer onPersonalUse,
		Runnable onWatchLast, java.util.function.IntConsumer onUnwatch,
		Runnable onResetJournal)
	{
		this.itemManager = itemManager;
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
		legend.setText("<html><span style='color:#999'>gp/h · buy→sell +margin ×qty · ~fill · ↻limit · %fill · ★basket ×qty · 🛡trust(is the margin real?) · ✓margin-verified · 🔥dip · ⚠decline · ⚡volatile · ⏳low</span></html>");
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
		procRows.setLayout(new BoxLayout(procRows, BoxLayout.Y_AXIS));
		repairRows.setLayout(new BoxLayout(repairRows, BoxLayout.Y_AXIS));
		moverRows.setLayout(new BoxLayout(moverRows, BoxLayout.Y_AXIS));
		setsRows.setLayout(new BoxLayout(setsRows, BoxLayout.Y_AXIS));
		offersBox.setLayout(new BoxLayout(offersBox, BoxLayout.Y_AXIS));
		holdBox.setLayout(new BoxLayout(holdBox, BoxLayout.Y_AXIS));
		perfBox.setLayout(new BoxLayout(perfBox, BoxLayout.Y_AXIS));
		suppressedBox.setLayout(new BoxLayout(suppressedBox, BoxLayout.Y_AXIS));
		accountBox.setLayout(new BoxLayout(accountBox, BoxLayout.Y_AXIS));
		accountBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		stableBox.setLayout(new BoxLayout(stableBox, BoxLayout.Y_AXIS));
		stableBox.setAlignmentX(Component.LEFT_ALIGNMENT);
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
		youBox.add(stableBox);
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
		JPanel edgesBox = new JPanel();
		edgesBox.setLayout(new BoxLayout(edgesBox, BoxLayout.Y_AXIS));
		alchRows.setAlignmentX(Component.LEFT_ALIGNMENT); procRows.setAlignmentX(Component.LEFT_ALIGNMENT);
		repairRows.setAlignmentX(Component.LEFT_ALIGNMENT);
		moverRows.setAlignmentX(Component.LEFT_ALIGNMENT);
		edgesBox.add(alchRows); edgesBox.add(procRows); edgesBox.add(repairRows); edgesBox.add(moverRows);
		cardPanel.add(scrollOf(edgesBox), "alch");
		cardPanel.add(scrollOf(youBox), "you");
		add(cardPanel, BorderLayout.CENTER);
		showCard("flips");
	}

	private static JScrollPane scrollOf(JPanel content)
	{
		// horizontal scroll AS-NEEDED so any wide row (e.g. To-sell/offer controls on the You tab) is always
		// REACHABLE — never clipped off-edge with no way to get to it. Bar only appears if something overflows.
		JScrollPane s = new JScrollPane(content,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		s.setBorder(null);
		s.getVerticalScrollBar().setUnitIncrement(16);
		s.getHorizontalScrollBar().setUnitIncrement(16);
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
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (GeflipScanner.Alch a : alchs) comps.add(alchRow(a));
				addTopN(alchRows, comps, 8, true);
			}
			tabAlch.setText(alchs != null && !alchs.isEmpty() ? "Alch (" + alchs.size() + ")" : "Alch");
			alchRows.revalidate(); alchRows.repaint();
		});
	}

	/** Render abnormal movers: items whose price + volume are ramping vs their 24h norm (front-run / crash). */
	void setMovers(List<GeflipScanner.Mover> movers)
	{
		SwingUtilities.invokeLater(() ->
		{
			moverRows.removeAll();
			JLabel hint = new JLabel("<html><span style='color:#999'>— movers: abnormal price + volume vs 24h (front-run ⤴ / crash ⤵) —</span></html>");
			hint.setFont(FontManager.getRunescapeSmallFont());
			hint.setBorder(BorderFactory.createEmptyBorder(8, 6, 4, 6));
			hint.setAlignmentX(Component.LEFT_ALIGNMENT);
			moverRows.add(hint);
			if (movers == null || movers.isEmpty())
			{
				JLabel none = new JLabel("nothing unusual right now");
				none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				moverRows.add(none);
			}
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (GeflipScanner.Mover m : movers) comps.add(moverRow(m));
				addTopN(moverRows, comps, 8, true);
			}
			moverRows.revalidate(); moverRows.repaint();
		});
	}

	/** Render processing arbitrage: make → sell edges (cannonballs, planks, tanning, gems, cleaning…). */
	void setProcessing(List<GeflipScanner.Proc> procs)
	{
		SwingUtilities.invokeLater(() ->
		{
			procRows.removeAll();
			JLabel hint = new JLabel("<html><span style='color:#999'>— processing: buy inputs → make → sell (tax on the sale only) —</span></html>");
			hint.setFont(FontManager.getRunescapeSmallFont());
			hint.setBorder(BorderFactory.createEmptyBorder(8, 6, 4, 6));
			hint.setAlignmentX(Component.LEFT_ALIGNMENT);
			procRows.add(hint);
			if (procs == null || procs.isEmpty())
			{
				JLabel none = new JLabel("no profitable processing right now");
				none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				procRows.add(none);
			}
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (GeflipScanner.Proc pr : procs) comps.add(procRow(pr));
				addTopN(procRows, comps, 8, true);
			}
			procRows.revalidate(); procRows.repaint();
		});
	}

	void setRepairs(List<GeflipScanner.Repair> repairs)
	{
		SwingUtilities.invokeLater(() ->
		{
			repairRows.removeAll();
			JLabel hint = new JLabel("<html><span style='color:#999'>— Barrows repair: buy the broken (0) piece → repair → sell whole (set your Smithing in config) —</span></html>");
			hint.setFont(FontManager.getRunescapeSmallFont());
			hint.setBorder(BorderFactory.createEmptyBorder(8, 6, 4, 6));
			hint.setAlignmentX(Component.LEFT_ALIGNMENT);
			repairRows.add(hint);
			if (repairs == null || repairs.isEmpty())
			{
				JLabel none = new JLabel("no profitable Barrows repairs right now");
				none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				none.setFont(FontManager.getRunescapeSmallFont());
				none.setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
				none.setAlignmentX(Component.LEFT_ALIGNMENT);
				repairRows.add(none);
			}
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (GeflipScanner.Repair r : repairs) comps.add(repairRow(r));
				addTopN(repairRows, comps, 8, true);
			}
			repairRows.revalidate(); repairRows.repaint();
		});
	}

	private JPanel moverRow(GeflipScanner.Mover m)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(iconLabel(m.id, 36), BorderLayout.WEST);
		JLabel name = new JLabel((m.thin ? "⚠ " : m.crash ? "⤵ " : "⤴ ") + trunc(m.name, 16));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(m.thin ? ColorScheme.PROGRESS_INPROGRESS_COLOR
			: m.crash ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);
		JLabel r = new JLabel((m.priceRamp >= 0 ? "+" : "") + Math.round(m.priceRamp * 100) + "%  ·  "
			+ (m.thin ? "thin vol" : Math.round(m.volRatio) + "× vol"));
		r.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		r.setFont(FontManager.getRunescapeSmallFont());
		r.setHorizontalAlignment(JLabel.RIGHT);
		p.add(name, BorderLayout.CENTER); p.add(r, BorderLayout.EAST);
		p.setToolTipText(m.thin
			? m.name + " moved ~" + Math.round(Math.abs(m.priceRamp) * 100) + "% vs its 24h average on BELOW-normal volume — "
				+ "a thin/possibly-manipulated move (~" + gp(m.price) + "). Don't be the exit liquidity: avoid unless you know why."
			: m.name + " is " + (m.crash ? "crashing" : "spiking") + " ~" + Math.round(Math.abs(m.priceRamp) * 100)
				+ "% vs its 24h average on ~" + Math.round(m.volRatio) + "× normal volume (~" + gp(m.price) + "). "
				+ (m.crash ? "A dump — sell/avoid, or a dip to buy if it's a known-good item." : "A demand ramp — possible update front-run; buy before the crowd if you have a thesis."));
		p.addMouseListener(copyOnClick(m.name));
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JPanel procRow(GeflipScanner.Proc pr)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(iconLabel(pr.id, 36), BorderLayout.WEST);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel(trunc(pr.name, 22));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(ColorScheme.TEXT_COLOR);
		JLabel prof = new JLabel("+" + gp(pr.profit) + "/ea");
		prof.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		prof.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prof.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER); top.add(prof, BorderLayout.EAST);
		JLabel sub = new JLabel(gp(pr.buyCost) + " → " + gp(pr.sellNet) + (pr.limit > 0 ? "  ·  " + pr.limit + "/4h" : ""));
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top); col.add(sub);
		p.add(col, BorderLayout.CENTER);
		p.setToolTipText("Buy inputs ~" + gp(pr.buyCost) + " → sell output ~" + gp(pr.sellNet) + " (after tax) → +"
			+ gp(pr.profit) + " each" + (pr.limit > 0 ? ". Limit " + pr.limit + "/4h" : "") + ". Needs: " + pr.req);
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JPanel repairRow(GeflipScanner.Repair r)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(iconLabel(r.id, 36), BorderLayout.WEST);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel(trunc(r.name, 20));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(ColorScheme.TEXT_COLOR);
		JLabel prof = new JLabel("+" + gp(r.profit) + "/ea");
		prof.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		prof.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prof.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER); top.add(prof, BorderLayout.EAST);
		JLabel sub = new JLabel("buy " + gp(r.brokenBuy) + " + fix " + gp(r.cost) + " → " + gp(r.repairedSell));
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top); col.add(sub);
		p.add(col, BorderLayout.CENTER);
		p.setToolTipText("Buy broken ~" + gp(r.brokenBuy) + " + repair ~" + gp(r.cost)
			+ " → sell whole ~" + gp(r.repairedSell) + " (after 2% tax) → +" + gp(r.profit) + " each"
			+ (r.limit > 0 ? ". Limit " + r.limit + "/4h" : "") + ". Repair at a POH armour stand for the discount.");
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JPanel alchRow(GeflipScanner.Alch a)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(iconLabel(a.id, 36), BorderLayout.WEST);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel(trunc(a.name, 18));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(ColorScheme.TEXT_COLOR);
		JLabel prof = new JLabel("+" + gp(a.profit) + "/ea");
		prof.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		prof.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prof.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER);
		top.add(prof, BorderLayout.EAST);
		JLabel sub = new JLabel("buy @" + gp(a.buy) + " → alch " + gp(a.alch) + "  ·  " + a.limit + "/4h");
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top); col.add(sub);
		p.add(col, BorderLayout.CENTER);
		name.setToolTipText("Buy at ~" + gp(a.buy) + ", High Alch for " + gp(a.alch) + " → +" + gp(a.profit)
			+ " each (after the nature rune, no GE tax). Buy limit " + a.limit + "/4h (~" + gp((long) a.profit * a.limit) + "/limit). Click to copy the name.");
		p.addMouseListener(copyOnClick(a.name));
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
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
		p.add(iconLabel(w.id, 36), BorderLayout.WEST);
		JLabel name = new JLabel((w.cheap ? "🔥 " : "") + trunc(w.name, 18));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
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
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));
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
				List<JComponent> comps = new ArrayList<>();
				for (String s : lines)
				{
					JLabel l = new JLabel(wrap(s));
					l.setFont(FontManager.getRunescapeSmallFont());
					l.setForeground(s.contains(": +") ? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.PROGRESS_ERROR_COLOR);
					l.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
					l.setAlignmentX(Component.LEFT_ALIGNMENT);
					comps.add(l);
				}
				addTopN(perfBox, comps, 12, false);
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

	/** Render "◆ Your stable" — items you consistently profit on (flip these repeatedly). */
	void setStable(List<String> lines)
	{
		SwingUtilities.invokeLater(() ->
		{
			stableBox.removeAll();
			if (lines != null && !lines.isEmpty())
			{
				JLabel hdr = new JLabel("◆ Your stable — flip these repeatedly");
				hdr.setForeground(ColorScheme.BRAND_ORANGE);
				hdr.setBorder(BorderFactory.createEmptyBorder(6, 1, 2, 1));
				hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
				hdr.setToolTipText("Items your journal shows you consistently profit on (≥5 flips, ≥60% win, net positive) — "
					+ "your reliable core. ◆ marks them in the flip list too.");
				stableBox.add(hdr);
				for (String s : lines)
				{
					JLabel l = new JLabel(s);
					l.setFont(FontManager.getRunescapeSmallFont());
					l.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
					l.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
					l.setAlignmentX(Component.LEFT_ALIGNMENT);
					stableBox.add(l);
				}
			}
			stableBox.revalidate();
			stableBox.repaint();
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
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(iconLabel(h.id, 36), BorderLayout.WEST);
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		JLabel name = new JLabel(trunc(h.name, 16) + "  ×" + h.qty + (h.listed > 0 ? "  (" + h.listed + " listed)" : ""));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(ColorScheme.TEXT_COLOR);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		String line2;
		JLabel sub;
		if (h.sellHint > 0 && h.avgCost < 0)
		{
			// untracked item (in your bag, no flip cost basis) — just tell you where to list it
			sub = new JLabel("sell @ " + gp(h.sellHint) + "   (no cost tracked)");
			sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
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
			sub = new JLabel(line2);
			sub.setForeground(profit ? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.PROGRESS_ERROR_COLOR);
			p.setToolTipText("You hold " + h.qty + " at ~" + gp(h.avgCost) + " each. List a sell at ~"
				+ gp(h.sellHint) + " to fill; "
				+ (taxTrap ? "raw spread is positive but the 2% tax (" + gp(tax) + ") eats it — this can only lose. Hold for a wider gap or cut."
					: profit ? "that's a profit." : "that's a LOSS — decide cut vs hold."));
		}
		else
		{
			sub = new JLabel("cost " + gp(h.avgCost) + "  ·  no live price");
			sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		sub.setFont(FontManager.getRunescapeSmallFont());
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(name); col.add(sub);
		p.add(col, BorderLayout.CENTER);
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
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));   // fit the panel — no h-scroll to reach the buttons
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
		p.add(iconLabel(o.id, 36), BorderLayout.WEST);
		JLabel l = new JLabel((o.stale ? "⚠ " : buy ? "▼ " : "▲ ") + trunc(nm, 13) + " @" + gp(o.price));
		l.setForeground(o.stale ? ColorScheme.PROGRESS_ERROR_COLOR
			: buy ? ColorScheme.PROGRESS_INPROGRESS_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);
		String tail = o.qtySold + "/" + o.qtyTotal
			+ (done ? " ✓" : cancelled ? " ✕" : o.stale ? " stale" : "");
		JLabel r = new JLabel(tail);
		r.setForeground(done ? ColorScheme.PROGRESS_COMPLETE_COLOR
			: o.stale ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		r.setHorizontalAlignment(JLabel.RIGHT);
		r.setFont(FontManager.getRunescapeSmallFont());
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		top.add(l, BorderLayout.CENTER); top.add(r, BorderLayout.EAST);

		// SELL GUIDANCE right on the row: buys → what to sell it for after; sells → the reprice target.
		String guide = null; java.awt.Color gcol = ColorScheme.LIGHT_GRAY_COLOR;
		if (o.sellHint > 0)
		{
			if (buy)
				guide = "→ then sell at ~" + gp(o.sellHint);
			else
			{
				int tick = GeflipScanner.tickSize(o.sellHint);
				// EXACT prices (not the 0.1k-rounded gp()). And DON'T scream "reprice down" the instant you
				// list — a fresh sell above the current bid may still fill as buyers come in. Only HARD-warn
				// once it's actually sat unfilled (stale); until then just calmly inform. This kills the
				// "as soon as I list, it says price down" nag.
				if (o.price > o.sellHint + tick && o.stale)
				{ guide = "⚠ reprice ↓ to " + exact(o.sellHint) + " (you're at " + exact(o.price) + ", −" + exact(o.price - o.sellHint) + ")"; gcol = ColorScheme.PROGRESS_ERROR_COLOR; }
				else if (o.price > o.sellHint + tick)
					guide = "buyers ~" + exact(o.sellHint) + " · yours " + exact(o.price) + " — may fill if you wait, or undercut to sell now";
				else
					guide = "sell target ~" + exact(o.sellHint) + " — your price is fine";
			}
		}
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top);
		if (guide != null)
		{
			JLabel sub = new JLabel(guide);
			sub.setFont(FontManager.getRunescapeSmallFont());
			sub.setForeground(gcol);
			sub.setAlignmentX(Component.LEFT_ALIGNMENT);
			col.add(sub);
		}
		p.add(col, BorderLayout.CENTER);
		String age = o.ageSec >= 3600 ? "~" + (o.ageSec / 3600) + "h" : "~" + Math.max(1, o.ageSec / 60) + "m";
		l.setToolTipText(o.stale ? "Unfilled for " + age + " — the price moved; reprice it."
			: (buy ? "Once this buys, sell it at ~" + gp(o.sellHint) : "Market sell price is ~" + gp(o.sellHint)));
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));   // fit the panel — no h-scroll
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
			// ⭐ HERO — the ★-basket: the max-profit slate you can fund + fill this cycle, pinned at the top.
			java.util.List<GeflipScanner.Flip> basket = new ArrayList<>();
			long heroTotal = 0;
			// Headline = the realistic slate you can actually FUND and that will fill: fill the 8 slots
			// top-down, skip won't-fill rows, and stop once the committed capital (qty*buy) reaches your
			// bankroll — so the number isn't 8 flips each sized to 25% of your coins (a 200% fantasy).
			double top = 0; int slots = 0; long spent = 0; long bank = bankrollGp;
			List<JComponent> flipRows = new ArrayList<>();
			List<JComponent> dipRows = new ArrayList<>();
			for (GeflipScanner.Flip f : flips)
			{
				flipRows.add(flipRow(f));
				if (f.basketQty > 0) { basket.add(f); heroTotal += (long) f.margin * f.basketQty; }
				if (f.dumping) { dipRows.add(flipRow(f)); dips++; }   // 🔥 cheap vs its recent norm
				if (slots >= 8 || f.wontFill) continue;
				long cost = (long) f.buy * f.quantity;
				if (bank > 0 && spent + cost > bank && slots > 0) continue;   // can't fund this one — skip it
				top += f.expGph; spent += cost; slots++;
			}
			if (!basket.isEmpty())
			{
				rows.add(heroCard(basket, heroTotal));
				rows.add(gap());
				rows.add(header("RANKED FLIPS"));
				rows.add(gap());
			}
			if (flipRows.isEmpty()) rows.add(hint("no flips right now — hit Rescan (or widen your filters in config)."));
			else addTopN(rows, flipRows, flipRows.size(), true);   // Flips = the money surface → show them ALL (no 10-cap)
			combined.setText(slots > 0 ? "≈ " + gp((long) top) + "/hr across " + slots + " slots" : " ");
			combined.setToolTipText("Your realistic earn rate = the flips you can actually run at once — capped "
				+ "at your bankroll (" + gp(bank) + ") and skipping ones that likely won't fill. Raise your "
				+ "Bankroll/coins to fund bigger or more slots.");
			if (dips == 0) dipsRows.add(hint("no dips right now — nothing's trading below its norm."));
			else addTopN(dipsRows, dipRows, dipRows.size(), true);   // show all dips too
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
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (GeflipScanner.Decant d : decants) comps.add(decantRow(d));
				addTopN(decantRows, comps, 8, true);
			}
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
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (GeflipScanner.SetFlip s : sets) comps.add(setRow(s));
				addTopN(setsRows, comps, 8, true);
			}
			tabSets.setText(sets != null && !sets.isEmpty() ? "Sets (" + sets.size() + ")" : "Sets");
			setsRows.revalidate(); setsRows.repaint();
		});
	}

	private JPanel setRow(GeflipScanner.SetFlip s)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(iconLabel(s.id, 36), BorderLayout.WEST);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel(trunc(s.name, 18));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(ColorScheme.TEXT_COLOR);
		JLabel prof = new JLabel("+" + gp(s.profit));
		prof.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		prof.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prof.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER);
		top.add(prof, BorderLayout.EAST);
		JLabel sub = new JLabel(s.dir + "  (" + gp(s.buyTotal) + " → " + gp(s.sellNet) + ")");
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top); col.add(sub);
		p.add(col, BorderLayout.CENTER);
		p.setToolTipText(s.name + ": " + s.dir + " for +" + gp(s.profit) + " (net of tax). Combine/split is free at a GE clerk. Click to copy the name.");
		p.addMouseListener(copyOnClick(s.name));
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JPanel decantRow(GeflipScanner.Decant d)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(iconLabel(d.sell4Id, 36), BorderLayout.WEST);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel(trunc(d.name, 18));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(ColorScheme.TEXT_COLOR);
		JLabel prof = new JLabel("+" + gp(d.profitPer4) + "/ea");
		prof.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		prof.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prof.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER);
		top.add(prof, BorderLayout.EAST);
		JLabel sub = new JLabel("buy " + trunc(d.buyLabel, 14) + " @" + gp(d.buyPrice) + " → (4) @" + gp(d.sell4));
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top); col.add(sub);
		p.add(col, BorderLayout.CENTER);
		p.setToolTipText("Buy " + d.buyLabel + " at ~" + gp(d.buyPrice) + ", decant to (4) free at Bob Barter, "
			+ "sell (4) at ~" + gp(d.sell4) + " → +" + gp(d.profitPer4) + " each after tax. Click to copy the name.");
		p.addMouseListener(copyOnClick(d.name));
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** THE flip row: [icon 36] · bold name + colour-coded gp/h · buy→sell +margin×qty · optional
	 *  sparkline (grounded picks) · limit/basket chips · a slim trust ProgressBar as the bottom bar. */
	private JPanel flipRow(GeflipScanner.Flip f)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(iconLabel(f.id, 36), BorderLayout.WEST);

		// --- line 1: flag tags + item name (left, truncated) + gp/hour headline (right) ---
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		String tag = (f.personalized && f.yourWinRate >= 0.6 ? "◆ " : "")
			+ (f.dumping ? "🔥 " : "") + (f.decliner ? "⚠ " : "")
			+ (f.unstable ? "⚡ " : "") + (f.tsChecked && f.marginPersist >= 0.7 ? "✓ " : "");
		JLabel name = new JLabel(tag + trunc(f.name, 15));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(f.decliner ? ColorScheme.PROGRESS_ERROR_COLOR
			: f.unstable ? ColorScheme.PROGRESS_INPROGRESS_COLOR
			: f.dumping ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
		JLabel gph = new JLabel(gp((long) f.expGph) + "/h");
		gph.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		// colour the headline by fill confidence — the honest signal: a fat gp/h in orange/red
		// means it probably won't fill. Green = reliable, orange = so-so, red = thin.
		gph.setForeground(f.wontFill || f.fillProb < 0.4 ? ColorScheme.PROGRESS_ERROR_COLOR
			: f.fillProb < 0.7 ? ColorScheme.PROGRESS_INPROGRESS_COLOR : ColorScheme.GRAND_EXCHANGE_PRICE);
		gph.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER);
		top.add(gph, BorderLayout.EAST);

		// --- line 2: buy -> sell, margin×qty, and the ESTIMATED FILL TIME so the gp/h isn't a mystery ---
		String ft = fillTxt(f.fillHours);
		JLabel sub = new JLabel(gp(f.buy) + " → " + gp(f.sell)
			+ "  +" + gp(f.margin) + "×" + f.quantity + (ft.isEmpty() ? "" : "  " + ft));
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(FontManager.getRunescapeSmallFont());

		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top);
		col.add(sub);

		// #6 sparkline — only the grounded top picks carry a ~2h price series (bounded, so it stays cheap)
		if (f.series != null && f.series.length >= 2)
		{
			Spark spark = new Spark(f.series, PluginPanel.PANEL_WIDTH - 70, 20);
			spark.setAlignmentX(Component.LEFT_ALIGNMENT);
			col.add(Box.createRigidArea(new Dimension(0, 2)));
			col.add(spark);
		}

		// chips: buy-limit reset (#5) + suggested basket size (#3) + a thin-fill warning — visuals, not text noise
		JPanel chips = chipRow();
		if (f.resetMins > 0)
			chips.add(chip("↻ " + fmtMins(f.resetMins) + (f.limitLeft >= 0 ? " · " + gp(f.limitLeft) + " left" : ""),
				ColorScheme.PROGRESS_INPROGRESS_COLOR));
		if (f.basketQty > 0) chips.add(chip("★ ×" + f.basketQty, ColorScheme.BRAND_ORANGE));
		if (f.wontFill) chips.add(chip("⏳ thin", ColorScheme.PROGRESS_ERROR_COLOR));
		if (chips.getComponentCount() > 0) col.add(chips);

		// #4 trust as the thin bottom bar (green ≥70 / orange 45-69 / red <45) — "is this margin REAL?"
		if (f.trust >= 0)
		{
			col.add(Box.createRigidArea(new Dimension(0, 3)));
			col.add(trustBar(f.trust));
		}

		p.add(col, BorderLayout.CENTER);

		// full plain-English explanation of this row on hover (escape name/why — this is an HTML label)
		StringBuilder tip = new StringBuilder("<html><b>").append(esc(f.name)).append("</b><br>");
		if (f.why != null && !f.why.isEmpty()) tip.append("<i>").append(esc(f.why)).append("</i><br>");
		if (f.trust >= 0) tip.append("🛡 <b>Trust ").append(f.trust).append("/100</b> — is this margin REAL? blends how long the margin actually held, fill probability, and price stability (").append(f.trust >= 70 ? "trustworthy" : f.trust >= 45 ? "so-so — check the fill" : "shaky — treat with caution").append(")<br>");
		tip.append("Buy at ").append(gp(f.buy)).append(", sell at ").append(gp(f.sell))
			.append(" → ").append(gp(f.margin)).append(" profit each after tax<br>");
		tip.append("Buy up to ").append(f.quantity).append(" (bankroll/limit/volume capped)<br>");
		if (f.capAbsorb > 0) tip.append("Soaks up to ").append(gp(f.capAbsorb)).append(" of bank per 4h (limit × buy) — big-bank capital fit<br>");
		if (f.volCV >= 0) tip.append("Price volatility ~").append(String.format("%.1f", f.volCV * 100)).append("% (steadier = ranked higher)<br>");
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
		p.addMouseListener(copyOnClick(f.name));
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));
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

	/** Exact gp with thousands separators — for reprice guidance where a ~50gp gap must be visible
	 *  (the abbreviated gp() rounds to 0.1k and would show a real reprice as "4.2k → 4.2k"). */
	private static String exact(long v) { return String.format("%,d", v); }
	private static String sign(long v) { return v < 0 ? "-" : ""; }
	private static String pct(double v) { return String.format("%+.1f%%", v * 100); }
	private static String fillTxt(double h)
	{
		if (h >= 900 || Double.isNaN(h)) return "";
		if (h < 1) return "~" + Math.max(1, (int) Math.round(h * 60)) + "m";
		if (h < 24) return "~" + (h < 9.5 ? String.format("%.1f", h) : "" + (int) Math.round(h)) + "h";
		return "~" + String.format("%.1f", h / 24) + "d";
	}

	// ==================== house-style row-grammar helpers (match the Coach panel) ====================

	/** ⭐ BUY THESE NOW — the ★-basket picks as a 2x-weight hero card: the max-profit slate you can fund +
	 *  fill this cycle, with a header line showing the TOTAL expected gp across the slots. THE headline. */
	private JPanel heroCard(List<GeflipScanner.Flip> basket, long total)
	{
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARK_GRAY_COLOR);
		card.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(2, 0, 0, 0, ColorScheme.BRAND_ORANGE),
			BorderFactory.createEmptyBorder(6, 7, 7, 7)));
		card.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));

		JLabel h = new JLabel("⭐ BUY THESE NOW");
		h.setFont(FontManager.getRunescapeBoldFont());
		h.setForeground(ColorScheme.BRAND_ORANGE);
		h.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(h);
		JLabel tot = new JLabel("≈ +" + gp(total) + " this cycle · " + basket.size() + (basket.size() == 1 ? " slot" : " slots"));
		tot.setFont(FontManager.getRunescapeSmallFont());
		tot.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		tot.setAlignmentX(Component.LEFT_ALIGNMENT);
		tot.setToolTipText("The full ★-basket this scan: each pick sized to your cash + buy-limit, summed to the "
			+ "expected profit if the whole slate buys and sells this 4h cycle. This is the max-profit plan — buy these first.");
		card.add(tot);
		card.add(gap());
		for (GeflipScanner.Flip f : basket) { card.add(heroRow(f)); card.add(Box.createRigidArea(new Dimension(0, 3))); }
		return card;
	}

	/** One basket pick inside the hero card: icon · name · "buy N @buy → sell @sell" · expected profit (+ spark). */
	private JPanel heroRow(GeflipScanner.Flip f)
	{
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.add(iconLabel(f.id, 40), BorderLayout.WEST);
		JPanel col = new JPanel();
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setOpaque(false);
		JPanel top = new JPanel(new BorderLayout(6, 0));
		top.setOpaque(false);
		JLabel name = new JLabel(trunc(f.name, 16));
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(ColorScheme.TEXT_COLOR);
		JLabel prof = new JLabel("+" + gp((long) f.margin * f.basketQty));
		prof.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		prof.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prof.setHorizontalAlignment(JLabel.RIGHT);
		top.add(name, BorderLayout.CENTER); top.add(prof, BorderLayout.EAST);
		top.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel sub = new JLabel("buy " + f.basketQty + " @" + gp(f.buy) + " → sell @" + gp(f.sell));
		sub.setFont(FontManager.getRunescapeSmallFont());
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(top); col.add(sub);
		if (f.series != null && f.series.length >= 2)
		{
			Spark spark = new Spark(f.series, PluginPanel.PANEL_WIDTH - 74, 18);
			spark.setAlignmentX(Component.LEFT_ALIGNMENT);
			col.add(Box.createRigidArea(new Dimension(0, 2)));
			col.add(spark);
		}
		p.add(col, BorderLayout.CENTER);
		p.setToolTipText("Put " + f.basketQty + " of " + esc(f.name) + " in a GE slot: buy @" + gp(f.buy)
			+ ", sell @" + gp(f.sell) + " → +" + gp((long) f.margin * f.basketQty) + " expected this cycle. Click to copy.");
		p.addMouseListener(copyOnClick(f.name));
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	/** A fixed-size item-icon label. getImage is safe off the client thread (a placeholder that addTo fills
	 *  in on the EDT). id ≤ 0 → an empty cell, so the row grammar stays aligned. NEVER new ImageIcon (blank bug). */
	private JLabel iconLabel(int itemId, int size)
	{
		JLabel l = new JLabel();
		Dimension d = new Dimension(size, size);
		l.setPreferredSize(d); l.setMinimumSize(d); l.setMaximumSize(d);
		l.setHorizontalAlignment(SwingConstants.CENTER);
		l.setVerticalAlignment(SwingConstants.CENTER);
		if (itemId > 0 && itemManager != null)
		{
			AsyncBufferedImage img = itemManager.getImage(itemId, 1, false);
			img.addTo(l);
		}
		return l;
	}

	/** A slim colour-coded trust ProgressBar (green ≥70 / orange 45-69 / red <45) — the "is this margin REAL?"
	 *  score as a visual instead of "🛡N" text. Doubles as the row's thin bottom bar. */
	private static ProgressBar trustBar(int trust)
	{
		int t = Math.max(0, Math.min(100, trust));
		ProgressBar bar = new ProgressBar();
		bar.setMaximumValue(100);
		bar.setValue(t);
		bar.setCenterLabel("🛡 " + t);
		bar.setForeground(t >= 70 ? ColorScheme.PROGRESS_COMPLETE_COLOR
			: t >= 45 ? ColorScheme.BRAND_ORANGE : ColorScheme.PROGRESS_ERROR_COLOR);
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 60, 14));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);
		bar.setToolTipText("Trust " + t + "/100 — is this margin REAL? Blends how long the margin actually held, "
			+ "fill probability, and price stability. " + (t >= 70 ? "Trustworthy." : t >= 45 ? "So-so — check the fill." : "Shaky — treat with caution."));
		return bar;
	}

	/** A small colour-coded chip (a compact pill of context: buy-limit reset, basket size, thin-fill). */
	private static JLabel chip(String text, Color fg)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(fg);
		l.setOpaque(true);
		l.setBackground(ColorScheme.DARK_GRAY_COLOR);
		l.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		return l;
	}

	/** A left-aligned, height-capped row that holds chips without stretching the card. */
	private static JPanel chipRow()
	{
		JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
		r.setOpaque(false);
		r.setAlignmentX(Component.LEFT_ALIGNMENT);
		r.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 20));
		return r;
	}

	/** A clipboard-copy click handler shared by every copyable row (copies the item name for the GE search). */
	private java.awt.event.MouseAdapter copyOnClick(String nameToCopy)
	{
		return new java.awt.event.MouseAdapter()
		{
			@Override public void mouseClicked(java.awt.event.MouseEvent e)
			{
				java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(nameToCopy);
				java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
				status.setText("copied \"" + nameToCopy + "\"");
			}
		};
	}

	private static String fmtMins(int m) { return m >= 60 ? (m / 60) + "h" : m + "m"; }

	private static Component gap() { return Box.createRigidArea(new Dimension(0, 5)); }

	/** An orange section header. */
	private static JLabel header(String t)
	{
		JLabel l = new JLabel(wrap(t));
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setBorder(BorderFactory.createEmptyBorder(6, 1, 2, 1));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** A muted, width-wrapped hint line. */
	private static JLabel hint(String t)
	{
		JLabel l = new JLabel(wrap(t));
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	/** Wrap text to the sidebar width so long lines WRAP instead of clipping off the right edge. */
	private static String wrap(String t) { return "<html><div style='width:176px'>" + esc(t) + "</div></html>"; }

	/** Truncate a single-line label so the row grammar stays tight; full text lives in the tooltip. */
	private static String trunc(String s, int max)
	{
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)).trim() + "…";
	}

	/** Add up to topN rows, then (if more) a "▾ show N more" toggle that reveals the rest in place, so a long
	 *  list is never a 40-row wall. spaced=true inserts a breathing gap between card rows. */
	private void addTopN(JPanel box, List<? extends JComponent> rows, int topN, boolean spaced)
	{
		int n = rows.size();
		int show = Math.min(topN, n);
		for (int i = 0; i < show; i++) { box.add(rows.get(i)); if (spaced) box.add(gap()); }
		if (n > topN)
		{
			JPanel more = new JPanel();
			more.setLayout(new BoxLayout(more, BoxLayout.Y_AXIS));
			more.setOpaque(false);
			more.setAlignmentX(Component.LEFT_ALIGNMENT);
			more.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
			for (int i = topN; i < n; i++) { more.add(rows.get(i)); if (spaced) more.add(gap()); }
			more.setVisible(false);
			final int hidden = n - topN;
			JButton toggle = linkBtn("▾ show " + hidden + " more");
			toggle.addActionListener(e ->
			{
				boolean v = !more.isVisible();
				more.setVisible(v);
				toggle.setText(v ? "▴ show less" : "▾ show " + hidden + " more");
				box.revalidate(); box.repaint();
			});
			box.add(toggle);
			box.add(more);
		}
	}

	private static JButton linkBtn(String t)
	{
		JButton b = new JButton(t);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setForeground(ColorScheme.BRAND_ORANGE);
		b.setFocusPainted(false);
		b.setContentAreaFilled(false);
		b.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		b.setHorizontalAlignment(SwingConstants.LEFT);
		b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 22));
		return b;
	}

	/** #6 — a tiny inline price sparkline (antialiased, green-up / red-down). Guards length &lt; 2 and a flat
	 *  series (draws a mid-line). Painted from the grounded pick's recent ~2h mids. */
	private static final class Spark extends JComponent
	{
		private final int[] s;
		Spark(int[] s, int w, int h)
		{
			this.s = s;
			Dimension d = new Dimension(Math.max(20, w), h);
			setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
			setOpaque(false);
		}
		@Override protected void paintComponent(Graphics g)
		{
			if (s == null || s.length < 2) return;
			int w = getWidth(), h = getHeight(), pad = 2;
			int min = s[0], max = s[0];
			for (int v : s) { if (v < min) min = v; if (v > max) max = v; }
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			boolean up = s[s.length - 1] >= s[0];
			g2.setColor(up ? ColorScheme.GRAND_EXCHANGE_PRICE : ColorScheme.PROGRESS_ERROR_COLOR);
			g2.setStroke(new BasicStroke(1.4f));
			double range = max - min;
			if (range <= 0)   // flat series — a calm mid-line
			{
				g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				int y = h / 2;
				g2.drawLine(pad, y, w - pad, y);
				g2.dispose();
				return;
			}
			int n = s.length, prevX = 0, prevY = 0;
			for (int i = 0; i < n; i++)
			{
				int x = pad + (int) Math.round((w - 2.0 * pad) * (i / (double) (n - 1)));
				int y = pad + (int) Math.round((h - 2.0 * pad) * (1 - (s[i] - min) / range));
				if (i > 0) g2.drawLine(prevX, prevY, x, y);
				prevX = x; prevY = y;
			}
			g2.dispose();
		}
	}
}
