package com.geflip.coach;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** Sanity for the DPS engine: hit-chance bounds/monotonicity, DPS responds to gear + boss defence, data loaded. */
public class CoachDpsTest
{
	@Test
	public void bossesLoaded()
	{
		assertTrue("boss table populated", CoachDps.BOSSES.size() >= 15);
	}

	@Test
	public void hitChanceIsBoundedAndMonotone()
	{
		double overwhelming = CoachDps.hitChance(100_000, 1_000);   // atk >> def → near 1
		double hopeless = CoachDps.hitChance(1_000, 100_000);       // atk << def → near 0
		assertTrue(overwhelming > 0.9 && overwhelming <= 1.0);
		assertTrue(hopeless >= 0.0 && hopeless < 0.1);
		// more attack roll never lowers hit chance
		assertTrue(CoachDps.hitChance(50_000, 30_000) >= CoachDps.hitChance(40_000, 30_000));
	}

	@Test
	public void dpsRespondsToGearAndDefence()
	{
		// same everything, more attack bonus → more DPS
		double lowBonus = CoachDps.dps(120, 50, 30, 200, 100, 4);
		double highBonus = CoachDps.dps(120, 150, 30, 200, 100, 4);
		assertTrue(highBonus > lowBonus);
		// tougher boss defence → less DPS
		double vsWeak = CoachDps.dps(120, 100, 30, 100, 20, 4);
		double vsTank = CoachDps.dps(120, 100, 30, 400, 300, 4);
		assertTrue(vsWeak > vsTank);
		// faster weapon (fewer ticks) → more DPS
		double slow = CoachDps.dps(120, 100, 30, 200, 100, 6);
		double fast = CoachDps.dps(120, 100, 30, 200, 100, 3);
		assertTrue(fast > slow);
	}
}
