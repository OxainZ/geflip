package com.geflip;

import java.util.Collections;
import java.util.List;

/**
 * A tiny in-process bridge between the two plugins (both live in one jar → one classloader, so this
 * static state is genuinely shared). The Coach publishes what your ACCOUNT needs right now — farm-run
 * seeds, training supplies, goal items — and the flipper cross-references those against live GE prices
 * so buying your progression is one glance. Read-only data hand-off; neither plugin drives the other.
 */
public final class GeflipShared
{
	private GeflipShared() {}

	/** One thing your account needs, published by the Coach for the flipper to price. */
	public static final class Need
	{
		public final String item;    // exact GE item name (e.g. "Yew sapling", "Ranarr seed", "Ultracompost")
		public final int qty;        // rough amount for the current run/goal
		public final String reason;  // why (e.g. "tree run", "Herblore supply", "goal: Fire cape")
		public Need(String item, int qty, String reason) { this.item = item; this.qty = qty; this.reason = reason; }
	}

	private static volatile List<Need> needs = Collections.emptyList();

	/** Coach → publish the current account shopping list (null clears it). */
	public static void setNeeds(List<Need> n) { needs = (n != null) ? n : Collections.<Need>emptyList(); }

	/** Flipper → read the current account shopping list (never null). */
	public static List<Need> needs() { return needs; }

	/** One live money ROUTE the flipper found (a flip / high-alch / repair edge), published for the Coach's
	 *  unified GP/hr router so the passive GE routes rank alongside the Coach's boss/skilling routes. */
	public static final class Route
	{
		public final String label;    // "Flip: Dragon bones", "High-alch: Rune platebody", "Repair: Dharok's helm"
		public final long gpHr;        // estimated gp/hour for this route
		public final String kind;      // "flip" | "alch" | "repair" — the GE-side, mostly-passive routes
		public Route(String label, long gpHr, String kind) { this.label = label; this.gpHr = gpHr; this.kind = kind; }
	}

	private static volatile List<Route> flipRoutes = Collections.emptyList();

	/** Flipper → publish its top live GE money routes (null clears). */
	public static void setFlipRoutes(List<Route> r) { flipRoutes = (r != null) ? r : Collections.<Route>emptyList(); }

	/** Coach → read the flipper's top GE routes for the unified GP/hr leaderboard (never null). */
	public static List<Route> flipRoutes() { return flipRoutes; }
}
