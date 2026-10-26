package com.flippingmasterminds;

/**
 * One completed trade as it appears in the in-game GE history list.
 */
class GeHistoryEntry
{
	boolean buy;
	int itemId;
	int quantity;
	/** Total coins for the trade. */
	long total;
	/** Coins per item, as shown in the "= X each" line. */
	long each;
	/** When the trade was collected, in epoch millis; 0 if unknown (e.g. collected outside RuneLite). */
	long timestamp;

	GeHistoryEntry()
	{
	}

	GeHistoryEntry(boolean buy, int itemId, int quantity, long total, long each, long timestamp)
	{
		this.buy       = buy;
		this.itemId    = itemId;
		this.quantity  = quantity;
		this.total     = total;
		this.each      = each;
		this.timestamp = timestamp;
	}

	/** Match ignoring price; live sale totals can differ from the game's after tax. */
	boolean sameItemAndSide(GeHistoryEntry o)
	{
		return o != null && buy == o.buy && itemId == o.itemId && quantity == o.quantity;
	}

	/** Copy with the given timestamp. */
	GeHistoryEntry withTime(long time)
	{
		return new GeHistoryEntry(buy, itemId, quantity, total, each, time);
	}

	/** Whether two entries describe the same row in the native history list. */
	boolean sameTrade(GeHistoryEntry o)
	{
		return o != null
				&& buy == o.buy
				&& itemId == o.itemId
				&& quantity == o.quantity
				&& total == o.total;
	}

	@Override
	public String toString()
	{
		return (buy ? "Bought " : "Sold ") + quantity + "x " + itemId + " for " + total;
	}
}
