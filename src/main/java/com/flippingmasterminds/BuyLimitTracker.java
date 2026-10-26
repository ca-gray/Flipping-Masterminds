package com.flippingmasterminds;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfile;
import net.runelite.client.config.RuneScapeProfileType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tracks the GE's 4-hour buy limit windows, per account (RS profile).
 * A window starts at an item's first buy and resets four hours later.
 * Thread-safe: called from the client thread, the scheduler and the Swing thread.
 */
public class BuyLimitTracker
{
    private static final long FOUR_HOURS_MS = 4 * 60 * 60 * 1000L;

    private final ConfigManager configManager;
    private final Map<Integer, BuyRecord> records = new HashMap<>();

    public BuyLimitTracker(ConfigManager configManager)
    {
        this.configManager = configManager;
        reload();
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

    /** Reload for the current RuneScape profile (account). */
    public synchronized void reload()
    {
        records.clear();
        load();
        slots.clear();
        slots.putAll(parseSlots(configManager.getRSProfileKey() == null ? null
                : configManager.getRSProfileConfiguration(CONFIG_GROUP, SLOTS_KEY)));
    }

    // ── Offer slots ──────────────────────────────────────────────────────────
    // The last seen fill of each slot is saved per account, so fills that happen
    // while logged out are counted on the next login, and nothing is counted twice.

    private static final String SLOTS_KEY = "buyLimitSlots";
    /** Slot index to {itemId, quantitySold} last seen, for buy offers only. */
    private final Map<Integer, int[]> slots = new HashMap<>();

    /**
     * Count newly filled buy quantity for an offer update.
     *
     * @param settling true while the client replays offer states after login, when EMPTY
     *                 events are not real (they arrive before each slot's actual state)
     */
    public synchronized void onOfferUpdate(int slot, int itemId, int quantitySold, boolean buy,
                                           boolean empty, boolean settling)
    {
        if (empty || !buy)
        {
            // Only buy offers count towards limits. Login replays EMPTY before each slot's
            // real state, so an EMPTY then must not wipe the saved fill.
            if ((!empty || !settling) && slots.remove(slot) != null) saveSlots();
            return;
        }

        int[] last = slots.get(slot);
        // During the login replay with nothing saved for the slot (e.g. first run), we can't
        // tell when it filled, so just remember where it is now instead of counting it.
        int delta = last == null && settling ? 0 : newlyFilled(last, itemId, quantitySold);
        if (delta > 0) recordBuy(itemId, delta);

        if (last == null || last[0] != itemId || last[1] != quantitySold)
        {
            slots.put(slot, new int[]{itemId, quantitySold});
            saveSlots();
        }
    }

    /**
     * Quantity filled since the slot was last seen. It's the same offer if the item matches
     * and the fill hasn't gone backwards; otherwise it's a new offer and all of it is new.
     *
     * @param last {itemId, quantitySold} last seen for the slot, or null
     */
    static int newlyFilled(int[] last, int itemId, int quantitySold)
    {
        boolean sameOffer = last != null && last[0] == itemId && quantitySold >= last[1];
        return sameOffer ? quantitySold - last[1] : quantitySold;
    }

    static Map<Integer, int[]> parseSlots(String raw)
    {
        Map<Integer, int[]> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String entry : raw.split(";"))
        {
            String[] p = entry.split(",");
            if (p.length != 3) continue;
            try
            {
                out.put(Integer.parseInt(p[0]), new int[]{Integer.parseInt(p[1]), Integer.parseInt(p[2])});
            }
            catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private void saveSlots()
    {
        if (configManager.getRSProfileKey() == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, int[]> e : slots.entrySet())
        {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey()).append(',').append(e.getValue()[0]).append(',').append(e.getValue()[1]);
        }
        configManager.setRSProfileConfiguration(CONFIG_GROUP, SLOTS_KEY, sb.toString());
    }

    /**
     * Buy limits are per account, so they are stored in the RS profile config.
     * Older versions stored one shared list; it is moved to the first account that loads.
     */
    private void load()
    {
        if (configManager.getRSProfileKey() == null) return;

        String raw = configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY);
        boolean migrating = false;
        if (raw == null)
        {
            raw = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY);
            migrating = raw != null;
        }
        records.putAll(parse(raw));
        if (migrating)
        {
            save();
            configManager.unsetConfiguration(CONFIG_GROUP, CONFIG_KEY);
        }
    }

    /** Active (unexpired) records from a saved "itemId,firstBuyTimestamp,quantity;..." string. */
    private static Map<Integer, BuyRecord> parse(String raw)
    {
        Map<Integer, BuyRecord> parsed = new HashMap<>();
        if (raw == null || raw.isEmpty()) return parsed;

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
                    parsed.put(itemId, record);
                }
            }
            catch (NumberFormatException ignored) {}
        }
        return parsed;
    }

    // ── All accounts ─────────────────────────────────────────────────────────

    public static class AccountLimits
    {
        public final String profileKey;
        public final String name;
        public final boolean current;
        /** Same shape as {@link #getAllTracked()}. */
        public final Map<Integer, Map<String, Object>> limits;

        AccountLimits(String profileKey, String name, boolean current, Map<Integer, Map<String, Object>> limits)
        {
            this.profileKey = profileKey;
            this.name       = name;
            this.current    = current;
            this.limits     = limits;
        }
    }

    /** Active buy limits for every account RuneLite has a profile for, current account first. */
    public synchronized List<AccountLimits> getAllAccounts()
    {
        List<AccountLimits> out = new ArrayList<>();
        String currentKey = configManager.getRSProfileKey();
        boolean foundCurrent = false;

        for (RuneScapeProfile p : configManager.getRSProfiles())
        {
            boolean current = p.getKey().equals(currentKey);
            foundCurrent |= current;
            Map<Integer, Map<String, Object>> limits = current
                    ? getAllTracked()
                    : toTracked(parse(configManager.getConfiguration(CONFIG_GROUP, p.getKey(), CONFIG_KEY)));
            AccountLimits a = new AccountLimits(p.getKey(), profileName(p), current, limits);
            if (current) out.add(0, a);
            else out.add(a);
        }

        if (!foundCurrent && currentKey != null)
        {
            out.add(0, new AccountLimits(currentKey, "Current account", true, getAllTracked()));
        }
        return out;
    }

    private static String profileName(RuneScapeProfile p)
    {
        String name = p.getDisplayName() == null || p.getDisplayName().isEmpty()
                ? "Unknown account" : p.getDisplayName();
        if (p.getType() != null && p.getType() != RuneScapeProfileType.STANDARD)
        {
            String type = p.getType().name().toLowerCase().replace('_', ' ');
            name += " (" + Character.toUpperCase(type.charAt(0)) + type.substring(1) + ")";
        }
        return name;
    }

    private static Map<Integer, Map<String, Object>> toTracked(Map<Integer, BuyRecord> recs)
    {
        Map<Integer, Map<String, Object>> out = new HashMap<>();
        for (Map.Entry<Integer, BuyRecord> e : recs.entrySet())
        {
            Map<String, Object> itemData = new HashMap<>();
            itemData.put("firstBuyTimestamp", e.getValue().getFirstBuyTimestamp());
            itemData.put("quantityBought", e.getValue().getQuantityBought());
            out.put(e.getKey(), itemData);
        }
        return out;
    }

    private void save()
    {
        if (configManager.getRSProfileKey() == null) return;

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
        configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_KEY, sb.toString());
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