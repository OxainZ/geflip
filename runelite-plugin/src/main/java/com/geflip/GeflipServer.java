package com.geflip;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The LOCAL BRIDGE. A tiny embedded HTTP server (JDK built-in, no deps) that makes the
 * plugin and the web app one system on your own network:
 *
 *   GET  /                → serves the geflip web UI over http (same-origin as the API,
 *                            so NO CORS and NO https→http mixed-content wall)
 *   GET  /api/state       → your LIVE game data: real fills + session P&L + config
 *   POST /api/state       → the web app pushes config/journal changes back
 *   GET  /api/ping        → so the web app can detect "am I on the bridge?"
 *
 * Bound to all interfaces so your PHONE on the same wifi can open http://<pc-ip>:port.
 * A token (in the plugin config) gates it so nobody else on the wifi can read your book.
 * READ-ONLY toward the game: it exposes data, it never drives the client.
 */
class GeflipServer
{
	private final HttpServer http;
	private final String token;
	private final Gson gson = new Gson();

	private volatile Supplier<Object> stateSupplier = () -> new Object();
	private volatile Consumer<String> onPost = s -> {};
	private volatile String ui = null;   // cached web UI, fetched on start

	GeflipServer(int port, String token) throws IOException
	{
		this.token = token == null ? "" : token;
		http = HttpServer.create(new InetSocketAddress(port), 0);
		http.createContext("/api/ping", this::ping);
		http.createContext("/api/state", this::state);
		http.createContext("/", this::root);
		http.setExecutor(Executors.newFixedThreadPool(2));
	}

	GeflipServer withState(Supplier<Object> s) { this.stateSupplier = s; return this; }
	GeflipServer onConfigPost(Consumer<String> c) { this.onPost = c; return this; }

	void start()
	{
		// Serve the LATEST hosted UI over http locally so it never drifts from the site
		// and same-origin sync just works. Fallback to a stub if offline.
		try { ui = fetch("https://oxainz.github.io/geflip/index.html"); }
		catch (Exception e) { ui = null; }
		http.start();
	}

	void stop() { http.stop(0); }

	private boolean authed(HttpExchange ex)
	{
		if (token.isEmpty()) return true;
		String q = ex.getRequestURI().getQuery();
		return q != null && q.contains("t=" + token);
	}

	private void ping(HttpExchange ex) throws IOException
	{
		cors(ex);
		respond(ex, 200, "{\"ok\":true,\"app\":\"geflip-bridge\",\"needsToken\":" + (!token.isEmpty()) + "}");
	}

	private void state(HttpExchange ex) throws IOException
	{
		cors(ex);
		if (ex.getRequestMethod().equalsIgnoreCase("OPTIONS")) { respond(ex, 204, ""); return; }
		if (!authed(ex)) { respond(ex, 401, "{\"ok\":false,\"error\":\"token\"}"); return; }
		if (ex.getRequestMethod().equalsIgnoreCase("POST"))
		{
			String body = read(ex.getRequestBody());
			try { onPost.accept(body); respond(ex, 200, "{\"ok\":true}"); }
			catch (Exception e) { respond(ex, 400, "{\"ok\":false}"); }
			return;
		}
		respond(ex, 200, gson.toJson(stateSupplier.get()));
	}

	private void root(HttpExchange ex) throws IOException
	{
		if (ui != null) respondHtml(ex, ui);
		else respondHtml(ex, "<h3>geflip bridge is up</h3><p>Could not fetch the UI (offline?). "
			+ "The API still works at <code>/api/state</code>.</p>");
	}

	// --- helpers ---------------------------------------------------------
	private static void cors(HttpExchange ex)
	{
		ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
		ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
		ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
	}
	private static void respond(HttpExchange ex, int code, String json) throws IOException
	{
		byte[] b = json.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(code, b.length);
		try (OutputStream os = ex.getResponseBody()) { os.write(b); }
	}
	private static void respondHtml(HttpExchange ex, String html) throws IOException
	{
		byte[] b = html.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		ex.sendResponseHeaders(200, b.length);
		try (OutputStream os = ex.getResponseBody()) { os.write(b); }
	}
	private static String read(InputStream in) throws IOException
	{
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		byte[] buf = new byte[4096]; int n;
		while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
		return bo.toString("UTF-8");
	}
	private static String fetch(String url) throws IOException
	{
		java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
		c.setRequestProperty("User-Agent", "geflip-bridge");
		c.setConnectTimeout(10000); c.setReadTimeout(15000);
		try (InputStream in = c.getInputStream()) { return read(in); }
		finally { c.disconnect(); }
	}

	// mock shapes for the smoke test (named classes so Gson serialises them cleanly —
	// the real plugin passes a named state object too, never an anonymous one)
	static final class MockFill { String item="Nature rune"; String side="SELL"; int price=100; int qty=500; long ts=1; }
	static final class MockSession { long realized=1_234_567; long deployed=5_000_000; }
	static final class MockState { boolean ok=true; long ts=System.currentTimeMillis()/1000;
		MockSession session=new MockSession(); MockFill[] fills={new MockFill()}; }

	/** Standalone smoke test: start with mock state, no game needed. */
	public static void main(String[] args) throws Exception
	{
		GeflipServer s = new GeflipServer(7777, "test")
			.withState(MockState::new)
			.onConfigPost(body -> System.out.println("[bridge] got config POST: " + body));
		s.start();
		System.out.println("bridge on http://localhost:7777  (ping /api/ping, state /api/state?t=test)");
		Thread.sleep(3_600_000);
	}
}
