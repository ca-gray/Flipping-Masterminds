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
     * Automatically removes expired records to prevent memory leaks.
     */
    public synchronized Map<Integer, Map<String, Object>> getAllTracked()
    {
        Map<Integer, Map<String, Object>> trackedData = new HashMap<>();

        // Use an iterator to safely remove elements while looping
        Iterator<Map.Entry<Integer, BuyRecord>> iterator = records.entrySet().iterator();
        boolean removedAny = false;

        while (iterator.hasNext())
        {
            Map.Entry<Integer, BuyRecord> entry = iterator.next();
            BuyRecord record = entry.getValue();

            if (record.isExpired())
            {
                // Remove the expired entry from the map to free memory
                iterator.remove();
                removedAny = true;
            }
            else
            {
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("firstBuyTimestamp", record.getFirstBuyTimestamp());
                itemData.put("quantityBought", record.getQuantityBought());
                trackedData.put(entry.getKey(), itemData);
            }
        }

        if (removedAny)
        {
            save();
        }

        return trackedData;
    }

    /**
     * Load records from config if you want persistence between sessions.
     * For now, this is a no-op placeholder.
     */
    private void load()
    {
        // Placeholder for future persistence logic
    }

    /**
     * Save records to config for persistence.
     * For now, this is a no-op placeholder.
     */
    private void save()
    {
        // Placeholder for future persistence logic
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