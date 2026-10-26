package com.flippingmasterminds;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Labels showing a placeholder until their item icon has loaded. When icons arrive they are
 * set on the waiting labels in place, instead of rebuilding the whole list. EDT only.
 */
final class PendingIcons
{
	private final Map<Integer, List<JLabel>> waiting = new HashMap<>();

	void watch(int itemId, JLabel label)
	{
		waiting.computeIfAbsent(itemId, k -> new ArrayList<>()).add(label);
	}

	/** Set every icon that has loaded since, and stop watching those labels. */
	void apply(Map<Integer, ImageIcon> cache)
	{
		waiting.entrySet().removeIf(e ->
		{
			ImageIcon icon = cache.get(e.getKey());
			if (icon == null) return false;
			for (JLabel l : e.getValue()) l.setIcon(icon);
			return true;
		});
	}

	void clear()
	{
		waiting.clear();
	}
}
