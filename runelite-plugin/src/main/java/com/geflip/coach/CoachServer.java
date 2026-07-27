package com.geflip.coach;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The Coach's PHONE BRIDGE — a tiny embedded HTTP server (JDK built-in, no deps) so you can read
 * your goals / next step / farm run / net-worth / max-hit + upgrades from your phone on the same wifi.
 *
 *   GET /            → a self-contained mobile page that renders your live Coach state (auto-refresh)
 *   GET /api/coach   → the raw JSON snapshot the page fetches
 *   GET /api/ping    → "am I on the bridge?"
 *
 * Mirrors the flipper's GeflipServer security model: bound to all interfaces for LAN reach, gated by a
 * token (constant-time compared, parsed from the `t` query param). READ-ONLY — it only exposes data,
 * it never sends input to the game. No POST endpoint at all, so nothing on your wifi can change state.
 */
class CoachServer
{
	private final HttpServer http;
	private final java.util.concurrent.ExecutorService pool = Executors.newFixedThreadPool(2);
	private final String token;
	private final Gson gson = new Gson();
	private volatile Supplier<Snapshot> supplier = Snapshot::new;

	/** Plain data the page renders. Public fields → Gson serialises cleanly. */
	static final class Snapshot
	{
		boolean ok = true;
		long updated = 0;
		String status = "log in to read your account";
		String summary = "";
		String session = "";
		List<String> next = java.util.Collections.emptyList();
		List<String> path = java.util.Collections.emptyList();
		List<String> farm = java.util.Collections.emptyList();
		List<String> risk = java.util.Collections.emptyList();
		List<String> goals = java.util.Collections.emptyList();
	}

	CoachServer(int port, String token) throws IOException
	{
		this.token = token == null ? "" : token;
		http = HttpServer.create(new InetSocketAddress(port), 0);
		http.createContext("/api/ping", this::ping);
		http.createContext("/api/coach", this::coach);
		http.createContext("/", this::root);
		http.setExecutor(pool);
	}

	CoachServer withState(Supplier<Snapshot> s) { this.supplier = s; return this; }

	void start() { http.start(); }
	void stop() { http.stop(0); pool.shutdownNow(); }

	private boolean authed(HttpExchange ex)
	{
		if (token.isEmpty()) return true;
		String t = param(ex.getRequestURI().getQuery(), "t");
		return t != null && java.security.MessageDigest.isEqual(
			t.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
	}

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
		respond(ex, 200, "application/json; charset=utf-8",
			"{\"ok\":true,\"app\":\"geflip-coach\",\"needsToken\":" + (!token.isEmpty()) + "}");
	}

	private void coach(HttpExchange ex) throws IOException
	{
		if (!authed(ex)) { respond(ex, 401, "application/json", "{\"ok\":false,\"error\":\"token\"}"); return; }
		respond(ex, 200, "application/json; charset=utf-8", gson.toJson(supplier.get()));
	}

	private void root(HttpExchange ex) throws IOException
	{
		respond(ex, 200, "text/html; charset=utf-8", PAGE);
	}

	private static void respond(HttpExchange ex, int code, String type, String body) throws IOException
	{
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", type);
		ex.sendResponseHeaders(code, b.length);
		try (OutputStream os = ex.getResponseBody()) { os.write(b); }
	}

	// self-contained mobile page: reads ?t=<token> from its own URL, fetches /api/coach, renders cards,
	// auto-refreshes every 30s. No external assets, so it works offline on the LAN.
	private static final String PAGE =
		"<!doctype html><html><head><meta charset=utf-8>"
		+ "<meta name=viewport content='width=device-width,initial-scale=1'>"
		+ "<title>Geflip Coach</title><style>"
		+ "*{box-sizing:border-box}body{margin:0;background:#0f1115;color:#e6e6e6;"
		+ "font:15px/1.45 -apple-system,Segoe UI,Roboto,sans-serif}"
		+ "header{position:sticky;top:0;background:#151922;padding:12px 16px;border-bottom:1px solid #262b36;"
		+ "display:flex;justify-content:space-between;align-items:center}"
		+ "h1{font-size:16px;margin:0;color:#4fd1c5}#upd{font-size:11px;color:#8a93a2}"
		+ ".card{margin:12px;background:#151922;border:1px solid #262b36;border-radius:10px;overflow:hidden}"
		+ ".card h2{font-size:12px;letter-spacing:.06em;text-transform:uppercase;color:#8a93a2;"
		+ "margin:0;padding:10px 14px;border-bottom:1px solid #262b36;background:#121620}"
		+ "ul{margin:0;padding:6px 0;list-style:none}li{padding:5px 14px;white-space:pre-wrap;word-break:break-word}"
		+ "li:nth-child(even){background:#12151c}.sum{padding:12px 14px}.big{color:#fff}"
		+ "#err{color:#f6a}</style></head><body>"
		+ "<header><h1>⚔ Geflip Coach</h1><span id=upd>…</span></header>"
		+ "<div id=app></div><div id=err></div>"
		+ "<script>"
		+ "var t=new URLSearchParams(location.search).get('t')||'';"
		+ "function card(title,arr){if(!arr||!arr.length)return '';"
		+ "var li=arr.map(function(x){return '<li>'+esc(x)+'</li>'}).join('');"
		+ "return '<div class=card><h2>'+esc(title)+'</h2><ul>'+li+'</ul></div>'}"
		+ "function esc(s){return String(s).replace(/[&<>]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;'}[c]})}"
		+ "function load(){fetch('/api/coach?t='+encodeURIComponent(t)).then(function(r){"
		+ "if(!r.ok)throw new Error(r.status==401?'wrong or missing token':'http '+r.status);return r.json()})"
		+ ".then(function(d){document.getElementById('err').textContent='';"
		+ "var h='';if(d.summary)h+='<div class=card><div class=\"sum big\">'+esc(d.summary)+'</div>'"
		+ "+(d.session?'<div class=sum>'+esc(d.session)+'</div>':'')+'</div>';"
		+ "h+=card('Do next',d.next)+card('How to get there',d.path)+card('Farm run',d.farm)"
		+ "+card('Combat & upgrades',d.risk)+card('Goals',d.goals);"
		+ "document.getElementById('app').innerHTML=h||'<div class=card><div class=sum>'+esc(d.status||'no data')+'</div></div>';"
		+ "var u=d.updated?new Date(d.updated*1000).toLocaleTimeString():'';"
		+ "document.getElementById('upd').textContent=u?('updated '+u):''})"
		+ ".catch(function(e){document.getElementById('err').innerHTML='<div class=card><div class=sum id=err>'+esc(e.message)+'</div></div>'})}"
		+ "load();setInterval(load,30000);"
		+ "</script></body></html>";
}
