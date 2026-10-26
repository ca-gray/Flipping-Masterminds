package com.flippingmasterminds;

import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

public class FlippingMastermindsPanel extends PluginPanel
{
    // ── Filter controls ───────────────────────────────────────────────────────
    private JComboBox<String> timeRangeDropdown;
    private JComboBox<String> performanceDropdown;
    private JTextField minPriceField;
    private JTextField maxPriceField;
    private JTextField minVolumeField;

    // ── Display toggles (driven by config, not checkboxes in the panel) ───────
    private boolean showVolume = true;
    private boolean showPrices = true;

    // ── Header widgets ────────────────────────────────────────────────────────
    private JButton refreshButton;
    private JLabel  lastUpdatedLabel;

    // ── Scrollable item list ──────────────────────────────────────────────────
    private JScrollPane viewportScroll;

    // ── Pagination bar (fixed at bottom of panel) ─────────────────────────────
    private JPanel paginationPanel;
    private JLabel pageInfoLabel;

    // ── Pagination state ──────────────────────────────────────────────────────
    private List<JPanel> resultPages = new ArrayList<>();
    private int currentPage = 0;

    // ── Data ──────────────────────────────────────────────────────────────────
    private Map<Integer, Long> baseline, day, week, month, year;
    private Map<Integer, ItemMeta> meta;
    private Map<Integer, Long> dayVolume    = Collections.emptyMap();
    private Map<Integer, Long> weekVolume   = Collections.emptyMap();
    private Map<Integer, Long> monthVolume  = Collections.emptyMap();
    private Map<Integer, Long> yearVolume   = Collections.emptyMap();

    // ── Image loading ─────────────────────────────────────────────────────────
    private final ConcurrentMap<Integer, ImageIcon> imageCache = new ConcurrentHashMap<>();
    private final Set<Integer> loadingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ItemManager itemManager;
    private final ImageIcon placeholderIcon;

    // ── Tab switching ────────────────────────────────────────────────────────
    private JPanel tabBar;
    private TabButton marketTabBtn;
    private TabButton alertsTabBtn;
    private TabButton buyLimitTabBtn;
    private CardLayout cardLayout;
    private JPanel cardPanel;
    private PriceAlertPanel alertPanel;
    private BuyLimitPanel buyLimitPanel;

    // ── Plugin callback ───────────────────────────────────────────────────────
    private Runnable onRefreshRequested;
    private Runnable onActivated;
    private volatile boolean panelOpen;

    // ── Tab state ────────────────────────────────────────────────────────────
    private String activeTab = "market";

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int ITEMS_PER_PAGE = 20;
    private static final int ICON_SIZE      = 32;
    private static final int TAB_ICON_SIZE  = 22;
    private static final int NAME_LIMIT     = 20;
    private static final int MAX_PAGES      = 10;
    private static final int CARD_ARC       = 10;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int FILTER_DEBOUNCE_MS = 250;

    private javax.swing.Timer filterDebounce;

