package com.flippingmasterminds;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
class PriceDataFetcher
{
	private static final String LATEST_URL = "https://prices.runescape.wiki/api/v2/osrs/latest";
	private static final String ITEM_META_URL = "https://chisel.weirdgloop.org/gazproj/gazbot/os_dump.json";
	private static final String USER_AGENT = "Call from FMM Plugin, code owner discord: Lindor.";

	private final OkHttpClient httpClient;
	private final Gson gson;

	PriceDataFetcher(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	FetchResult fetchAll() throws IOException
	{
		LatestPriceResult latestResult = fetchLatestPrices(LATEST_URL);

		long now = Instant.now().getEpochSecond();

		PriceAndVolume day1h   = fetchPricesAndVolume(makeUrl1h(now, 86_400));
		PriceAndVolume week1h  = fetchPricesAndVolume(makeUrl1h(now, 604_800));
		PriceAndVolume month24 = fetchPricesAndVolume(makeUrl24h(now, 2_629_743));
		PriceAndVolume year24  = fetchPricesAndVolume(makeUrl24h(now, 31_556_926));

		Map<Integer, ItemMeta> meta = fetchItemMeta(ITEM_META_URL);

		return new FetchResult(latestResult, day1h, week1h, month24, year24, meta);
	}

	LatestPriceResult fetchLatestPrices(String urlStr) throws IOException
	{
		Request request = new Request.Builder()
				.url(urlStr)
				.header("User-Agent", USER_AGENT)
				.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new IOException("Failed to fetch latest prices: " + response.code());
			}

			try (InputStreamReader reader = new InputStreamReader(response.body().byteStream()))
			{
				Map<Integer, Long> map    = new HashMap<>();
				Map<Integer, Long> high   = new HashMap<>();
				Map<Integer, Long> low    = new HashMap<>();
				JsonObject root = gson.fromJson(reader, JsonObject.class);
				JsonObject data = root.getAsJsonObject("data");

				for (String key : data.keySet())
				{
					try
					{
						int id  = Integer.parseInt(key);
						JsonObject obj = data.getAsJsonObject(key);
						if (obj.has("high") && obj.has("low")
								&& !obj.get("high").isJsonNull()
								&& !obj.get("low").isJsonNull())
						{
							long h = obj.get("high").getAsLong();
							long l = obj.get("low").getAsLong();
							map.put(id, (h + l) / 2);
							high.put(id, h);
							low.put(id, l);
						}
					}
					catch (Exception e)
					{
						log.debug("Skipping item {} in latest prices: {}", key, e.getMessage());
					}
				}

				return new LatestPriceResult(map, high, low);
			}
		}
	}

	PriceAndVolume fetchPricesAndVolume(String urlStr) throws IOException
	{
		Request request = new Request.Builder()
				.url(urlStr)
				.header("User-Agent", USER_AGENT)
				.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new IOException("Failed to fetch prices: " + response.code());
			}

			try (InputStreamReader reader = new InputStreamReader(response.body().byteStream()))
			{
				Map<Integer, Long> prices = new HashMap<>();
				Map<Integer, Long> volume = new HashMap<>();
				JsonObject root = gson.fromJson(reader, JsonObject.class);
				JsonObject data = root.getAsJsonObject("data");

				for (String key : data.keySet())
				{
					try
					{
						int id  = Integer.parseInt(key);
						JsonObject obj = data.getAsJsonObject(key);

						if (obj.has("avgHighPrice") && obj.has("avgLowPrice")
								&& !obj.get("avgHighPrice").isJsonNull()
								&& !obj.get("avgLowPrice").isJsonNull())
						{
							double h = obj.get("avgHighPrice").getAsDouble();
							double l = obj.get("avgLowPrice").getAsDouble();
							prices.put(id, Math.round((h + l) / 2.0));
						}

						long vol = 0;
						if (obj.has("highPriceVolume") && !obj.get("highPriceVolume").isJsonNull())
						{
							vol += obj.get("highPriceVolume").getAsLong();
						}
						if (obj.has("lowPriceVolume") && !obj.get("lowPriceVolume").isJsonNull())
						{
							vol += obj.get("lowPriceVolume").getAsLong();
						}
						if (vol > 0) volume.put(id, vol);
					}
					catch (Exception e)
					{
						log.debug("Skipping item {} in timestamped prices: {}", key, e.getMessage());
					}
				}
				return new PriceAndVolume(prices, volume);
			}
		}
	}

	Map<Integer, ItemMeta> fetchItemMeta(String urlStr) throws IOException
	{
		Request request = new Request.Builder()
				.url(urlStr)
				.header("User-Agent", USER_AGENT)
				.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new IOException("Failed to fetch item meta: " + response.code());
			}

			try (InputStreamReader reader = new InputStreamReader(response.body().byteStream()))
			{
				Map<Integer, ItemMeta> map = new HashMap<>();
				JsonObject root = gson.fromJson(reader, JsonObject.class);

				for (String key : root.keySet())
				{
					try
					{
						int    id   = Integer.parseInt(key);
						JsonObject obj  = root.getAsJsonObject(key);
						String name = obj.has("name") ? obj.get("name").getAsString() : "Item " + id;
						String icon = obj.has("icon") ? obj.get("icon").getAsString() : "";

						String safeIcon = icon
								.replace(" ", "_")
								.replace("'", "%27")
								.replace("(", "%28")
								.replace(")", "%29");

						String iconUrl = "https://oldschool.runescape.wiki/w/Special:FilePath/" + safeIcon;
						map.put(id, new ItemMeta(id, name, iconUrl));
					}
					catch (Exception e)
					{
						log.debug("Skipping item {} in meta: {}", key, e.getMessage());
					}
				}
				return map;
			}
		}
	}

	static String makeUrl1h(long now, long offset)
	{
		long ts = now - offset;
		ts -= ts % 3600;
		return "https://prices.runescape.wiki/api/v2/osrs/1h?timestamp=" + ts;
	}

	static String makeUrl24h(long now, long offset)
	{
		long ts = now - offset;
		ts -= ts % 86400;
		return "https://prices.runescape.wiki/api/v2/osrs/24h?timestamp=" + ts;
	}

	// ── Result types ─────────────────────────────────────────────────────────

	static class FetchResult
	{
		final LatestPriceResult latest;
		final PriceAndVolume day;
		final PriceAndVolume week;
		final PriceAndVolume month;
		final PriceAndVolume year;
		final Map<Integer, ItemMeta> itemMeta;

		FetchResult(LatestPriceResult latest,
					PriceAndVolume day, PriceAndVolume week,
					PriceAndVolume month, PriceAndVolume year,
					Map<Integer, ItemMeta> itemMeta)
		{
			this.latest   = latest;
			this.day      = day;
			this.week     = week;
			this.month    = month;
			this.year     = year;
			this.itemMeta = itemMeta;
		}
	}

	static class LatestPriceResult
	{
		final Map<Integer, Long> baseline;
		final Map<Integer, Long> high;
		final Map<Integer, Long> low;

		LatestPriceResult(Map<Integer, Long> baseline, Map<Integer, Long> high, Map<Integer, Long> low)
		{
			this.baseline = baseline;
			this.high     = high;
			this.low      = low;
		}
	}

	static class PriceAndVolume
	{
		final Map<Integer, Long> prices;
		final Map<Integer, Long> volume;

		PriceAndVolume(Map<Integer, Long> prices, Map<Integer, Long> volume)
		{
			this.prices = prices;
			this.volume = volume;
		}
	}
}
