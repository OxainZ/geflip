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
 *
 * Security: NO CORS header is sent — the API is same-origin with the UI it serves, so
 * only the bridge's own page can read it; a random website you visit cannot fetch your
 * LAN IP and steal your book (the old wildcard CORS allowed exactly that). The token is
 * parsed from the `t` query param and compared in constant time. Request bodies are capped.
 */
class GeflipServer
{
	private static final String UI_URL = "https://oxainz.github.io/geflip/index.html";
	private static final int BODY_CAP = 1_000_000;   // 1 MB — refuse larger bodies (OOM guard)

	private final HttpServer http;
	private final java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2);
	private final String token;
	private final Gson gson = new Gson();

	private volatile Supplier<Object> stateSupplier = () -> new Object();
	private volatile Consumer<String> onPost = s -> {};
	private volatile String ui = null;   // cached web UI, fetched lazily on first request

	GeflipServer(int port, String token) throws IOException
	{
		this.token = token == null ? "" : token;
		// Security baseline: with NO token, never bind beyond loopback (an unauth'd LAN read of your book).
		// A token set => allow all interfaces so a phone on the same wifi can reach it, but auth is required.
		InetSocketAddress addr = this.token.isEmpty()
			? new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port)
			: new InetSocketAddress(port);
		http = HttpServer.create(addr, 0);
		http.createContext("/api/ping", this::ping);
		http.createContext("/api/state", this::state);
		http.createContext("/", this::root);
		http.setExecutor(pool);
	}

	GeflipServer withState(Supplier<Object> s) { this.stateSupplier = s; return this; }
	GeflipServer onConfigPost(Consumer<String> c) { this.onPost = c; return this; }

	// start() must NOT block: it's called from the client thread. The UI is fetched lazily
	// on the first HTTP request (on a pool thread), never on the game thread.
	void start() { http.start(); }

	void stop() { http.stop(0); pool.shutdownNow(); }

	private boolean authed(HttpExchange ex)
	{
		if (token.isEmpty()) return true;
		String t = param(ex.getRequestURI().getQuery(), "t");
		return t != null && java.security.MessageDigest.isEqual(
			t.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
	}

	/** True if the request came from the bridge's OWN page (or a non-browser client with no
	 *  Origin/Referer). Blocks a drive-by website from POSTing into your journal when the token
	 *  is empty. A browser always sends Origin on a cross-origin POST. */
	private boolean sameOrigin(HttpExchange ex)
	{
		String src = ex.getRequestHeaders().getFirst("Origin");
		if (src == null) src = ex.getRequestHeaders().getFirst("Referer");
		if (src == null) return true;   // curl / native app — the token still gates it
		String host = ex.getRequestHeaders().getFirst("Host");   // e.g. "192.168.1.168:7777"
		try
		{
			java.net.URI u = java.net.URI.create(src);
			String srcHost = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
			return host != null && (host.equalsIgnoreCase(srcHost) || host.equalsIgnoreCase(u.getHost()));
		}
		catch (Exception e) { return false; }
	}

	/** Value of a single query param, URL-decoded (null if absent). */
	private static String param(String query, String key)
	{
		if (query == null) return null;
		for (String p : query.split("&"))
		{
			int i = p.indexOf('=');
			if (i > 0 && p.substring(0, i).equals(key))
			{
				try { return java.net.URLDecoder.decode(p.substring(i + 1), "UTF-8"); }
				catch (Exception e) { return p.substring(i + 1); }
			}
		}
		return null;
	}

	private void ping(HttpExchange ex) throws IOException
	{
		respond(ex, 200, "{\"ok\":true,\"app\":\"geflip-bridge\",\"needsToken\":" + (!token.isEmpty()) + "}");
	}

	private void state(HttpExchange ex) throws IOException
	{
		if (!authed(ex)) { respond(ex, 401, "{\"ok\":false,\"error\":\"token\"}"); return; }
		if (ex.getRequestMethod().equalsIgnoreCase("POST"))
		{
			// CSRF guard: a POST is a "simple" cross-origin request (no preflight), so with the
			// default empty token any site you visit could write to your journal. Reject unless the
			// request originates from the bridge's own page (Origin/Referer host == our Host).
			if (!sameOrigin(ex)) { respond(ex, 403, "{\"ok\":false,\"error\":\"origin\"}"); return; }
			String body = read(ex.getRequestBody());
			try { onPost.accept(body); respond(ex, 200, "{\"ok\":true}"); }
			catch (Exception e) { respond(ex, 400, "{\"ok\":false}"); }
			return;
		}
		respond(ex, 200, gson.toJson(stateSupplier.get()));
	}

	private void root(HttpExchange ex) throws IOException
	{
		if (ui == null) { try { ui = fetch(UI_URL); } catch (Exception ignored) { /* retry next request */ } }
		if (ui != null) respondHtml(ex, ui);
		else respondHtml(ex, "<h3>geflip bridge is up</h3><p>Could not fetch the UI (offline?). "
			+ "The API still works at <code>/api/state</code>.</p>");
	}

	// --- helpers ---------------------------------------------------------
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
		byte[] buf = new byte[4096]; int n, total = 0;
		while ((n = in.read(buf)) != -1)
		{
			total += n;
			if (total > BODY_CAP) throw new IOException("request body too large");
			bo.write(buf, 0, n);
		}
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