    // ── UI colours ───────────────────────────────────────────────────────────
    private static final Color BG_CARD       = ColorScheme.DARKER_GRAY_COLOR;
    private static final Color BORDER_SUBTLE = ColorScheme.DARK_GRAY_COLOR;
    private static final Color BTN_HOVER_BG  = ColorScheme.DARKER_GRAY_HOVER_COLOR;
    private static final Color BTN_PRESS_BG  = ColorScheme.MEDIUM_GRAY_COLOR;
    private static final int   BTN_ARC       = 6;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pass {@code false} to the PluginPanel super-constructor so RuneLite does
     * NOT wrap the entire panel in its own JScrollPane. This lets us control
     * the layout precisely: fixed header at top, scrollable item list in the
     * middle, fixed pagination bar at the bottom.
     */
    public FlippingMastermindsPanel(ItemManager itemManager)
    {
        super(false);

        this.itemManager = itemManager;
        placeholderIcon = makePlaceholderIcon(ICON_SIZE, ICON_SIZE);

        setLayout(new BorderLayout());

        // Top strip: social links + tab bar, always visible
        JPanel topStrip = new JPanel();
        topStrip.setLayout(new BoxLayout(topStrip, BoxLayout.Y_AXIS));
        topStrip.setOpaque(false);

        JPanel socialBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        socialBar.setOpaque(false);
        socialBar.add(createIconHoverButton("/discord_logo.png",
                "https://discord.gg/VnsS2PP4Vt", "Join our Discord!"));
        socialBar.add(createIconHoverButton("/github_logo.png",
                "https://github.com/ca-gray/Flipping-Masterminds", "View on GitHub!"));
        socialBar.add(createIconHoverButton("/oswiki_logo.png",
                "https://prices.runescape.wiki/osrs/", "View Wiki Prices!"));
        topStrip.add(socialBar);

        tabBar = new JPanel(new GridLayout(1, 3, 6, 0));
        tabBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        tabBar.setOpaque(false);
        marketTabBtn   = new TabButton(loadTabIcon("/market_logo.png"),   "Market");
        alertsTabBtn   = new TabButton(loadTabIcon("/tracking_logo.png"), "Alerts");
        buyLimitTabBtn = new TabButton(loadTabIcon("/buylimit_logo.png"), "Buy Limits");
        marketTabBtn.setTabActive(true);
        marketTabBtn.addActionListener(e -> switchTab("market"));
        alertsTabBtn.addActionListener(e -> switchTab("alerts"));
        buyLimitTabBtn.addActionListener(e -> switchTab("buylimits"));
        tabBar.add(marketTabBtn);
        tabBar.add(alertsTabBtn);
        tabBar.add(buyLimitTabBtn);
        topStrip.add(tabBar);

        add(topStrip, BorderLayout.NORTH);

        // Market view
        JPanel marketView = new JPanel(new BorderLayout());
        marketView.add(createHeaderPanel(),     BorderLayout.NORTH);
        marketView.add(createBodyPanel(),       BorderLayout.CENTER);
        marketView.add(createPaginationPanel(), BorderLayout.SOUTH);

        // Alerts view
        alertPanel = new PriceAlertPanel(imageCache, loadingSet, itemManager, placeholderIcon);

        // Buy limits view
        buyLimitPanel = new BuyLimitPanel(imageCache, loadingSet, itemManager, placeholderIcon);

        // Card layout to switch between them
        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.add(marketView, "market");
        cardPanel.add(alertPanel, "alerts");
        cardPanel.add(buyLimitPanel, "buylimits");

        add(cardPanel, BorderLayout.CENTER);

        attachFilterListeners();
    }

    private ImageIcon loadTabIcon(String resourcePath)
    {
        try
        {
            URL res = getClass().getResource(resourcePath);
            if (res != null)
            {
                BufferedImage raw = ImageIO.read(res);
                Image scaled = raw.getScaledInstance(TAB_ICON_SIZE, TAB_ICON_SIZE, Image.SCALE_SMOOTH);
                return new ImageIcon(scaled);
            }
        }
        catch (IOException ignored) {}
        return null;
    }

    private void switchTab(String tab)
    {
        activeTab = tab;
        cardLayout.show(cardPanel, tab);
        marketTabBtn.setTabActive("market".equals(tab));
        alertsTabBtn.setTabActive("alerts".equals(tab));
        buyLimitTabBtn.setTabActive("buylimits".equals(tab));
        if ("alerts".equals(tab)) alertsTabBtn.setNotification(false);
        else if ("buylimits".equals(tab)) buyLimitTabBtn.setNotification(false);
    }

    public void notifyTab(String tab)
    {
        if ("alerts".equals(tab) && !"alerts".equals(activeTab))
        {
            alertsTabBtn.setNotification(true);
        }
        else if ("buylimits".equals(tab) && !"buylimits".equals(activeTab))
        {
            buyLimitTabBtn.setNotification(true);
        }
    }

    public PriceAlertPanel getAlertPanel()
    {
        return alertPanel;
    }

