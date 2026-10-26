package com.flippingmasterminds;

import org.junit.Test;

import static org.junit.Assert.*;

public class GeOfferValueExpanderTest
{
	private static final long[] NONE = {};

	@Test
	public void expandsCondensedCoinValue()
	{
		long[] coins = {130_311_121L};
		assertEquals("130,311,121 coins", GeOfferValueExpander.expand("130.3M coins", coins, NONE));
		assertEquals("130,311,121 coins", GeOfferValueExpander.expand("130.3m coins", coins, NONE));
	}

	@Test
	public void keepsTagsAndOtherText()
	{
		long[] coins = {1_250_000L};
		assertEquals("<col=ffb83f>1,250,000 coins</col>",
				GeOfferValueExpander.expand("<col=ffb83f>1.25M coins</col>", coins, NONE));
		assertEquals("<col=ff9040>12,500</col> coins",
				GeOfferValueExpander.expand("<col=ff9040>12.5K</col> coins", new long[]{12_500}, NONE));
	}

	@Test
	public void picksTheCandidateThatMatches()
	{
		// Price each 4.5M, total 13.5M: "13.5M coins" must become the total.
		long[] coins = {4_500_000L, 13_500_000L, 9_000_000L};
		assertEquals("13,500,000 coins", GeOfferValueExpander.expand("13.5M coins", coins, NONE));
	}

	@Test
	public void leavesTextAloneWhenTheValueIsAmbiguous()
	{
		// Total 130,000,000 and spent 129,950,000 both show as "130M": don't guess.
		long[] coins = {1_000_000L, 130_000_000L, 129_950_000L};
		assertEquals("130M coins", GeOfferValueExpander.expand("130M coins", coins, NONE));
		// Equal candidates (quantity 1: price == total) are not ambiguous.
		assertEquals("2,500,000 coins",
				GeOfferValueExpander.expand("2.5M coins", new long[]{2_500_000L, 2_500_000L, 0}, NONE));
	}

	@Test
	public void leavesTextAloneWhenNothingMatches()
	{
		long[] coins = {999L};
		assertEquals("130.3M coins", GeOfferValueExpander.expand("130.3M coins", coins, NONE));
		assertEquals("1,234 coins", GeOfferValueExpander.expand("1,234 coins", coins, NONE));
		assertEquals("Rune 2h sword", GeOfferValueExpander.expand("Rune 2h sword", coins, new long[]{2}));
	}

	@Test
	public void quantitiesUseQuantityCandidates()
	{
		long[] coins = {1_450L};
		long[] qty = {1_500L, 600L};
		assertEquals("1,500", GeOfferValueExpander.expand("1.5K", coins, qty));
	}

	@Test
	public void matchHandlesRoundingAndTruncation()
	{
		// 130.35M rounds to 130.4M but truncates to 130.3M; both displays map back.
		assertEquals(Long.valueOf(130_350_000L), GeOfferValueExpander.match("130.3", "M", new long[]{130_350_000L}));
		assertEquals(Long.valueOf(130_350_000L), GeOfferValueExpander.match("130.4", "M", new long[]{130_350_000L}));
		assertNull(GeOfferValueExpander.match("130.3", "M", new long[]{130_500_000L}));
		assertEquals(Long.valueOf(2_000_000_000L), GeOfferValueExpander.match("2", "B", new long[]{2_000_000_000L}));
	}
}
