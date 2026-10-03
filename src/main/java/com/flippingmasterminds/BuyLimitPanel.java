package com.flippingmasterminds;

import javax.swing.*;
import java.awt.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

public class BuyLimitPanel extends JPanel
{
    private BuyLimitTracker buyLimitTracker;
    private Map<Integer, ItemMeta> meta;

    private JPanel listPanel;
    private final Timer refreshTimer;

    private final ConcurrentMap<Integer, ImageIcon> imageCache;
    private final Set<Integer> loadingSet;
    private final ExecutorService imageLoader;
    private final ImageIcon placeholderIcon;

    private static final int ICON_SIZE = 32;
    private static final long FOUR_HOURS_MS = 4 * 60 * 60 * 1000L;

    public BuyLimitPanel(ConcurrentMap<Integer, ImageIcon> imageCache,
                         Set<Integer> loadingSet,
                         ExecutorService imageLoader,
                         ImageIcon placeholderIcon)
    {
        this.imageCache      = imageCache;
        this.loadingSet      = loadingSet;
        this.imageLoader     = imageLoader;
        this.placeholderIcon = placeholderIcon;

        setLayout(new BorderLayout());

        // Header
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        JLabel title = new JLabel("⏱ Buy Limit Cooldowns");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        header.add(title);

        JLabel subtitle = new JLabel("Active 4-hour GE buy limit windows");
        subtitle.setFont(subtitle.getFont().deriveFont(10f));
        subtitle.setForeground(Color.GRAY);
        header.add(subtitle);
        add(header, BorderLayout.NORTH);

        // Scrollable list
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(listPanel, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(wrapper);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JScrollBar vsb = scroll.getVerticalScrollBar();
        vsb.setPreferredSize(new Dimension(8, 0));
        vsb.setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        // Refresh every 30 seconds for accurate countdowns
        refreshTimer = new Timer(30_000, e -> rebuildList());
        refreshTimer.start();
    }

    public void stopTimer()
    {
        refreshTimer.stop();
    }

    public void setBuyLimitTracker(BuyLimitTracker tracker)
    {
        this.buyLimitTracker = tracker;
        rebuildList();
    }

    public void updateMeta(Map<Integer, ItemMeta> meta)
    {
        this.meta = meta;
        rebuildList();
    }

    void rebuildList()
    {
        listPanel.removeAll();

        if (buyLimitTracker == null)
        {
            addEmptyMessage("Tracker not ready.");
            return;
        }

        Map<Integer, Map<String, Object>> tracked = buyLimitTracker.getAllTracked();
        if (tracked.isEmpty())
        {
            addEmptyMessage("No active buy limits. Buy items on the GE to start tracking.");
            listPanel.revalidate();
            listPanel.repaint();
            return;
        }

        long now = System.currentTimeMillis();

        for (Map.Entry<Integer, Map<String, Object>> entry : tracked.entrySet())
        {
            int itemId  = entry.getKey();
            long firstBuy = (long) entry.getValue().get("firstBuyTimestamp");
            int  qty      = (int)  entry.getValue().get("quantityBought");

            long resetAt = firstBuy + FOUR_HOURS_MS;
            long msLeft  = resetAt - now;
            if (msLeft <= 0) continue;

            listPanel.add(makeLimitRow(itemId, qty, msLeft));
            listPanel.add(Box.createVerticalStrut(4));
        }

        if (listPanel.getComponentCount() == 0)
        {
            addEmptyMessage("All buy limits have reset.");
        }

        listPanel.revalidate();
        listPanel.repaint();
    }

    private JPanel makeLimitRow(int itemId, int qty, long msLeft)
    {
        long totalSeconds = msLeft / 1000;
        long hours   = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        String timeStr = String.format("%dh %02dm %02ds", hours, minutes, seconds);

        // Progress bar value (0-100, how much time has passed)
        double progress = 1.0 - ((double) msLeft / FOUR_HOURS_MS);
        int progressPct = Math.min(100, Math.max(0, (int)(progress * 100)));

        String itemName = "Item " + itemId;
        if (meta != null && meta.containsKey(itemId))
        {
            itemName = meta.get(itemId).name;
        }

        JPanel row = new JPanel(new BorderLayout(8, 0))
        {
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(30, 32, 42));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(new Color(44, 47, 58));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

        // Icon
        JLabel iconLbl = new JLabel();
        ImageIcon cached = imageCache.get(itemId);
        iconLbl.setIcon(cached != null ? cached : placeholderIcon);
        if (cached == null) scheduleIconLoad(itemId);
        row.add(iconLbl, BorderLayout.WEST);

        // Center text + progress bar
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        String shortName = itemName.length() > 22 ? itemName.substring(0, 22) + "..." : itemName;
        JLabel nameLabel = new JLabel(shortName);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setToolTipText(itemName);
        center.add(nameLabel);

        JLabel infoLabel = new JLabel(qty + " bought  ·  resets in " + timeStr);
        infoLabel.setFont(infoLabel.getFont().deriveFont(10f));
        infoLabel.setForeground(new Color(150, 160, 190));
        center.add(infoLabel);

        // Progress bar showing time elapsed
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(progressPct);
        bar.setStringPainted(false);
        bar.setPreferredSize(new Dimension(0, 6));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        bar.setForeground(new Color(80, 120, 200));
        bar.setBackground(new Color(45, 45, 55));
        bar.setBorderPainted(false);
        center.add(Box.createVerticalStrut(3));
        center.add(bar);

        row.add(center, BorderLayout.CENTER);

        return row;
    }

    private void addEmptyMessage(String text)
    {
        JPanel empty = new JPanel(new GridBagLayout());
        empty.setBorder(BorderFactory.createEmptyBorder(30, 10, 30, 10));
        JLabel emptyLabel = new JLabel(text);
        emptyLabel.setForeground(Color.GRAY);
        empty.add(emptyLabel);
        listPanel.add(empty);
    }

    private void scheduleIconLoad(int itemId)
    {
        if (meta == null) return;
        ItemMeta im = meta.get(itemId);
        if (im == null) return;
        FlippingMastermindsPanel.loadIconAsync(itemId, im.iconUrl, ICON_SIZE,
                imageCache, loadingSet, imageLoader, this::rebuildList);
    }
}
