package com.geflip.coach;

/**
 * OSRS DPS engine — the verified accuracy / max-hit / DPS formulas from weirdgloop/osrs-dps-calc
 * (PlayerVsNPCCalc.ts) and the OSRS Wiki. Floor placement + operation order match the game exactly:
 * effective levels build accuracy and strength SEPARATELY, gear multipliers floor after EACH step in
 * the documented order (crystal → salve/slayer → Dragon-hunter), and hit chance is LINEAR.
 *
 * The Coach can't know which prayer/potion the player will click, so {@link #rangedLoadout} and
 * {@link #meleeLoadout} pick a SENSIBLE assumed loadout (best affordable offensive prayer + the
 * standard potion + Rapid/Aggressive style) and carry a human-readable {@code label} of what was
 * assumed. Conditional gear (Void / crystal / Salve / Slayer helm / Dragon-hunter) is applied ONLY
 * when the equipped item AND the target flag both qualify — see {@link Boss}.
 */
final class CoachDps
{
	private CoachDps() {}

	/** floor for non-negative values (== trunc in the wiki formulas). */
	private static int trunc(double x) { return (int) Math.floor(x); }
	private static long tlong(double x) { return (long) Math.floor(x); }

	/** Boss defensive profile + the flags the engine's conditional multipliers need. */
	static final class Boss
	{
		final String name; final int defLvl, dStab, dSlash, dCrush, dRange, dMagic, hp;
		final boolean dragon, undead, slayer; final String note;
		Boss(String name, int defLvl, int dStab, int dSlash, int dCrush, int dRange, int dMagic, int hp,
			boolean dragon, boolean undead, boolean slayer, String note)
		{ this.name = name; this.defLvl = defLvl; this.dStab = dStab; this.dSlash = dSlash; this.dCrush = dCrush;
		  this.dRange = dRange; this.dMagic = dMagic; this.hp = hp; this.dragon = dragon; this.undead = undead;
		  this.slayer = slayer; this.note = note; }
	}

	static final java.util.List<Boss> BOSSES = new java.util.ArrayList<>();
	static { BossData.load(BOSSES); }

	/** One boss's outcome: DPS, the max hit used, hit chance (0-1), and time-to-kill in seconds. */
	static final class Result
	{
		final double dps, hitChance, ttk; final int maxHit;
		Result(double dps, int maxHit, double hitChance, double ttk)
		{ this.dps = dps; this.maxHit = maxHit; this.hitChance = hitChance; this.ttk = ttk; }
	}

	/** OSRS hit chance — LINEAR in the rolls (not squared). */
	static double hitChance(long atkRoll, long defRoll)
	{
		return atkRoll > defRoll
			? 1.0 - (defRoll + 2.0) / (2.0 * (atkRoll + 1.0))
			: atkRoll / (2.0 * (defRoll + 1.0));
	}

	/** DPS = (hitChance × maxHit/2) / (speedTicks × 0.6s). maxHit/2 is the exact average of a 0..max roll. */
	private static double dpsOf(double hc, int maxHit, int speedTicks)
	{
		return (hc * maxHit / 2.0) / (Math.max(1, speedTicks) * 0.6);
	}

	// =========================================================================================
	// RANGED  (the priority — blowpipe account)
	// =========================================================================================

	/** Assumed-loadout inputs for a ranged calc, produced by {@link #rangedLoadout}. */
	static final class RangedIn
	{
		int lvl, potBoost, atkBonus, strBonus, speed, styleAcc, styleStr;
		double prayAcc, prayStr, voidAcc, voidStr, crystalAcc, crystalDmg;
		boolean salveEi, slayerHelm, dhcb;
		String label = "";
	}

