package com.geflip.coach;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Wise Old Man efficiency metrics (EHP / EHB) for the local player. Read-only GET to the public WOM API,
 * meant to be called off the client thread (blocking HTTP) and cached. EHP = Efficient Hours Played,
 * EHB = Efficient Hours Bossing, TTM = Time To Max — the standard OSRS progression yardsticks the Coach
 * otherwise had no source for. No API key needed at Coach volumes (limit 20 req/min); a descriptive
 * User-Agent is sent as WOM requests.
 */
final class CoachWom
{
	static final class Result
	{
		final double ehp, ehb, ttm; final boolean tracked;   // tracked=false => WOM has never seen this player (404)
		Result(double ehp, double ehb, double ttm, boolean tracked)
		{ this.ehp = ehp; this.ehb = ehb; this.ttm = ttm; this.tracked = tracked; }
	}

	private CoachWom() {}

	private static String url(String username) throws Exception
	{
		return "https://api.wiseoldman.net/v2/players/"
			+ URLEncoder.encode(username, "UTF-8").replace("+", "%20");
	}

	/** Fetch EHP/EHB/TTM. Returns null on network/parse error; a Result with tracked=false if WOM 404s
	 *  (player never tracked — call {@link #track} first). Blocking — never call on the client thread. */
	static Result fetch(String username)
	{
		try
		{
			HttpURLConnection c = (HttpURLConnection) new URL(url(username)).openConnection();
			c.setRequestProperty("User-Agent", "geflip-coach RuneLite plugin (OSRS progression coach)");
			c.setConnectTimeout(8000); c.setReadTimeout(8000);
			int code = c.getResponseCode();
			if (code == 404) return new Result(0, 0, 0, false);
			if (code != 200) return null;
			StringBuilder sb = new StringBuilder();
			try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)))
			{ String line; while ((line = br.readLine()) != null) sb.append(line); }
			JsonObject o = new JsonParser().parse(sb.toString()).getAsJsonObject();
			// EHP/EHB appear top-level on the player object; fall back to latestSnapshot.data.computed.{m}.value
			// so we're robust to either API shape.
			double ehp = num(o, "ehp"), ehb = num(o, "ehb");
			if (ehp == 0 || ehb == 0)
			{
				JsonObject computed = nested(o, "latestSnapshot", "data", "computed");
				if (computed != null)
				{
					if (ehp == 0 && computed.has("ehp") && computed.get("ehp").isJsonObject())
						ehp = num(computed.getAsJsonObject("ehp"), "value");
					if (ehb == 0 && computed.has("ehb") && computed.get("ehb").isJsonObject())
						ehb = num(computed.getAsJsonObject("ehb"), "value");
				}
			}
			return new Result(ehp, ehb, num(o, "ttm"), true);
		}
		catch (Exception e) { return null; }
	}

	/** Track / force-update a player (POST) — creates the player on WOM if never tracked, or refreshes
	 *  their snapshot. Returns true on success. Blocking — never call on the client thread. */
	static boolean track(String username)
	{
		try
		{
			HttpURLConnection c = (HttpURLConnection) new URL(url(username)).openConnection();
			c.setRequestMethod("POST");
			c.setRequestProperty("User-Agent", "geflip-coach RuneLite plugin (OSRS progression coach)");
			c.setRequestProperty("Content-Type", "application/json");
			c.setConnectTimeout(8000); c.setReadTimeout(12000);
			c.setDoOutput(true);
			c.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
			int code = c.getResponseCode();
			return code == 200 || code == 201;
		}
		catch (Exception e) { return false; }
	}

	private static double num(JsonObject o, String k)
	{
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : 0;
	}

	/** Walk a chain of nested objects, null if any hop is missing/not an object. */
	private static JsonObject nested(JsonObject o, String... path)
	{
		JsonObject cur = o;
		for (String k : path)
		{
			if (cur == null || !cur.has(k) || !cur.get(k).isJsonObject()) return null;
			cur = cur.getAsJsonObject(k);
		}
		return cur;
	}
}
