package com.flippingmasterminds;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class PriceDataFetcherTest
{
	@Test
	public void parsesWikiMapping()
	{
		String json = "["
				+ "{\"examine\":\"A weapon from the abyss.\",\"id\":4151,\"members\":true,\"limit\":70,"
				+ "\"value\":120001,\"icon\":\"Abyssal whip.png\",\"name\":\"Abyssal whip\"},"
				+ "{\"id\":560,\"name\":\"Death rune\"},"
				+ "{\"id\":2},"
				+ "{\"name\":\"no id\"}"
				+ "]";
		Map<Integer, ItemMeta> meta = PriceDataFetcher.parseMapping(new Gson().fromJson(json, JsonArray.class));

		assertEquals(3, meta.size());
		assertEquals("Abyssal whip", meta.get(4151).name);
		assertEquals("Death rune", meta.get(560).name);
		assertEquals("Item 2", meta.get(2).name);
	}

	@Test
	public void nullMappingIsEmpty()
	{
		assertTrue(PriceDataFetcher.parseMapping(null).isEmpty());
	}
}
