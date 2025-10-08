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
			description = "API token for Authorization header (Bearer)"
	)
	default String apiToken()
	{
		return "";
	}
}
