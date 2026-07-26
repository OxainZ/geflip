package com.geflip.coach;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The coach side panel: Next (ranked actions), Goals (every tracked unlock + progress), Blocked
 * (long-term arcs and exactly what gates them) and Ask (LLM coaching on your live account). All
 * setters marshal to the EDT; the plugin feeds it snapshots off the client thread.
 */
class CoachPanel extends PluginPanel
{
	private final JLabel status = new JLabel("log in to begin");
	private final JLabel summary = new JLabel(" ");
	private final JLabel sessionStats = new JLabel(" ");
	private final JPanel nextBox = new JPanel();
	private final JPanel goalsBox = new JPanel();
	private final JPanel blockedBox = new JPanel();
	private final JPanel farmBox = new JPanel();
	private final JTextField askInput = new JTextField();
	private final JTextArea askResult = new JTextArea();

	private final JButton tabNext = new JButton("Next");
	private final JButton tabGoals = new JButton("Goals");
	private final JButton tabBlocked = new JButton("Blocked");
	private final JButton tabFarm = new JButton("Farm");
	private final JButton tabAsk = new JButton("Ask");
	private final JPanel cards = new JPanel(new java.awt.CardLayout());

	private final Consumer<String> onAsk;
	private final Supplier<String> onCopyContext;
	private final Runnable onFarmRunDone;

