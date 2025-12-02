package com.flippingmasterminds;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.*;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@PluginDescriptor(
		name = "Flipping Masterminds",
		description = "Grabs Best/Worst Performing item price changes to analyse the market easily!",
		tags = {"grand exchange", "prices", "flipping", "merching"}
)
public class FlippingMastermindsPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private FlippingMastermindsConfig config;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ConfigManager configManager;
	@Inject private BuyLimitTracker buyLimitTracker;

	private NavigationButton navButton;
	private FlippingMastermindsPanel panel;

	private boolean loggedIn = false;

	@Inject private Gson gson;
	@Inject private OkHttpClient okHttpClient;
	private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
	private static final String TARGET_URL = "http://api.flippingmasterminds.net/ge";

	private long loginTime = 0;
	private static final long LOGIN_IGNORE_WINDOW_MS = 3_000;

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> pendingSend = null;
	private final long DEBOUNCE_DELAY_MS = 200;
	private String lastReason = "Slot updated";
	private String lastSentPayload = null;

	private final OfferStateCache[] lastOfferStates = new OfferStateCache[8];

	private ExecutorService executor;
	private Map<Integer, Integer> baselinePrices = new HashMap<>();
	private Map<Integer, Integer> dayPrices = new HashMap<>();
	private Map<Integer, Integer> weekPrices = new HashMap<>();
	private Map<Integer, Integer> monthPrices = new HashMap<>();
	private Map<Integer, Integer> yearPrices = new HashMap<>();
	private Map<Integer, ItemMeta> itemMeta = new HashMap<>();

	private static final String USER_AGENT_HEADER = "Call from FMM Plugin, code owner discord: Lindor.";

	@Override
	protected void startUp()
	{
		log.info("Flipping Masterminds plugin started");

		panel = new FlippingMastermindsPanel();
		BufferedImage icon = null;
		try
		{
			icon = ImageUtil.loadImageResource(getClass(), "/mastermind_logo.png");
		}
		catch (Exception e)
		{
			log.warn("Could not load mastermind_logo.png, using null icon");
		}

		navButton = NavigationButton.builder()
				.tooltip("Flipping Masterminds")
				.icon(icon)
				.priority(5)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);
		loggedIn = false;

		executor = Executors.newSingleThreadExecutor();
		executor.submit(this::fetchAllData);
	}

	@Override
	protected void shutDown()
	{
		log.info("Flipping Masterminds plugin stopped");
		loggedIn = false;

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}

		// UPDATED: Correctly dispose of the panel to stop the imageLoader thread
		if (panel != null)
		{
			panel.dispose();
		}

		if (executor != null)
		{
			executor.shutdownNow();
		}
		if (pendingSend != null && !pendingSend.isDone())
		{
			pendingSend.cancel(false);
		}
		scheduler.shutdown();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loggedIn = true;
			loginTime = System.currentTimeMillis();
			log.info("Account logged in – Flipping Masterminds GE scanning enabled (cooldown started)");
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			loggedIn = false;
			log.info("Account logged out – Flipping Masterminds GE scanning disabled");
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!loggedIn || config.apiToken().isEmpty())
		{
			return;
		}

		long now = System.currentTimeMillis();
		if (now - loginTime < LOGIN_IGNORE_WINDOW_MS)
		{
			log.debug("Ignoring GE event during login cooldown");
			return;
		}

		GrandExchangeOffer offer = event.getOffer();
		int slot = event.getSlot();

		if (offer.getState() == GrandExchangeOfferState.BUYING || offer.getState() == GrandExchangeOfferState.BOUGHT)
		{
			OfferStateCache oldState = lastOfferStates[slot];
			int newQuantitySold = offer.getQuantitySold();
			int quantityDelta = 0;

			if (oldState != null && oldState.itemId == offer.getItemId())
			{
				if (newQuantitySold > oldState.quantitySold)
				{
					quantityDelta = newQuantitySold - oldState.quantitySold;
				}
			}
			else
			{
				quantityDelta = newQuantitySold;
			}

			if (quantityDelta > 0)
			{
				buyLimitTracker.recordBuy(offer.getItemId(), quantityDelta);
			}
		}

		if (offer.getState() != GrandExchangeOfferState.EMPTY) {
			lastOfferStates[slot] = new OfferStateCache(offer.getItemId(), offer.getQuantitySold());
		} else {
			lastOfferStates[slot] = null;
		}

		lastReason = "Slot updated: " + event.getSlot();
		if (pendingSend != null && !pendingSend.isDone())
		{
			pendingSend.cancel(false);
		}
		pendingSend = scheduler.schedule(() -> sendOffersIfChanged(lastReason), DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	private void sendOffersIfChanged(String reason)
	{
		if (client == null || client.getGrandExchangeOffers() == null || client.getLocalPlayer() == null)
		{
			log.info("Client not ready yet.");
			return;
		}

		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		List<Map<String, Object>> offerList = new ArrayList<>();

		for (int i = 0; i < offers.length; i++)
		{
			GrandExchangeOffer offer = offers[i];
			Map<String, Object> slotData = new HashMap<>();
			slotData.put("slot", i);

			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				slotData.put("state", "EMPTY");
			}
			else
			{
				slotData.put("state", offer.getState().toString());
				slotData.put("itemId", offer.getItemId());
				slotData.put("quantitySold", offer.getQuantitySold());
				slotData.put("totalQuantity", offer.getTotalQuantity());
				slotData.put("price", offer.getPrice());
			}

			offerList.add(slotData);
		}

		List<Map<String, Object>> buyLimitList = new ArrayList<>();
		Map<Integer, Map<String, Object>> tracked = buyLimitTracker.getAllTracked();

		for (Map.Entry<Integer, Map<String, Object>> entry : tracked.entrySet())
		{
			Map<String, Object> record = new HashMap<>();
			record.put("itemId", entry.getKey());
			record.put("quantityBought", entry.getValue().get("quantityBought"));
			record.put("firstBuyTimestamp", entry.getValue().get("firstBuyTimestamp"));
			buyLimitList.add(record);
		}

		String playerName = client.getLocalPlayer().getName();
		long accountHash = client.getAccountHash();

		Map<String, Object> payloadMap = new HashMap<>();
		payloadMap.put("reason", reason);
		payloadMap.put("playerName", playerName);
		payloadMap.put("accountHash", accountHash);
		payloadMap.put("offers", offerList);
		payloadMap.put("buyLimits", buyLimitList);

		String jsonPayload = gson.toJson(payloadMap);

		if (jsonPayload.equals(lastSentPayload))
		{
			return;
		}
		lastSentPayload = jsonPayload;

		RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, jsonPayload);
		Request request = new Request.Builder()
				.url(TARGET_URL)
				.post(body)
				.addHeader("Authorization", "Bearer " + config.apiToken())
				.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.error("❌ Failed to send GE data", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				int code = response.code();
				String resp = response.body() != null ? response.body().string() : "";
				response.close();
				log.info("✅ GE data sent for {} | Response {}: {}", playerName, code, resp);
			}
		});
	}

	// =============================
	// Price Fetching Methods (UPDATED to use OkHttpClient)
	// =============================

	private void fetchAllData()
	{
		try
		{
			baselinePrices = fetchLatestPrices("https://prices.runescape.wiki/api/v1/osrs/latest");

			long now = Instant.now().getEpochSecond();
			dayPrices = fetchPrices(makeUrl1h(now, 86400));
			weekPrices = fetchPrices(makeUrl1h(now, 604800));
			monthPrices = fetchPrices(makeUrl24h(now, 2629743));
			yearPrices = fetchPrices(makeUrl24h(now, 31556926));

			itemMeta = fetchItemMeta("https://chisel.weirdgloop.org/gazproj/gazbot/os_dump.json");

			SwingUtilities.invokeLater(() -> panel.updateMovers(
					baselinePrices, dayPrices, weekPrices, monthPrices, yearPrices, itemMeta
			));
		}
		catch (Exception e)
		{
			log.error("❌ Failed to fetch price data", e);
		}
	}

	private String makeUrl1h(long now, long offset)
	{
		long ts = now - offset;
		ts -= ts % 3600;
		return "https://prices.runescape.wiki/api/v1/osrs/1h?timestamp=" + ts;
	}

	private String makeUrl24h(long now, long offset)
	{
		long ts = now - offset;
		ts -= ts % 86400;
		return "https://prices.runescape.wiki/api/v1/osrs/24h?timestamp=" + ts;
	}

	// UPDATED: Uses OkHttpClient instead of HttpURLConnection
	private Map<Integer, Integer> fetchPrices(String urlStr) throws IOException
	{
		Request request = new Request.Builder()
				.url(urlStr)
				.header("User-Agent", USER_AGENT_HEADER)
				.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new IOException("Failed to fetch prices: " + response.code());
			}

			try (InputStreamReader reader = new InputStreamReader(response.body().byteStream()))
			{
				Map<Integer, Integer> map = new HashMap<>();
				var root = gson.fromJson(reader, JsonObject.class);
				var data = root.getAsJsonObject("data");

				for (String key : data.keySet())
				{
					try
					{
						int id = Integer.parseInt(key);
						var obj = data.getAsJsonObject(key);
						if (obj.has("avgHighPrice") && obj.has("avgLowPrice")
								&& !obj.get("avgHighPrice").isJsonNull()
								&& !obj.get("avgLowPrice").isJsonNull())
						{
							int high = obj.get("avgHighPrice").getAsInt();
							int low = obj.get("avgLowPrice").getAsInt();
							int mid = (high + low) / 2;
							map.put(id, mid);
						}
					}
					catch (Exception ignored) {}
				}
				return map;
			}
		}
	}

	// UPDATED: Uses OkHttpClient instead of HttpURLConnection
	private Map<Integer, Integer> fetchLatestPrices(String urlStr) throws IOException
	{
		Request request = new Request.Builder()
				.url(urlStr)
				.header("User-Agent", USER_AGENT_HEADER)
				.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new IOException("Failed to fetch latest prices: " + response.code());
			}

			try (InputStreamReader reader = new InputStreamReader(response.body().byteStream()))
			{
				Map<Integer, Integer> map = new HashMap<>();
				var root = gson.fromJson(reader, JsonObject.class);
				var data = root.getAsJsonObject("data");

				for (String key : data.keySet())
				{
					try
					{
						int id = Integer.parseInt(key);
						var obj = data.getAsJsonObject(key);
						if (obj.has("high") && obj.has("low")
								&& !obj.get("high").isJsonNull()
								&& !obj.get("low").isJsonNull())
						{
							int high = obj.get("high").getAsInt();
							int low = obj.get("low").getAsInt();
							int mid = (high + low) / 2;
							map.put(id, mid);
						}
					}
					catch (Exception ignored) {}
				}
				return map;
			}
		}
	}

	// UPDATED: Uses OkHttpClient instead of HttpURLConnection
	private Map<Integer, ItemMeta> fetchItemMeta(String urlStr) throws IOException
	{
		Request request = new Request.Builder()
				.url(urlStr)
				.header("User-Agent", USER_AGENT_HEADER)
				.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new IOException("Failed to fetch item meta: " + response.code());
			}

			try (InputStreamReader reader = new InputStreamReader(response.body().byteStream()))
			{
				Map<Integer, ItemMeta> map = new HashMap<>();
				var root = gson.fromJson(reader, JsonObject.class);

				for (String key : root.keySet())
				{
					try
					{
						int id = Integer.parseInt(key);
						var obj = root.getAsJsonObject(key);
						String name = obj.has("name") ? obj.get("name").getAsString() : "Item " + id;
						String icon = obj.has("icon") ? obj.get("icon").getAsString() : "";

						String safeIcon = icon.replace(" ", "_")
								.replace("'", "%27")
								.replace("(", "%28")
								.replace(")", "%29");

						String iconUrl = "https://oldschool.runescape.wiki/images/c/c0/" + safeIcon + "?7263b";
						map.put(id, new ItemMeta(id, name, iconUrl));
					}
					catch (Exception ignored) {}
				}
				return map;
			}
		}
	}

	@Provides
	BuyLimitTracker provideBuyLimitTracker(ConfigManager configManager)
	{
		return new BuyLimitTracker(configManager);
	}

	@Provides
	FlippingMastermindsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlippingMastermindsConfig.class);
	}

	private static class OfferStateCache
	{
		int itemId;
		int quantitySold;

		OfferStateCache(int itemId, int quantitySold)
		{
			this.itemId = itemId;
			this.quantitySold = quantitySold;
		}
	}

	public static class ItemMeta
	{
		public final int id;
		public final String name;
		public final String iconUrl;

		public ItemMeta(int id, String name, String iconUrl)
		{
			this.id = id;
			this.name = name;
			this.iconUrl = iconUrl;
		}
	}
}