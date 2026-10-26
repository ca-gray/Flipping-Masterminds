package com.flippingmasterminds;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

public class BuyLimitPanel extends JPanel
{
    private BuyLimitTracker buyLimitTracker;
    private Map<Integer, ItemMeta> meta;

    private JPanel listPanel;
    private final Timer refreshTimer;

    private final ConcurrentMap<Integer, ImageIcon> imageCache;
    private final Set<Integer> loadingSet;
    private final ItemManager itemManager;
    private final ImageIcon placeholderIcon;

    private static final int ICON_SIZE = 32;
    private static final long FOUR_HOURS_MS = 4 * 60 * 60 * 1000L;

    public BuyLimitPanel(ConcurrentMap<Integer, ImageIcon> imageCache,
                         Set<Integer> loadingSet,
                         ItemManager itemManager,
                         ImageIcon placeholderIcon)
    {
        this.imageCache      = imageCache;
        this.loadingSet      = loadingSet;
        this.itemManager     = itemManager;
        this.placeholderIcon = placeholderIcon;

        setLayout(new BorderLayout());

        // Header
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JLabel title = new JLabel("⏱ Buy Limit Cooldowns");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(title);

        JLabel subtitle = new JLabel("Active 4-hour buy limit windows");
        subtitle.setFont(FontManager.getRunescapeSmallFont());
        subtitle.setForeground(Color.GRAY);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.add(subtitle);
        header.add(Box.createVerticalStrut(6));

        viewDropdown = new JComboBox<>(new ViewOption[]{CURRENT, ALL});
        viewDropdown.setToolTipText("Choose which account's buy limits to show");
        viewDropdown.setAlignmentX(Component.LEFT_ALIGNMENT);
        viewDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        viewDropdown.addActionListener(e ->
        {
            if (!updatingOptions) rebuildList();
        });
        header.add(viewDropdown);
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
        pendingIcons.clear();

        if (buyLimitTracker == null)
        {
            addEmptyMessage("Tracker not ready.");
            listPanel.revalidate();
            listPanel.repaint();
            return;
        }

        List<BuyLimitTracker.AccountLimits> accounts = buyLimitTracker.getAllAccounts();
        updateViewOptions(accounts);
        ViewOption view = (ViewOption) viewDropdown.getSelectedItem();
        if (view == null) view = CURRENT;

        if (view == ALL)
        {
            boolean any = false;
            for (BuyLimitTracker.AccountLimits a : accounts)
            {
                if (a.limits.isEmpty()) continue;
                addAccountHeader(a.name + (a.current ? "  (this account)" : ""));
                addLimitRows(a.limits);
                any = true;
            }
            if (!any) addEmptyMessage("No active buy limits on any account.");
        }
        else
        {
            Map<Integer, Map<String, Object>> tracked = null;
            if (view == CURRENT)
            {
                tracked = buyLimitTracker.getAllTracked();
            }
            else
            {
                for (BuyLimitTracker.AccountLimits a : accounts)
                {
                    if (a.profileKey.equals(view.profileKey)) tracked = a.limits;
                }
            }

            if (tracked == null || tracked.isEmpty())
            {
                addEmptyMessage(view == CURRENT
                        ? "No active buy limits. Buy items on the GE to start tracking."
                        : "No active buy limits on this account.");
            }
            else
            {
                addLimitRows(tracked);
            }
        }

        listPanel.revalidate();
        listPanel.repaint();
    }

    /** Rows for one account, soonest reset first. */
    private void addLimitRows(Map<Integer, Map<String, Object>> tracked)
    {
        long now = System.currentTimeMillis();
        List<long[]> active = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Object>> entry : tracked.entrySet())
        {
            long firstBuy = (long) entry.getValue().get("firstBuyTimestamp");
            int  qty      = (int)  entry.getValue().get("quantityBought");
            long msLeft   = firstBuy + FOUR_HOURS_MS - now;
            if (msLeft > 0) active.add(new long[]{entry.getKey(), qty, msLeft});
        }
        active.sort((a, b) -> Long.compare(a[2], b[2]));

