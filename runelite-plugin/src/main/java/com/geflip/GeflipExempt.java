package com.geflip;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** GE tax-exempt items (mirrors the web app): bonds + starter tools. */
final class GeflipExempt
{
	static final Set<String> EXEMPT = new HashSet<>(Arrays.asList(
		"old school bond", "chisel", "gardening trowel", "glassblowing pipe",
		"hammer", "needle", "pestle and mortar", "rake", "saw", "secateurs",
		"seed dibber", "shears", "spade", "watering can"));

	private GeflipExempt() {}
}
