package com.flippingmasterminds;

import net.runelite.client.config.ConfigManager;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class BuyLimitTracker
{
    private static final long FOUR_HOURS_MS = 4 * 60 * 60 * 1000L;

    private final ConfigManager configManager;
    private final Map<Integer, BuyRecord> records = new HashMap<>();

    public BuyLimitTracker(ConfigManager configManager)
    {
        this.configManager = configManager;
        load();
    }

    /**
     * Record a buy of a specific quantity for an item.
     * Starts a new 4-hour window if the previous one has expired.
     */
    public synchronized void recordBuy(int itemId, int quantity)
    {
        BuyRecord record = records.get(itemId);

        if (record == null || record.isExpired())
        {
            record = new BuyRecord(System.currentTimeMillis(), quantity);
            records.put(itemId, record);
        }
        else
        {
            record.addQuantity(quantity);
        }

        save();
    }



    /**
     * Returns the timestamp (ms) of the first buy in the current 4-hour window.
     * Returns 0 if none exists.
     */
    public synchronized Long getBuyTimestamp(int itemId)
    {
        BuyRecord record = records.get(itemId);
        if (record != null && !record.isExpired())
        {
            return record.getFirstBuyTimestamp();
        }
        return 0L;
    }

    /**
     * Returns total quantity bought in current active window.
     */
    public synchronized int getQuantityBoughtInWindow(int itemId)
    {
        BuyRecord record = records.get(itemId);
        if (record != null && !record.isExpired())
        {
            return record.getQuantityBought();
        }
        return 0;
    }

    /**
     * Returns a map of all non-expired tracked items and their buy data.
     * The key is the item ID, and the value is another map containing buy details.
     */
    public synchronized Map<Integer, Map<String, Object>> getAllTracked()
    {
        Map<Integer, Map<String, Object>> trackedData = new HashMap<>();
        for (Map.Entry<Integer, BuyRecord> entry : records.entrySet())
        {
            // Only include non-expired records in the payload
            if (!entry.getValue().isExpired())
            {
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("firstBuyTimestamp", entry.getValue().getFirstBuyTimestamp());
                itemData.put("quantityBought", entry.getValue().getQuantityBought());
                trackedData.put(entry.getKey(), itemData);
            }
        }
        return trackedData;
    }

    /**
     * Optional: remove expired records periodically.
     */
    public synchronized void cleanupExpired()
    {
        Iterator<Map.Entry<Integer, BuyRecord>> iterator = records.entrySet().iterator();
        while (iterator.hasNext())
        {
            Map.Entry<Integer, BuyRecord> entry = iterator.next();
            if (entry.getValue().isExpired())
            {
                iterator.remove();
            }
        }
        save();
    }

    /**
     * Load records from config if you want persistence between sessions.
     * For now, this can be a no-op.
     */
    private void load()
    {
        // optional: implement persistence later
    }

    /**
     * Save records to config for persistence.
     */
    private void save()
    {
        // optional: implement persistence later
    }

    // =========================
    // Inner BuyRecord Class
    // =========================
    private static class BuyRecord
    {
        private final long firstBuyTimestamp;
        private int quantityBought;

        public BuyRecord(long firstBuyTimestamp, int quantityBought)
        {
            this.firstBuyTimestamp = firstBuyTimestamp;
            this.quantityBought = quantityBought;
        }

        public long getFirstBuyTimestamp()
        {
            return firstBuyTimestamp;
        }

        public int getQuantityBought()
        {
            return quantityBought;
        }

        public void addQuantity(int quantity)
        {
            this.quantityBought += quantity;
        }

        public boolean isExpired()
        {
            return System.currentTimeMillis() - firstBuyTimestamp > FOUR_HOURS_MS;
        }
    }
}
