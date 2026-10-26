package com.flippingmasterminds;

import org.junit.Test;

import static org.junit.Assert.*;

public class GeHistoryFilterTest
{
	/** 3 whips at 1.5m each = 4.5m total. */
	private static final GeHistoryEntry WHIPS = new GeHistoryEntry(true, 4151, 3, 4_500_000, 1_500_000, 0);
	/** 1 twisted bow for 1.2b. */
	private static final GeHistoryEntry TBOW = new GeHistoryEntry(false, 20997, 1, 1_200_000_000, 1_200_000_000, 0);
	/** 10k death runes at 180 = 1.8m. */
	private static final GeHistoryEntry RUNES = new GeHistoryEntry(true, 560, 10_000, 1_800_000, 180, 0);

	private static boolean m(String query, GeHistoryEntry e, String name)
	{
		GeHistoryFilter f = GeHistoryFilter.parse(query);
		return f == null || f.matches(e, name);
	}

	@Test
	public void blankQueryIsNoFilter()
	{
		assertNull(GeHistoryFilter.parse(null));
		assertNull(GeHistoryFilter.parse("   "));
	}

	@Test
	public void matchesNameIgnoringCase()
	{
		assertTrue(m("whip", WHIPS, "Abyssal whip"));
		assertTrue(m("ABYSSAL W", WHIPS, "Abyssal whip"));
		assertFalse(m("bow", WHIPS, "Abyssal whip"));
	}

	@Test
	public void comparesTotal()
	{
		assertTrue(m(">100m", TBOW, "Twisted bow"));
		assertFalse(m(">100m", WHIPS, "Abyssal whip"));
		assertTrue(m("<5m", WHIPS, "Abyssal whip"));
		assertTrue(m(">=4.5m", WHIPS, "Abyssal whip"));
		assertFalse(m(">4.5m", WHIPS, "Abyssal whip"));
		assertTrue(m("=1.2b", TBOW, "Twisted bow"));
		assertTrue(m("> 1M", TBOW, "Twisted bow"));
	}

	@Test
	public void comparesEach()
	{
		assertTrue(m("each>1m", WHIPS, "Abyssal whip"));
		assertFalse(m(">2m", RUNES, "Death rune"));
		assertTrue(m("each<200", RUNES, "Death rune"));
		assertTrue(m("per <200", RUNES, "Death rune"));
		assertFalse(m("each>1m", RUNES, "Death rune"));
	}

	@Test
	public void ranges()
	{
		assertTrue(m("1m-5m", WHIPS, "Abyssal whip"));
		assertTrue(m("5m - 1m", RUNES, "Death rune"));
		assertFalse(m("1m-5m", TBOW, "Twisted bow"));
		assertTrue(m("each 100-200", RUNES, "Death rune"));
	}

	@Test
	public void combinesNameAndValues()
	{
		assertTrue(m("whip >1m <5m", WHIPS, "Abyssal whip"));
		assertFalse(m("whip >5m", WHIPS, "Abyssal whip"));
		assertFalse(m("rune >1m", WHIPS, "Abyssal whip"));
		assertTrue(m("death rune >1m", RUNES, "Death rune"));
		assertTrue(m("whip>1m", WHIPS, "Abyssal whip"));
		assertFalse(m("whip>5m", WHIPS, "Abyssal whip"));
	}

	@Test
	public void numbersInsideNamesStayNames()
	{
		GeHistoryEntry pot = new GeHistoryEntry(true, 2434, 1, 9_000, 9_000, 0);
		assertTrue(m("potion(4)", pot, "Prayer potion(4)"));
		assertTrue(m("3rd age", pot, "3rd age platebody"));
		assertFalse(m("3rd age", pot, "Prayer potion(4)"));
	}
}
