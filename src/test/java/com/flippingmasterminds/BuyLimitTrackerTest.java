package com.flippingmasterminds;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class BuyLimitTrackerTest
{
	@Test
	public void countsOnlyNewFillsOfTheSameOffer()
	{
		int[] last = {4151, 40};
		assertEquals(10, BuyLimitTracker.newlyFilled(last, 4151, 50));
		assertEquals(0, BuyLimitTracker.newlyFilled(last, 4151, 40));
	}

	@Test
	public void fillsWhileLoggedOutAreCountedAgainstTheSavedState()
	{
		// 40 bought before logout; offer finished at 100 while offline.
		assertEquals(60, BuyLimitTracker.newlyFilled(new int[]{4151, 40}, 4151, 100));
	}

	@Test
	public void newOfferInTheSlotCountsInFull()
	{
		assertEquals(25, BuyLimitTracker.newlyFilled(null, 4151, 25));
		// Different item in the slot.
		assertEquals(25, BuyLimitTracker.newlyFilled(new int[]{560, 900}, 4151, 25));
		// Same item but the fill went backwards: the old offer was collected, this is a new one.
		assertEquals(5, BuyLimitTracker.newlyFilled(new int[]{4151, 100}, 4151, 5));
	}

	@Test
	public void parsesSavedSlots()
	{
		Map<Integer, int[]> slots = BuyLimitTracker.parseSlots("0,4151,40;3,560,900;bad;7,x,1");
		assertEquals(2, slots.size());
		assertArrayEquals(new int[]{4151, 40}, slots.get(0));
		assertArrayEquals(new int[]{560, 900}, slots.get(3));
		assertTrue(BuyLimitTracker.parseSlots(null).isEmpty());
	}
}