        for (long[] l : active)
        {
            listPanel.add(makeLimitRow((int) l[0], (int) l[1], l[2]));
            listPanel.add(Box.createVerticalStrut(4));
        }
        if (active.isEmpty()) addEmptyMessage("All buy limits have reset.");
    }

    private void addAccountHeader(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeBoldFont());
        label.setForeground(ColorScheme.BRAND_ORANGE);
        label.setBorder(BorderFactory.createEmptyBorder(8, 4, 4, 4));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(label, BorderLayout.WEST);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        listPanel.add(wrap);
    }

    // ── Account dropdown ─────────────────────────────────────────────────────

    private static class ViewOption
    {
        final String label;
        /** Profile key for a specific account; null for the fixed options. */
        final String profileKey;

        ViewOption(String label, String profileKey)
        {
            this.label      = label;
            this.profileKey = profileKey;
        }

        @Override
        public String toString()
        {
            return label;
        }
    }

    private static final ViewOption CURRENT = new ViewOption("Current account", null);
    private static final ViewOption ALL     = new ViewOption("All accounts", null);

    private final JComboBox<ViewOption> viewDropdown;
    private boolean updatingOptions;
    private List<String> optionSignature = new ArrayList<>();

    /** Keep one entry per known account, preserving the current selection. */
    private void updateViewOptions(List<BuyLimitTracker.AccountLimits> accounts)
    {
        List<String> signature = new ArrayList<>();
        for (BuyLimitTracker.AccountLimits a : accounts)
        {
            signature.add(a.profileKey + "|" + a.name);
        }
        if (signature.equals(optionSignature)) return;
        optionSignature = signature;

        ViewOption selected = (ViewOption) viewDropdown.getSelectedItem();
        updatingOptions = true;
        try
        {
            viewDropdown.removeAllItems();
            viewDropdown.addItem(CURRENT);
            viewDropdown.addItem(ALL);
            ViewOption reselect = selected == ALL ? ALL : CURRENT;
            for (BuyLimitTracker.AccountLimits a : accounts)
            {
                ViewOption opt = new ViewOption(a.name, a.profileKey);
                viewDropdown.addItem(opt);
                if (selected != null && a.profileKey.equals(selected.profileKey)) reselect = opt;
            }
            viewDropdown.setSelectedItem(reselect);
        }
        finally
        {
            updatingOptions = false;
        }
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
                g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(ColorScheme.DARK_GRAY_COLOR);
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
        if (cached == null)
        {
            pendingIcons.watch(itemId, iconLbl);
            scheduleIconLoad(itemId);
        }
        row.add(iconLbl, BorderLayout.WEST);

        // Center text + progress bar
        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setOpaque(false);

        String shortName = itemName.length() > 22 ? itemName.substring(0, 22) + "..." : itemName;
        JLabel nameLabel = new JLabel(shortName);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(FontManager.getRunescapeBoldFont());
        nameLabel.setToolTipText(itemName);
        center.add(nameLabel);

        JLabel infoLabel = new JLabel(qty + " bought  ·  resets in " + timeStr);
        infoLabel.setFont(FontManager.getRunescapeSmallFont());
        infoLabel.setForeground(new Color(150, 160, 190));
        center.add(infoLabel);

        // Progress bar showing time elapsed
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(progressPct);
        bar.setStringPainted(false);
        bar.setPreferredSize(new Dimension(0, 6));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        bar.setForeground(new Color(80, 120, 200));
        bar.setBackground(ColorScheme.DARK_GRAY_COLOR);
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
        // HTML with a fixed width lets longer messages wrap instead of running off the panel.
        JLabel emptyLabel = new JLabel("<html><div style='text-align:center;width:150px'>" + text + "</div></html>");
        emptyLabel.setForeground(Color.GRAY);
        empty.add(emptyLabel);
        listPanel.add(empty);
    }

    private final PendingIcons pendingIcons = new PendingIcons();

    private void scheduleIconLoad(int itemId)
    {
        FlippingMastermindsPanel.loadIconAsync(itemId, ICON_SIZE,
                imageCache, loadingSet, itemManager, () -> pendingIcons.apply(imageCache));
    }

}
