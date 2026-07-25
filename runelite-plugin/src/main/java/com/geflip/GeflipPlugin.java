package com.geflip;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

/**
 * geflip — ranks GE flips by gp/hour in a side panel and tracks your own fills for a
 * live session P&L. READ-ONLY by construction: it reads offer events and shows
 * suggestions; it never sends input or places/collects an offer (that would be
 * macroing = a ban). A scaffold to build + test against the live client — see README.
 */
@Slf4j
@PluginDescriptor(
	name = "Geflip",
	description = "Ranks GE flips by gp/hour (tax, limits, liquidity, long-term trend); tracks your fills.",
	tags = {"flipping", "grand", "exchange", "ge", "money", "merchanting"}
)
public class GeflipPlugin extends Plugin
{
	@Inject private ClientToolbar clientToolbar;
	@Inject private GeflipConfig config;
	@Inject private ScheduledExecutorService executor;

	private final GeflipScanner scanner = new GeflipScanner();
	private GeflipPanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> refresh;

	// running session totals from real fills
	private long realizedProfit = 0;
	private long spentBuying = 0;

	@Provides
	GeflipConfig provideConfig(net.runelite.client.config.ConfigManager cm)
	{
		return cm.getConfig(GeflipConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new GeflipPanel(this::triggerScan);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/geflip_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Geflip")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		scheduleRefresh();
		triggerScan();
	}

	@Override
	protected void shutDown()
	{
		if (refresh != null) refresh.cancel(true);
		clientToolbar.removeNavigation(navButton);
		panel = null;
	}

	private void scheduleRefresh()
	{
		if (refresh != null) refresh.cancel(false);
		int s = Math.max(60, config.refreshSec());
		refresh = executor.scheduleWithFixedDelay(this::triggerScan, s, s, TimeUnit.SECONDS);
	}

	/** Off-thread scan → push rows to the panel. Wiki API is polite once/2min. */
	private void triggerScan()
	{
		executor.submit(() ->
		{
			try
			{
				panel.setStatus("scanning…");
				java.util.List<GeflipScanner.Flip> flips = scanner.scan(config);
				panel.setFlips(flips);
				panel.setStatus(flips.size() + " flips · " + timeNow());
			}
			catch (Exception e)
			{
				log.warn("geflip scan failed", e);
				panel.setStatus("scan failed — check connection");
			}
		});
	}

	/**
	 * Real fills → session P&L. This is the read-only heart of the RuneLite tie-in:
	 * the client hands us every offer state change; we account BOUGHT spend and SOLD
	 * proceeds net of the 2% tax. No input is ever sent back.
	 */
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged ev)
	{
		GrandExchangeOffer o = ev.getOffer();
		if (o == null) return;
		GrandExchangeOfferState st = o.getState();
		if (st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD)
		{
			long spent = o.getSpent();          // gp moved so far on this offer
			int qty = o.getQuantitySold();      // filled units
			if (st == GrandExchangeOfferState.BOUGHT)
			{
				spentBuying += spent;
				realizedProfit -= spent;
			}
			else // SOLD — subtract the 2% tax the GE takes on the sale
			{
				int unit = qty > 0 ? (int) (spent / qty) : 0;
				long tax = (long) GeflipScanner.saleTax(unit, false) * qty;
				realizedProfit += (spent - tax);
			}
			if (panel != null) panel.setSession(realizedProfit, spentBuying);
		}
	}

	private static String timeNow()
	{
		return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
	}
}
