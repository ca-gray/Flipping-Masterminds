package com.flippingmasterminds;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@PluginDescriptor(
		name = "Flipping Masterminds",
		description = "Grabs Best/Worst Performing item price changes to analyse the market easily!",
		tags = {"grand exchange", "prices", "flipping", "merching"}
)
public class FlippingMastermindsPlugin extends Plugin
{
	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private FlippingMastermindsConfig config;
	@Inject private ClientToolbar clientToolbar;
	@Inject private ConfigManager configManager;
	@Inject private BuyLimitTracker buyLimitTracker;
	@Inject private PriceAlertTracker priceAlertTracker;
	@Inject private OverlayManager overlayManager;
	@Inject private GrandExchangeOverlay geOverlay;

	private NavigationButton navButton;
	private FlippingMastermindsPanel panel;

	private volatile boolean loggedIn = false;

	@Inject private Gson gson;
	@Inject private OkHttpClient okHttpClient;
	private GEDataSender sender;
	private PriceDataFetcher fetcher;

	private volatile long loginTime = 0;
	/** Short window after login to let the client fully settle before we fire events. */
	private static final long LOGIN_IGNORE_WINDOW_MS = 3_000;
	/** Delay after login before sending queued chat alerts so the player is fully loaded in. */
	private static final long CHAT_READY_DELAY_MS = 15_000;
	private final List<String> pendingChatMessages = new CopyOnWriteArrayList<>();

	private ScheduledExecutorService scheduler;
	private ScheduledFuture<?> pendingSend = null;
	private static final long DEBOUNCE_DELAY_MS = 200;
	private static final long LOGIN_JITTER_MAX_MS = 5_000;
	private String lastReason = "Slot updated";

	private final OfferStateCache[] lastOfferStates = new OfferStateCache[8];

	private ScheduledFuture<?> autoRefreshFuture = null;
	private ScheduledFuture<?> buyLimitCheckFuture = null;
	private Set<Integer> knownBuyLimitItems = new HashSet<>();

	private ExecutorService executor;

	private static final int GE_EXAMINE_GROUP = 465;
	private static final int GE_EXAMINE_DESC_CHILD = 27;

	// ── Price / volume data held in memory ────────────────────────────────────
	private Map<Integer, Long> baselinePrices = new HashMap<>();
	private Map<Integer, Long> dayPrices      = new HashMap<>();
	private Map<Integer, Long> weekPrices     = new HashMap<>();
	private Map<Integer, Long> monthPrices    = new HashMap<>();
	private Map<Integer, Long> yearPrices     = new HashMap<>();

	private Map<Integer, Long> dayVolume   = new HashMap<>();
	private Map<Integer, Long> weekVolume  = new HashMap<>();
	private Map<Integer, Long> monthVolume = new HashMap<>();
	private Map<Integer, Long> yearVolume  = new HashMap<>();

	private volatile Map<Integer, Long> latestHigh = new HashMap<>();
	private volatile Map<Integer, Long> latestLow  = new HashMap<>();

	private volatile Map<Integer, ItemMeta> itemMeta = new HashMap<>();


