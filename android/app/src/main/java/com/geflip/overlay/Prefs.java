package com.geflip.overlay;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the overlay remembers: where the page lives, and where the window sat. */
final class Prefs
{
	static final String DEFAULT_URL = "https://oxainz.github.io/geflip/";

	private static final String FILE = "geflip-overlay";
	private static final String K_URL = "url";
	private static final String K_X = "x", K_Y = "y", K_W = "w", K_H = "h";
	private static final String K_ALPHA = "alpha";

	private final SharedPreferences sp;

	Prefs(Context c) { sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

	String url() { return sp.getString(K_URL, DEFAULT_URL); }
	void url(String v) { sp.edit().putString(K_URL, v).apply(); }

	int x() { return sp.getInt(K_X, 0); }
	int y() { return sp.getInt(K_Y, 0); }
	int w(int dflt) { return sp.getInt(K_W, dflt); }
	int h(int dflt) { return sp.getInt(K_H, dflt); }
	float alpha() { return sp.getFloat(K_ALPHA, 1f); }

	void position(int x, int y) { sp.edit().putInt(K_X, x).putInt(K_Y, y).apply(); }
	void size(int w, int h) { sp.edit().putInt(K_W, w).putInt(K_H, h).apply(); }
	void alpha(float a) { sp.edit().putFloat(K_ALPHA, a).apply(); }

	/**
	 * Turn whatever the user typed into the URL the overlay layout actually lives at.
	 * Accepts a bare host ("192.168.1.20:7777"), keeps any query they pasted (the LAN
	 * bridge needs its {@code ?t=} token), and adds {@code overlay=1} exactly once.
	 *
	 * <p>Deliberately plain string work rather than android.net.Uri: this is the only real
	 * logic in the app, and this way it is covered by an ordinary JVM unit test instead of
	 * needing a device.
	 */
	static String overlayUrl(String raw)
	{
		String s = raw == null ? "" : raw.trim();
		if (s.isEmpty()) return DEFAULT_URL + "?overlay=1";

		if (!s.contains("://"))
		{
			// No scheme typed. A LAN address is the plugin bridge, which is plain http;
			// anything else is a real site and gets https.
			boolean lan = s.startsWith("192.168.") || s.startsWith("10.")
				|| s.startsWith("172.") || s.startsWith("127.") || s.startsWith("localhost");
			s = (lan ? "http://" : "https://") + s;
		}

		// A fragment must stay last, so split it off and put it back at the end.
		String frag = "";
		int hash = s.indexOf('#');
		if (hash >= 0)
		{
			frag = s.substring(hash);
			s = s.substring(0, hash);
		}

		int q = s.indexOf('?');
		if (q >= 0)
		{
			for (String part : s.substring(q + 1).split("&"))
			{
				if (part.equals("overlay=1")) return s + frag;   // already asked for
			}
		}
		return s + (q >= 0 ? "&" : "?") + "overlay=1" + frag;
	}
}
