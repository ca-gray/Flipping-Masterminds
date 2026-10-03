package com.flippingmasterminds;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flippingmasterminds")
public interface FlippingMastermindsConfig extends Config
{
	@ConfigItem(
			keyName = "apiToken",
			name = "API Token",
			description = "Run /generate_api_token in the Flipping Masterminds Discord to get your token. Join at discord.gg/VnsS2PP4Vt"
	)
	default String apiToken()
	{
		return "";
	}

	@ConfigItem(
			keyName = "showVolume",
			name = "Show Volume",
			description = "Display trade volume on each item row"
	)
	default boolean showVolume()
	{
		return true;
	}

	@ConfigItem(
			keyName = "showPrices",
			name = "Show Prices",
			description = "Display the historical (snapshot) price and current (latest) price on each item row"
	)
	default boolean showPrices()
	{
		return true;
	}

	@ConfigItem(
			keyName = "autoRefresh",
			name = "Auto Refresh Prices",
			description = "Automatically fetch the latest prices on a timer (needed for price alerts to trigger)"
	)
	default boolean autoRefresh()
	{
		return true;
	}

	@ConfigItem(
			keyName = "autoRefreshMinutes",
			name = "Auto Refresh Interval (mins)",
			description = "How often to auto-refresh prices in minutes (minimum 2)"
	)
	default int autoRefreshMinutes()
	{
		return 5;
	}

	@ConfigItem(
			keyName = "geOverlay",
			name = "GE Price Overlay",
			description = "Highlight GE offer slots green (competitive) or red (outside market range)"
	)
	default boolean geOverlay()
	{
		return true;
	}

	@ConfigItem(
			keyName = "geLatestPrices",
			name = "GE Latest Prices",
			description = "Show latest wiki buy/sell price on the GE item examine text"
	)
	default boolean geLatestPrices()
	{
		return true;
	}
}