	// ─────────────────────────────────────────────────────────────────────────
	@Override
	protected void startUp()
	{
		log.info("Flipping Masterminds plugin started");

		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "fmm-scheduler");
			t.setDaemon(true);
			return t;
		});

		sender = new GEDataSender(okHttpClient, scheduler);
		fetcher = new PriceDataFetcher(okHttpClient, gson);

		panel = new FlippingMastermindsPanel();

		// Wire the manual-refresh button back to this plugin
		panel.setOnRefreshRequested(() -> {
			ExecutorService ex = executor;
			if (ex != null) ex.submit(this::fetchAllData);
		});

		// Apply persisted toggle states from config
		panel.applyConfig(config.showVolume(), config.showPrices());

		// Wire trackers to tabs
		panel.getAlertPanel().setTracker(priceAlertTracker);
		panel.getBuyLimitPanel().setBuyLimitTracker(buyLimitTracker);

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
		overlayManager.add(geOverlay);
		loggedIn = false;

		knownBuyLimitItems = new HashSet<>(buyLimitTracker.getAllTracked().keySet());

		executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "fmm-executor");
			t.setDaemon(true);
			return t;
		});
		executor.submit(this::fetchAllData);

		scheduleAutoRefresh();

		buyLimitCheckFuture = scheduler.scheduleAtFixedRate(
				this::checkBuyLimitResets, 30, 30, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		log.info("Flipping Masterminds plugin stopped");
		loggedIn = false;
		pendingChatMessages.clear();

		overlayManager.remove(geOverlay);
		if (navButton != null) clientToolbar.removeNavigation(navButton);
		if (panel    != null)
		{
			panel.getAlertPanel().stopTimer();
			panel.getBuyLimitPanel().stopTimer();
			panel.dispose();
		}

		if (autoRefreshFuture != null && !autoRefreshFuture.isDone()) autoRefreshFuture.cancel(false);
		if (buyLimitCheckFuture != null && !buyLimitCheckFuture.isDone()) buyLimitCheckFuture.cancel(false);
		autoRefreshFuture = null;
		buyLimitCheckFuture = null;
		knownBuyLimitItems.clear();

		if (executor != null) executor.shutdownNow();
		executor = null;

		if (sender != null) sender.shutdown();
		sender = null;

		if (pendingSend != null && !pendingSend.isDone()) pendingSend.cancel(false);
		pendingSend = null;
		if (scheduler != null) scheduler.shutdownNow();
		scheduler = null;

		Arrays.fill(lastOfferStates, null);
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

			// Send a GE snapshot on login (if token is set), with jitter to spread login storms
			if (!config.apiToken().isEmpty())
			{
				long loginJitter = (long) (Math.random() * LOGIN_JITTER_MAX_MS);
				scheduler.schedule(
						() -> sendOffersIfChanged("Login snapshot"),
						LOGIN_IGNORE_WINDOW_MS + loginJitter,
						TimeUnit.MILLISECONDS
				);
			}
			else
			{
				log.debug("No API token configured – skipping login snapshot");
			}

			// Flush any queued alert messages after a delay so chat is ready
			if (!pendingChatMessages.isEmpty())
			{
				scheduler.schedule(this::flushPendingChatMessages,
						CHAT_READY_DELAY_MS, TimeUnit.MILLISECONDS);
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
		else if ("autoRefresh".equals(key) || "autoRefreshMinutes".equals(key))
		{
			scheduleAutoRefresh();
		}
		else if ("apiToken".equals(key))
		{
			sender.onTokenChanged(config.apiToken());
		}
	}

	private void scheduleAutoRefresh()
	{
		if (autoRefreshFuture != null && !autoRefreshFuture.isDone())
		{
			autoRefreshFuture.cancel(false);
			autoRefreshFuture = null;
		}

		if (!config.autoRefresh()) return;

		long intervalMinutes = Math.max(2, config.autoRefreshMinutes());
		log.info("Auto-refresh scheduled every {} minutes", intervalMinutes);

		autoRefreshFuture = scheduler.scheduleAtFixedRate(
				() -> {
					ExecutorService ex = executor;
					if (ex != null) ex.submit(this::fetchAllData);
				},
				intervalMinutes,
				intervalMinutes,
				TimeUnit.MINUTES
		);
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

	// ── GE examine text enhancement ──────────────────────────────────────────

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!config.geLatestPrices()) return;

		String name = event.getEventName();
		if (!"geBuyExamineText".equals(name) && !"geSellExamineText".equals(name))
			return;

		int itemId = client.getVarpValue(VarPlayer.CURRENT_GE_ITEM);
		if (itemId <= 0) return;

		Long inb = latestHigh.get(itemId);
		Long ins = latestLow.get(itemId);
		if (inb == null && ins == null) return;

		StringBuilder sb = new StringBuilder("Latest Wiki");
		if (inb != null) sb.append(" INB: ").append(QuantityFormatter.formatNumber(inb));
		if (inb != null && ins != null) sb.append(" /");
		if (ins != null) sb.append(" INS: ").append(QuantityFormatter.formatNumber(ins));
		String wikiLine = sb.toString();

		clientThread.invokeLater(() ->
		{
			Widget descWidget = client.getWidget(GE_EXAMINE_GROUP, GE_EXAMINE_DESC_CHILD);
			if (descWidget == null) return;

			String text = descWidget.getText();
			if (text != null && !text.contains("Latest Wiki"))
			{
				descWidget.setText(text + "<br>" + wikiLine);
			}
		});
	}

	// ── Sending GE data ───────────────────────────────────────────────────────

	private void sendOffersIfChanged(String reason)
	{
		clientThread.invokeLater(() ->
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

			GEDataSender s = sender;
			if (s == null) return;
			String jsonPayload = gson.toJson(payloadMap);
			s.submit(jsonPayload, config.apiToken());
		});
	}

	// ── Price / volume fetching ───────────────────────────────────────────────

	void fetchAllData()
	{
		try
		{
			PriceDataFetcher.FetchResult result = fetcher.fetchAll();

			latestHigh     = result.latest.high;
			latestLow      = result.latest.low;
			baselinePrices = result.latest.baseline;
			dayPrices      = result.day.prices;
			weekPrices     = result.week.prices;
			monthPrices    = result.month.prices;
			yearPrices     = result.year.prices;
			dayVolume      = result.day.volume;
			weekVolume     = result.week.volume;
			monthVolume    = result.month.volume;
			yearVolume     = result.year.volume;
			itemMeta       = result.itemMeta;

			SwingUtilities.invokeLater(() -> panel.updateMovers(
					baselinePrices,
					dayPrices, weekPrices, monthPrices, yearPrices,
					itemMeta,
					dayVolume, weekVolume, monthVolume, yearVolume
			));

			checkPriceAlerts(result.latest.baseline);
		}
		catch (Exception e)
		{
			log.error("Failed to fetch price data", e);
			SwingUtilities.invokeLater(() -> {
				panel.refreshButtonReset();
			});
		}
	}

	// ── Price alert checking ─────────────────────────────────────────────────

	private void checkPriceAlerts(Map<Integer, Long> prices)
	{
		PriceAlertTracker.CheckResult result = priceAlertTracker.checkPrices(prices);

		boolean uiChanged = !result.newlyTriggered.isEmpty() || !result.newlyStale.isEmpty();

		if (uiChanged)
		{
			SwingUtilities.invokeLater(() -> {
				panel.getAlertPanel().rebuildAlertList();
				panel.notifyTab("alerts");
			});
		}

		for (PriceAlertTracker.PriceAlert alert : result.newlyTriggered)
		{
			String dir = alert.direction == PriceAlertTracker.Direction.BELOW
					? "fell to" : "rose to";
			String msg = "[FMM] " + alert.itemName + " " + dir + " "
					+ formatGpSimple(alert.triggeredPrice)
					+ " (target: " + formatGpSimple(alert.targetPrice) + ")";
			pendingChatMessages.add(msg);
		}

		for (PriceAlertTracker.PriceAlert alert : result.newlyStale)
		{
			String dir = alert.direction == PriceAlertTracker.Direction.BELOW
					? "fall below" : "rise above";
			String msg = "[FMM] Alert expired: " + alert.itemName
					+ " never " + dir + " " + formatGpSimple(alert.targetPrice)
					+ " within " + formatDurationSimple(alert.expiryMs) + ".";
			pendingChatMessages.add(msg);
		}

		if (!pendingChatMessages.isEmpty() && loggedIn
				&& System.currentTimeMillis() - loginTime >= CHAT_READY_DELAY_MS)
		{
			flushPendingChatMessages();
		}
	}

	private void flushPendingChatMessages()
	{
		if (pendingChatMessages.isEmpty() || !loggedIn) return;

		List<String> toSend = new ArrayList<>(pendingChatMessages);
		pendingChatMessages.clear();

		clientThread.invokeLater(() ->
		{
			for (String msg : toSend)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
			}
		});
	}

	// ── Buy limit reset detection ────────────────────────────────────────────

	private void checkBuyLimitResets()
	{
		Map<Integer, Map<String, Object>> tracked = buyLimitTracker.getAllTracked();
		Set<Integer> currentItems = tracked.keySet();

		List<Integer> resetItems = new ArrayList<>();
		for (int itemId : knownBuyLimitItems)
		{
			if (!currentItems.contains(itemId))
			{
				resetItems.add(itemId);
			}
		}

		knownBuyLimitItems = new HashSet<>(currentItems);

		if (resetItems.isEmpty()) return;

		if (loggedIn && resetItems.size() >= 5)
		{
			pendingChatMessages.add("[FMM] " + resetItems.size()
					+ " buy limits have reset — check the Buy Limits tab.");
		}
		else
		{
			for (int itemId : resetItems)
			{
				String itemName = "Item " + itemId;
				if (itemMeta != null && itemMeta.containsKey(itemId))
				{
					itemName = itemMeta.get(itemId).name;
				}
				pendingChatMessages.add("[FMM] Buy limit reset: " + itemName
						+ " — 4-hour window expired, you can buy again.");
			}
		}

		SwingUtilities.invokeLater(() -> {
			panel.notifyTab("buylimits");
			panel.getBuyLimitPanel().rebuildList();
		});

		if (loggedIn && System.currentTimeMillis() - loginTime >= CHAT_READY_DELAY_MS)
		{
			flushPendingChatMessages();
		}
	}

	private static String formatDurationSimple(long ms)
	{
		long hours = ms / (60 * 60 * 1000L);
		if (hours < 24) return hours + " hour" + (hours != 1 ? "s" : "");
		long days = hours / 24;
		if (days < 7)   return days + " day" + (days != 1 ? "s" : "");
		long weeks = days / 7;
		return weeks + " week" + (weeks != 1 ? "s" : "");
	}

	private static String formatGpSimple(long gp)
	{
		double abs = Math.abs((double) gp);
		if (abs >= 1_000_000_000) return String.format("%.1fB gp", gp / 1_000_000_000.0);
		if (abs >= 1_000_000)     return String.format("%.1fM gp", gp / 1_000_000.0);
		if (abs >= 1_000)         return String.format("%.1fK gp", gp / 1_000.0);
		return gp + " gp";
	}

	// ── Guice providers ───────────────────────────────────────────────────────

	@Provides
	BuyLimitTracker provideBuyLimitTracker(ConfigManager configManager)
	{
		return new BuyLimitTracker(configManager);
	}

	@Provides
	PriceAlertTracker providePriceAlertTracker(ConfigManager configManager)
	{
		return new PriceAlertTracker(configManager);
	}

	@Provides
	FlippingMastermindsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlippingMastermindsConfig.class);
	}

	// ── Accessors for GE overlay ─────────────────────────────────────────────

	Map<Integer, Long> getLatestHigh()
	{
		return latestHigh;
	}

	Map<Integer, Long> getLatestLow()
	{
		return latestLow;
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

}