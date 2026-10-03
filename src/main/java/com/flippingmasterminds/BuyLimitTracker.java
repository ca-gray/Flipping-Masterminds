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

    public synchronized Long getBuyTimestamp(int itemId)
    {
        BuyRecord record = records.get(itemId);
        if (record != null && !record.isExpired())
        {
            return record.getFirstBuyTimestamp();
        }
        return 0L;
    }

    public synchronized int getQuantityBoughtInWindow(int itemId)
    {
        BuyRecord record = records.get(itemId);
        if (record != null && !record.isExpired())
        {
            return record.getQuantityBought();
        }
        return 0;
    }

    public synchronized Map<Integer, Map<String, Object>> getAllTracked()
    {
        Map<Integer, Map<String, Object>> trackedData = new HashMap<>();

        Iterator<Map.Entry<Integer, BuyRecord>> iterator = records.entrySet().iterator();
        boolean removedAny = false;

        while (iterator.hasNext())
        {
            Map.Entry<Integer, BuyRecord> entry = iterator.next();
            BuyRecord record = entry.getValue();

            if (record.isExpired())
            {
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

    private static final String CONFIG_GROUP = "flippingmasterminds";
    private static final String CONFIG_KEY   = "buyLimitRecords";

    private void load()
    {
        String raw = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY);
        if (raw == null || raw.isEmpty()) return;

        for (String entry : raw.split(";"))
        {
            String[] parts = entry.split(",");
            if (parts.length != 3) continue;
            try
            {
                int  itemId    = Integer.parseInt(parts[0]);
                long timestamp = Long.parseLong(parts[1]);
                int  qty       = Integer.parseInt(parts[2]);
                BuyRecord record = new BuyRecord(timestamp, qty);
                if (!record.isExpired())
                {
                    records.put(itemId, record);
                }
            }
            catch (NumberFormatException ignored) {}
        }
    }

    private void save()
    {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, BuyRecord> entry : records.entrySet())
        {
            BuyRecord r = entry.getValue();
            if (r.isExpired()) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(entry.getKey()).append(',')
              .append(r.getFirstBuyTimestamp()).append(',')
              .append(r.getQuantityBought());
        }
        configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY, sb.toString());
    }

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