package com.geflip.coach;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the account snapshot + goal graph into ranked advice. Pure logic (no client, no Swing) so
 * it's trivially testable and thread-safe. For each goal it collects the unmet requirements; a goal
 * with zero gaps is READY, 1-2 gaps is ALMOST, 3+ is BLOCKED. "Do next" = ready goals by impact,
 * then the almost-there ones (momentum), so you always see both what you can do now and the
 * cheapest thing standing between you and the next unlock.
 */
final class CoachEngine
{
	enum Status { DONE, READY, ALMOST, BLOCKED }

	static final class Scored
	{
		final CoachGoals.Goal goal; final Status status; final List<String> gaps;
		Scored(CoachGoals.Goal goal, Status status, List<String> gaps) { this.goal = goal; this.status = status; this.gaps = gaps; }
		int gapCount() { return gaps.size(); }
	}

	/** Effort → a small numeric cost so ready goals of equal impact prefer the quicker one. */
	private static int effortRank(String e)
	{
		switch (e) { case "quick": return 0; case "medium": return 1; case "long": return 2; default: return 3; }
	}

	static List<Scored> evaluate(CoachState s)
	{
		List<Scored> out = new ArrayList<>();
		for (CoachGoals.Goal g : CoachGoals.GOALS)
		{
			List<String> gaps = new ArrayList<>();
			for (CoachGoals.Req r : g.reqs)
			{
				String gap = r.gap(s);
				if (gap != null) gaps.add(gap);
			}
			Status st = gaps.isEmpty() ? Status.READY : gaps.size() <= 2 ? Status.ALMOST : Status.BLOCKED;
			out.add(new Scored(g, st, gaps));
		}
		return out;
	}

	/** The headline "do next" list: everything you can do RIGHT NOW (impact desc, effort asc),
	 *  followed by the almost-there goals (fewest gaps first) for momentum. */
	static List<Scored> doNext(List<Scored> all)
	{
		List<Scored> ready = new ArrayList<>();
		List<Scored> almost = new ArrayList<>();
		for (Scored sc : all)
		{
			if (sc.status == Status.READY) ready.add(sc);
			else if (sc.status == Status.ALMOST) almost.add(sc);
		}
		ready.sort(Comparator.<Scored>comparingInt(x -> -x.goal.impact)
			.thenComparingInt(x -> effortRank(x.goal.effort)));
		almost.sort(Comparator.<Scored>comparingInt(x -> x.gapCount())
			.thenComparingInt(x -> -x.goal.impact));
		List<Scored> out = new ArrayList<>(ready);
		out.addAll(almost);
		return out;
	}

	/** Goals still fully blocked (3+ gaps), highest-impact first — the longer-term arcs. */
	static List<Scored> blocked(List<Scored> all)
	{
		List<Scored> out = new ArrayList<>();
		for (Scored sc : all) if (sc.status == Status.BLOCKED) out.add(sc);
		out.sort(Comparator.<Scored>comparingInt(x -> -x.goal.impact).thenComparingInt(Scored::gapCount));
		return out;
	}
}
