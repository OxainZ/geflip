package com.geflip;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Locks in the GE tax-exempt list + the charge/dose-suffix stripping. A wrong entry here silently
 *  distorts margin AND realized P&L, so the membership is pinned. */
public class GeflipExemptTest
{
	@Test
	public void plainExemptItems()
	{
		assertTrue(GeflipExempt.isExempt("Lobster"));
		assertTrue(GeflipExempt.isExempt("Iron dart"));
		assertTrue(GeflipExempt.isExempt("Steel arrow"));
		assertTrue(GeflipExempt.isExempt("Mind rune"));
		assertTrue(GeflipExempt.isExempt("Old school bond"));
		assertTrue(GeflipExempt.isExempt("Varrock teleport"));
		assertTrue(GeflipExempt.isExempt("Teleport to house"));
		assertTrue(GeflipExempt.isExempt("watering can"));   // case-insensitive
	}

	@Test
	public void chargedAndDoseVariantsStripToBase()
	{
		assertTrue("energy potion doses all exempt", GeflipExempt.isExempt("Energy potion(4)"));
		assertTrue(GeflipExempt.isExempt("Energy potion(1)"));
		assertTrue("full-charge jewellery exempt", GeflipExempt.isExempt("Games necklace(8)"));
		assertTrue(GeflipExempt.isExempt("Ring of dueling(8)"));
	}

	@Test
	public void nonExemptStaysTaxed()
	{
		assertFalse(GeflipExempt.isExempt("Yew logs"));
		assertFalse(GeflipExempt.isExempt("Super energy potion(4)"));   // must NOT collide with "energy potion"
		assertFalse(GeflipExempt.isExempt("Adamant arrow"));
		assertFalse(GeflipExempt.isExempt("Ring of wealth (5)"));
		assertFalse(GeflipExempt.isExempt(null));
	}

	@Test
	public void listCoversAllExemptCategories()
	{
		// anti-regression: guard against silently shrinking back toward the old 15-item stale set.
		// 45 wiki category items collapse to 45 base names (energy potion / the two jewellery are single bases).
		org.junit.Assert.assertTrue("exempt list must stay complete (was 15, should be 45)",
			GeflipExempt.EXEMPT.size() >= 45);
	}
}