    public BuyLimitPanel getBuyLimitPanel()
    {
        return buyLimitPanel;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void refreshButtonReset()
    {
        refreshButton.setEnabled(true);
        refreshButton.setText("⟳ Refresh");
        lastUpdatedLabel.setText("Fetch failed");
        lastUpdatedLabel.setForeground(new Color(220, 50, 50));
    }

    public void setOnRefreshRequested(Runnable callback)
    {
        this.onRefreshRequested = callback;
    }

    /** Called when the panel is opened in the sidebar. */
    public void setOnActivated(Runnable callback)
    {
        this.onActivated = callback;
    }

    /** Whether the panel is currently open in the sidebar. */
    public boolean isPanelOpen()
    {
        return panelOpen;
    }

    @Override
    public void onActivate()
    {
        panelOpen = true;
        if (onActivated != null) onActivated.run();
    }

    @Override
    public void onDeactivate()
    {
        panelOpen = false;
    }

    public void applyConfig(boolean showVolume, boolean showPrices)
    {
        boolean changed = (this.showVolume != showVolume) || (this.showPrices != showPrices);
        this.showVolume = showVolume;
        this.showPrices = showPrices;
        if (changed && baseline != null && meta != null)
        {
            rebuildResults();
        }
    }

    // ── Panel builders ────────────────────────────────────────────────────────

    private JPanel createHeaderPanel()
    {
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));

        // Filter grid
        JPanel filterPanel = new JPanel(new GridBagLayout());
        filterPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        // Label constraint: tight, no horizontal grow
        GridBagConstraints lbl = new GridBagConstraints();
        lbl.anchor  = GridBagConstraints.WEST;
        lbl.insets  = new Insets(2, 2, 2, 4);
        lbl.gridx   = 0;
        lbl.gridy   = 0;
        lbl.fill    = GridBagConstraints.NONE;
        lbl.weightx = 0.0;

        // Field constraint: fills remaining width
        GridBagConstraints fld = new GridBagConstraints();
        fld.anchor  = GridBagConstraints.WEST;
        fld.insets  = new Insets(2, 0, 2, 2);
        fld.gridx   = 1;
        fld.gridy   = 0;
        fld.fill    = GridBagConstraints.HORIZONTAL;
        fld.weightx = 1.0;

        // Row 0 – Time Range
        filterPanel.add(new JLabel("Time Range:"), lbl);
        timeRangeDropdown = new JComboBox<>(new String[]{"Day", "Week", "Month", "Year"});
        filterPanel.add(timeRangeDropdown, fld);

        // Row 1 – Performance
        lbl.gridy++; fld.gridy++;
        filterPanel.add(new JLabel("Performance:"), lbl);
        performanceDropdown = new JComboBox<>(
                new String[]{"Top Performers", "Underperformers"});
        filterPanel.add(performanceDropdown, fld);

        // Row 2 – Min Price
        lbl.gridy++; fld.gridy++;
        filterPanel.add(new JLabel("Min Price:"), lbl);
        minPriceField = new JTextField("1");
        filterPanel.add(minPriceField, fld);

        // Row 3 – Max Price
        lbl.gridy++; fld.gridy++;
        filterPanel.add(new JLabel("Max Price:"), lbl);
        maxPriceField = new JTextField();
        maxPriceField.setToolTipText("Leave empty for no upper limit");
        filterPanel.add(maxPriceField, fld);

        // Row 4 – Min 24h volume
        lbl.gridy++; fld.gridy++;
        filterPanel.add(new JLabel("Min 24h Vol:"), lbl);
        minVolumeField = new JTextField("0");
        minVolumeField.setToolTipText("Minimum number of items traded in the last 24 hours");
        filterPanel.add(minVolumeField, fld);

        headerPanel.add(filterPanel);