	/**
	 * Pick the ranged loadout: best affordable prayer (Rigour ≥74 → Eagle Eye ≥44 → none), a Ranging
	 * potion, and Rapid style (no level bonus, −1 tick — already reflected in {@code speedTicks}).
	 * Void/crystal multipliers are passed in from the live gear scan. potion + prayer are computed from
	 * REAL levels so the number is a stable "with this loadout" estimate regardless of current buffs.
	 */
	static RangedIn rangedLoadout(int realRanged, int realPrayer, int atkBonus, int strBonus, int speedTicks,
		boolean fullVoid, boolean eliteVoid, double crystalAcc, double crystalDmg,
		boolean salveEi, boolean slayerHelm, boolean dhcb)
	{
		RangedIn in = new RangedIn();
		in.lvl = realRanged;
		in.potBoost = 4 + trunc(0.10 * realRanged);         // Ranging potion
		in.atkBonus = atkBonus; in.strBonus = strBonus; in.speed = speedTicks;
		in.styleAcc = 0; in.styleStr = 0;                    // Rapid: no level bonus

		String prayer;
		if (realPrayer >= 74)      { in.prayAcc = 1.20; in.prayStr = 1.23; prayer = "Rigour"; }
		else if (realPrayer >= 44) { in.prayAcc = 1.15; in.prayStr = 1.15; prayer = "Eagle Eye"; }
		else                       { in.prayAcc = 1.0;  in.prayStr = 1.0;  prayer = "no prayer"; }

		if (fullVoid) { in.voidAcc = 1.10; in.voidStr = eliteVoid ? 1.125 : 1.10; }
		else          { in.voidAcc = 1.0;  in.voidStr = 1.0; }
		in.crystalAcc = crystalAcc; in.crystalDmg = crystalDmg;
		in.salveEi = salveEi; in.slayerHelm = slayerHelm; in.dhcb = dhcb;

		StringBuilder l = new StringBuilder(prayer + " + ranging pot + Rapid");
		if (fullVoid) l.append(eliteVoid ? " + elite void" : " + void");
		if (crystalDmg > 1.0) l.append(" + crystal");
		in.label = l.toString();
		return in;
	}

	/** Ranged DPS vs one boss. Applies conditional gear only where the target flags qualify. */
	static Result ranged(RangedIn in, Boss b)
	{
		int base = in.lvl + in.potBoost;
		int effAcc = trunc(base * in.prayAcc); effAcc += in.styleAcc; effAcc += 8; effAcc = trunc(effAcc * in.voidAcc);
		int effStr = trunc(base * in.prayStr); effStr += in.styleStr; effStr += 8; effStr = trunc(effStr * in.voidStr);

		// max hit — floor after each multiplier, in order: crystal → salve/slayer → DHCB
		int max = trunc(0.5 + effStr * (in.strBonus + 64) / 640.0);
		if (in.crystalDmg > 1.0) max = trunc(max * in.crystalDmg);
		double dmgMul = combatBonus(in.salveEi && b.undead, in.slayerHelm && b.slayer, 1.20, 1.15);
		if (dmgMul > 1.0) max = trunc(max * dmgMul);
		if (in.dhcb && b.dragon) max = trunc(max * 1.25);

		// accuracy roll — same order of multipliers as max hit
		long attRoll = (long) effAcc * (in.atkBonus + 64);
		if (in.crystalAcc > 1.0) attRoll = tlong(attRoll * in.crystalAcc);
		double accMul = combatBonus(in.salveEi && b.undead, in.slayerHelm && b.slayer, 1.20, 1.15);
		if (accMul > 1.0) attRoll = tlong(attRoll * accMul);
		if (in.dhcb && b.dragon) attRoll = tlong(attRoll * 1.30);

		long defRoll = (long) (b.defLvl + 9) * (b.dRange + 64);
		double hc = hitChance(attRoll, defRoll);
		double dps = dpsOf(hc, max, in.speed);
		return new Result(dps, max, hc, dps > 0 ? b.hp / dps : 0);
	}

	// =========================================================================================
	// MELEE  (secondary — assumes Aggressive style; picks the best of stab/slash/crush per boss)
	// =========================================================================================

	static final class MeleeIn
	{
		int strLvl, atkLvl, strPot, atkPot, aStab, aSlash, aCrush, strBonus, speed, styleAcc, styleStr;
		double prayAcc, prayStr, voidAcc, voidStr;
		boolean salveEi, salveI, slayerHelm, dhl;
		String label = "";
	}

