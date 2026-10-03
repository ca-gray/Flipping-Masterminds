package com.flippingmasterminds;

import net.runelite.client.config.ConfigManager;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class PriceAlertTracker
{
    private static final String CONFIG_GROUP       = "flippingmasterminds";
    private static final String CONFIG_KEY         = "priceAlerts";
    private static final String HISTORY_CONFIG_KEY = "priceAlertHistory";
    private static final long DEFAULT_EXPIRY_MS    = 24L * 60 * 60 * 1000;
    private static final int MAX_HISTORY_ENTRIES   = 50;

    private final ConfigManager configManager;
    private final List<PriceAlert> alerts = new CopyOnWriteArrayList<>();
    private final List<AlertHistoryEntry> history = new CopyOnWriteArrayList<>();

    public PriceAlertTracker(ConfigManager configManager)
    {
        this.configManager = configManager;
        load();
        loadHistory();
    }

    public List<PriceAlert> getAlerts()
    {
        return Collections.unmodifiableList(alerts);
    }

    public List<AlertHistoryEntry> getHistory()
    {
        return Collections.unmodifiableList(history);
    }

    public void addAlert(int itemId, String itemName, long targetPrice, Direction direction,
                         long expiryMs)
    {
        alerts.add(new PriceAlert(itemId, itemName, targetPrice, direction,
                false, false, System.currentTimeMillis(), expiryMs));
        save();
    }

    public void removeAlert(int index)
    {
        if (index >= 0 && index < alerts.size())
        {
            alerts.remove(index);
            save();
        }
    }

    public static class CheckResult
    {
        public final List<PriceAlert> newlyTriggered;
        public final List<PriceAlert> newlyStale;

        CheckResult(List<PriceAlert> triggered, List<PriceAlert> stale)
        {
            this.newlyTriggered = triggered;
            this.newlyStale     = stale;
        }
    }

    public CheckResult checkPrices(Map<Integer, Long> currentPrices)
    {
        List<PriceAlert> newlyTriggered = new ArrayList<>();
        List<PriceAlert> newlyStale     = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (PriceAlert alert : alerts)
        {
            if (alert.triggered || alert.stale) continue;

            Long currentPrice = currentPrices.get(alert.itemId);
            if (currentPrice == null) continue;

            boolean met = false;
            if (alert.direction == Direction.BELOW && currentPrice <= alert.targetPrice)
                met = true;
            else if (alert.direction == Direction.ABOVE && currentPrice >= alert.targetPrice)
                met = true;

            if (met)
            {
                alert.triggered = true;
                alert.triggeredPrice = currentPrice;
                newlyTriggered.add(alert);
                recordHistory(alert, now, currentPrice, "TRIGGERED");
            }
            else if (now - alert.createdAt >= alert.expiryMs)
            {
                alert.stale = true;
                newlyStale.add(alert);
                recordHistory(alert, now, 0, "EXPIRED");
            }
        }

        boolean changed = !newlyTriggered.isEmpty() || !newlyStale.isEmpty();
        if (changed) save();
        return new CheckResult(newlyTriggered, newlyStale);
    }

    public void clearTriggered()
    {
        alerts.removeIf(a -> a.triggered);
        save();
    }

    public void clearStale()
    {
        alerts.removeIf(a -> a.stale);
        save();
    }

    public void clearCompleted()
    {
        alerts.removeIf(a -> a.triggered || a.stale);
        save();
    }

    public void removeHistoryEntry(int index)
    {
        if (index >= 0 && index < history.size())
        {
            history.remove(index);
            saveHistory();
        }
    }

    public void clearHistory()
    {
        history.clear();
        saveHistory();
    }

    private void recordHistory(PriceAlert alert, long completedAt, long trigPrice, String outcome)
    {
        history.add(0, new AlertHistoryEntry(
                alert.itemId, alert.itemName, alert.targetPrice,
                alert.direction, alert.createdAt, completedAt, trigPrice, outcome));

        while (history.size() > MAX_HISTORY_ENTRIES)
        {
            history.remove(history.size() - 1);
        }
        saveHistory();
    }

    // ── Alert persistence ───────────────────────────────────────────────────

    private void load()
    {
        String raw = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY);
        if (raw == null || raw.isEmpty()) return;

        for (String entry : raw.split("\\|"))
        {
            String[] parts = entry.split(",", 10);
            if (parts.length < 5) continue;
            try
            {
                int       itemId      = Integer.parseInt(parts[0]);
                String    itemName    = parts[1].replace("&pipe;", "|").replace("&comma;", ",");
                long      target      = Long.parseLong(parts[2]);
                Direction dir         = Direction.valueOf(parts[3]);
                boolean   triggered   = Boolean.parseBoolean(parts[4]);
                long      trigPrice   = parts.length >= 6 ? Long.parseLong(parts[5]) : 0;
                long      created     = parts.length >= 7 ? Long.parseLong(parts[6]) : System.currentTimeMillis();
                boolean   stale       = parts.length >= 8 && Boolean.parseBoolean(parts[7]);
                long      expiryMs    = parts.length >= 9 ? Long.parseLong(parts[8]) : DEFAULT_EXPIRY_MS;

                PriceAlert alert = new PriceAlert(itemId, itemName, target, dir, triggered, stale, created, expiryMs);
                alert.triggeredPrice = trigPrice;
                alerts.add(alert);
            }
            catch (Exception ignored) {}
        }
    }

    private void save()
    {
        StringBuilder sb = new StringBuilder();
        for (PriceAlert a : alerts)
        {
            if (sb.length() > 0) sb.append('|');
            String safeName = a.itemName.replace(",", "&comma;").replace("|", "&pipe;");
            sb.append(a.itemId).append(',')
              .append(safeName).append(',')
              .append(a.targetPrice).append(',')
              .append(a.direction.name()).append(',')
              .append(a.triggered).append(',')
              .append(a.triggeredPrice).append(',')
              .append(a.createdAt).append(',')
              .append(a.stale).append(',')
              .append(a.expiryMs);
        }
        configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY, sb.toString());
    }

    // ── History persistence ─────────────────────────────────────────────────

    private void loadHistory()
    {
        String raw = configManager.getConfiguration(CONFIG_GROUP, HISTORY_CONFIG_KEY);
        if (raw == null || raw.isEmpty()) return;

        for (String entry : raw.split("\\|"))
        {
            String[] parts = entry.split(",", 8);
            if (parts.length < 8) continue;
            try
            {
                int       itemId      = Integer.parseInt(parts[0]);
                String    itemName    = parts[1].replace("&pipe;", "|").replace("&comma;", ",");
                long      target      = Long.parseLong(parts[2]);
                Direction dir         = Direction.valueOf(parts[3]);
                long      created     = Long.parseLong(parts[4]);
                long      completed   = Long.parseLong(parts[5]);
                long      trigPrice   = Long.parseLong(parts[6]);
                String    outcome     = parts[7];

                history.add(new AlertHistoryEntry(itemId, itemName, target, dir,
                        created, completed, trigPrice, outcome));
            }
            catch (Exception ignored) {}
        }
    }

    private void saveHistory()
    {
        StringBuilder sb = new StringBuilder();
        for (AlertHistoryEntry h : history)
        {
            if (sb.length() > 0) sb.append('|');
            String safeName = h.itemName.replace(",", "&comma;").replace("|", "&pipe;");
            sb.append(h.itemId).append(',')
              .append(safeName).append(',')
              .append(h.targetPrice).append(',')
              .append(h.direction.name()).append(',')
              .append(h.createdAt).append(',')
              .append(h.completedAt).append(',')
              .append(h.triggeredPrice).append(',')
              .append(h.outcome);
        }
        configManager.setConfiguration(CONFIG_GROUP, HISTORY_CONFIG_KEY, sb.toString());
    }

    // ── Types ───────────────────────────────────────────────────────────────

    public enum Direction
    {
        BELOW("Falls to or below"),
        ABOVE("Rises to or above");

        public final String label;

        Direction(String label) { this.label = label; }

        @Override
        public String toString() { return label; }
    }

    public static class PriceAlert
    {
        public final int       itemId;
        public final String    itemName;
        public final long      targetPrice;
        public final Direction direction;
        public final long      expiryMs;
        public long            createdAt;
        public boolean         triggered;
        public boolean         stale;
        public long            triggeredPrice;

        PriceAlert(int itemId, String itemName, long targetPrice, Direction direction,
                   boolean triggered, boolean stale, long createdAt, long expiryMs)
        {
            this.itemId      = itemId;
            this.itemName    = itemName;
            this.targetPrice = targetPrice;
            this.direction   = direction;
            this.triggered   = triggered;
            this.stale       = stale;
            this.createdAt   = createdAt;
            this.expiryMs    = expiryMs;
        }
    }

    public static class AlertHistoryEntry
    {
        public final int       itemId;
        public final String    itemName;
        public final long      targetPrice;
        public final Direction direction;
        public final long      createdAt;
        public final long      completedAt;
        public final long      triggeredPrice;
        public final String    outcome;

        AlertHistoryEntry(int itemId, String itemName, long targetPrice, Direction direction,
                          long createdAt, long completedAt, long triggeredPrice, String outcome)
        {
            this.itemId         = itemId;
            this.itemName       = itemName;
            this.targetPrice    = targetPrice;
            this.direction      = direction;
            this.createdAt      = createdAt;
            this.completedAt    = completedAt;
            this.triggeredPrice = triggeredPrice;
            this.outcome        = outcome;
        }
    }
}
