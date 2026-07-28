package com.geflip.coach;

/**
 * DPS-vs-boss engine. The standard OSRS accuracy + max-hit + DPS formulas (pure math), plus a curated
 * table of boss defensive stats (wiki-verified) so the plugin can estimate your damage-per-second against
 * real targets with your CURRENT gear — and, with the upgrade finder, "the cheapest upgrade that adds the
 * most DPS here." Estimates use your live levels + equipment bonuses + weapon speed; prayers/potions off
 * unless boosted levels already include them (clearly labelled as a base estimate).
 */
final class CoachDps
{
	private CoachDps() {}

	/** Boss defensive profile: defence level + the 5 defensive bonuses, and its weakness note. */
	static final class Boss
	{
		final String name; final int defLvl, dStab, dSlash, dCrush, dRange, dMagic; final String note;
		Boss(String name, int defLvl, int dStab, int dSlash, int dCrush, int dRange, int dMagic, String note)
		{ this.name = name; this.defLvl = defLvl; this.dStab = dStab; this.dSlash = dSlash; this.dCrush = dCrush;
		  this.dRange = dRange; this.dMagic = dMagic; this.note = note; }
	}

	// Filled from the deep-research pass (wiki defensive stats). Populated in BossData.
	static final java.util.List<Boss> BOSSES = new java.util.ArrayList<>();
	static { BossData.load(BOSSES); }

	/** OSRS hit chance from the attack roll vs the defence roll. */
	static double hitChance(long atkRoll, long defRoll)
	{
		return atkRoll > defRoll
			? 1.0 - (defRoll + 2.0) / (2.0 * (atkRoll + 1.0))
			: atkRoll / (2.0 * (defRoll + 1.0));
	}

	/** DPS = (hitChance × maxHit/2) / (speedTicks × 0.6s). effAtkLvl already includes the +8 constant. */
	static double dps(int effAtkLvl, int atkBonus, int maxHit, int defLvl, int defBonus, int speedTicks)
	{
		long atkRoll = (long) effAtkLvl * (atkBonus + 64);
		long defRoll = (long) (defLvl + 9) * (defBonus + 64);
		double hc = hitChance(atkRoll, defRoll);
		double avg = hc * maxHit / 2.0;
		return avg / (Math.max(1, speedTicks) * 0.6);
	}

	static double hitChanceFor(int effAtkLvl, int atkBonus, int defLvl, int defBonus)
	{
		return hitChance((long) effAtkLvl * (atkBonus + 64), (long) (defLvl + 9) * (defBonus + 64));
	}
}
