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
import net.runelite.client.events.ConfigChanged;
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
	/** Short window after login to let the client fully settle before we fire events. */
	private static final long LOGIN_IGNORE_WINDOW_MS = 3_000;

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
	private ScheduledFuture<?> pendingSend = null;
	private final long DEBOUNCE_DELAY_MS = 200;
	private String lastReason = "Slot updated";
	private String lastSentPayload = null;

	private final OfferStateCache[] lastOfferStates = new OfferStateCache[8];

	private ExecutorService executor;

	// ── Price / volume data held in memory ────────────────────────────────────
	// CHANGED: Integer → Long to support prices > 2,147,483,647 gp (v2 API requirement)
	private Map<Integer, Long> baselinePrices = new HashMap<>();
	private Map<Integer, Long> dayPrices      = new HashMap<>();
	private Map<Integer, Long> weekPrices     = new HashMap<>();
	private Map<Integer, Long> monthPrices    = new HashMap<>();
	private Map<Integer, Long> yearPrices     = new HashMap<>();

	private Map<Integer, Long> dayVolume   = new HashMap<>();
	private Map<Integer, Long> weekVolume  = new HashMap<>();
	private Map<Integer, Long> monthVolume = new HashMap<>();
	private Map<Integer, Long> yearVolume  = new HashMap<>();

	private Map<Integer, ItemMeta> itemMeta = new HashMap<>();

	private static final String USER_AGENT_HEADER = "Call from FMM Plugin, code owner discord: Lindor.";

	// ─────────────────────────────────────────────────────────────────────────
	@Override
	protected void startUp()
	{
		log.info("Flipping Masterminds plugin started");

		panel = new FlippingMastermindsPanel();

		// Wire the manual-refresh button back to this plugin
		panel.setOnRefreshRequested(() -> executor.submit(this::fetchAllData));

		// Apply persisted toggle states from config
		panel.applyConfig(config.showVolume(), config.showPrices());

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

		if (navButton != null) clientToolbar.removeNavigation(navButton);
		if (panel    != null) panel.dispose();

		if (executor != null) executor.shutdownNow();

		if (pendingSend != null && !pendingSend.isDone()) pendingSend.cancel(false);
		scheduler.shutdownNow();
	}

	// ── Game-state events ─────────────────────────────────────────────────────

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			loggedIn  = true;
			loginTime = System.currentTimeMillis();
			log.info("Account logged in – GE scanning enabled (cooldown started)");

			// Send an immediate GE snapshot on login (if token is set)
			if (!config.apiToken().isEmpty())
			{
				// Schedule just after the ignore window so the client is ready
				scheduler.schedule(
						() -> sendOffersIfChanged("Login snapshot"),
						LOGIN_IGNORE_WINDOW_MS,
						TimeUnit.MILLISECONDS
				);
			}
			else
			{
				log.debug("No API token configured – skipping login snapshot");
			}
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN
				|| event.getGameState() == GameState.HOPPING)
		{
			loggedIn = false;
			log.debug("Account logged out – GE scanning disabled");
		}
	}

	// ── Config change events ──────────────────────────────────────────────────

	/**
	 * Fired whenever any config value changes in the RuneLite settings panel.
	 * We only care about our own group's display toggles; other keys are ignored.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"flippingmasterminds".equals(event.getGroup())) return;

		String key = event.getKey();
		if ("showVolume".equals(key) || "showPrices".equals(key))
		{
			SwingUtilities.invokeLater(() ->
					panel.applyConfig(config.showVolume(), config.showPrices()));
		}
	}

	// ── GE offer events ───────────────────────────────────────────────────────

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!loggedIn || config.apiToken().isEmpty()) return;

		long now = System.currentTimeMillis();
		if (now - loginTime < LOGIN_IGNORE_WINDOW_MS)
		{
			log.debug("Ignoring GE event during login cooldown");
			return;
		}

		GrandExchangeOffer offer = event.getOffer();
		int slot = event.getSlot();

		if (offer.getState() == GrandExchangeOfferState.BUYING
				|| offer.getState() == GrandExchangeOfferState.BOUGHT)
		{
			OfferStateCache oldState    = lastOfferStates[slot];
			int newQuantitySold = offer.getQuantitySold();
			int quantityDelta   = 0;

			if (oldState != null && oldState.itemId == offer.getItemId())
			{
				if (newQuantitySold > oldState.quantitySold)
					quantityDelta = newQuantitySold - oldState.quantitySold;
			}
			else
			{
				quantityDelta = newQuantitySold;
			}

			if (quantityDelta > 0)
				buyLimitTracker.recordBuy(offer.getItemId(), quantityDelta);
		}

		if (offer.getState() != GrandExchangeOfferState.EMPTY)
			lastOfferStates[slot] = new OfferStateCache(offer.getItemId(), offer.getQuantitySold());
		else
			lastOfferStates[slot] = null;

		lastReason = "Slot updated: " + event.getSlot();
		if (pendingSend != null && !pendingSend.isDone()) pendingSend.cancel(false);
		pendingSend = scheduler.schedule(
				() -> sendOffersIfChanged(lastReason), DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	// ── Sending GE data ───────────────────────────────────────────────────────

	private void sendOffersIfChanged(String reason)
	{
		if (client == null
				|| client.getGrandExchangeOffers() == null
				|| client.getLocalPlayer()        == null)
		{
			log.debug("sendOffersIfChanged: client not ready, skipping");
			return;
		}

		GrandExchangeOffer[]          offers    = client.getGrandExchangeOffers();
		List<Map<String, Object>>     offerList = new ArrayList<>();

		for (int i = 0; i < offers.length; i++)
		{
			GrandExchangeOffer    offer    = offers[i];
			Map<String, Object>   slotData = new HashMap<>();
			slotData.put("slot", i);

			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY)
			{
				slotData.put("state", "EMPTY");
			}
			else
			{
				slotData.put("state",          offer.getState().toString());
				slotData.put("itemId",         offer.getItemId());
				slotData.put("quantitySold",   offer.getQuantitySold());
				slotData.put("totalQuantity",  offer.getTotalQuantity());
				slotData.put("price",          offer.getPrice());
			}
			offerList.add(slotData);
		}

		List<Map<String, Object>>          buyLimitList = new ArrayList<>();
		Map<Integer, Map<String, Object>>  tracked      = buyLimitTracker.getAllTracked();

		for (Map.Entry<Integer, Map<String, Object>> entry : tracked.entrySet())
		{
			Map<String, Object> record = new HashMap<>();
			record.put("itemId",            entry.getKey());
			record.put("quantityBought",    entry.getValue().get("quantityBought"));
			record.put("firstBuyTimestamp", entry.getValue().get("firstBuyTimestamp"));
			buyLimitList.add(record);
		}

		String playerName  = client.getLocalPlayer().getName();
		long   accountHash = client.getAccountHash();

		Map<String, Object> payloadMap = new HashMap<>();
		payloadMap.put("reason",      reason);
		payloadMap.put("playerName",  playerName);
		payloadMap.put("accountHash", accountHash);
		payloadMap.put("offers",      offerList);
		payloadMap.put("buyLimits",   buyLimitList);

		String jsonPayload = gson.toJson(payloadMap);
		if (jsonPayload.equals(lastSentPayload)) return;
		lastSentPayload = jsonPayload;

		RequestBody body    = RequestBody.create(JSON_MEDIA_TYPE, jsonPayload);
		Request     request = new Request.Builder()
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
				int    code = response.code();
				String resp = response.body() != null ? response.body().string() : "";
				response.close();
				log.info("✅ GE data sent ({}) for {} | Response {}: {}", reason, playerName, code, resp);
			}
		});
	}

	// ── Price / volume fetching ───────────────────────────────────────────────

	/** Fetches all price and volume data then pushes it to the panel. */
	void fetchAllData()
	{
		try
		{
			// CHANGED: /v1/osrs/latest → /v2/osrs/latest
			baselinePrices = fetchLatestPrices("https://prices.runescape.wiki/api/v2/osrs/latest");

			long now = Instant.now().getEpochSecond();

			PriceAndVolume day1h   = fetchPricesAndVolume(makeUrl1h(now, 86400));
			PriceAndVolume week1h  = fetchPricesAndVolume(makeUrl1h(now, 604800));
			PriceAndVolume month24 = fetchPricesAndVolume(makeUrl24h(now, 2629743));
			PriceAndVolume year24  = fetchPricesAndVolume(makeUrl24h(now, 31556926));

			dayPrices   = day1h.prices;
			weekPrices  = week1h.prices;
			monthPrices = month24.prices;
			yearPrices  = year24.prices;

			dayVolume   = day1h.volume;
			weekVolume  = week1h.volume;
			monthVolume = month24.volume;
			yearVolume  = year24.volume;

			itemMeta = fetchItemMeta("https://chisel.weirdgloop.org/gazproj/gazbot/os_dump.json");

			SwingUtilities.invokeLater(() -> panel.updateMovers(
					baselinePrices,
					dayPrices, weekPrices, monthPrices, yearPrices,
					itemMeta,
					dayVolume, weekVolume, monthVolume, yearVolume
			));
		}
		catch (Exception e)
		{
			log.error("❌ Failed to fetch price data", e);
			// Re-enable the refresh button even on failure
			SwingUtilities.invokeLater(() -> {
				panel.updateMovers(
						baselinePrices,
						dayPrices, weekPrices, monthPrices, yearPrices,
						itemMeta,
						dayVolume, weekVolume, monthVolume, yearVolume
				);
			});
		}
	}

	// ── URL helpers ───────────────────────────────────────────────────────────

	private String makeUrl1h(long now, long offset)
	{
		long ts = now - offset;
		ts -= ts % 3600;
		return "https://prices.runescape.wiki/api/v2/osrs/1h?timestamp=" + ts;
	}

	private String makeUrl24h(long now, long offset)
	{
		long ts = now - offset;
		ts -= ts % 86400;
		return "https://prices.runescape.wiki/api/v2/osrs/24h?timestamp=" + ts;
	}

	// ── HTTP fetchers ─────────────────────────────────────────────────────────

	/**
	 * Fetches a timestamped price endpoint and returns both mid-prices and trade volumes.
	 * CHANGED: return type uses Long values to handle prices > Integer.MAX_VALUE.
	 * CHANGED: avgHighPrice/avgLowPrice parsed as double (v2 allows up to 2 decimal places).
	 */
	private PriceAndVolume fetchPricesAndVolume(String urlStr) throws IOException
	{
		Request request = new Request.Builder()
				.url(urlStr)
				.header("User-Agent", USER_AGENT_HEADER)
				.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
				throw new IOException("Failed to fetch prices: " + response.code());

			try (InputStreamReader reader = new InputStreamReader(response.body().byteStream()))
			{
				// CHANGED: Map value type Integer → Long
				Map<Integer, Long> prices = new HashMap<>();
				Map<Integer, Long> volume = new HashMap<>();

				var root = gson.fromJson(reader, JsonObject.class);
				var data = root.getAsJsonObject("data");

				for (String key : data.keySet())
				{
					try
					{
						int id  = Integer.parseInt(key);
						var obj = data.getAsJsonObject(key);

						// CHANGED: getAsDouble() instead of getAsInt() — v2 allows decimals.
						// Math.round() gives us the nearest long, safe for > 32-bit values.
						if (obj.has("avgHighPrice") && obj.has("avgLowPrice")
								&& !obj.get("avgHighPrice").isJsonNull()
								&& !obj.get("avgLowPrice").isJsonNull())
						{
							double high = obj.get("avgHighPrice").getAsDouble();
							double low  = obj.get("avgLowPrice").getAsDouble();
							prices.put(id, Math.round((high + low) / 2.0));
						}

						// Volume – sum of highPriceVolume + lowPriceVolume
						// CHANGED: accumulate into long to avoid int overflow on high-volume items
						long vol = 0;
						if (obj.has("highPriceVolume") && !obj.get("highPriceVolume").isJsonNull())
							vol += obj.get("highPriceVolume").getAsLong();
						if (obj.has("lowPriceVolume") && !obj.get("lowPriceVolume").isJsonNull())
							vol += obj.get("lowPriceVolume").getAsLong();
						if (vol > 0) volume.put(id, vol);
					}
					catch (Exception ignored) {}
				}
				return new PriceAndVolume(prices, volume);
			}
		}
	}

	/**
	 * Fetches the /latest endpoint for current spot prices.
	 * CHANGED: return type Long; getAsLong() used so values > Integer.MAX_VALUE are safe.
	 */
	private Map<Integer, Long> fetchLatestPrices(String urlStr) throws IOException
	{
		Request request = new Request.Builder()
				.url(urlStr)
				.header("User-Agent", USER_AGENT_HEADER)
				.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
				throw new IOException("Failed to fetch latest prices: " + response.code());

			try (InputStreamReader reader = new InputStreamReader(response.body().byteStream()))
			{
				// CHANGED: Map value type Integer → Long
				Map<Integer, Long> map  = new HashMap<>();
				var root = gson.fromJson(reader, JsonObject.class);
				var data = root.getAsJsonObject("data");

				for (String key : data.keySet())
				{
					try
					{
						int id  = Integer.parseInt(key);
						var obj = data.getAsJsonObject(key);
						if (obj.has("high") && obj.has("low")
								&& !obj.get("high").isJsonNull()
								&& !obj.get("low").isJsonNull())
						{
							// CHANGED: getAsLong() — latest prices are integers but can exceed int max
							long high = obj.get("high").getAsLong();
							long low  = obj.get("low").getAsLong();
							map.put(id, (high + low) / 2);
						}
					}
					catch (Exception ignored) {}
				}
				return map;
			}
		}
	}

	private Map<Integer, ItemMeta> fetchItemMeta(String urlStr) throws IOException
	{
		Request request = new Request.Builder()
				.url(urlStr)
				.header("User-Agent", USER_AGENT_HEADER)
				.build();

		try (Response response = okHttpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
				throw new IOException("Failed to fetch item meta: " + response.code());

			try (InputStreamReader reader = new InputStreamReader(response.body().byteStream()))
			{
				Map<Integer, ItemMeta> map  = new HashMap<>();
				var root = gson.fromJson(reader, JsonObject.class);

				for (String key : root.keySet())
				{
					try
					{
						int    id   = Integer.parseInt(key);
						var    obj  = root.getAsJsonObject(key);
						String name = obj.has("name") ? obj.get("name").getAsString() : "Item " + id;
						String icon = obj.has("icon") ? obj.get("icon").getAsString() : "";

						String safeIcon = icon
								.replace(" ", "_")
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

	// ── Guice providers ───────────────────────────────────────────────────────

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

	// ── Inner / static types ──────────────────────────────────────────────────

	private static class OfferStateCache
	{
		int itemId;
		int quantitySold;

		OfferStateCache(int itemId, int quantitySold)
		{
			this.itemId       = itemId;
			this.quantitySold = quantitySold;
		}
	}

	/**
	 * Holds both prices and trade volumes returned from one API call.
	 * CHANGED: Map value type Integer → Long throughout.
	 */
	private static class PriceAndVolume
	{
		final Map<Integer, Long> prices;
		final Map<Integer, Long> volume;

		PriceAndVolume(Map<Integer, Long> prices, Map<Integer, Long> volume)
		{
			this.prices = prices;
			this.volume = volume;
		}
	}

	public static class ItemMeta
	{
		public final int    id;
		public final String name;
		public final String iconUrl;

		public ItemMeta(int id, String name, String iconUrl)
		{
			this.id      = id;
			this.name    = name;
			this.iconUrl = iconUrl;
		}
	}
}