	CoachPanel(Runnable onRefresh, Consumer<String> onAsk, Supplier<String> onCopyContext, Runnable onFarmRunDone)
	{
		this.onAsk = onAsk; this.onCopyContext = onCopyContext; this.onFarmRunDone = onFarmRunDone;
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		summary.setForeground(ColorScheme.BRAND_ORANGE);
		summary.setFont(FontManager.getRunescapeSmallFont());
		sessionStats.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sessionStats.setFont(FontManager.getRunescapeSmallFont());
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		summary.setAlignmentX(Component.LEFT_ALIGNMENT);
		sessionStats.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton refresh = new JButton("Rescan account");
		refresh.addActionListener(e -> onRefresh.run());
		refresh.setAlignmentX(Component.LEFT_ALIGNMENT);

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.add(refresh); top.add(status); top.add(summary); top.add(sessionStats);

		JPanel tabBar = new JPanel(new GridLayout(1, 5, 2, 0));
		tabBar.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
		tabBar.add(tabNext); tabBar.add(tabGoals); tabBar.add(tabBlocked); tabBar.add(tabFarm); tabBar.add(tabAsk);
		for (JButton b : new JButton[]{ tabNext, tabGoals, tabBlocked, tabFarm, tabAsk })
			b.setMargin(new java.awt.Insets(2, 1, 2, 1));
		tabNext.addActionListener(e -> show("next"));
		tabGoals.addActionListener(e -> show("goals"));
		tabBlocked.addActionListener(e -> show("blocked"));
		tabFarm.addActionListener(e -> show("farm"));
		tabAsk.addActionListener(e -> show("ask"));

		for (JPanel p : new JPanel[]{ nextBox, goalsBox, blockedBox, farmBox })
			p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));

		cards.add(scroll(nextBox), "next");
		cards.add(scroll(goalsBox), "goals");
		cards.add(scroll(blockedBox), "blocked");
		cards.add(farmCard(), "farm");
		cards.add(askCard(), "ask");

		JPanel north = new JPanel(new BorderLayout());
		north.add(top, BorderLayout.NORTH);
		north.add(tabBar, BorderLayout.SOUTH);
		add(north, BorderLayout.NORTH);
		add(cards, BorderLayout.CENTER);
		show("next");
	}

	private JPanel farmCard()
	{
		JPanel p = new JPanel(new BorderLayout(0, 4));
		JButton done = new JButton("✓ Mark farm run done");
		done.setToolTipText("Log that you just did a farm run — the timers count down to when herbs/trees/fruit are ready again.");
		done.addActionListener(e -> { if (onFarmRunDone != null) onFarmRunDone.run(); });
		p.add(done, BorderLayout.NORTH);
		p.add(scroll(farmBox), BorderLayout.CENTER);
		return p;
	}

	private JPanel askCard()
	{
		JPanel p = new JPanel(new BorderLayout(0, 4));
		askInput.putClientProperty("JTextField.placeholderText", "ask about your account…");
		askInput.addActionListener(e -> fireAsk());
		JButton ask = new JButton("Ask");
		ask.addActionListener(e -> fireAsk());
		JButton copy = new JButton("Copy context");
		copy.setToolTipText("Copy a ready-made prompt (your full live account + the coach's plan) to paste into Claude.");
		copy.addActionListener(e -> {
			String ctx = onCopyContext != null ? onCopyContext.get() : "";
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(ctx), null);
			setStatus("context copied — paste it into Claude");
		});
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.add(askInput, BorderLayout.CENTER);
		row.add(ask, BorderLayout.EAST);
		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.add(row, BorderLayout.NORTH);
		top.add(copy, BorderLayout.SOUTH);
		askResult.setEditable(false); askResult.setLineWrap(true); askResult.setWrapStyleWord(true);
		askResult.setFont(FontManager.getRunescapeSmallFont());
		askResult.setForeground(ColorScheme.TEXT_COLOR);
		askResult.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		askResult.setText("Type a question and Ask (needs an LLM endpoint in config), or hit Copy context "
			+ "and paste it into Claude for zero-setup coaching.");
		p.add(top, BorderLayout.NORTH);
		p.add(scroll(askResult), BorderLayout.CENTER);
		return p;
	}

	private void fireAsk()
	{
		String q = askInput.getText().trim();
		if (q.isEmpty()) return;
		askResult.setText("thinking…");
		if (onAsk != null) onAsk.accept(q);
	}

	private void show(String card)
	{
		((java.awt.CardLayout) cards.getLayout()).show(cards, card);
		tabNext.setForeground("next".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabGoals.setForeground("goals".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabBlocked.setForeground("blocked".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabFarm.setForeground("farm".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabAsk.setForeground("ask".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
	}

	private static JScrollPane scroll(Component c)
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.add(c, BorderLayout.NORTH);
		JScrollPane sp = new JScrollPane(wrap, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getVerticalScrollBar().setUnitIncrement(16);
		return sp;
	}

	// --- setters (all EDT-safe) ----------------------------------------------
	void setStatus(String s) { SwingUtilities.invokeLater(() -> status.setText(s)); }
	void setSummary(String s) { SwingUtilities.invokeLater(() -> summary.setText(s)); }
	void setSessionStats(String s) { SwingUtilities.invokeLater(() -> sessionStats.setText(s)); }
	void setAskResult(String s) { SwingUtilities.invokeLater(() -> askResult.setText(s)); }

	void setNext(List<CoachEngine.Scored> rows)
	{
		SwingUtilities.invokeLater(() -> {
			nextBox.removeAll();
			if (rows == null || rows.isEmpty()) nextBox.add(hint("Nothing to show yet — hit Rescan while logged in."));
			else for (CoachEngine.Scored sc : rows) nextBox.add(row(sc));
			nextBox.revalidate(); nextBox.repaint();
		});
	}

	void setGoals(List<CoachEngine.Scored> rows, List<String> quests, List<String> diaries)
	{
		SwingUtilities.invokeLater(() -> {
			goalsBox.removeAll();
			if (rows != null) for (CoachEngine.Scored sc : rows) goalsBox.add(row(sc));
			if (quests != null && !quests.isEmpty())
			{
				goalsBox.add(header("Next quests"));
				for (String q : quests) goalsBox.add(hint(q));
			}
			if (diaries != null && !diaries.isEmpty())
			{
				goalsBox.add(header("Achievement diaries"));
				for (String d : diaries) goalsBox.add(hint(d));
			}
			goalsBox.revalidate(); goalsBox.repaint();
		});
	}

	void setFarm(List<String> lines)
	{
		SwingUtilities.invokeLater(() -> {
			farmBox.removeAll();
			if (lines == null || lines.isEmpty())
				farmBox.add(hint("Enable 'Farming run helper' in Config to see what to plant + where."));
			else for (String l : lines) farmBox.add(l.isEmpty() ? hint(" ") : hint(l));
			farmBox.revalidate(); farmBox.repaint();
		});
	}

	void setBlocked(List<CoachEngine.Scored> rows)
	{
		SwingUtilities.invokeLater(() -> {
			blockedBox.removeAll();
			if (rows == null || rows.isEmpty()) blockedBox.add(hint("Nothing blocked — you're clear!"));
			else for (CoachEngine.Scored sc : rows) blockedBox.add(row(sc));
			blockedBox.revalidate(); blockedBox.repaint();
		});
	}

	private JPanel row(CoachEngine.Scored sc)
	{
		JPanel p = new JPanel(new BorderLayout(6, 0));
		p.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		String tag = sc.status == CoachEngine.Status.READY ? "✓ " :
			sc.status == CoachEngine.Status.ALMOST ? "○ " : "✕ ";
		JLabel name = new JLabel(tag + sc.goal.name);
		name.setForeground(sc.status == CoachEngine.Status.READY ? ColorScheme.PROGRESS_COMPLETE_COLOR :
			sc.status == CoachEngine.Status.ALMOST ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		JLabel sub = new JLabel(sc.gaps.isEmpty() ? "ready now" : String.join(" · ", sc.gaps));
		sub.setFont(FontManager.getRunescapeSmallFont());
		sub.setForeground(sc.gaps.isEmpty() ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		JPanel col = new JPanel(); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS)); col.setOpaque(false);
		name.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(name); col.add(sub);
		p.add(col, BorderLayout.CENTER);
		p.setToolTipText("<html><b>" + esc(sc.goal.name) + "</b> — impact " + sc.goal.impact + "/5 · " + esc(sc.goal.effort)
			+ "<br>" + esc(sc.goal.note) + "</html>");
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
	}

	private JLabel header(String t)
	{
		JLabel l = new JLabel(t);
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setBorder(BorderFactory.createEmptyBorder(8, 1, 2, 1));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}
	private JLabel hint(String t)
	{
		JLabel l = new JLabel(t);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}
	private static String esc(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
