package com.geflip.coach;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Sanity for the 1→99 Farming plan: correct band selection, the canonical 99 XP, and a monotone ETA. */
public class CoachFarmPlanTest
{
	@Test
	public void xpForNinetyNineIsCanonical()
	{
		assertEquals(13_034_431L, CoachFarmPlan.xpForLevel(99));
	}

	@Test
	public void bandSelection()
	{
		assertEquals(1, CoachFarmPlan.bandFor(1).min);
		assertEquals(15, CoachFarmPlan.bandFor(20).min);   // 20 sits in the 15+ Oak band
		assertEquals(45, CoachFarmPlan.bandFor(45).min);   // exact breakpoint → that band
		assertEquals(60, CoachFarmPlan.bandFor(64).min);   // 64 is in the Yew band, before 65
		assertEquals(90, CoachFarmPlan.bandFor(99).min);   // capped at the final band
		assertEquals("Magic", CoachFarmPlan.bandFor(78).tree);
		assertEquals("Dragonfruit (81)", CoachFarmPlan.bandFor(81).fruit);
	}

	@Test
	public void nextBandAndEta()
	{
		assertEquals(60, CoachFarmPlan.nextBand(58).min);   // from 58 the next milestone is Yew@60
		assertTrue(CoachFarmPlan.nextBand(95) == null);      // in the final band, no next
		// ETA shrinks as you gain XP, and is 0 once you hit 99
		double early = CoachFarmPlan.daysTo99(CoachFarmPlan.xpForLevel(50));
		double late = CoachFarmPlan.daysTo99(CoachFarmPlan.xpForLevel(90));
		assertTrue("more level = fewer days left", early > late);
		assertEquals(0.0, CoachFarmPlan.daysTo99(CoachFarmPlan.xpForLevel(99)), 0.001);
	}
}
