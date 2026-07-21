package com.flippingmasterminds;

import net.runelite.client.ui.PluginPanel;
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
    private Map<Integer, Integer> baseline, day, week, month, year;
    private Map<Integer, FlippingMastermindsPlugin.ItemMeta> meta;
    private Map<Integer, Integer> dayVolume    = Collections.emptyMap();
    private Map<Integer, Integer> weekVolume   = Collections.emptyMap();
    private Map<Integer, Integer> monthVolume  = Collections.emptyMap();
    private Map<Integer, Integer> yearVolume   = Collections.emptyMap();

    // ── Image loading ─────────────────────────────────────────────────────────
    private final ConcurrentMap<Integer, ImageIcon> imageCache = new ConcurrentHashMap<>();
    private final Set<Integer> loadingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ExecutorService imageLoader;
    private final ImageIcon placeholderIcon;

    // ── Plugin callback ───────────────────────────────────────────────────────
    private Runnable onRefreshRequested;

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final int ITEMS_PER_PAGE = 20;
    private static final int ICON_SIZE      = 32;
    private static final int NAME_LIMIT     = 20;
    private static final int MAX_PAGES      = 10;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // Hover/press colours for animated buttons
    private static final Color BTN_HOVER_BG = new Color(60, 60, 60);
    private static final Color BTN_PRESS_BG = new Color(90, 90, 90);
    private static final int   BTN_ARC      = 6;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pass {@code false} to the PluginPanel super-constructor so RuneLite does
     * NOT wrap the entire panel in its own JScrollPane. This lets us control
     * the layout precisely: fixed header at top, scrollable item list in the
     * middle, fixed pagination bar at the bottom.
     */
    public FlippingMastermindsPanel()
    {
        super(false);

        imageLoader = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "ge-panel-image-loader");
            t.setDaemon(true);
            return t;
        });

        placeholderIcon = makePlaceholderIcon(ICON_SIZE, ICON_SIZE);

        setLayout(new BorderLayout());
        add(createHeaderPanel(),     BorderLayout.NORTH);
        add(createBodyPanel(),       BorderLayout.CENTER);
        add(createPaginationPanel(), BorderLayout.SOUTH);

        attachFilterListeners();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called by the plugin to wire the Refresh button. */
    public void setOnRefreshRequested(Runnable callback)
    {
        this.onRefreshRequested = callback;
    }

    /**
     * Called once on startup and whenever the user changes the Show Volume or
     * Show Prices config items in the RuneLite settings panel.
     * Triggers a list rebuild if data is already loaded.
     */
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

        // Social icon buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        buttonPanel.add(createIconHoverButton("/discord_logo.png",
                "https://discord.gg/VnsS2PP4Vt", "Join our Discord!"));
        buttonPanel.add(createIconHoverButton("/github_logo.png",
                "https://github.com/ca-gray/Flipping-Masterminds", "View on GitHub!"));
        buttonPanel.add(createIconHoverButton("/oswiki_logo.png",
                "https://prices.runescape.wiki/osrs/", "View Wiki Prices!"));
        headerPanel.add(buttonPanel);

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
        maxPriceField = new JTextField("2147483647");
        filterPanel.add(maxPriceField, fld);

        // Row 4 – Min Volume
        lbl.gridy++; fld.gridy++;
        filterPanel.add(new JLabel("Min Volume:"), lbl);
        minVolumeField = new JTextField("0");
        minVolumeField.setToolTipText("Minimum total trades in the selected time window");
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
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));

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
        addDocumentListener(minPriceField,  this::refreshWithFilters);
        addDocumentListener(maxPriceField,  this::refreshWithFilters);
        addDocumentListener(minVolumeField, this::refreshWithFilters);
    }

    // ── Public data entry point ───────────────────────────────────────────────

    public void updateMovers(
            Map<Integer, Integer> baseline,
            Map<Integer, Integer> day,
            Map<Integer, Integer> week,
            Map<Integer, Integer> month,
            Map<Integer, Integer> year,
            Map<Integer, FlippingMastermindsPlugin.ItemMeta> meta,
            Map<Integer, Integer> dayVolume,
            Map<Integer, Integer> weekVolume,
            Map<Integer, Integer> monthVolume,
            Map<Integer, Integer> yearVolume)
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
        int    min       = safeParseInt(minPriceField.getText(),  1);
        int    max       = safeParseInt(maxPriceField.getText(),  Integer.MAX_VALUE);
        int    minVol    = safeParseInt(minVolumeField.getText(), 0);

        if (min > max) return;

        Map<Integer, Integer> snapshot;
        Map<Integer, Integer> volumeMap;
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
        for (Map.Entry<Integer, Integer> e : snapshot.entrySet())
        {
            int id        = e.getKey();
            int snapPrice = e.getValue();
            int curPrice  = baseline.getOrDefault(id, -1);

            if (curPrice <= 0 || snapPrice <= 0) continue;
            if (snapPrice < min || snapPrice > max) continue;

            int volume = volumeMap.getOrDefault(id, 0);
            if (volume < minVol) continue;

            double changePct = ((double)(curPrice - snapPrice) / snapPrice) * 100.0;
            int    changeAbs = curPrice - snapPrice;

            if (perf.equals("Top Performers")  && !(changePct > 0.0)) continue;
            if (perf.equals("Underperformers") && !(changePct < 0.0)) continue;

            FlippingMastermindsPlugin.ItemMeta im = meta.get(id);
            if (im == null) continue;

            rows.add(new Row(id, im.name, truncateName(im.name), im.iconUrl,
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
                scheduleImageLoad(r.id, r.iconUrl);
            }
            pages.add(page);
        }

        resultPages = pages;
        showPage(0);
    }

    // ── Row panel builder ─────────────────────────────────────────────────────

    private JPanel makeRowPanel(Row r)
    {
        JPanel rowPanel = new JPanel(new BorderLayout(8, 4));
        rowPanel.setBackground(new Color(34, 34, 34));
        rowPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

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
        JLabel changeLabel = new JLabel(String.format("%.2f%% (%s)", r.changePct, absText));
        changeLabel.setForeground(changeClr);
        textPanel.add(changeLabel);

        // Volume line – shown only when config toggle is on
        if (showVolume && r.volume > 0)
        {
            JLabel volLabel = new JLabel("Vol: " + formatNumber(r.volume));
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

    private void scheduleImageLoad(int id, String rawIconUrl)
    {
        if (imageCache.containsKey(id) || loadingSet.contains(id)) return;
        if (rawIconUrl == null || rawIconUrl.isEmpty())              return;

        loadingSet.add(id);
        imageLoader.submit(() -> {
            try
            {
                String urlStr = rawIconUrl.startsWith("http")
                        ? rawIconUrl : sanitizeIconUrl(rawIconUrl);
                BufferedImage img = ImageIO.read(new URL(urlStr));
                if (img != null)
                {
                    Image scaled = img.getScaledInstance(ICON_SIZE, ICON_SIZE, Image.SCALE_SMOOTH);
                    imageCache.put(id, new ImageIcon(scaled));
                }
            }
            catch (Exception ignored) { }
            finally { loadingSet.remove(id); }

            SwingUtilities.invokeLater(this::refreshVisibleIcons);
        });
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
                    ImageIcon icon = imageCache.get(Integer.parseInt(name));
                    if (icon != null) lbl.setIcon(icon);
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

    private static String formatGp(int gp)
    {
        double abs = Math.abs(gp);
        if (abs >= 1_000_000_000) return String.format("%.1fB", gp / 1_000_000_000.0);
        if (abs >= 1_000_000)     return String.format("%.1fM", gp / 1_000_000.0);
        if (abs >= 1_000)         return String.format("%.1fK", gp / 1_000.0);
        return gp + " gp";
    }

    private static String formatNumber(int num)
    {
        double abs = Math.abs(num);
        if (abs >= 1_000_000_000) return String.format("%.1fB", num / 1_000_000_000.0);
        if (abs >= 1_000_000)     return String.format("%.1fM", num / 1_000_000.0);
        if (abs >= 1_000)         return String.format("%.1fK", num / 1_000.0);
        return String.valueOf(num);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static int safeParseInt(String s, int fallback)
    {
        try { return Integer.parseInt(s.trim()); }
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
        g.setColor(new Color(64, 64, 64));
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

    private static String sanitizeIconUrl(String raw)
    {
        String safe = raw.replace(" ", "_")
                .replace("'", "%27")
                .replace("(", "%28")
                .replace(")", "%29");
        return "https://oldschool.runescape.wiki/images/c/c0/" + safe + "?7263b";
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

    public void dispose() { imageLoader.shutdownNow(); }

    // ── Row data class ────────────────────────────────────────────────────────

    private static class Row
    {
        final int    id;
        final String fullName;
        final String displayName;
        final String iconUrl;
        final double changePct;
        final int    changeAbs;
        final int    volume;
        final int    snapPrice;
        final int    curPrice;

        Row(int id, String fullName, String displayName, String iconUrl,
            double changePct, int changeAbs, int volume, int snapPrice, int curPrice)
        {
            this.id          = id;
            this.fullName    = fullName;
            this.displayName = displayName;
            this.iconUrl     = iconUrl;
            this.changePct   = changePct;
            this.changeAbs   = changeAbs;
            this.volume      = volume;
            this.snapPrice   = snapPrice;
            this.curPrice    = curPrice;
        }
    }
}
