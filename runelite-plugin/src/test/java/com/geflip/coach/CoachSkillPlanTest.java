package com.geflip.coach;

import org.junit.Test;
import net.runelite.api.Skill;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;

/** Sanity for the all-skills 1→99 trainer: data loaded, band selection, passive HP, and a monotone ETA. */
public class CoachSkillPlanTest
{
	@Test
	public void dataLoadedForEveryCombatGatheringArtisanSupportSkill()
	{
		Skill[] covered = { Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.HITPOINTS, Skill.RANGED,
			Skill.MAGIC, Skill.PRAYER, Skill.MINING, Skill.FISHING, Skill.WOODCUTTING, Skill.HUNTER,
			Skill.SMITHING, Skill.CRAFTING, Skill.FLETCHING, Skill.CONSTRUCTION, Skill.FIREMAKING,
			Skill.COOKING, Skill.HERBLORE, Skill.RUNECRAFT, Skill.AGILITY, Skill.THIEVING, Skill.SLAYER };
		for (Skill s : covered)
			assertTrue(s + " should have method bands", CoachSkillPlan.bands(s).length > 0);
	}

	@Test
	public void bandSelectionPicksTheRightMethod()
	{
		assertEquals("first band at low level", 1, CoachSkillPlan.bestBand(Skill.MINING, 3).min);
		assertTrue("Ranged 50 = chinning band", CoachSkillPlan.bestBand(Skill.RANGED, 50).method.toLowerCase().contains("chin"));
		assertTrue("Firemaking 60 = Wintertodt", CoachSkillPlan.bestBand(Skill.FIREMAKING, 60).method.contains("Wintertodt"));
		assertNotNull(CoachSkillPlan.nextBand(Skill.PRAYER, 40));
	}

	@Test
	public void hitpointsIsPassiveAndEtaIsMonotone()
	{
		// HP has a single passive band with xpHr 0 → hoursTo99 contributes nothing (passive)
		assertEquals(0, CoachSkillPlan.bestBand(Skill.HITPOINTS, 50).xpHr);
		// a real skill: fewer hours left the higher your XP, and 0 at 99
		double at50 = CoachSkillPlan.hoursTo99(Skill.THIEVING, CoachFarmPlan.xpForLevel(50));
		double at85 = CoachSkillPlan.hoursTo99(Skill.THIEVING, CoachFarmPlan.xpForLevel(85));
		assertTrue(at50 > at85);
		assertEquals(0.0, CoachSkillPlan.hoursTo99(Skill.THIEVING, CoachFarmPlan.xpForLevel(99)), 0.001);
	}
}
