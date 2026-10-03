package com.flippingmasterminds;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;
import java.util.Map;

public class GrandExchangeOverlay extends Overlay
{
	private static final int GE_GROUP_ID = 465;
	private static final int GE_FIRST_SLOT_CHILD = 7;
	private static final int GE_SETUP_CHILD = 26;
	private static final int GE_SETUP_DESC_CHILD = 27;
	private static final int GE_SETUP_CONFIRM_CHILD = 30;
	private static final int CURRENT_GE_ITEM_VARP = 1151;

	private static final Color GREEN_FILL    = new Color(0, 180, 0, 20);
	private static final Color GREEN_BORDER  = new Color(0, 180, 0, 60);
	private static final Color RED_FILL      = new Color(180, 0, 0, 20);
	private static final Color RED_BORDER    = new Color(180, 0, 0, 60);
	private static final Color YELLOW_FILL   = new Color(200, 180, 0, 20);
	private static final Color YELLOW_BORDER = new Color(200, 180, 0, 60);

	private final Client client;
	private final FlippingMastermindsPlugin plugin;
	private final FlippingMastermindsConfig config;

	@Inject
	public GrandExchangeOverlay(Client client, FlippingMastermindsPlugin plugin,
								FlippingMastermindsConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.geOverlay()) return null;

		Widget geWindow = client.getWidget(GE_GROUP_ID, 0);
		if (geWindow == null || geWindow.isHidden()) return null;

		Map<Integer, Long> highPrices = plugin.getLatestHigh();
		Map<Integer, Long> lowPrices  = plugin.getLatestLow();
		if (highPrices.isEmpty() || lowPrices.isEmpty()) return null;

		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null) return null;

		for (int slot = 0; slot < offers.length && slot < 8; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null) continue;

			GrandExchangeOfferState state = offer.getState();
			if (state == GrandExchangeOfferState.EMPTY
					|| state == GrandExchangeOfferState.CANCELLED_BUY
					|| state == GrandExchangeOfferState.CANCELLED_SELL)
			{
				continue;
			}

			int itemId      = offer.getItemId();
			long offerPrice = offer.getPrice();

			Long apiHigh = highPrices.get(itemId);
			Long apiLow  = lowPrices.get(itemId);
			if (apiHigh == null || apiLow == null) continue;

			boolean isBuy = (state == GrandExchangeOfferState.BUYING
					|| state == GrandExchangeOfferState.BOUGHT);

			Widget slotWidget = client.getWidget(GE_GROUP_ID, GE_FIRST_SLOT_CHILD + slot);
			if (slotWidget == null || slotWidget.isHidden()) continue;

			Rectangle bounds = slotWidget.getBounds();
			if (bounds == null || bounds.width <= 0) continue;

			paintHighlight(graphics, bounds, offerPrice, isBuy, apiHigh, apiLow);
		}

		renderSetupHighlight(graphics, highPrices, lowPrices);
		return null;
	}

	private void renderSetupHighlight(Graphics2D graphics,
									  Map<Integer, Long> highPrices,
									  Map<Integer, Long> lowPrices)
	{
		Widget setupWidget = client.getWidget(GE_GROUP_ID, GE_SETUP_CHILD);
		if (setupWidget == null || setupWidget.isHidden()) return;

		int itemId = client.getVarpValue(CURRENT_GE_ITEM_VARP);
		if (itemId <= 0) return;

		Long apiHigh = highPrices.get(itemId);
		Long apiLow  = lowPrices.get(itemId);
		if (apiHigh == null || apiLow == null) return;

		boolean isBuy = determineSetupBuyOrSell(setupWidget);
		long offerPrice = findSetupPrice(itemId, setupWidget);
		if (offerPrice <= 0) return;

		Widget confirmWidget = client.getWidget(GE_GROUP_ID, GE_SETUP_CONFIRM_CHILD);
		if (confirmWidget == null || confirmWidget.isHidden()) return;
		Rectangle bounds = confirmWidget.getBounds();
		if (bounds == null || bounds.width <= 0) return;

		paintHighlight(graphics, bounds, offerPrice, isBuy, apiHigh, apiLow);
	}

	private void paintHighlight(Graphics2D graphics, Rectangle bounds,
								long offerPrice, boolean isBuy, long apiHigh, long apiLow)
	{
		boolean exactMatch, competitive;
		if (isBuy)
		{
			exactMatch  = offerPrice == apiLow;
			competitive = offerPrice >= apiLow;
		}
		else
		{
			exactMatch  = offerPrice == apiHigh;
			competitive = offerPrice <= apiHigh;
		}

		Color fill, border;
		if (exactMatch)       { fill = YELLOW_FILL; border = YELLOW_BORDER; }
		else if (competitive) { fill = GREEN_FILL;  border = GREEN_BORDER;  }
		else                  { fill = RED_FILL;    border = RED_BORDER;    }

		graphics.setColor(fill);
		graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
		graphics.setColor(border);
		graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
	}

	private boolean determineSetupBuyOrSell(Widget setupWidget)
	{
		Widget descWidget = client.getWidget(GE_GROUP_ID, GE_SETUP_DESC_CHILD);
		if (descWidget != null)
		{
			String text = descWidget.getText();
			if (text != null && !text.isEmpty())
			{
				String stripped = text.replaceAll("<[^>]+>", "").trim().toLowerCase();
				if (stripped.startsWith("sell")) return false;
				if (stripped.startsWith("buy")) return true;
			}
		}

		Widget[] children = setupWidget.getChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child == null) continue;
				String text = child.getText();
				if (text == null || text.isEmpty()) continue;
				String lower = text.replaceAll("<[^>]+>", "").trim().toLowerCase();
				if (lower.startsWith("sell")) return false;
				if (lower.startsWith("buy")) return true;
			}
		}

		return true;
	}

	private long findSetupPrice(int itemId, Widget setupWidget)
	{
		Widget descWidget = client.getWidget(GE_GROUP_ID, GE_SETUP_DESC_CHILD);
		if (descWidget != null && descWidget.getText() != null)
		{
			long price = parsePriceText(descWidget.getText().replaceAll("<[^>]+>", ""));
			if (price > 0) return price;
		}

		Widget[] children = setupWidget.getChildren();
		if (children != null)
		{
			for (Widget child : children)
			{
				if (child == null) continue;
				String text = child.getText();
				if (text == null || text.isEmpty()) continue;
				long price = parsePriceText(text.replaceAll("<[^>]+>", "").trim());
				if (price > 0) return price;
			}
		}

		return -1;
	}

	private static long parsePriceText(String text)
	{
		int forIdx = text.lastIndexOf("for ");
		if (forIdx >= 0)
		{
			long price = parseNumberString(text.substring(forIdx + 4).trim());
			if (price > 0) return price;
		}

		int coinIdx = text.indexOf("coin");
		if (coinIdx < 0) coinIdx = text.indexOf("gp");
		if (coinIdx > 0)
		{
			long price = parseNumberString(text.substring(0, coinIdx).trim());
			if (price > 0) return price;
		}

		return -1;
	}

	private static long parseNumberString(String s)
	{
		StringBuilder digits = new StringBuilder();
		for (char c : s.toCharArray())
		{
			if (Character.isDigit(c)) digits.append(c);
			else if (c != ',') break;
		}
		if (digits.length() == 0) return -1;
		try { return Long.parseLong(digits.toString()); }
		catch (NumberFormatException e) { return -1; }
	}
}
