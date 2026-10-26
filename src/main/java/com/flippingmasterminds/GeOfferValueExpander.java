package com.flippingmasterminds;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Replaces condensed values in the GE offer slots (e.g. "130.3M coins") with the
 * exact amount ("130,311,121 coins"). The exact value is taken from the offer
 * itself and only used when it rounds to the number the game displays.
 *
 * Client thread only.
 */
class GeOfferValueExpander
{
	private static final int[] SLOT_WIDGETS = GrandExchangeOverlay.SLOT_WIDGETS;

	private static final Pattern CONDENSED = Pattern.compile("(?<![\\d.,])(\\d+(?:\\.\\d+)?)\\s?([kmb])(?![a-z])",
			Pattern.CASE_INSENSITIVE);
	private static final int SEARCH_DEPTH = 3;
	private static final long SCAN_INTERVAL_MS = 100;
	private long lastScan;

	private final Client client;
	private final FlippingMastermindsConfig config;
	/** Game-written text of widgets we changed, so the change can be undone. */
	private final Map<Widget, String> originals = new IdentityHashMap<>();

	GeOfferValueExpander(Client client, FlippingMastermindsConfig config)
	{
		this.client = client;
		this.config = config;
	}

	void tick()
	{
		if (!config.fullGeOfferValues())
		{
			restore();
			return;
		}

		Widget universe = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		if (universe == null || universe.isHidden())
		{
			originals.clear();
			return;
		}

		// Scanning the slots every frame is wasteful; a short interval looks the same.
		long now = System.currentTimeMillis();
		if (now - lastScan < SCAN_INTERVAL_MS) return;
		lastScan = now;

		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null) return;

		for (int slot = 0; slot < offers.length && slot < SLOT_WIDGETS.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null || offer.getState() == GrandExchangeOfferState.EMPTY) continue;

			Widget slotWidget = client.getWidget(SLOT_WIDGETS[slot]);
			if (slotWidget == null || slotWidget.isHidden()) continue;

			expandIn(slotWidget, offer, SEARCH_DEPTH);
		}
	}

	/** Put back the game's own text, e.g. when the option is turned off. */
	void restore()
	{
		for (Map.Entry<Widget, String> e : originals.entrySet())
		{
			e.getKey().setText(e.getValue());
		}
		originals.clear();
	}

	private void expandIn(Widget w, GrandExchangeOffer offer, int depth)
	{
		if (w == null || depth < 0) return;

		if (w.getType() == WidgetType.TEXT)
		{
			String text = w.getText();
			if (text != null && !text.isEmpty())
			{
				String expanded = expand(text, coinsCandidates(offer), quantityCandidates(offer));
				if (!expanded.equals(text))
				{
					// The game rewrote the text since we last changed it; remember its version.
					originals.put(w, text);
					w.setText(expanded);
				}
			}
		}

		for (Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (kids == null) continue;
			for (Widget k : kids)
			{
				expandIn(k, offer, depth - 1);
			}
		}
	}

	private static long[] coinsCandidates(GrandExchangeOffer o)
	{
		return new long[]{o.getPrice(), (long) o.getPrice() * o.getTotalQuantity(), o.getSpent()};
	}

	private static long[] quantityCandidates(GrandExchangeOffer o)
	{
		return new long[]{o.getTotalQuantity(), o.getQuantitySold()};
	}

	/**
	 * Replace each condensed number in {@code raw} with the first candidate that rounds to it.
	 * Text mentioning coins/gp is matched against coin values, anything else against quantities.
	 * Tags are left untouched.
	 */
	static String expand(String raw, long[] coins, long[] quantities)
	{
		String plain = Text.removeTags(raw).toLowerCase(Locale.ROOT);
		long[] candidates = plain.contains("coin") || plain.contains("gp") ? coins : quantities;

		return TaggedText.replace(raw, CONDENSED, (m, after) ->
		{
			Long exact = match(m.group(1), m.group(2), candidates);
			return exact != null ? QuantityFormatter.formatNumber(exact) : m.group();
		});
	}

	/**
	 * The exact value the displayed number came from, whether the game rounded or truncated it.
	 * Null if no candidate fits, or if different candidates fit and it's unclear which is shown.
	 */
	static Long match(String number, String suffix, long[] candidates)
	{
		long mult;
		switch (suffix.toLowerCase(Locale.ROOT))
		{
			case "k": mult = 1_000L; break;
			case "m": mult = 1_000_000L; break;
			default:  mult = 1_000_000_000L; break;
		}
		int dot = number.indexOf('.');
		int decimals = dot < 0 ? 0 : number.length() - dot - 1;
		double shown = Double.parseDouble(number) * mult;
		double step = mult / Math.pow(10, decimals);

		Long found = null;
		for (long c : candidates)
		{
			if (c <= 0 || c < shown - step / 2 || c >= shown + step) continue;
			if (found != null && found != c) return null;
			found = c;
		}
		return found;
	}
}
