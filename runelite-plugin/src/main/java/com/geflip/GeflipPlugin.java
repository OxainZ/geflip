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

	// local bridge: serves the UI + these live fills to the web app on your network
	private GeflipServer bridge;
	private final java.util.List<Fill> fills = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** One real GE fill. Carries the item ID; the web app resolves the name from /mapping. */
	static final class Fill
	{
		final int id; final String side; final int price, qty, tax; final long ts;
		Fill(int id, String side, int price, int qty, int tax, long ts)
		{ this.id = id; this.side = side; this.price = price; this.qty = qty; this.tax = tax; this.ts = ts; }
	}
	static final class Session { final long realized, deployed; Session(long r, long d) { realized = r; deployed = d; } }
	static final class State
	{
		final boolean ok = true; final long ts = System.currentTimeMillis() / 1000;
		Session session; java.util.List<Fill> fills;
	}

	private State buildState()
	{
		State s = new State();
		s.session = new Session(realizedProfit, spentBuying);
		s.fills = fills;
		return s;
	}

	/**
	 * Push our slice (fills + session) to the cloud sync Worker. The Worker shallow-
	 * merges, so we never clobber the web app's `config`. Best-effort, off the client
	 * thread (called from the scan executor). READ-ONLY toward the game.
	 */
	private void cloudPush()
	{
		String url = config.cloudUrl(), id = config.cloudId();
		if (url == null || url.isEmpty() || id == null || id.length() < 16) return;
		try
		{
			com.google.gson.JsonObject o = new com.google.gson.JsonObject();
			o.add("fills", new com.google.gson.Gson().toJsonTree(fills));
			com.google.gson.JsonObject sess = new com.google.gson.JsonObject();
			sess.addProperty("realized", realizedProfit);
			sess.addProperty("deployed", spentBuying);
			o.add("session", sess);
			o.addProperty("updated", System.currentTimeMillis() / 1000);
			byte[] body = o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

			String full = url.replaceAll("/+$", "") + "/?id="
				+ java.net.URLEncoder.encode(id, "UTF-8");
			java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(full).openConnection();
			c.setRequestMethod("PUT");
			c.setDoOutput(true);
			c.setRequestProperty("Content-Type", "application/json");
			c.setConnectTimeout(10000);
			c.setReadTimeout(15000);
			try (java.io.OutputStream os = c.getOutputStream()) { os.write(body); }
			int code = c.getResponseCode();
			c.disconnect();
			if (code >= 300) log.debug("geflip cloud push http {}", code);
		}
		catch (Exception e) { log.debug("geflip cloud push failed", e); }
	}

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

		if (config.bridgeEnabled())
		{
			try
			{
				bridge = new GeflipServer(config.bridgePort(), config.bridgeToken())
					.withState(this::buildState)
					.onConfigPost(body -> log.debug("bridge config: {}", body));
				bridge.start();
				log.info("geflip bridge on port {} — open http://<pc-ip>:{} on your phone",
					config.bridgePort(), config.bridgePort());
			}
			catch (Exception e) { log.warn("geflip bridge failed to start", e); }
		}
	}

	@Override
	protected void shutDown()
	{
		if (refresh != null) refresh.cancel(true);
		if (bridge != null) { bridge.stop(); bridge = null; }
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
			cloudPush();   // push fills/session to the cloud store (no-op if not configured)
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
		if (st != GrandExchangeOfferState.BOUGHT && st != GrandExchangeOfferState.SOLD) return;

		long spent = o.getSpent();          // gp moved so far on this offer
		int qty = o.getQuantitySold();      // filled units
		int unit = qty > 0 ? (int) (spent / qty) : o.getPrice();
		long now = System.currentTimeMillis() / 1000;

		if (st == GrandExchangeOfferState.BOUGHT)
		{
			spentBuying += spent;
			realizedProfit -= spent;
			fills.add(new Fill(o.getItemId(), "BUY", unit, qty, 0, now));
		}
		else // SOLD — subtract the 2% tax the GE takes on the sale
		{
			int tax = GeflipScanner.saleTax(unit, false) * qty;
			realizedProfit += (spent - tax);
			fills.add(new Fill(o.getItemId(), "SELL", unit, qty, tax, now));
		}
		if (fills.size() > 500) fills.remove(0);   // keep it bounded
		if (panel != null) panel.setSession(realizedProfit, spentBuying);
	}

	private static String timeNow()
	{
		return new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
	}
}
