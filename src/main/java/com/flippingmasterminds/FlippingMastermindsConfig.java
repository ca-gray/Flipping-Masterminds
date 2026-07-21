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
			description = "API Token produced from the /generate_api_token in FMM discord!"
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
}
