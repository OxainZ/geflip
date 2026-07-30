package com.geflip.coach;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
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
 * The coach side panel — redesigned for readability: 5 focused tabs, item-icon rows with a strict
 * one-row grammar (24-36px icon · bold name · short chip · right-aligned status), a "DO THIS NEXT"
 * hero card, and top-N sections that collapse the long tail behind a "show more" toggle so nothing
 * is a wall of text. All setters marshal to the EDT; the plugin feeds it snapshots off the client
 * thread. Native RuneLite styling only (ColorScheme / FontManager / ProgressBar).
 *
 * Tabs: Now (Next + Risk) · Goals (Goals + Path + Blocked) · Skills (road-to-99 bars) ·
 * Money (money-router + Farm) · Ask. The data setters are unchanged — only which CARD each box
 * lives in moved, so the plugin's plumbing keeps working.
 */
class CoachPanel extends PluginPanel
{
	private final ItemManager itemManager;

	private final JLabel status = new JLabel("log in to begin");
	private final JLabel summary = new JLabel(" ");
	private final JLabel sessionStats = new JLabel(" ");
	private final JPanel nextBox = new JPanel();
	private final JPanel nowExtraBox = new JPanel();   // dailies + permanent-unlocks + "so close" (on the Now tab)
	private final JPanel goalsBox = new JPanel();
	private final JPanel blockedBox = new JPanel();
	private final JPanel pathBox = new JPanel();
	private final JPanel farmBox = new JPanel();
	private final JPanel farmStepsBox = new JPanel();   // clickable "lead me there" steps (arrows)
	private final JPanel skillsBox = new JPanel();       // money-router + quickest-99 text (now on the Money tab)
	private final JPanel skillProgBox = new JPanel();    // per-skill progress-bar rows (the Skills tab headline)

	/** One skill's road-to-99 as structured data → rendered as a native ProgressBar row (not text). */
	static final class SkillProg
	{
		public String name, method; public int level, pct, xpHr; public double hours;
	}

	/** One tappable farm stop the panel shows: tap → the plugin drops a hint arrow on (x,y,plane).
	 *  occupied: −1 unknown · 0 empty (go plant) · 1 occupied. locked = can't reach yet (shows reqLabel). */
	static final class FarmStep
	{
		public String loc, tele, reqLabel; public int x, y, plane, occupied = -1; public boolean locked;
	}
	private final JPanel riskBox = new JPanel();
	private final JTextField askInput = new JTextField();
	private final JTextArea askResult = new JTextArea();

	private final JButton tabNow = new JButton("Now");
	private final JButton tabGoals = new JButton("Goals");
	private final JButton tabSkills = new JButton("Skills");
	private final JButton tabMoney = new JButton("Money");
	private final JButton tabAsk = new JButton("Ask");
	private final JPanel cards = new JPanel(new java.awt.CardLayout());

	private final Consumer<String> onAsk;
	private final Supplier<String> onCopyContext;
	private final Runnable onFarmRunDone;
	private final Consumer<String> onGuide;
	private final Consumer<String> onFarmSelect;
	private final Consumer<FarmStep> onFarmGuide;

