package com.geflip.coach;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the account snapshot + goal graph into ranked advice. Pure logic (no client, no Swing) so
 * it's trivially testable and thread-safe. Crucially it grades by the SIZE of what's missing, not
 * the count: one +47-Slayer gap is "blocked/long-term", not "almost" — the old count-only logic
 * wrongly flagged Infernal and +40-slayer bosses as nearly done.
 */
final class CoachEngine
{
	enum Status { DONE, READY, ALMOST, BLOCKED }

	// "almost" = genuinely close: no single big grind, and not much total.
	private static final int ALMOST_MAX_GAP = 12;    // no single requirement worse than ~12 levels
	private static final int ALMOST_TOTAL = 20;      // and ~20 total "distance" across all gaps

	static final class Scored
	{
		final CoachGoals.Goal goal; final Status status; final List<String> gaps;
		final int totalWeight, maxWeight;
		Scored(CoachGoals.Goal goal, Status status, List<String> gaps, int totalWeight, int maxWeight)
		{ this.goal = goal; this.status = status; this.gaps = gaps; this.totalWeight = totalWeight; this.maxWeight = maxWeight; }
		int gapCount() { return gaps.size(); }
	}

	private static int effortRank(String e)
	{
		switch (e) { case "quick": return 0; case "medium": return 1; case "long": return 2; default: return 3; }
	}

	static List<Scored> evaluate(CoachState s)
	{
		List<Scored> out = new ArrayList<>();
		for (CoachGoals.Goal g : CoachGoals.GOALS)
		{
			// already own the item this goal produces? → DONE (don't nag you to get what you have)
			int[] doneIds = CoachGoals.DONE_IF_OWN.get(g.name);
			if (doneIds != null) { boolean have = false; for (int id : doneIds) if (s.owns(id)) { have = true; break; }
				if (have) { out.add(new Scored(g, Status.DONE, new ArrayList<>(), 0, 0)); continue; } }
			List<String> gaps = new ArrayList<>();
			int total = 0, max = 0;
			for (CoachGoals.Req r : g.reqs)
			{
				CoachGoals.Gap gap = r.gap(s);
				if (gap != null) { gaps.add(gap.text); total += gap.weight; max = Math.max(max, gap.weight); }
			}
			Status st;
			if (gaps.isEmpty()) st = Status.READY;
			else if (max <= ALMOST_MAX_GAP && total <= ALMOST_TOTAL && gaps.size() <= 3) st = Status.ALMOST;
			else st = Status.BLOCKED;
			out.add(new Scored(g, st, gaps, total, max));
		}
		return out;
	}

	/** "Do next": everything READY (impact desc, effort asc), then ALMOST (closest first). */
	static List<Scored> doNext(List<Scored> all)
	{
		List<Scored> ready = new ArrayList<>(), almost = new ArrayList<>();
		for (Scored sc : all)
		{
			if (sc.status == Status.READY) ready.add(sc);
			else if (sc.status == Status.ALMOST) almost.add(sc);
		}
		ready.sort(Comparator.<Scored>comparingInt(x -> -x.goal.impact).thenComparingInt(x -> effortRank(x.goal.effort)));
		almost.sort(Comparator.<Scored>comparingInt(x -> x.totalWeight).thenComparingInt(x -> -x.goal.impact));
		List<Scored> out = new ArrayList<>(ready);
		out.addAll(almost);
		return out;
	}

	/** Blocked/long-term goals, highest-impact first then closest. */
	static List<Scored> blocked(List<Scored> all)
	{
		List<Scored> out = new ArrayList<>();
		for (Scored sc : all) if (sc.status == Status.BLOCKED) out.add(sc);
		out.sort(Comparator.<Scored>comparingInt(x -> -x.goal.impact).thenComparingInt(x -> x.totalWeight));
		return out;
	}

	/** Curated quests not yet finished, split into do-now vs needs-skills, by gap size. */
	static final class QuestScored
	{
		final CoachGoals.QuestRec rec; final boolean ready; final List<String> gaps; final int totalWeight;
		QuestScored(CoachGoals.QuestRec rec, boolean ready, List<String> gaps, int totalWeight)
		{ this.rec = rec; this.ready = ready; this.gaps = gaps; this.totalWeight = totalWeight; }
	}

	static List<QuestScored> quests(CoachState s)
	{
		List<QuestScored> out = new ArrayList<>();
		for (CoachGoals.QuestRec q : CoachGoals.QUESTS)
		{
			if (s.finished(q.q)) continue;   // already done — the whole point of reading every quest
			List<String> gaps = new ArrayList<>();
			int total = 0;
			for (CoachGoals.Req r : q.reqs)
			{
				CoachGoals.Gap gap = r.gap(s);
				if (gap != null) { gaps.add(gap.text); total += gap.weight; }
			}
			out.add(new QuestScored(q, gaps.isEmpty(), gaps, total));
		}
		// do-now first, then by how far off
		out.sort(Comparator.<QuestScored>comparingInt(x -> x.ready ? 0 : 1).thenComparingInt(x -> x.totalWeight));
		return out;
	}

	/** Personalised OPTIMAL quest order: a topological sort of the unfinished recommended quests so every
	 *  prerequisite quest comes before the quest that needs it, tie-broken by "ready now, then closest".
	 *  Answers "what order do I actually do these in" instead of a flat nearest-first list. */
	static List<QuestScored> questOrder(CoachState s)
	{
		List<QuestScored> nodes = quests(s);   // already excludes finished + carries ready/gaps
		java.util.Map<net.runelite.api.Quest, QuestScored> byQuest = new java.util.HashMap<>();
		for (QuestScored n : nodes) byQuest.put(n.rec.q, n);

		// edges: prereq → dependent, but only among unfinished nodes (finished/out-of-list prereqs are gaps, not order)
		java.util.Map<net.runelite.api.Quest, Integer> indeg = new java.util.HashMap<>();
		java.util.Map<net.runelite.api.Quest, List<net.runelite.api.Quest>> adj = new java.util.HashMap<>();
		for (QuestScored n : nodes) indeg.put(n.rec.q, 0);
		for (QuestScored n : nodes)
			for (net.runelite.api.Quest pre : n.rec.prereqQuests())
				if (byQuest.containsKey(pre))
				{
					adj.computeIfAbsent(pre, k -> new ArrayList<>()).add(n.rec.q);
					indeg.merge(n.rec.q, 1, Integer::sum);
				}

		Comparator<QuestScored> cmp = Comparator.<QuestScored>comparingInt(x -> x.ready ? 0 : 1).thenComparingInt(x -> x.totalWeight);
		java.util.PriorityQueue<QuestScored> pq = new java.util.PriorityQueue<>(cmp);
		for (QuestScored n : nodes) if (indeg.get(n.rec.q) == 0) pq.add(n);
		List<QuestScored> order = new ArrayList<>();
		while (!pq.isEmpty())
		{
			QuestScored n = pq.poll();
			order.add(n);
			for (net.runelite.api.Quest m : adj.getOrDefault(n.rec.q, java.util.Collections.emptyList()))
				if (indeg.merge(m, -1, Integer::sum) == 0) pq.add(byQuest.get(m));
		}
		// any node left in a cycle (shouldn't happen with real quest data) — append so nothing is dropped
		if (order.size() < nodes.size())
			for (QuestScored n : nodes) if (!order.contains(n)) order.add(n);
		return order;
	}
}
