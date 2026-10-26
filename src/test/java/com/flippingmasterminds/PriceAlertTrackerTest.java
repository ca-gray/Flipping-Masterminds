package com.flippingmasterminds;

import com.google.gson.Gson;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class PriceAlertTrackerTest
{
	private final Gson gson = new Gson();

	@Test
	public void readsLegacyAlertFormat()
	{
		String legacy = "4151,Abyssal whip,1500000,BELOW,false,0,1000,false,86400000"
				+ "|560,Rune&comma; death &pipe; test,200,ABOVE,true,210,2000,false,3600000";
		List<PriceAlertTracker.PriceAlert> alerts = PriceAlertTracker.parseAlerts(legacy, gson);

		assertEquals(2, alerts.size());
		assertEquals("Abyssal whip", alerts.get(0).itemName);
		assertEquals(PriceAlertTracker.Direction.BELOW, alerts.get(0).direction);
		assertEquals("Rune, death | test", alerts.get(1).itemName);
		assertTrue(alerts.get(1).triggered);
		assertEquals(210, alerts.get(1).triggeredPrice);
		assertEquals(3_600_000, alerts.get(1).expiryMs);
	}

	@Test
	public void alertsRoundTripThroughJson()
	{
		PriceAlertTracker.PriceAlert a = PriceAlertTracker.parseAlerts(
				"4151,Abyssal whip,1500000,ABOVE,true,1600000,1000,false,86400000", gson).get(0);
		String json = gson.toJson(Arrays.asList(a));
		assertTrue(PriceAlertTracker.isJson(json));

		PriceAlertTracker.PriceAlert back = PriceAlertTracker.parseAlerts(json, gson).get(0);
		assertEquals(4151, back.itemId);
		assertEquals("Abyssal whip", back.itemName);
		assertEquals(1_500_000, back.targetPrice);
		assertEquals(PriceAlertTracker.Direction.ABOVE, back.direction);
		assertTrue(back.triggered);
		assertEquals(1_600_000, back.triggeredPrice);
		assertEquals(86_400_000, back.expiryMs);
	}

	@Test
	public void readsLegacyAndJsonHistory()
	{
		String legacy = "4151,Abyssal whip,1500000,BELOW,1000,2000,1490000,TRIGGERED";
		List<PriceAlertTracker.AlertHistoryEntry> h = PriceAlertTracker.parseHistory(legacy, gson);
		assertEquals(1, h.size());
		assertEquals("TRIGGERED", h.get(0).outcome);

		List<PriceAlertTracker.AlertHistoryEntry> back =
				PriceAlertTracker.parseHistory(gson.toJson(h), gson);
		assertEquals(1, back.size());
		assertEquals(1_490_000, back.get(0).triggeredPrice);
		assertEquals(PriceAlertTracker.Direction.BELOW, back.get(0).direction);
	}

	@Test
	public void badInputIsIgnored()
	{
		assertTrue(PriceAlertTracker.parseAlerts(null, gson).isEmpty());
		assertTrue(PriceAlertTracker.parseAlerts("[not json", gson).isEmpty());
		assertTrue(PriceAlertTracker.parseAlerts("garbage|1,2", gson).isEmpty());
		assertTrue(PriceAlertTracker.parseHistory("[{\"itemId\":1}]", gson).isEmpty());
	}
}