	CoachPanel(ItemManager itemManager, Runnable onRefresh, Consumer<String> onAsk, Supplier<String> onCopyContext,
		Runnable onFarmRunDone, Consumer<String> onGuide, Consumer<String> onFarmSelect, Consumer<FarmStep> onFarmGuide)
	{
		this.itemManager = itemManager;
		this.onFarmGuide = onFarmGuide;
		this.onAsk = onAsk; this.onCopyContext = onCopyContext; this.onFarmRunDone = onFarmRunDone; this.onGuide = onGuide; this.onFarmSelect = onFarmSelect;
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

		JPanel tabBar = new JPanel(new GridLayout(1, 5, 2, 2));   // one clean row of 5 focused tabs
		tabBar.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
		for (JButton b : new JButton[]{ tabNow, tabGoals, tabSkills, tabMoney, tabAsk })
		{ tabBar.add(b); b.setMargin(new java.awt.Insets(3, 1, 3, 1)); }
		tabNow.addActionListener(e -> show("now"));
		tabGoals.addActionListener(e -> show("goals"));
		tabSkills.addActionListener(e -> show("skills"));
		tabMoney.addActionListener(e -> show("money"));
		tabAsk.addActionListener(e -> show("ask"));

		for (JPanel p : new JPanel[]{ nextBox, nowExtraBox, goalsBox, blockedBox, pathBox, farmBox, farmStepsBox, riskBox, skillsBox, skillProgBox })
		{
			p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
			p.setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		// Now = the ranked action list (hero + top-N) above your live death-risk read
		cards.add(scroll(vstack(nextBox, gap(), nowExtraBox, gap(), riskBox)), "now");
		// Goals = tracked goals + the focus PATH + the long-term BLOCKED arcs, one scannable column
		cards.add(scroll(vstack(goalsBox, gap(), pathBox, gap(), blockedBox)), "goals");
		// Skills = the road-to-99 progress bars (kept — the one part that already reads well)
		cards.add(scroll(skillProgBox), "skills");
		// Money = the gp/hr router (skillsBox) above the full Farm helper
		cards.add(moneyCard(), "money");
		cards.add(askCard(), "ask");

		JPanel north = new JPanel(new BorderLayout());
		north.add(top, BorderLayout.NORTH);
		north.add(tabBar, BorderLayout.SOUTH);
		add(north, BorderLayout.NORTH);
		add(cards, BorderLayout.CENTER);
		show("now");
	}

	/** The Money tab: the gp/hr router text (skillsBox) then the full Farm helper (run picker, done
	 *  button, tappable "lead me there" steps, and the path-to-99 text) — all in one scroll. */
	private JScrollPane moneyCard()
	{
		JPanel v = vstack();
		v.add(skillsBox);
		v.add(gap());
		v.add(header("🌱 FARMING"));
		// preset-run selector: pick which crop route to show
		JPanel types = new JPanel(new GridLayout(2, 3, 2, 2));
		for (String t : new String[]{ "Herb", "Tree", "Fruit", "Flower", "Bush", "All" })
		{
			JButton b = new JButton(t);
			b.setMargin(new java.awt.Insets(2, 1, 2, 1));
			b.addActionListener(e -> { if (onFarmSelect != null) onFarmSelect.accept(t); });
			types.add(b);
		}
		types.setAlignmentX(Component.LEFT_ALIGNMENT);
		types.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 56));
		JButton done = new JButton("✓ Mark this run done");
		done.setToolTipText("Log that you just did this run — the timer counts down to when it's ready again.");
		done.setAlignmentX(Component.LEFT_ALIGNMENT);
		done.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 26));
		done.addActionListener(e -> { if (onFarmRunDone != null) onFarmRunDone.run(); });
		v.add(types);
		v.add(Box.createRigidArea(new Dimension(0, 3)));
		v.add(done);
		v.add(gap());
		v.add(farmStepsBox);
		v.add(farmBox);
		return scroll(v);
	}

	/** Render the tappable "lead me there" steps: tap a stop → the plugin drops an in-world hint arrow.
	 *  Open stops are clickable (with an empty/occupied marker if known); locked stops show what unlocks. */
	void setFarmSteps(List<FarmStep> steps)
	{
		SwingUtilities.invokeLater(() ->
		{
			farmStepsBox.removeAll();
			if (steps != null && !steps.isEmpty())
			{
				JLabel hdr = new JLabel("🧭 Lead me there — tap a stop:");
				hdr.setForeground(ColorScheme.BRAND_ORANGE);
				hdr.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
				hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
				hdr.setToolTipText("Tap a stop to drop an in-world arrow that walks you to that patch. "
					+ "Clear the arrow by tapping the same stop again.");
				farmStepsBox.add(hdr);
				List<JComponent> rows = new ArrayList<>();
				for (FarmStep s : steps) rows.add(farmStepRow(s));
				addTopN(farmStepsBox, rows, 6, true);
			}
			farmStepsBox.revalidate();
			farmStepsBox.repaint();
		});
	}

	private JPanel farmStepRow(FarmStep s)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		String mark = s.locked ? "✖ " : s.occupied == 0 ? "🌱 " : s.occupied == 1 ? "✓ " : "📍 ";
		JLabel name = new JLabel(mark + s.loc);
		name.setForeground(s.locked ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.TEXT_COLOR);
		JLabel sub = new JLabel(trunc(s.locked ? "unlock: " + s.reqLabel : s.tele, 30));
		sub.setFont(FontManager.getRunescapeSmallFont());
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel col = new JPanel(); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS)); col.setOpaque(false);
		name.setAlignmentX(Component.LEFT_ALIGNMENT); sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(name); col.add(sub);
		row.add(col, BorderLayout.CENTER);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 44));
		if (!s.locked)
		{
			row.setToolTipText(s.occupied == 0 ? "Empty — go plant here. Tap for an arrow."
				: s.occupied == 1 ? "Something's growing here (check Timetracking for ready-time). Tap for an arrow."
				: "Tap for an in-world arrow to this patch.");
			row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			row.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override public void mouseClicked(java.awt.event.MouseEvent e) { if (onFarmGuide != null) onFarmGuide.accept(s); }
			});
		}
		return row;
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
		tabNow.setForeground("now".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabGoals.setForeground("goals".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabSkills.setForeground("skills".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabMoney.setForeground("money".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		tabAsk.setForeground("ask".equals(card) ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** Render the "Skills" trainer text (money-router + quickest-99), now on the Money tab. A leading
	 *  "*" marks a header line. Long tail collapses behind a show-more toggle. */
	void setSkills(List<String> lines)
	{
		SwingUtilities.invokeLater(() ->
		{
			skillsBox.removeAll();
			if (lines != null)
			{
				List<JComponent> comps = new ArrayList<>();
				for (String s : lines)
				{
					boolean head = s.startsWith("*");
					JLabel l = new JLabel(wrap(head ? s.substring(1) : s));
					l.setFont(FontManager.getRunescapeSmallFont());
					l.setForeground(head ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
					l.setBorder(BorderFactory.createEmptyBorder(head ? 6 : 1, 6, 1, 6));
					l.setAlignmentX(Component.LEFT_ALIGNMENT);
					comps.add(l);
				}
				addTopN(skillsBox, comps, 14, false);
			}
			skillsBox.revalidate();
			skillsBox.repaint();
		});
	}

	/** Now-tab extras: dailies + permanent-unlocks + "SO CLOSE" (re-homed here after the Skills-tab strip).
	 *  Same "*"-header rendering + top-N collapse as setSkills. */
	void setNowExtras(List<String> lines)
	{
		SwingUtilities.invokeLater(() ->
		{
			nowExtraBox.removeAll();
			if (lines != null && !lines.isEmpty())
			{
				List<JComponent> comps = new ArrayList<>();
				for (String s : lines)
				{
					if (s.isEmpty()) continue;
					boolean head = s.startsWith("*");
					JLabel l = new JLabel(wrap(head ? s.substring(1) : s));
					l.setFont(FontManager.getRunescapeSmallFont());
					l.setForeground(head ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
					l.setBorder(BorderFactory.createEmptyBorder(head ? 6 : 1, 6, 1, 6));
					l.setAlignmentX(Component.LEFT_ALIGNMENT);
					comps.add(l);
				}
				addTopN(nowExtraBox, comps, 12, false);
			}
			nowExtraBox.revalidate();
			nowExtraBox.repaint();
		});
	}

	/** The "road to 99": one native ProgressBar row per skill (XP% + ETA), like the XP Tracker. */
	void setSkillProgress(List<SkillProg> rows)
	{
		SwingUtilities.invokeLater(() ->
		{
			skillProgBox.removeAll();
			JLabel hdr = new JLabel(wrap("⚔ ROAD TO 99 — nearest first"));
			hdr.setForeground(ColorScheme.BRAND_ORANGE);
			hdr.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
			hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			skillProgBox.add(hdr);
			if (rows == null || rows.isEmpty())
			{
				skillProgBox.add(hint("every trainable skill is 99 — maxed!"));
			}
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (SkillProg sp : rows) comps.add(skillProgRow(sp));
				addTopN(skillProgBox, comps, 6, true);
			}
			skillProgBox.revalidate();
			skillProgBox.repaint();
		});
	}

	private JPanel skillProgRow(SkillProg sp)
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 66));

		JLabel name = new JLabel(sp.name + "  " + sp.level);
		name.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(ColorScheme.TEXT_COLOR);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);

		ProgressBar bar = new ProgressBar();
		bar.setMaximumValue(100);
		bar.setValue(Math.max(0, Math.min(100, sp.pct)));   // XP-based % to 99 (level is non-linear)
		bar.setCenterLabel(sp.pct + "%  ·  " + (sp.hours > 0 ? "~" + Math.round(sp.hours) + "h to 99" : "passive"));
		bar.setForeground(ColorScheme.PROGRESS_INPROGRESS_COLOR);
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setPreferredSize(new Dimension(PluginPanel.PANEL_WIDTH - 24, 16));
		bar.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 16));
		bar.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel method = new JLabel(wrap(sp.method + (sp.xpHr > 0 ? "  (~" + CoachGoals.gp(sp.xpHr) + "/hr)" : "")));
		method.setFont(FontManager.getRunescapeSmallFont());
		method.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		method.setAlignmentX(Component.LEFT_ALIGNMENT);

		row.add(name);
		row.add(Box.createRigidArea(new Dimension(0, 2)));
		row.add(bar);
		row.add(method);
		return row;
	}

	private static JScrollPane scroll(Component c)
	{
		// horizontal scroll AS-NEEDED so any wide row/control is always REACHABLE (bar only shows if content
		// overflows). Content goes straight into the scrollpane so height sizes correctly + you reach the bottom.
		JScrollPane sp = new JScrollPane(c,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		sp.setBorder(BorderFactory.createEmptyBorder());
		sp.getVerticalScrollBar().setUnitIncrement(16);
		sp.getHorizontalScrollBar().setUnitIncrement(16);
		return sp;
	}

	// --- setters (all EDT-safe) ----------------------------------------------
	void setStatus(String s) { SwingUtilities.invokeLater(() -> status.setText(s)); }
	void setSummary(String s) { SwingUtilities.invokeLater(() -> summary.setText(wrap(s))); }
	void setSessionStats(String s) { SwingUtilities.invokeLater(() -> sessionStats.setText(wrap(s))); }
	void setAskResult(String s) { SwingUtilities.invokeLater(() -> askResult.setText(s)); }

	void setNext(List<CoachEngine.Scored> rows)
	{
		SwingUtilities.invokeLater(() -> {
			nextBox.removeAll();
			if (rows == null || rows.isEmpty())
			{
				nextBox.add(hint("Nothing to show yet — hit Rescan while logged in."));
			}
			else
			{
				// hero: the #1 ranked action, visually 2x weight
				nextBox.add(header("⭐ DO THIS NEXT"));
				nextBox.add(gap());
				nextBox.add(scoredRow(rows.get(0), true));
				nextBox.add(gap());
				if (rows.size() > 1)
				{
					nextBox.add(header("THEN"));
					nextBox.add(gap());
					List<JComponent> rest = new ArrayList<>();
					for (int i = 1; i < rows.size(); i++) rest.add(scoredRow(rows.get(i), false));
					addTopN(nextBox, rest, 5, true);
				}
			}
			nextBox.revalidate(); nextBox.repaint();
		});
	}

	void setGoals(List<CoachEngine.Scored> rows, List<String> quests, List<String> pvm, List<String> diaries)
	{
		SwingUtilities.invokeLater(() -> {
			goalsBox.removeAll();
			if (rows != null && !rows.isEmpty())
			{
				goalsBox.add(header("Tracked goals"));
				goalsBox.add(gap());
				List<JComponent> rws = new ArrayList<>();
				for (CoachEngine.Scored sc : rows) rws.add(scoredRow(sc, false));
				addTopN(goalsBox, rws, 6, true);
			}
			if (quests != null && !quests.isEmpty())
			{
				goalsBox.add(header("Next quests"));
				addTopN(goalsBox, hints(quests), 6, false);
			}
			if (pvm != null && !pvm.isEmpty())
			{
				goalsBox.add(header("PvM (kill counts)"));
				addTopN(goalsBox, hints(pvm), 6, false);
			}
			if (diaries != null && !diaries.isEmpty())
			{
				goalsBox.add(header("Achievement diaries"));
				addTopN(goalsBox, hints(diaries), 6, false);
			}
			goalsBox.revalidate(); goalsBox.repaint();
		});
	}

	void setRisk(List<String> lines)
	{
		SwingUtilities.invokeLater(() -> {
			riskBox.removeAll();
			if (lines == null || lines.isEmpty())
			{
				riskBox.add(hint("Log in to see what you're risking."));
			}
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (String l : lines) comps.add(l.isEmpty() ? hint(" ") : (l.startsWith("RISK") ? header(l) : hint(l)));
				addTopN(riskBox, comps, 12, false);
			}
			riskBox.revalidate(); riskBox.repaint();
		});
	}

	void setPath(List<String> lines)
	{
		SwingUtilities.invokeLater(() -> {
			pathBox.removeAll();
			if (lines == null || lines.isEmpty())
			{
				pathBox.add(hint("Set a Focus goal in config (or wait for auto-pick)."));
			}
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (String l : lines) comps.add(l.isEmpty() ? hint(" ") : (l.startsWith("PATH") || l.startsWith("🎯") || l.startsWith("HOW") ? header(l) : hint(l)));
				addTopN(pathBox, comps, 10, false);
			}
			pathBox.revalidate(); pathBox.repaint();
		});
	}

	void setFarm(List<String> lines)
	{
		SwingUtilities.invokeLater(() -> {
			farmBox.removeAll();
			if (lines == null || lines.isEmpty())
			{
				farmBox.add(hint("Enable 'Farming run helper' in Config to see what to plant + where."));
			}
			else
			{
				List<JComponent> comps = new ArrayList<>();
				for (String l : lines) comps.add(l.isEmpty() ? hint(" ") : (l.startsWith("PATH") || l.startsWith("DO NOW") || l.startsWith("NEXT") || l.startsWith("MUST") ? header(l) : hint(l)));
				addTopN(farmBox, comps, 14, false);
			}
			farmBox.revalidate(); farmBox.repaint();
		});
	}

	void setBlocked(List<CoachEngine.Scored> rows)
	{
		SwingUtilities.invokeLater(() -> {
			blockedBox.removeAll();
			blockedBox.add(header("🔒 Blocked — long-term"));
			blockedBox.add(gap());
			if (rows == null || rows.isEmpty())
			{
				blockedBox.add(hint("Nothing blocked — you're clear!"));
			}
			else
			{
				List<JComponent> rws = new ArrayList<>();
				for (CoachEngine.Scored sc : rows) rws.add(scoredRow(sc, false));
				addTopN(blockedBox, rws, 5, true);
			}
			blockedBox.revalidate(); blockedBox.repaint();
		});
	}

	/** THE one row template for a scored goal: [icon] · bold name + short chip · right-aligned status.
	 *  hero=true renders the #1 action at 2x weight (bigger icon, bold font, orange accent). Click opens
	 *  the walkthrough + drops a guide arrow; hover shows the mini-guide tooltip. */
	private JPanel scoredRow(CoachEngine.Scored sc, boolean hero)
	{
		boolean done = sc.status == CoachEngine.Status.DONE;
		boolean ready = sc.status == CoachEngine.Status.READY;
		boolean almost = sc.status == CoachEngine.Status.ALMOST;

		JPanel p = new JPanel(new BorderLayout(hero ? 8 : 6, 0));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		if (hero)
			p.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(2, 0, 0, 0, ColorScheme.BRAND_ORANGE),
				BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		else
			p.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));

		p.add(iconLabel(goalIconId(sc.goal.name), hero ? 40 : 36), BorderLayout.WEST);

		// center: bold name (truncated) on line 1, a short chip on line 2
		JPanel col = new JPanel(); col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS)); col.setOpaque(false);
		JLabel name = new JLabel(trunc(sc.goal.name, hero ? 24 : 22));
		name.setFont(hero ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		name.setForeground(done ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.TEXT_COLOR);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		String chipText = done ? "have it" : ready ? "ready now"
			: trunc(sc.gaps.get(0), hero ? 30 : 26) + (sc.gaps.size() > 1 ? "  +" + (sc.gaps.size() - 1) : "");
		JLabel chip = new JLabel(chipText);
		chip.setFont(FontManager.getRunescapeSmallFont());
		chip.setForeground(done || ready ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
		chip.setAlignmentX(Component.LEFT_ALIGNMENT);
		col.add(name); col.add(chip);
		p.add(col, BorderLayout.CENTER);

		// east: right-aligned, colour-coded status token (✓ ready · N steps otherwise)
		JLabel east = new JLabel(done || ready ? "✓" : String.valueOf(sc.gaps.size()));
		east.setFont(FontManager.getRunescapeSmallFont().deriveFont(Font.BOLD));
		east.setForeground(done || ready ? ColorScheme.PROGRESS_COMPLETE_COLOR
			: almost ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
		east.setHorizontalAlignment(SwingConstants.RIGHT);
		east.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 2));
		p.add(east, BorderLayout.EAST);

		// hover = the mini-guide (note + HOW/where); the tooltip wraps long text.
		String how = CoachGoals.HOW.get(sc.goal.name);
		p.setToolTipText("<html><div width=260><b>" + esc(sc.goal.name) + "</b> — impact " + sc.goal.impact + "/5 · " + esc(sc.goal.effort)
			+ "<br>" + (done ? "You already own this." : sc.gaps.isEmpty() ? "Ready now." : "Still need: " + esc(String.join(", ", sc.gaps)))
			+ "<br><br>" + esc(sc.goal.note) + (how != null ? "<br><br><b>How:</b> " + esc(how) : "")
			+ "<br><br><i>Click for the full step-by-step.</i></div></html>");
		// click = a full readable walkthrough popup (+ ask the plugin to drop a guide arrow)
		p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		final String gname = sc.goal.name;
		p.addMouseListener(new java.awt.event.MouseAdapter() {
			public void mouseClicked(java.awt.event.MouseEvent e) { showGuide(sc); if (onGuide != null) onGuide.accept(gname); }
		});
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, hero ? 66 : 48));
		return p;
	}

	/** A readable step-by-step popup for a goal — what it is, how/where to get it, and the handoff
	 *  to Quest Helper for any quest gaps. */
	private void showGuide(CoachEngine.Scored sc)
	{
		StringBuilder b = new StringBuilder();
		b.append(sc.goal.note).append("\n");
		String how = CoachGoals.HOW.get(sc.goal.name);
		if (how != null) b.append("\nHOW: ").append(how).append("\n");
		if (!sc.gaps.isEmpty()) b.append("\nStill need: ").append(String.join(", ", sc.gaps)).append("\n");
		boolean questGap = sc.gaps.stream().anyMatch(g -> g.startsWith("quest:") || g.startsWith("start:"));
		if (questGap) b.append("\n→ Install the Quest Helper plugin — it draws turn-by-turn arrows + item lists for the quest. The Coach picks WHICH quest and the order; Quest Helper walks you through it.");
		b.append("\n\n(For a destination with a fixed spot, the Coach sets an in-game hint arrow; otherwise use Quest Helper / your shortest-path plugin to route there.)");
		// offer the OSRS Wiki — the deepest, always-current knowledge base — for this exact goal
		Object[] opts = { "Close", "📖 Open Wiki" };
		int r = javax.swing.JOptionPane.showOptionDialog(this, b.toString(), sc.goal.name,
			javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.INFORMATION_MESSAGE, null, opts, opts[0]);
		if (r == 1) net.runelite.client.util.LinkBrowser.browse(wikiSearch(sc.goal.name));
	}

	/** OSRS Wiki search URL for a goal (search, not a direct page, so a name mismatch never 404s). */
	private static String wikiSearch(String goal)
	{
		String q = goal.replaceAll("\\(.*?\\)", " ").replaceAll("[^A-Za-z0-9 ]", " ").trim();   // drop "(...)" + symbols
		try { return "https://oldschool.runescape.wiki/?search=" + java.net.URLEncoder.encode(q, "UTF-8"); }
		catch (Exception e) { return "https://oldschool.runescape.wiki/"; }
	}

	// --- row grammar helpers -------------------------------------------------

	/** A fixed-size item-icon label. getImage is safe off the client thread (returns a placeholder that
	 *  addTo fills in on the EDT). id ≤ 0 → an empty cell, so the row grammar stays aligned. */
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

	// representative gear item id per goal, for the row icon. Primary source is CoachGoals.DONE_IF_OWN
	// (the item that means "you have this"); this map fills in goals that have no completion item, using
	// ids already verified in the codebase.
	private static final java.util.Map<String, Integer> GOAL_ICON = new java.util.HashMap<>();
	static
	{
		GOAL_ICON.put("Zulrah (money boss)", 12926);          // Toxic blowpipe
		GOAL_ICON.put("Cerberus (primordial etc.)", 13239);   // Primordial boots
		GOAL_ICON.put("Alchemical Hydra", 22981);             // Ferocious gloves
		GOAL_ICON.put("Armadyl (Kree'arra, GWD)", 11826);     // Armadyl helmet
		GOAL_ICON.put("Dizana's quiver (BiS ranged cape)", 22109);   // Ava's assembler
	}

	/** The item id to draw for a goal (−1 = no icon → empty aligned cell). */
	private static int goalIconId(String name)
	{
		int[] own = CoachGoals.DONE_IF_OWN.get(name);
		if (own != null && own.length > 0) return own[0];
		Integer c = GOAL_ICON.get(name);
		return c != null ? c : -1;
	}

	/** Add up to topN rows, then (if more) a "▾ show N more" toggle that reveals the rest in place.
	 *  spaced=true inserts a breathing gap between rows (for card rows); false keeps text lines tight. */
	private void addTopN(JPanel box, List<? extends JComponent> rows, int topN, boolean spaced)
	{
		int n = rows.size();
		int show = Math.min(topN, n);
		for (int i = 0; i < show; i++) { box.add(rows.get(i)); if (spaced) box.add(gap()); }
		if (n > topN)
		{
			JPanel more = vstack();
			more.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, Integer.MAX_VALUE));
			for (int i = topN; i < n; i++) { more.add(rows.get(i)); if (spaced) more.add(gap()); }
			more.setVisible(false);
			final int hidden = n - topN;
			JButton toggle = linkBtn("▾ show " + hidden + " more");
			toggle.addActionListener(e -> {
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
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.setAlignmentX(Component.LEFT_ALIGNMENT);
		b.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH, 22));
		return b;
	}

	private List<JComponent> hints(List<String> lines)
	{
		List<JComponent> out = new ArrayList<>();
		for (String s : lines) out.add(hint(s));
		return out;
	}

	private static JPanel vstack(Component... kids)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (Component k : kids) { if (k instanceof JComponent) ((JComponent) k).setAlignmentX(Component.LEFT_ALIGNMENT); p.add(k); }
		return p;
	}

	private static Component gap() { return Box.createRigidArea(new Dimension(0, 5)); }

	private JLabel header(String t)
	{
		JLabel l = new JLabel(wrap(t));
		l.setForeground(ColorScheme.BRAND_ORANGE);
		l.setBorder(BorderFactory.createEmptyBorder(8, 1, 2, 1));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}
	private JLabel hint(String t)
	{
		JLabel l = new JLabel(wrap(t));
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

	/** Truncate a single-line label to keep the row grammar tight; full text lives in the tooltip. */
	private static String trunc(String s, int max)
	{
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)).trim() + "…";
	}

	/** Wrap text to the RuneLite sidebar width so long lines WRAP instead of clipping off the right edge.
	 *  ~176px fits the ~225px panel minus padding. Used for any remaining multi-line text. */
	static String wrap(String t) { return "<html><div style='width:176px'>" + esc(t) + "</div></html>"; }
}
