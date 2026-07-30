package com.geflip.coach;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** Sanity for the DPS engine: hit-chance bounds/monotonicity, and DPS responding to gear / boss defence /
 *  weapon speed via the loadout→ranged() pipeline. Pins the invariants so a formula edit can't silently
 *  invert them (the DPS numbers feed gear advice, so a wrong sign is a wrong recommendation). */
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

	// a plain ranged loadout (no prayer/void/crystal/conditionals) with a given ranged-str bonus + speed
	private static CoachDps.RangedIn rin(int strBonus, int speedTicks)
	{
		return CoachDps.rangedLoadout(90, 1, 100, strBonus, speedTicks, false, false, 1.0, 1.0, false, false, false);
	}

	@Test
	public void dpsRespondsToGearDefenceSpeed()
	{
		CoachDps.Boss weak = new CoachDps.Boss("weak", 1, 0, 0, 0, 0, 0, 500, false, false, false, "");
		CoachDps.Boss tank = new CoachDps.Boss("tank", 300, 300, 300, 300, 300, 300, 500, false, false, false, "");

		// more ranged strength bonus → more DPS (same boss, same speed)
		assertTrue(CoachDps.ranged(rin(120, 3), weak).dps > CoachDps.ranged(rin(40, 3), weak).dps);
		// tougher boss defence → less DPS (same gear)
		assertTrue(CoachDps.ranged(rin(80, 3), weak).dps > CoachDps.ranged(rin(80, 3), tank).dps);
		// faster weapon (fewer ticks) → more DPS
		assertTrue(CoachDps.ranged(rin(80, 3), weak).dps > CoachDps.ranged(rin(80, 6), weak).dps);

		// sanity vs a real boss: positive, bounded outputs
		CoachDps.Result r = CoachDps.ranged(rin(80, 3), CoachDps.BOSSES.get(0));
		assertTrue(r.dps > 0 && r.maxHit > 0 && r.hitChance > 0 && r.hitChance <= 1.0 && r.ttk > 0);
	}
}
