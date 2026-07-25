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
		add(top, BorderLayout.NORTH);

		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		JScrollPane scroll = new JScrollPane(rows);
		scroll.setBorder(null);
		add(scroll, BorderLayout.CENTER);
	}

	void setStatus(String s) { SwingUtilities.invokeLater(() -> status.setText(s)); }

	void setSession(long realized, long spent)
	{
		SwingUtilities.invokeLater(() ->
			session.setText("session: " + gp(realized) + " realized · " + gp(spent) + " deployed"));
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
		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		JLabel name = new JLabel((f.decliner ? "⚠ " : "") + f.name);
		name.setForeground(f.decliner ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.TEXT_COLOR);
		name.setToolTipText("Click to copy the name for the GE search"
			+ (f.t90 != null ? " · 90d trend " + pct(f.t90) : ""));

		JLabel nums = new JLabel(gp(f.margin) + "/ea · " + gp((long) f.expGph) + "/h · x" + f.quantity);
		nums.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		nums.setFont(nums.getFont().deriveFont(nums.getFont().getSize2D() - 1f));

		JPanel txt = new JPanel();
		txt.setLayout(new BoxLayout(txt, BoxLayout.Y_AXIS));
		txt.setOpaque(false);
		txt.add(name);
		txt.add(nums);
		p.add(txt, BorderLayout.CENTER);

		JLabel prices = new JLabel("<html>buy " + f.buy + "<br>sell " + f.sell + "</html>");
		prices.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		prices.setHorizontalAlignment(JLabel.RIGHT);
		p.add(prices, BorderLayout.EAST);

		// click = copy the item name to paste into the GE search (no game input)
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
}
