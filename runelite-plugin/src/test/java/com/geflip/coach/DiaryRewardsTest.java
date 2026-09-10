package com.geflip.coach;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The diary reward table. The coach read diary progress but only reported it; the reward is the
 * reason to do the next tier, so the table has to be complete and correctly keyed or the panel
 * silently drops back to a bare scoreboard.
 */
public class DiaryRewardsTest
{
	private static final String[] TIERS = { "Easy", "Medium", "Hard", "Elite" };
	private static final String[] DIARIES = {
		"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
		"Kourend", "Lumbridge", "Morytania", "Varrock", "Western", "Wilderness" };

	/** All 12 diaries x 4 tiers must resolve - a gap shows as a missing reward in the panel. */
	@Test public void everyDiaryAndTierHasAReward()
	{
		for (String d : DIARIES)
			for (String t : TIERS)
			{
				String[] r = DiaryRewards.of(d, t);
				assertNotNull(d + " " + t + " missing", r);
				assertTrue(d + " " + t + " has no item", r[0] != null && !r[0].isEmpty());
				assertTrue(d + " " + t + " has no benefit", r[1] != null && !r[1].isEmpty());
			}
	}

	@Test public void tableIsExactlyTwelveByFour()
	{
		assertEquals(48, DiaryRewards.REWARDS.length);
		Set<String> keys = new HashSet<>();
		for (String[] r : DiaryRewards.REWARDS) keys.add(r[0] + "|" + r[1]);
		assertEquals("duplicate diary/tier keys", 48, keys.size());
	}

	/** Names must match CoachPlugin's DIARIES table, or lookups silently return null. */
	@Test public void diaryNamesMatchThePluginsKeys()
	{
		Set<String> inTable = new HashSet<>();
		for (String[] r : DiaryRewards.REWARDS) inTable.add(r[0]);
		for (String d : DIARIES) assertTrue("plugin key not in reward table: " + d, inTable.contains(d));
		assertEquals(DIARIES.length, inTable.size());
	}

	/** Spot-check real scraped content rather than trusting the shape alone. */
	@Test public void rewardsCarryTheRealItemNames()
	{
		assertEquals("Ardougne cloak 4", DiaryRewards.of("Ardougne", "Elite")[0]);
		assertEquals("Karamja gloves 3", DiaryRewards.of("Karamja", "Hard")[0]);
		assertEquals("Morytania legs 3", DiaryRewards.of("Morytania", "Hard")[0]);
		assertTrue(DiaryRewards.of("Karamja", "Elite")[1].toLowerCase().contains("duradel"));
	}

	/** Unknown keys return null rather than a wrong reward. */
	@Test public void unknownKeysReturnNull()
	{
		assertNull(DiaryRewards.of("Ardougne", "Master"));
		assertNull(DiaryRewards.of("Lunar Isle", "Easy"));
		assertNull(DiaryRewards.of("ardougne", "Easy"));   // case-sensitive by design
	}
}