	/**
	 * Melee loadout: Piety (≥70 Prayer & Defence) → Ultimate Str + Incredible Reflexes (≥44) →
	 * Superhuman Str + Improved Reflexes (≥16) → none; Super combat potion; Aggressive style (+3 str).
	 */
	static MeleeIn meleeLoadout(int realStr, int realAtk, int realPrayer, int realDef,
		int aStab, int aSlash, int aCrush, int strBonus, int speedTicks,
		boolean fullVoid, boolean salveEi, boolean salveI, boolean slayerHelm, boolean dhl)
	{
		MeleeIn in = new MeleeIn();
		in.strLvl = realStr; in.atkLvl = realAtk;
		in.strPot = 5 + trunc(0.15 * realStr);              // Super combat (strength component)
		in.atkPot = 5 + trunc(0.15 * realAtk);              // Super combat (attack component)
		in.aStab = aStab; in.aSlash = aSlash; in.aCrush = aCrush; in.strBonus = strBonus; in.speed = speedTicks;
		in.styleAcc = 0; in.styleStr = 3;                    // Aggressive: +3 strength, no accuracy bonus

		String prayer;
		if (realPrayer >= 70 && realDef >= 70) { in.prayAcc = 1.20; in.prayStr = 1.23; prayer = "Piety"; }
		else if (realPrayer >= 44)             { in.prayAcc = 1.15; in.prayStr = 1.15; prayer = "Ultimate Str"; }
		else if (realPrayer >= 16)             { in.prayAcc = 1.10; in.prayStr = 1.10; prayer = "Superhuman Str"; }
		else                                   { in.prayAcc = 1.0;  in.prayStr = 1.0;  prayer = "no prayer"; }

		if (fullVoid) { in.voidAcc = 1.10; in.voidStr = 1.10; }   // melee void: no elite bonus
		else          { in.voidAcc = 1.0;  in.voidStr = 1.0; }
		in.salveEi = salveEi; in.salveI = salveI; in.slayerHelm = slayerHelm; in.dhl = dhl;

		StringBuilder l = new StringBuilder(prayer + " + super combat + Aggressive");
		if (fullVoid) l.append(" + void");
		in.label = l.toString();
		return in;
	}

	/** Melee DPS vs one boss — evaluates stab/slash/crush against that boss's matching defence, best wins. */
	static Result melee(MeleeIn in, Boss b)
	{
		int effStr = trunc((in.strLvl + in.strPot) * in.prayStr); effStr += in.styleStr; effStr += 8; effStr = trunc(effStr * in.voidStr);
		int effAtk = trunc((in.atkLvl + in.atkPot) * in.prayAcc); effAtk += in.styleAcc; effAtk += 8; effAtk = trunc(effAtk * in.voidAcc);

		// Salve is mutually exclusive with Slayer and wins. Salve(ei) 1.20, Salve(i) 7/6 (melee only). Slayer(i) melee 7/6.
		double salveMul = (in.salveEi && b.undead) ? 1.20 : (in.salveI && b.undead) ? (7.0 / 6.0) : 1.0;
		double gearMul  = combatBonus(salveMul > 1.0, in.slayerHelm && b.slayer, salveMul, 7.0 / 6.0);

		// max hit — gear multiplier floored, then DHL 6/5 vs dragons
		int max = trunc(0.5 + effStr * (in.strBonus + 64) / 640.0);
		if (gearMul > 1.0) max = trunc(max * gearMul);
		if (in.dhl && b.dragon) max = trunc(max * (6.0 / 5.0));

		// accuracy — pick the best of stab/slash/crush vs the boss's matching defence
		double bestHc = 0; int[] atk = { in.aStab, in.aSlash, in.aCrush }; int[] def = { b.dStab, b.dSlash, b.dCrush };
		for (int i = 0; i < 3; i++)
		{
			long attRoll = (long) effAtk * (atk[i] + 64);
			if (gearMul > 1.0) attRoll = tlong(attRoll * gearMul);
			if (in.dhl && b.dragon) attRoll = tlong(attRoll * (6.0 / 5.0));
			long defRoll = (long) (b.defLvl + 9) * (def[i] + 64);
			bestHc = Math.max(bestHc, hitChance(attRoll, defRoll));
		}
		double dps = dpsOf(bestHc, max, in.speed);
		return new Result(dps, max, bestHc, dps > 0 ? b.hp / dps : 0);
	}

	/**
	 * Salve/Slayer are mutually exclusive and Salve WINS. Returns the chosen damage/accuracy multiplier
	 * (they share the same value on each roll). {@code salveMul} used if salve applies, else {@code slayerMul}
	 * if the slayer condition holds, else 1.0.
	 */
	private static double combatBonus(boolean salveApplies, boolean slayerApplies, double salveMul, double slayerMul)
	{
		if (salveApplies) return salveMul;
		if (slayerApplies) return slayerMul;
		return 1.0;
	}
}