        // Refresh row
        JPanel refreshRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));

        refreshButton = new JButton("⟳ Refresh");
        refreshButton.setToolTipText("Re-fetch latest GE price data");
        refreshButton.setFocusPainted(false);
        refreshButton.addActionListener(e -> {
            refreshButton.setEnabled(false);
            refreshButton.setText("Fetching…");
            if (onRefreshRequested != null) onRefreshRequested.run();
        });
        refreshRow.add(refreshButton);

        lastUpdatedLabel = new JLabel("Not yet loaded");
        lastUpdatedLabel.setForeground(Color.GRAY);
        lastUpdatedLabel.setFont(lastUpdatedLabel.getFont().deriveFont(10f));
        refreshRow.add(lastUpdatedLabel);

        headerPanel.add(refreshRow);
        return headerPanel;
    }

    /**
     * The CENTER region: a JScrollPane whose viewport holds the item list.
     * Only this region scrolls; the header (NORTH) and pagination bar (SOUTH)
     * stay fixed.
     */
    private JScrollPane createBodyPanel()
    {
        viewportScroll = new JScrollPane();
        viewportScroll.setBorder(null);
        viewportScroll.setBackground(getBackground());
        viewportScroll.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        JScrollBar vsb = viewportScroll.getVerticalScrollBar();
        vsb.setPreferredSize(new Dimension(8, 0));
        vsb.setUnitIncrement(16);

        return viewportScroll;
    }

    /**
     * Fixed pagination bar in the SOUTH region.
     * Always visible regardless of scroll position.
     */
    private JPanel createPaginationPanel()
    {
        paginationPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        paginationPanel.setBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.DARKER_GRAY_HOVER_COLOR));

        JButton prev = new JButton("◀");
        prev.setFocusPainted(false);
        prev.setToolTipText("Previous page");
        prev.addActionListener(e -> showPage(currentPage - 1));
        paginationPanel.add(prev);

        pageInfoLabel = new JLabel("Page 0 / 0");
        paginationPanel.add(pageInfoLabel);

        JButton next = new JButton("▶");
        next.setFocusPainted(false);
        next.setToolTipText("Next page");
        next.addActionListener(e -> showPage(currentPage + 1));
        paginationPanel.add(next);

        return paginationPanel;
    }

    // ── Filter listeners ──────────────────────────────────────────────────────

    private void attachFilterListeners()
    {
        timeRangeDropdown  .addActionListener(e -> refreshWithFilters());
        performanceDropdown.addActionListener(e -> refreshWithFilters());
        // Text fields wait for a pause in typing before rebuilding the list.
        filterDebounce = new javax.swing.Timer(FILTER_DEBOUNCE_MS, e -> refreshWithFilters());
        filterDebounce.setRepeats(false);
        addDocumentListener(minPriceField,  filterDebounce::restart);
        addDocumentListener(maxPriceField,  filterDebounce::restart);
        addDocumentListener(minVolumeField, filterDebounce::restart);
    }

    // ── Public data entry point ───────────────────────────────────────────────

    public void updateMovers(
            Map<Integer, Long> baseline,
            Map<Integer, Long> day,
            Map<Integer, Long> week,
            Map<Integer, Long> month,
            Map<Integer, Long> year,
            Map<Integer, ItemMeta> meta,
            Map<Integer, Long> dayVolume,
            Map<Integer, Long> weekVolume,
            Map<Integer, Long> monthVolume,
            Map<Integer, Long> yearVolume)
    {
        this.baseline    = baseline;
        this.day         = day;
        this.week        = week;
        this.month       = month;
        this.year        = year;
        this.meta        = meta;
        this.dayVolume   = dayVolume   != null ? dayVolume   : Collections.emptyMap();
        this.weekVolume  = weekVolume  != null ? weekVolume  : Collections.emptyMap();
        this.monthVolume = monthVolume != null ? monthVolume : Collections.emptyMap();
        this.yearVolume  = yearVolume  != null ? yearVolume  : Collections.emptyMap();

        refreshButton.setEnabled(true);
        refreshButton.setText("⟳ Refresh");
        lastUpdatedLabel.setText("Updated " + LocalTime.now().format(TIME_FMT));
        lastUpdatedLabel.setForeground(Color.GRAY);

        alertPanel.updateMeta(meta);
        alertPanel.updatePrices(baseline);
        buyLimitPanel.updateMeta(meta);

        rebuildResults();
    }

    // ── Building / filtering results ──────────────────────────────────────────

    private void refreshWithFilters()
    {
        if (baseline != null && meta != null) rebuildResults();
    }

    private void rebuildResults()
    {
        String timeRange = safeSelected(timeRangeDropdown,   "Day");
        String perf      = safeSelected(performanceDropdown, "Top Performers");
        long   min       = safeParseLong(minPriceField.getText(),  1L);
        long   max       = safeParseLong(maxPriceField.getText(),  Long.MAX_VALUE);
        long   minVol    = safeParseLong(minVolumeField.getText(), 0L);

        if (min > max) return;

        Map<Integer, Long> snapshot;
        Map<Integer, Long> volumeMap;
        switch (timeRange)
        {
            case "Week":  snapshot = week;  volumeMap = weekVolume;  break;
            case "Month": snapshot = month; volumeMap = monthVolume; break;
            case "Year":  snapshot = year;  volumeMap = yearVolume;  break;
            default:      snapshot = day;   volumeMap = dayVolume;   break;
        }
        if (snapshot  == null) snapshot  = Collections.emptyMap();
        if (volumeMap == null) volumeMap = Collections.emptyMap();

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : baseline.entrySet())
        {
            int  id        = e.getKey();
            long curPrice = e.getValue();
            long snapPrice  = snapshot.getOrDefault(id, -1L);

            if (curPrice <= 0 || snapPrice <= 0) continue;
            // Filter on current (baseline) price, NOT the historical snapshot price.
            // This keeps the item list consistent when switching time ranges —
            // only the % change column varies, never which items are shown.
            if (curPrice < min || curPrice > max) continue;

            long volume = volumeMap.getOrDefault(id, 0L);
            if (volume < minVol) continue;

            double changePct = ((double)(curPrice - snapPrice) / snapPrice) * 100.0;
            long   changeAbs = curPrice - snapPrice;

            if (perf.equals("Top Performers")  && !(changePct > 0.0)) continue;
            if (perf.equals("Underperformers") && !(changePct < 0.0)) continue;

            ItemMeta im = meta.get(id);
            if (im == null) continue;

            rows.add(new Row(id, im.name, truncateName(im.name),
                    changePct, changeAbs, volume, snapPrice, curPrice));
        }

        rows.sort((a, b) -> perf.equals("Top Performers")
                ? Double.compare(b.changePct, a.changePct)
                : Double.compare(a.changePct, b.changePct));

        List<JPanel> pages = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += ITEMS_PER_PAGE)
        {
            if (pages.size() >= MAX_PAGES) break;

            JPanel page = new JPanel(new GridLayout(0, 1, 4, 4));
            page.setBackground(getBackground());

            int end = Math.min(i + ITEMS_PER_PAGE, rows.size());
            for (int j = i; j < end; j++)
            {
                Row r = rows.get(j);
                page.add(makeRowPanel(r));
            }
            pages.add(page);
        }

        resultPages = pages;
        showPage(0);
    }

    // ── Row panel builder ─────────────────────────────────────────────────────

    private JPanel makeRowPanel(Row r)
    {
        JPanel rowPanel = new JPanel(new BorderLayout(8, 4))
        {
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG_CARD);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), CARD_ARC, CARD_ARC);
                g2.setColor(BORDER_SUBTLE);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, CARD_ARC, CARD_ARC);
                g2.dispose();
            }
        };
        rowPanel.setOpaque(false);
        rowPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        // Item icon – name attribute lets refreshVisibleIcons find this label
        JLabel iconLabel = new JLabel();
        iconLabel.setName(String.valueOf(r.id));
        ImageIcon cached = imageCache.get(r.id);
        iconLabel.setIcon(cached != null ? cached : placeholderIcon);
        rowPanel.add(iconLabel, BorderLayout.WEST);

        // Text stack
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        JLabel nameLabel = new JLabel(r.displayName);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setToolTipText(r.fullName);
        textPanel.add(nameLabel);

        String absText   = (r.changeAbs > 0 ? "+" : "") + formatGp(r.changeAbs);
        Color  changeClr = r.changeAbs >= 0 ? new Color(0, 192, 0) : new Color(220, 50, 50);
        JLabel changeLabel = new JLabel(String.format(Locale.ROOT, "%.2f%% (%s)", r.changePct, absText));
        changeLabel.setForeground(changeClr);
        textPanel.add(changeLabel);

        // Volume line – shown only when config toggle is on
        if (showVolume && r.volume > 0)
        {
            JLabel volLabel = new JLabel("24h vol: " + formatNumber(r.volume));
            volLabel.setForeground(new Color(140, 140, 180));
            volLabel.setFont(volLabel.getFont().deriveFont(10f));
            textPanel.add(volLabel);
        }

        // Historical → current price line – shown only when config toggle is on
        if (showPrices)
        {
            JLabel priceLabel = new JLabel(formatGp(r.snapPrice) + " → " + formatGp(r.curPrice));
            priceLabel.setForeground(new Color(180, 160, 100));
            priceLabel.setFont(priceLabel.getFont().deriveFont(10f));
            priceLabel.setToolTipText("Price at start of window → Current price");
            textPanel.add(priceLabel);
        }

        rowPanel.add(textPanel, BorderLayout.CENTER);
        rowPanel.add(createWikiButton(r.id), BorderLayout.EAST);

        return rowPanel;
    }

    // ── Animated buttons ──────────────────────────────────────────────────────

    /**
     * Shared button base: paints a rounded highlight behind the content on
     * hover (darker on press), making interactivity visually clear.
     */
    static class AnimatedButton extends JButton
    {
        private Color bgColor = null;

        void setBg(Color c) { bgColor = c; repaint(); }

        @Override
        protected void paintComponent(Graphics g)
        {
            if (bgColor != null)
            {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bgColor);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), BTN_ARC, BTN_ARC);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    static class TabButton extends JButton
    {
        private boolean tabActive = false;
        private boolean notification = false;
        private boolean hovered = false;

        private static final Color ACTIVE_BG       = ColorScheme.DARKER_GRAY_HOVER_COLOR;
        private static final Color HOVER_BG        = ColorScheme.DARK_GRAY_COLOR;
        private static final Color INACTIVE_BG     = ColorScheme.DARKER_GRAY_COLOR;
        private static final Color ACCENT           = new Color(90, 140, 220);
        private static final Color NOTIF_DOT       = new Color(240, 160, 40);
        private static final int TAB_ARC           = 10;

        TabButton(ImageIcon icon, String tooltip)
        {
            if (icon != null) setIcon(icon);
            setToolTipText(tooltip);
            setFocusPainted(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(0, 36));

            addMouseListener(new MouseAdapter()
            {
                @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hovered = false; repaint(); }
            });
        }

        void setTabActive(boolean active) { this.tabActive = active; repaint(); }
        void setNotification(boolean on)  { this.notification = on;  repaint(); }

        @Override
        protected void paintComponent(Graphics g)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();

            if (tabActive)
                g2.setColor(ACTIVE_BG);
            else if (hovered)
                g2.setColor(HOVER_BG);
            else
                g2.setColor(INACTIVE_BG);

            g2.fillRoundRect(0, 0, w, h, TAB_ARC, TAB_ARC);

            if (tabActive)
            {
                g2.setColor(ACCENT);
                int lineW = w / 2;
                g2.fillRoundRect((w - lineW) / 2, h - 3, lineW, 3, 2, 2);
            }

            g2.dispose();
            super.paintComponent(g);

            if (notification && !tabActive)
            {
                Graphics2D g3 = (Graphics2D) g.create();
                g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g3.setColor(NOTIF_DOT);
                int dotSize = 8;
                g3.fillOval(w - 14, 4, dotSize, dotSize);
                g3.dispose();
            }
        }
    }

    private AnimatedButton createWikiButton(int itemId)
    {
        AnimatedButton btn = new AnimatedButton();
        btn.setText("🌐");
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setOpaque(false);
        btn.setForeground(new Color(180, 180, 220));
        btn.setToolTipText("View on Wiki Prices");
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseEntered(MouseEvent e)  { btn.setBg(BTN_HOVER_BG); }
            @Override public void mouseExited(MouseEvent e)   { btn.setBg(null); }
            @Override public void mousePressed(MouseEvent e)  { btn.setBg(BTN_PRESS_BG); }
            @Override public void mouseReleased(MouseEvent e) { btn.setBg(BTN_HOVER_BG); }
        });
        btn.addActionListener(e -> LinkBrowser.browse(
                "https://prices.runescape.wiki/osrs/item/" + itemId));
        return btn;
    }

    private AnimatedButton createIconHoverButton(String resourcePath, String url, String tooltip)
    {
        AnimatedButton btn = new AnimatedButton();
        try
        {
            URL res = getClass().getResource(resourcePath);
            if (res != null)
            {
                BufferedImage raw    = ImageIO.read(res);
                Image         scaled = raw.getScaledInstance(24, 24, Image.SCALE_SMOOTH);
                btn.setIcon(new ImageIcon(scaled));
            }
            else
            {
                btn.setText("?");
            }
        }
        catch (IOException e)
        {
            btn.setText("?");
        }

        btn.setToolTipText(tooltip);
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setOpaque(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter()
        {
            @Override public void mouseEntered(MouseEvent e)  { btn.setBg(BTN_HOVER_BG); }
            @Override public void mouseExited(MouseEvent e)   { btn.setBg(null); }
            @Override public void mousePressed(MouseEvent e)  { btn.setBg(BTN_PRESS_BG); }
            @Override public void mouseReleased(MouseEvent e) { btn.setBg(BTN_HOVER_BG); }
        });
        btn.addActionListener(ev -> LinkBrowser.browse(url));
        return btn;
    }

    // ── Image loading ─────────────────────────────────────────────────────────

    /** Load an item icon from the game cache via ItemManager, then run {@code onComplete} on the EDT. */
    static void loadIconAsync(int itemId, int size,
                              ConcurrentMap<Integer, ImageIcon> cache,
                              Set<Integer> loading,
                              ItemManager itemManager,
                              Runnable onComplete)
    {
        if (cache.containsKey(itemId) || !loading.add(itemId)) return;

        AsyncBufferedImage img = itemManager.getImage(itemId);
        img.onLoaded(() ->
        {
            cache.put(itemId, new ImageIcon(ImageUtil.resizeImage(img, size, size, true)));
            loading.remove(itemId);
            SwingUtilities.invokeLater(onComplete);
        });
    }

    private void scheduleImageLoad(int id)
    {
        loadIconAsync(id, ICON_SIZE, imageCache, loadingSet, itemManager, this::refreshVisibleIcons);
    }

    private void refreshVisibleIcons()
    {
        if (resultPages.isEmpty() || currentPage < 0 || currentPage >= resultPages.size()) return;

        Component view = viewportScroll.getViewport().getView();
        if (!(view instanceof JPanel)) return;

        JPanel wrapper = (JPanel) view;
        if (wrapper.getComponentCount() == 0 || !(wrapper.getComponent(0) instanceof JPanel)) return;

        JPanel page = (JPanel) wrapper.getComponent(0);
        for (Component c : page.getComponents())
        {
            if (!(c instanceof JPanel)) continue;
            for (Component child : ((JPanel) c).getComponents())
            {
                if (!(child instanceof JLabel)) continue;
                JLabel lbl = (JLabel) child;
                String name = lbl.getName();
                if (name == null) continue;
                try
                {
                    int id = Integer.parseInt(name);
                    ImageIcon icon = imageCache.get(id);
                    if (icon != null) lbl.setIcon(icon);
                    else scheduleImageLoad(id);
                }
                catch (NumberFormatException ignored) {}
            }
        }
    }

    // ── Pagination ────────────────────────────────────────────────────────────

    private void showPage(int pageIndex)
    {
        final int totalPages = resultPages.size();

        if (totalPages == 0)
        {
            JPanel noResults = new JPanel(new GridBagLayout());
            noResults.add(new JLabel("No results found."));
            viewportScroll.setViewportView(noResults);
            currentPage = 0;
            pageInfoLabel.setText("Page 0 / 0");
        }
        else
        {
            if      (pageIndex < 0)            currentPage = totalPages - 1;
            else if (pageIndex >= totalPages)   currentPage = 0;
            else                                currentPage = pageIndex;

            // Wrap in a BorderLayout.NORTH so the list doesn't stretch vertically
            JPanel contentWrapper = new JPanel(new BorderLayout());
            contentWrapper.setBackground(getBackground());
            contentWrapper.add(resultPages.get(currentPage), BorderLayout.NORTH);
            viewportScroll.setViewportView(contentWrapper);

            pageInfoLabel.setText("Page " + (currentPage + 1) + " / " + totalPages);
        }

        paginationPanel.revalidate();
        paginationPanel.repaint();
        viewportScroll.revalidate();
        viewportScroll.repaint();

        if (totalPages > 0)
        {
            SwingUtilities.invokeLater(() ->
                    viewportScroll.getVerticalScrollBar().setValue(0));
            refreshVisibleIcons();
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    static String formatGp(long gp)
    {
        double abs = Math.abs((double) gp);
        if (abs >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fB", gp / 1_000_000_000.0);
        if (abs >= 1_000_000)     return String.format(Locale.ROOT, "%.1fM", gp / 1_000_000.0);
        if (abs >= 1_000)         return String.format(Locale.ROOT, "%.1fK", gp / 1_000.0);
        return gp + " gp";
    }

    private static String formatNumber(long num)
    {
        double abs = Math.abs((double) num);
        if (abs >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fB", num / 1_000_000_000.0);
        if (abs >= 1_000_000)     return String.format(Locale.ROOT, "%.1fM", num / 1_000_000.0);
        if (abs >= 1_000)         return String.format(Locale.ROOT, "%.1fK", num / 1_000.0);
        return String.valueOf(num);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static long safeParseLong(String s, long fallback)
    {
        if (s == null || s.trim().isEmpty()) return fallback;
        try { return Long.parseLong(s.trim()); }
        catch (Exception e) { return fallback; }
    }

    private static String safeSelected(JComboBox<String> cb, String fallback)
    {
        Object sel = cb.getSelectedItem();
        return sel == null ? fallback : sel.toString();
    }

    private static ImageIcon makePlaceholderIcon(int w, int h)
    {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return new ImageIcon(img);
    }

    private static String truncateName(String name)
    {
        if (name == null) return "";
        if (name.length() <= NAME_LIMIT) return name;
        return name.substring(0, NAME_LIMIT) + "…";
    }

    private static void addDocumentListener(JTextField field, Runnable onChange)
    {
        field.getDocument().addDocumentListener(new DocumentListener()
        {
            public void insertUpdate(DocumentEvent e)  { onChange.run(); }
            public void removeUpdate(DocumentEvent e)  { onChange.run(); }
            public void changedUpdate(DocumentEvent e) { onChange.run(); }
        });
    }

    // ── Row data class ────────────────────────────────────────────────────────

    private static class Row
    {
        final int    id;
        final String fullName;
        final String displayName;
        final double changePct;
        final long   changeAbs;
        final long   volume;
        final long   snapPrice;
        final long   curPrice;

        Row(int id, String fullName, String displayName,
            double changePct, long changeAbs, long volume, long snapPrice, long curPrice)
        {
            this.id          = id;
            this.fullName    = fullName;
            this.displayName = displayName;
            this.changePct   = changePct;
            this.changeAbs   = changeAbs;
            this.volume      = volume;
            this.snapPrice   = snapPrice;
            this.curPrice    = curPrice;
        }
    }
}