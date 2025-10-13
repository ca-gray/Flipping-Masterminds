package com.flippingmasterminds;

import com.google.gson.Gson;
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
import com.google.gson.Gson;
import okhttp3.OkHttpClient;
import javax.inject.Inject;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
	private static final String TARGET_URL = "http://api.flippingmasterminds.net/ge"; //"http://127.0.0.1:5000/ge";

	private long loginTime = 0;
	private static final long LOGIN_IGNORE_WINDOW_MS = 3_000; // 3 seconds grace period

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
			loginTime = System.currentTimeMillis(); // ⏱ Record login time
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

		// 🕒 Ignore events within 3 seconds after login
		long now = System.currentTimeMillis();
		if (now - loginTime < LOGIN_IGNORE_WINDOW_MS)
		{
			log.debug("Ignoring GE event during login cooldown");
			return;
		}


		GrandExchangeOffer offer = event.getOffer();
		int slot = event.getSlot();

		// Only track updates for BUYING or BOUGHT offers
		if (offer.getState() == GrandExchangeOfferState.BUYING || offer.getState() == GrandExchangeOfferState.BOUGHT)
		{
			OfferStateCache oldState = lastOfferStates[slot];
			int newQuantitySold = offer.getQuantitySold();
			int quantityDelta = 0;

			// Check if there was a previous state for this exact item in this slot
			if (oldState != null && oldState.itemId == offer.getItemId())
			{
				// If the new quantity is greater, we've bought more items
				if (newQuantitySold > oldState.quantitySold)
				{
					quantityDelta = newQuantitySold - oldState.quantitySold;
				}
			}
			else
			{
				// This is the first update for this item in this slot,
				// so the entire quantity sold so far is the delta.
				quantityDelta = newQuantitySold;
			}

			// If we actually bought more items, record the amount
			if (quantityDelta > 0)
			{
				buyLimitTracker.recordBuy(offer.getItemId(), quantityDelta);
			}
		}

		// Always update the cache with the newest state for the next event
		if (offer.getState() != GrandExchangeOfferState.EMPTY) {
			lastOfferStates[slot] = new OfferStateCache(offer.getItemId(), offer.getQuantitySold());
		} else {
			lastOfferStates[slot] = null;
		}

		// Debounce logic remains the same
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

		// Build offer data
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

		// =========================
		// Build buyLimits array
		// =========================
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

		// =========================
		// Build final payload
		// =========================
		String playerName = client.getLocalPlayer().getName();
		long accountHash = client.getAccountHash();

		Map<String, Object> payloadMap = new HashMap<>();
		payloadMap.put("reason", reason);
		payloadMap.put("playerName", playerName);
		payloadMap.put("accountHash", accountHash);
		payloadMap.put("offers", offerList);
		payloadMap.put("buyLimits", buyLimitList);

		String jsonPayload = gson.toJson(payloadMap);
		System.out.println(jsonPayload);

		// Avoid duplicate sends
		if (jsonPayload.equals(lastSentPayload))
		{
			return;
		}
		lastSentPayload = jsonPayload;

		// =========================
		// Send HTTP request
		// =========================
		RequestBody body = RequestBody.create(JSON, jsonPayload);
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
	// Price Fetching Methods (unchanged)
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

	private Map<Integer, Integer> fetchPrices(String urlStr) throws Exception
	{
		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("User-Agent", USER_AGENT_HEADER);

		try (InputStreamReader reader = new InputStreamReader(conn.getInputStream()))
		{
			Map<Integer, Integer> map = new HashMap<>();
			var root = gson.fromJson(reader, com.google.gson.JsonObject.class);
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

	private Map<Integer, Integer> fetchLatestPrices(String urlStr) throws Exception
	{
		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("User-Agent", USER_AGENT_HEADER);

		try (InputStreamReader reader = new InputStreamReader(conn.getInputStream()))
		{
			Map<Integer, Integer> map = new HashMap<>();
			var root = gson.fromJson(reader, com.google.gson.JsonObject.class);
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

	private Map<Integer, ItemMeta> fetchItemMeta(String urlStr) throws Exception
	{
		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("User-Agent", USER_AGENT_HEADER);

		try (InputStreamReader reader = new InputStreamReader(conn.getInputStream()))
		{
			Map<Integer, ItemMeta> map = new HashMap<>();
			var root = gson.fromJson(reader, com.google.gson.JsonObject.class);

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
