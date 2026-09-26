package com.geflip.overlay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The one piece of logic in the overlay app: what URL the WebView actually opens. */
public class PrefsUrlTest
{
	@Test
	public void hostedDefaultGetsTheOverlayFlag()
	{
		assertEquals("https://oxainz.github.io/geflip/?overlay=1",
			Prefs.overlayUrl("https://oxainz.github.io/geflip/"));
	}

	@Test
	public void blankFallsBackToTheHostedApp()
	{
		assertEquals("https://oxainz.github.io/geflip/?overlay=1", Prefs.overlayUrl(""));
		assertEquals("https://oxainz.github.io/geflip/?overlay=1", Prefs.overlayUrl(null));
		assertEquals("https://oxainz.github.io/geflip/?overlay=1", Prefs.overlayUrl("   "));
	}

	@Test
	public void bridgeTokenSurvives()
	{
		// Losing ?t= here would silently drop the user to hosted-only data: no fills, no offers.
		assertEquals("http://192.168.1.20:7777/?t=abc123&overlay=1",
			Prefs.overlayUrl("http://192.168.1.20:7777/?t=abc123"));
	}

	@Test
	public void bareLanAddressIsHttpAndBareHostIsHttps()
	{
		assertEquals("http://192.168.1.20:7777?overlay=1", Prefs.overlayUrl("192.168.1.20:7777"));
		assertEquals("http://10.0.0.5:7777?overlay=1", Prefs.overlayUrl("10.0.0.5:7777"));
		assertEquals("http://localhost:7777?overlay=1", Prefs.overlayUrl("localhost:7777"));
		assertEquals("https://oxainz.github.io/geflip/?overlay=1",
			Prefs.overlayUrl("oxainz.github.io/geflip/"));
	}

	@Test
	public void neverAddsTheFlagTwice()
	{
		assertEquals("https://x.dev/?overlay=1", Prefs.overlayUrl("https://x.dev/?overlay=1"));
		assertEquals("https://x.dev/?t=1&overlay=1", Prefs.overlayUrl("https://x.dev/?t=1&overlay=1"));
	}

	@Test
	public void aParamMerelyENDINGinOverlayIsNotTheFlag()
	{
		assertEquals("https://x.dev/?myoverlay=1&overlay=1",
			Prefs.overlayUrl("https://x.dev/?myoverlay=1"));
	}

	@Test
	public void fragmentStaysLast()
	{
		assertEquals("https://x.dev/?overlay=1#book", Prefs.overlayUrl("https://x.dev/#book"));
		assertEquals("https://x.dev/?t=9&overlay=1#book", Prefs.overlayUrl("https://x.dev/?t=9#book"));
	}

	@Test
	public void surroundingWhitespaceIsForgiven()
	{
		assertEquals("https://x.dev/?overlay=1", Prefs.overlayUrl("  https://x.dev/  "));
	}
}
