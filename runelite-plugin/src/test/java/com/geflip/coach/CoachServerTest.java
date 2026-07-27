package com.geflip.coach;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

/** Smoke test for the phone bridge: it starts, gates on the token, and serves the snapshot + page. */
public class CoachServerTest
{
	private static String get(String url) throws Exception
	{
		HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
		c.setConnectTimeout(2000); c.setReadTimeout(2000);
		int code = c.getResponseCode();
		InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
		java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
		byte[] b = new byte[4096]; int n;
		while (in != null && (n = in.read(b)) != -1) bo.write(b, 0, n);
		c.disconnect();
		return code + "|" + new String(bo.toByteArray(), StandardCharsets.UTF_8);
	}

	@Test
	public void servesSnapshotAndGatesToken() throws Exception
	{
		int port = 7787;
		CoachServer.Snapshot snap = new CoachServer.Snapshot();
		snap.summary = "combat 100 · 200 QP";
		snap.next = java.util.Arrays.asList("Fire cape — 43 Prayer");
		CoachServer s = new CoachServer(port, "secret").withState(() -> snap);
		s.start();
		try
		{
			String base = "http://127.0.0.1:" + port;
			// ping is open
			assertTrue(get(base + "/api/ping").startsWith("200|"));
			// wrong/missing token is rejected
			assertTrue(get(base + "/api/coach").startsWith("401|"));
			assertTrue(get(base + "/api/coach?t=nope").startsWith("401|"));
			// right token returns the snapshot as JSON
			String ok = get(base + "/api/coach?t=secret");
			assertTrue(ok.startsWith("200|"));
			assertTrue(ok.contains("combat 100"));
			assertTrue(ok.contains("Fire cape"));
			// the page renders (self-contained, references the api)
			String page = get(base + "/?t=secret");
			assertTrue(page.startsWith("200|"));
			assertTrue(page.contains("/api/coach"));
		}
		finally { s.stop(); }
	}

	@Test
	public void emptyTokenIsOpen() throws Exception
	{
		int port = 7788;
		CoachServer s = new CoachServer(port, "").withState(CoachServer.Snapshot::new);
		s.start();
		try { assertEquals("200|", get("http://127.0.0.1:" + port + "/api/coach").substring(0, 4)); }
		finally { s.stop(); }
	}
}
