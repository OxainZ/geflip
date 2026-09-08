package com.geflip.coach;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Ask path's response parse. This broke silently: the old code read content[0].text, but
 * current models (Opus 5 / Sonnet 5) think by default and put a THINKING block first, so the
 * panel printed a raw JSON blob instead of an answer. These pin the real wire shapes.
 */
public class CoachAnthropicParseTest
{
	private static JsonObject parse(String s) { return new JsonParser().parse(s).getAsJsonObject(); }

	/** The exact shape that broke it: thinking block first, text second. */
	@Test public void findsTextWhenAThinkingBlockComesFirst()
	{
		JsonObject j = parse("{\"stop_reason\":\"end_turn\",\"content\":["
			+ "{\"type\":\"thinking\",\"thinking\":\"\"},"
			+ "{\"type\":\"text\",\"text\":\"Do Barrows for the gp.\"}]}");
		assertEquals("Do Barrows for the gp.", CoachPlugin.anthropicAnswer(j));
	}

	/** display:"omitted" gives a thinking block with an EMPTY text field — must not be picked. */
	@Test public void skipsAnEmptyTextFieldOnAThinkingBlock()
	{
		JsonObject j = parse("{\"content\":["
			+ "{\"type\":\"thinking\",\"text\":\"\"},"
			+ "{\"type\":\"text\",\"text\":\"Train Slayer.\"}]}");
		assertEquals("Train Slayer.", CoachPlugin.anthropicAnswer(j));
	}

	/** The plain, no-thinking shape must still work. */
	@Test public void handlesTextOnlyResponse()
	{
		assertEquals("Zulrah needs 43 Prayer.",
			CoachPlugin.anthropicAnswer(parse("{\"content\":[{\"type\":\"text\",\"text\":\"Zulrah needs 43 Prayer.\"}]}")));
	}

	/** Tool-use style blocks in between shouldn't derail it. */
	@Test public void skipsNonTextBlocksAnywhereInTheArray()
	{
		JsonObject j = parse("{\"content\":["
			+ "{\"type\":\"thinking\",\"thinking\":\"...\"},"
			+ "{\"type\":\"tool_use\",\"name\":\"x\"},"
			+ "{\"type\":\"text\",\"text\":\"Answer here.\"}]}");
		assertEquals("Answer here.", CoachPlugin.anthropicAnswer(j));
	}

	/** A refusal is a 200 with no text — say so, don't dump JSON. */
	@Test public void explainsARefusalInPlainEnglish()
	{
		String out = CoachPlugin.anthropicAnswer(
			parse("{\"stop_reason\":\"refusal\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"}]}"));
		assertTrue(out, out.toLowerCase().contains("declined"));
		assertFalse("must not leak raw JSON", out.contains("{"));
	}

	/** Thinking ate the whole budget — explain rather than showing an empty panel. */
	@Test public void explainsATruncatedAnswer()
	{
		String out = CoachPlugin.anthropicAnswer(
			parse("{\"stop_reason\":\"max_tokens\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\"}]}"));
		assertTrue(out, out.toLowerCase().contains("token"));
		assertFalse("must not leak raw JSON", out.contains("{"));
	}

	/** Degenerate shapes must not throw — the old code's NPE is what caused the blob dump. */
	@Test public void neverThrowsOnMalformedOrEmptyContent()
	{
		assertFalse(CoachPlugin.anthropicAnswer(parse("{\"content\":[]}")).isEmpty());
		assertFalse(CoachPlugin.anthropicAnswer(parse("{}")).isEmpty());
		assertFalse(CoachPlugin.anthropicAnswer(parse("{\"content\":\"not-an-array\"}")).isEmpty());
		assertFalse(CoachPlugin.anthropicAnswer(
			parse("{\"content\":[{\"type\":\"text\",\"text\":null}]}")).isEmpty());
	}
}
