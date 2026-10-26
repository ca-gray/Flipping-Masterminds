package com.flippingmasterminds;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Notification;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup("flippingmasterminds")
public interface FlippingMastermindsConfig extends Config
{
	// ── Sections ──────────────────────────────────────────────────────────────

	@ConfigSection(
			name = "Account",
			description = "Connect the plugin to your Flipping Masterminds account",
			position = 0
	)
	String accountSection = "account";

	@ConfigSection(
			name = "Market Panel",
			description = "The side panel showing price movers, alerts and buy limits",
			position = 1
	)
	String marketSection = "market";

	@ConfigSection(
			name = "Grand Exchange",
			description = "Extra information shown on the Grand Exchange offer screens",
			position = 2
	)
	String geSection = "grandExchange";

	@ConfigSection(
			name = "GE Trade History",
			description = "Extended trade history, search and trade details in the GE history window",
			position = 3
	)
	String historySection = "tradeHistory";

	@ConfigSection(
			name = "Notifications",
			description = "Desktop notifications for price alerts and buy limit resets",
			position = 4
	)
	String notificationSection = "notifications";

	// ── Account ───────────────────────────────────────────────────────────────

	@ConfigItem(
			keyName = "apiToken",
			name = "API Token",
			description = "Run /generate_api_token in the Flipping Masterminds Discord to get your token. Join at discord.gg/VnsS2PP4Vt",
			position = 0,
			section = accountSection
	)
	default String apiToken()
	{
		return "";
	}

	// ── Market Panel ──────────────────────────────────────────────────────────

	@ConfigItem(
			keyName = "showVolume",
			name = "Show Volume",
			description = "Display the number of items traded in the last 24 hours on each item row",
			position = 0,
			section = marketSection
	)
	default boolean showVolume()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showPrices",
			name = "Show Prices",
			description = "Display the historical (snapshot) price and current (latest) price on each item row",
			position = 1,
			section = marketSection
	)
	default boolean showPrices()
	{
		return true;
	}

	@ConfigItem(
			keyName = "autoRefresh",
			name = "Auto Refresh Prices",
			description = "Automatically fetch the latest prices on a timer (needed for price alerts to trigger)",
			position = 2,
			section = marketSection
	)
	default boolean autoRefresh()
	{
		return true;
	}

	@Range(min = 2)
	@Units(Units.MINUTES)
	@ConfigItem(
			keyName = "autoRefreshMinutes",
			name = "Auto Refresh Interval",
			description = "How often to auto-refresh prices (minimum 2 minutes)",
			position = 3,
			section = marketSection
	)
	default int autoRefreshMinutes()
	{
		return 5;
	}

	// ── Grand Exchange ────────────────────────────────────────────────────────

	@ConfigItem(
			keyName = "geOverlay",
			name = "Offer Slot Highlights",
			description = "Highlight GE offer slots green (competitive) or red (outside market range)",
			position = 0,
			section = geSection
	)
	default boolean geOverlay()
	{
		return true;
	}

	@ConfigItem(
			keyName = "geLatestPrices",
			name = "Latest Prices in Examine Text",
			description = "Show latest wiki buy/sell price on the GE item examine text",
			position = 1,
			section = geSection
	)
	default boolean geLatestPrices()
	{
		return true;
	}

	@ConfigItem(
			keyName = "fullGeOfferValues",
			name = "Full Values on Offer Slots",
			description = "Show exact amounts on the GE offer slots (130,311,121) instead of the game's shortened values (130.3M)",
			position = 2,
			section = geSection
	)
	default boolean fullGeOfferValues()
	{
		return true;
	}

	// ── GE Trade History ──────────────────────────────────────────────────────

	@ConfigItem(
			keyName = "extendedGeHistory",
			name = "Extended History",
			description = "Record your trades locally and show older ones below the in-game GE history list",
			position = 0,
			section = historySection
	)
	default boolean extendedGeHistory()
	{
		return true;
	}

	@Range(min = 0, max = GeHistoryExtender.MAX_EXTRA_ROWS)
	@ConfigItem(
			keyName = "extendedGeHistorySize",
			name = "Extra Rows to Keep",
			description = "Maximum number of older trades to keep and show beyond the in-game history",
			position = 1,
			section = historySection
	)
	default int extendedGeHistorySize()
	{
		return 500;
	}

	@ConfigItem(
			keyName = "showTradeAge",
			name = "Show Trade Age",
			description = "Show how long ago each trade happened (e.g. 30m ago) under Bought/Sold",
			position = 2,
			section = historySection
	)
	default boolean showTradeAge()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showTradeDetails",
			name = "Trade Details on Hover",
			description = "Hovering a row shows the exact time, price per item and today's wiki price",
			position = 3,
			section = historySection
	)
	default boolean showTradeDetails()
	{
		return true;
	}

	// ── Notifications ─────────────────────────────────────────────────────────

	@ConfigItem(
			keyName = "priceAlertNotification",
			name = "Price Alerts",
			description = "Desktop notification when a price alert triggers or expires",
			position = 0,
			section = notificationSection
	)
	default Notification priceAlertNotification()
	{
		return Notification.ON;
	}

	@ConfigItem(
			keyName = "buyLimitNotification",
			name = "Buy Limit Resets",
			description = "Desktop notification when a 4-hour buy limit window resets",
			position = 1,
			section = notificationSection
	)
	default Notification buyLimitNotification()
	{
		return Notification.OFF;
	}
}
