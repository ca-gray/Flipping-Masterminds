package com.flippingmasterminds;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;
import okhttp3.OkHttpClient;

import javax.inject.Inject;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@PluginDescriptor(
		name = "Flipping Masterminds",
		description = "Market movers, price alerts, buy limit tracking and an extended, searchable GE trade history",
		tags = {"grand exchange", "prices", "flipping", "merching", "trade history", "buy limit", "alerts"}
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
	@Inject private ItemManager itemManager;
	@Inject private ChatboxPanelManager chatboxPanelManager;
	@Inject private TooltipManager tooltipManager;
	@Inject private ChatMessageManager chatMessageManager;
	@Inject private Notifier notifier;

	private NavigationButton navButton;
	private FlippingMastermindsPanel panel;

	private volatile boolean loggedIn = false;

	@Inject private Gson gson;
	@Inject private OkHttpClient okHttpClient;
	private GEDataSender sender;
	private PriceDataFetcher fetcher;

	private volatile long loginTime = 0;
	/** When prices were last fetched successfully. */
	private volatile long lastFetchMs = 0;
	private final AtomicBoolean fetchQueued = new AtomicBoolean();
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


	private ScheduledFuture<?> autoRefreshFuture = null;
	private ScheduledFuture<?> buyLimitCheckFuture = null;
	private volatile Set<Integer> knownBuyLimitItems = new HashSet<>();

	private ExecutorService executor;
	private GeHistoryExtender geHistory;
	private GeOfferValueExpander offerValues;

	private static final File HISTORY_DIR = new File(RuneLite.RUNELITE_DIR, "flipping-masterminds");


	// ── Price data held in memory ─────────────────────────────────────────────
	private volatile Map<Integer, Long> latestHigh = new HashMap<>();
	private volatile Map<Integer, Long> latestLow  = new HashMap<>();

	private volatile Map<Integer, ItemMeta> itemMeta = new HashMap<>();


	// ─────────────────────────────────────────────────────────────────────────
	@Override
	protected void startUp()
	{
		log.debug("Flipping Masterminds plugin started");

		ScheduledThreadPoolExecutor stpe = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "fmm-scheduler");
			t.setDaemon(true);
			return t;
		});
		// On shutdown, drop future delayed tasks (login snapshot, chat flush) but still
		// run tasks that are already due, such as pending history writes.
		stpe.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		scheduler = stpe;

		geHistory = new GeHistoryExtender(client, clientThread, chatboxPanelManager, itemManager,
				new GeHistoryStore(HISTORY_DIR, gson, scheduler), config,
				tooltipManager, this::getLatestHigh, this::getLatestLow);

		offerValues = new GeOfferValueExpander(client, config);

		sender = new GEDataSender(okHttpClient, scheduler);
		fetcher = new PriceDataFetcher(okHttpClient, gson);

		panel = new FlippingMastermindsPanel(itemManager);

		// Wire the manual-refresh button back to this plugin
		panel.setOnRefreshRequested(this::submitFetch);
		panel.setOnActivated(this::refreshIfStale);

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

		// Enabled (or updated) while already logged in: there will be no login event, so
		// treat the session as already settled.
		clientThread.invoke(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN) return;
			loginTime = 0;
			loggedIn  = true;
			GeHistoryExtender h = geHistory;
			if (h != null && config.extendedGeHistory())
			{
				h.preloadHistory(client.getAccountHash());
				// Pick up offers already finished and waiting to be collected.
				GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
				for (int i = 0; offers != null && i < offers.length; i++)
				{
					if (offers[i] != null) h.onOfferChanged(i, offers[i], true);
				}
			}
		});

		// The tracker may have loaded before login; make sure it holds the current account's limits.
		buyLimitTracker.reload();
		knownBuyLimitItems = new HashSet<>(buyLimitTracker.getAllTracked().keySet());
		SwingUtilities.invokeLater(() -> panel.getBuyLimitPanel().rebuildList());

		executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "fmm-executor");
			t.setDaemon(true);
			return t;
		});
		// A fetch dropped by the last shutdown never cleared its flag.
		fetchQueued.set(false);
		submitFetch();

		scheduleAutoRefresh();

		buyLimitCheckFuture = scheduler.scheduleAtFixedRate(
				this::checkBuyLimitResets, 30, 30, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		log.debug("Flipping Masterminds plugin stopped");
		loggedIn = false;
		pendingChatMessages.clear();

		overlayManager.remove(geOverlay);
		if (navButton != null) clientToolbar.removeNavigation(navButton);
		if (panel    != null)
		{
			panel.getAlertPanel().stopTimer();
			panel.getBuyLimitPanel().stopTimer();
		}

		if (autoRefreshFuture != null && !autoRefreshFuture.isDone()) autoRefreshFuture.cancel(false);
		if (buyLimitCheckFuture != null && !buyLimitCheckFuture.isDone()) buyLimitCheckFuture.cancel(false);
		autoRefreshFuture = null;
		buyLimitCheckFuture = null;
		knownBuyLimitItems = new HashSet<>();

		if (executor != null) executor.shutdownNow();
		executor = null;

		if (sender != null) sender.shutdown();
		sender = null;

		GeHistoryExtender h = geHistory;
		if (h != null) clientThread.invokeLater(h::removeInjected);
		geHistory = null;

		GeOfferValueExpander v = offerValues;
		if (v != null) clientThread.invokeLater(v::restore);
		offerValues = null;

		if (pendingSend != null && !pendingSend.isDone()) pendingSend.cancel(false);
		pendingSend = null;
		// shutdown() rather than shutdownNow() so queued history writes still finish;
		// delayed tasks are dropped by the executor's shutdown policy.
		if (scheduler != null) scheduler.shutdown();
		scheduler = null;
	}

	// ── Game-state events ─────────────────────────────────────────────────────

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// LOGGED_IN also fires after every region load (LOADING -> LOGGED_IN); only the first
		// one after the login screen or a world hop is an actual login.
		if (event.getGameState() == GameState.LOGGED_IN && !loggedIn)
		{
			loggedIn  = true;
			loginTime = System.currentTimeMillis();
			log.debug("Account logged in, GE scanning enabled after cooldown");
			refreshIfStale();

			GeHistoryExtender h = geHistory;
			if (h != null && config.extendedGeHistory()) h.preloadHistory(client.getAccountHash());

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
				|| event.getGameState() == GameState.HOPPING
				|| event.getGameState() == GameState.CONNECTION_LOST)
		{
			loggedIn = false;
			log.debug("Account logged out – GE scanning disabled");
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		// Buy limits are per account. Locked with checkBuyLimitResets so it never compares
		// the new account's limits against the old account's.
		synchronized (buyLimitTracker)
		{
			buyLimitTracker.reload();
			knownBuyLimitItems = new HashSet<>(buyLimitTracker.getAllTracked().keySet());
		}
		SwingUtilities.invokeLater(() -> panel.getBuyLimitPanel().rebuildList());
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
		else if ("extendedGeHistory".equals(key) || "extendedGeHistorySize".equals(key)
				|| "showTradeAge".equals(key))
		{
			// Re-injected on the next tick with the new settings.
			GeHistoryExtender h = geHistory;
			if (h != null) clientThread.invokeLater(h::removeInjected);
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
		log.debug("Auto-refresh scheduled every {} minutes", intervalMinutes);

		autoRefreshFuture = scheduler.scheduleAtFixedRate(
				() -> {
					// Nobody is looking and no alert needs checking: skip, and catch up
					// on login or when the panel opens.
					if (!loggedIn && !panel.isPanelOpen() && !priceAlertTracker.hasActiveAlerts()) return;
					submitFetch();
				},
				intervalMinutes,
				intervalMinutes,
				TimeUnit.MINUTES
		);
	}

	/** Queue a price fetch unless one is already queued or running. */
	private void submitFetch()
	{
		ExecutorService ex = executor;
		if (ex == null || !fetchQueued.compareAndSet(false, true)) return;
		try
		{
			ex.submit(() ->
			{
				try
				{
					fetchAllData();
				}
				finally
				{
					fetchQueued.set(false);
				}
			});
		}
		catch (RejectedExecutionException e)
		{
			fetchQueued.set(false);
		}
	}

	/** Fetch now if auto-refresh is on and the last successful fetch is older than the interval. */
	private void refreshIfStale()
	{
		if (!config.autoRefresh()) return;
		long intervalMs = TimeUnit.MINUTES.toMillis(Math.max(2, config.autoRefreshMinutes()));
		if (System.currentTimeMillis() - lastFetchMs >= intervalMs) submitFetch();
	}

	// ── GE offer events ───────────────────────────────────────────────────────

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		long now = System.currentTimeMillis();
		// "Settling": just after login the game replays every slot's state (EMPTY first, then
		// the real state). Those events are not new trades, so handlers treat them carefully.
		boolean settling = !loggedIn || now - loginTime < LOGIN_IGNORE_WINDOW_MS;
		GrandExchangeOffer offer = event.getOffer();
		int slot = event.getSlot();

		GeHistoryExtender h = geHistory;
		if (h != null && config.extendedGeHistory())
		{
			h.onOfferChanged(slot, offer, settling);
		}

		trackBuyLimit(slot, offer, settling);

		if (settling || config.apiToken().isEmpty()) return;

		lastReason = "Slot updated: " + event.getSlot();
		if (pendingSend != null && !pendingSend.isDone()) pendingSend.cancel(false);
		pendingSend = scheduler.schedule(
				() -> sendOffersIfChanged(lastReason), DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
	}

	/** Count newly bought quantity towards the 4-hour buy limit. */
	private void trackBuyLimit(int slot, GrandExchangeOffer offer, boolean settling)
	{
		GrandExchangeOfferState state = offer.getState();
		boolean buy = state == GrandExchangeOfferState.BUYING
				|| state == GrandExchangeOfferState.BOUGHT
				|| state == GrandExchangeOfferState.CANCELLED_BUY;
		buyLimitTracker.onOfferUpdate(slot, offer.getItemId(), offer.getQuantitySold(), buy,
				state == GrandExchangeOfferState.EMPTY, settling);
	}

	// ── Extended GE history ──────────────────────────────────────────────────

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		GeHistoryExtender h = geHistory;
		if (h != null && config.extendedGeHistory()) h.tick();

		GeOfferValueExpander v = offerValues;
		if (v != null) v.tick();
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		GeHistoryExtender h = geHistory;
		if (h != null && config.extendedGeHistory()) h.addHoverTooltip();
	}

	// ── GE examine text enhancement ──────────────────────────────────────────

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!config.geLatestPrices()) return;

		String name = event.getEventName();
		if (!"geBuyExamineText".equals(name) && !"geSellExamineText".equals(name))
			return;

		int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
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
			Widget descWidget = client.getWidget(InterfaceID.GeOffers.SETUP_DESC);
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
			lastFetchMs = System.currentTimeMillis();

			latestHigh = result.latest.high;
			latestLow  = result.latest.low;
			itemMeta   = result.itemMeta;

			// Hand the panel this fetch's data directly rather than re-reading shared fields later.
			SwingUtilities.invokeLater(() -> panel.updateMovers(
					result.latest.baseline,
					result.day.prices, result.week.prices, result.month.prices, result.year.prices,
					result.itemMeta,
					result.day.volume, result.week.volume, result.month.volume, result.year.volume
			));

			checkPriceAlerts(result.latest.baseline);
		}
		catch (Exception e)
		{
			log.warn("Failed to fetch price data: {}", e.toString());
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
			String msg = alert.itemName + " " + dir + " "
					+ formatGpSimple(alert.triggeredPrice)
					+ " (target: " + formatGpSimple(alert.targetPrice) + ")";
			pendingChatMessages.add(msg);
			notifier.notify(config.priceAlertNotification(), "Flipping Masterminds: " + msg);
		}

		for (PriceAlertTracker.PriceAlert alert : result.newlyStale)
		{
			String dir = alert.direction == PriceAlertTracker.Direction.BELOW
					? "fall below" : "rise above";
			String msg = "Alert expired: " + alert.itemName
					+ " never " + dir + " " + formatGpSimple(alert.targetPrice)
					+ " within " + formatDurationSimple(alert.expiryMs) + ".";
			pendingChatMessages.add(msg);
			notifier.notify(config.priceAlertNotification(), "Flipping Masterminds: " + msg);
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

		for (String msg : toSend)
		{
			String formatted = new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT)
					.append("[FMM] ")
					.append(ChatColorType.NORMAL)
					.append(msg)
					.build();
			chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.GAMEMESSAGE)
					.runeLiteFormattedMessage(formatted)
					.build());
		}
	}

	// ── Buy limit reset detection ────────────────────────────────────────────

	private void checkBuyLimitResets()
	{
		List<Integer> resetItems = new ArrayList<>();
		synchronized (buyLimitTracker)
		{
			Set<Integer> currentItems = buyLimitTracker.getAllTracked().keySet();
			for (int itemId : knownBuyLimitItems)
			{
				if (!currentItems.contains(itemId))
				{
					resetItems.add(itemId);
				}
			}
			knownBuyLimitItems = new HashSet<>(currentItems);
		}

		if (resetItems.isEmpty()) return;

		if (loggedIn && resetItems.size() >= 5)
		{
			pendingChatMessages.add(resetItems.size()
					+ " buy limits have reset. Check the Buy Limits tab.");
		}
		else
		{
			for (int itemId : resetItems)
			{
				pendingChatMessages.add("Buy limit reset: " + buyLimitItemName(itemId)
						+ ". 4-hour window expired, you can buy again.");
			}
		}

		notifier.notify(config.buyLimitNotification(), resetItems.size() == 1
				? "Flipping Masterminds: buy limit reset for " + buyLimitItemName(resetItems.get(0))
				: "Flipping Masterminds: " + resetItems.size() + " buy limits have reset");

		SwingUtilities.invokeLater(() -> {
			panel.notifyTab("buylimits");
			panel.getBuyLimitPanel().rebuildList();
		});

		if (loggedIn && System.currentTimeMillis() - loginTime >= CHAT_READY_DELAY_MS)
		{
			flushPendingChatMessages();
		}
	}

	private String buyLimitItemName(int itemId)
	{
		Map<Integer, ItemMeta> meta = itemMeta;
		ItemMeta im = meta != null ? meta.get(itemId) : null;
		return im != null ? im.name : "Item " + itemId;
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
		if (abs >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fB gp", gp / 1_000_000_000.0);
		if (abs >= 1_000_000)     return String.format(Locale.ROOT, "%.1fM gp", gp / 1_000_000.0);
		if (abs >= 1_000)         return String.format(Locale.ROOT, "%.1fK gp", gp / 1_000.0);
		return gp + " gp";
	}

	// ── Guice providers ───────────────────────────────────────────────────────

	@Provides
	BuyLimitTracker provideBuyLimitTracker(ConfigManager configManager)
	{
		return new BuyLimitTracker(configManager);
	}

	@Provides
	PriceAlertTracker providePriceAlertTracker(ConfigManager configManager, Gson gson)
	{
		return new PriceAlertTracker(configManager, gson);
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
}