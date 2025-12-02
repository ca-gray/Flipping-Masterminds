package com.flippingmasterminds;

import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

public class FlippingMastermindsPanel extends PluginPanel
{
    private JComboBox<String> timeRangeDropdown;
    private JComboBox<String> performanceDropdown;
    private JTextField minPriceField;
    private JTextField maxPriceField;
    private JButton discordButton;
    private JButton githubButton;
    private JButton wikiosButton;

    private JScrollPane viewportScroll;
    private JPanel paginationPanel;
    private JLabel pageInfoLabel;

    private List<JPanel> resultPages = new ArrayList<>();
    private int currentPage = 0;

    private Map<Integer, Integer> baseline, day, week, month, year;
    private Map<Integer, FlippingMastermindsPlugin.ItemMeta> meta;

    private final ConcurrentMap<Integer, ImageIcon> imageCache = new ConcurrentHashMap<>();
    private final Set<Integer> loadingSet = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ExecutorService imageLoader;

    private final ImageIcon placeholderIcon;

    private static final int ITEMS_PER_PAGE = 20;
    private static final int ICON_SIZE = 32;
    private static final int NAME_LIMIT = 20;
    private static final int MAX_PAGES = 10;

    public FlippingMastermindsPanel()
    {
        super();

        imageLoader = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "ge-panel-image-loader");
            t.setDaemon(true);
            return t;
        });

        placeholderIcon = makePlaceholderIcon(ICON_SIZE, ICON_SIZE);

        setLayout(new BorderLayout());
        add(createHeaderPanel(), BorderLayout.NORTH);
        add(createBodyPanel(), BorderLayout.CENTER);
        add(createFooterPanel(), BorderLayout.SOUTH);

        attachFilterListeners();
    }

    private JPanel createHeaderPanel() {
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        discordButton = createIconHoverButton("/discord_logo.png", "https://discord.gg/VnsS2PP4Vt", "Join our Discord!");
        githubButton = createIconHoverButton("/github_logo.png", "https://github.com/ca-gray/Flipping-Masterminds", "View on GitHub!");
        wikiosButton = createIconHoverButton("/oswiki_logo.png", "https://prices.runescape.wiki/osrs/", "View Wiki Prices!");

        buttonPanel.add(discordButton);
        buttonPanel.add(githubButton);
        buttonPanel.add(wikiosButton);
        headerPanel.add(buttonPanel);

        JPanel filterPanel = new JPanel();
        filterPanel.setLayout(new GridBagLayout());
        filterPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 2, 2, 2);
        gbc.gridx = 0; gbc.gridy = 0;

        filterPanel.add(new JLabel("Time Range:"), gbc);
        gbc.gridx++;
        timeRangeDropdown = new JComboBox<>(new String[]{"Day", "Week", "Month", "Year"});
        filterPanel.add(timeRangeDropdown, gbc);

        gbc.gridx = 0; gbc.gridy++;
        filterPanel.add(new JLabel("Performance:"), gbc);
        gbc.gridx++;
        performanceDropdown = new JComboBox<>(new String[]{"Top Performers", "Underperformers"});
        filterPanel.add(performanceDropdown, gbc);

        gbc.gridx = 0; gbc.gridy++;
        filterPanel.add(new JLabel("Min Price:"), gbc);
        gbc.gridx++;
        minPriceField = new JTextField("1", 10);
        filterPanel.add(minPriceField, gbc);

        gbc.gridx = 0; gbc.gridy++;
        filterPanel.add(new JLabel("Max Price:"), gbc);
        gbc.gridx++;
        maxPriceField = new JTextField("2147483647", 10);
        filterPanel.add(maxPriceField, gbc);

        headerPanel.add(filterPanel);
        return headerPanel;
    }

    private JScrollPane createBodyPanel() {
        viewportScroll = new JScrollPane();
        viewportScroll.setBorder(null);
        viewportScroll.setBackground(getBackground());

        JScrollBar verticalScrollBar = viewportScroll.getVerticalScrollBar();
        verticalScrollBar.setPreferredSize(new Dimension(8, 0));
        verticalScrollBar.setUnitIncrement(16);

        return viewportScroll;
    }

    private JPanel createFooterPanel() {
        paginationPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));

        JButton prev = new JButton("<");
        prev.addActionListener(e -> showPage(currentPage - 1));
        paginationPanel.add(prev);

        pageInfoLabel = new JLabel("Page 0 / 0");
        paginationPanel.add(pageInfoLabel);

        JButton next = new JButton(">");
        next.addActionListener(e -> showPage(currentPage + 1));
        paginationPanel.add(next);

        return paginationPanel;
    }

    private void attachFilterListeners() {
        timeRangeDropdown.addActionListener(e -> refreshWithFilters());
        performanceDropdown.addActionListener(e -> refreshWithFilters());
        addDocumentListener(minPriceField, this::refreshWithFilters);
        addDocumentListener(maxPriceField, this::refreshWithFilters);
    }

    public void updateMovers(
            Map<Integer, Integer> baseline,
            Map<Integer, Integer> day,
            Map<Integer, Integer> week,
            Map<Integer, Integer> month,
            Map<Integer, Integer> year,
            Map<Integer, FlippingMastermindsPlugin.ItemMeta> meta)
    {
        this.baseline = baseline;
        this.day = day;
        this.week = week;
        this.month = month;
        this.year = year;
        this.meta = meta;

        rebuildResults();
    }

    private void refreshWithFilters()
    {
        if (baseline != null && meta != null)
        {
            rebuildResults();
        }
    }

    private void rebuildResults()
    {
        String timeRange = safeSelected(timeRangeDropdown, "Day");
        String perf = safeSelected(performanceDropdown, "Top Performers");
        int min = safeParseInt(minPriceField.getText(), 1);
        int max = safeParseInt(maxPriceField.getText(), Integer.MAX_VALUE);

        if (min > max) return;

        Map<Integer, Integer> snapshot;
        switch (timeRange)
        {
            case "Week": snapshot = week; break;
            case "Month": snapshot = month; break;
            case "Year": snapshot = year; break;
            default: snapshot = day;
        }
        if (snapshot == null) snapshot = Collections.emptyMap();

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : snapshot.entrySet())
        {
            int id = e.getKey();
            int snapPrice = e.getValue();
            int basePrice = baseline.getOrDefault(id, -1);
            if (basePrice <= 0 || snapPrice <= 0) continue;
            if (snapPrice < min || snapPrice > max) continue;

            double changePct = ((double)(basePrice - snapPrice) / snapPrice) * 100.0;
            int changeAbs = basePrice - snapPrice;


            if (perf.equals("Top Performers") && !(changePct > 0.0)) continue;
            if (perf.equals("Underperformers") && !(changePct < 0.0)) continue;

            FlippingMastermindsPlugin.ItemMeta im = meta.get(id);
            if (im == null) continue;

            String displayName = truncateName(im.name);
            rows.add(new Row(id, im.name, displayName, im.iconUrl, changePct, changeAbs));
        }

        rows.sort((a, b) -> perf.equals("Top Performers") ?
                Double.compare(b.changePct, a.changePct) : Double.compare(a.changePct, b.changePct));

        List<JPanel> pages = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += ITEMS_PER_PAGE)
        {
            if (pages.size() >= MAX_PAGES) break;

            JPanel page = new JPanel();
            page.setLayout(new GridLayout(0, 1, 4, 4));
            page.setBackground(getBackground());

            int end = Math.min(i + ITEMS_PER_PAGE, rows.size());
            for (int j = i; j < end; j++)
            {
                Row r = rows.get(j);
                JPanel rowPanel = makeRowPanel(r);
                page.add(rowPanel);
                scheduleImageLoad(r.id, r.iconUrl);
            }
            pages.add(page);
        }

        resultPages = pages;
        showPage(0);
    }

    private JPanel makeRowPanel(Row r)
    {
        JPanel rowPanel = new JPanel(new BorderLayout(8, 4));
        rowPanel.setBackground(new Color(34, 34, 34));
        rowPanel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JLabel iconLabel = new JLabel();
        iconLabel.setName(String.valueOf(r.id));
        ImageIcon cached = imageCache.get(r.id);
        iconLabel.setIcon(cached != null ? cached : placeholderIcon);
        rowPanel.add(iconLabel, BorderLayout.WEST);

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        JLabel nameLabel = new JLabel(r.displayName);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setToolTipText(r.fullName);
        textPanel.add(nameLabel);

        String absText = (r.changeAbs > 0 ? "+" : "") + formatNumber(r.changeAbs);
        JLabel changeLabel = new JLabel(String.format("%.2f%% (%s)", r.changePct, absText));
        changeLabel.setForeground(r.changeAbs >= 0 ? new Color(0, 192, 0) : new Color(220, 50, 50));
        textPanel.add(changeLabel);

        rowPanel.add(textPanel, BorderLayout.CENTER);

        JButton arrow = new JButton("\uD83C\uDF10");
        arrow.setFocusPainted(false);
        arrow.setContentAreaFilled(false);
        arrow.setBorderPainted(false);
        arrow.setForeground(Color.LIGHT_GRAY);
        arrow.addActionListener(e -> openUrl("https://prices.runescape.wiki/osrs/item/" + r.id));
        rowPanel.add(arrow, BorderLayout.EAST);

        return rowPanel;
    }

    private static String formatNumber(int num)
    {
        double abs = Math.abs(num);
        if (abs >= 1_000_000_000)
            return String.format("%.1fB", num / 1_000_000_000.0);
        if (abs >= 1_000_000)
            return String.format("%.1fM", num / 1_000_000.0);
        if (abs >= 1_000)
            return String.format("%.1fK", num / 1_000.0);
        return String.valueOf(num);
    }

    private JButton createIconHoverButton(String resourcePath, String url, String tooltip)
    {
        JButton button = new JButton();
        try
        {
            URL res = getClass().getResource(resourcePath);
            if (res != null)
            {
                BufferedImage raw = ImageIO.read(res);
                Image scaled = raw.getScaledInstance(24, 24, Image.SCALE_SMOOTH);
                ImageIcon normalIcon = new ImageIcon(scaled);

                button.setIcon(normalIcon);
            }
            else
            {
                button.setText("?");
            }
        }
        catch (IOException e)
        {
            button.setText("?");
        }

        button.setToolTipText(tooltip);
        button.setFocusPainted(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.addActionListener(ev -> openUrl(url));
        return button;
    }

    private void scheduleImageLoad(int id, String rawIconUrl)
    {
        if (imageCache.containsKey(id) || loadingSet.contains(id)) return;
        if (rawIconUrl == null || rawIconUrl.isEmpty()) return;

        loadingSet.add(id);
        imageLoader.submit(() -> {
            try
            {
                String url = rawIconUrl.startsWith("http") ? rawIconUrl : sanitizeIconUrl(rawIconUrl);
                BufferedImage img = ImageIO.read(new URL(url));
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
        if (view instanceof JPanel)
        {
            JPanel wrapper = (JPanel) view;
            if (wrapper.getComponentCount() > 0 && wrapper.getComponent(0) instanceof JPanel)
            {
                JPanel page = (JPanel) wrapper.getComponent(0);
                for (Component c : page.getComponents())
                {
                    if (c instanceof JPanel)
                    {
                        for (Component child : ((JPanel) c).getComponents())
                        {
                            if (child instanceof JLabel && ((JLabel) child).getName() != null)
                            {
                                JLabel lbl = (JLabel) child;
                                try
                                {
                                    int id = Integer.parseInt(lbl.getName());
                                    ImageIcon icon = imageCache.get(id);
                                    if (icon != null) lbl.setIcon(icon);
                                }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            }
        }
    }

    private void showPage(int pageIndex)
    {
        final int totalPages = resultPages.size();

        if (totalPages == 0)
        {
            JPanel noResultsPanel = new JPanel(new GridBagLayout());
            noResultsPanel.add(new JLabel("No results found."));
            viewportScroll.setViewportView(noResultsPanel);
            currentPage = 0;
            pageInfoLabel.setText("Page 0 / 0");
        }
        else
        {
            if (pageIndex < 0) {
                currentPage = totalPages - 1;
            } else if (pageIndex >= totalPages) {
                currentPage = 0;
            } else {
                currentPage = pageIndex;
            }

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
            SwingUtilities.invokeLater(() -> viewportScroll.getVerticalScrollBar().setValue(0));
            refreshVisibleIcons();
        }
    }

    // UPDATED: Use LinkBrowser instead of Desktop.getDesktop()
    private void openUrl(String url)
    {
        LinkBrowser.browse(url);
    }

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
        return name.substring(0, NAME_LIMIT) + "...";
    }

    private String sanitizeIconUrl(String raw)
    {
        String iconUrl = raw.replace(" ", "_")
                .replace("'", "%27")
                .replace("(", "%28")
                .replace(")", "%29");
        return "https://oldschool.runescape.wiki/images/c/c0/" + iconUrl + "?7263b";
    }

    private static void addDocumentListener(JTextField field, Runnable onChange)
    {
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { onChange.run(); }
            public void removeUpdate(DocumentEvent e) { onChange.run(); }
            public void changedUpdate(DocumentEvent e) { onChange.run(); }
        });
    }

    public void dispose()
    {
        imageLoader.shutdownNow();
    }

    private static class Row
    {
        final int id;
        final String fullName;
        final String displayName;
        final String iconUrl;
        final double changePct;
        final int changeAbs;

        Row(int id, String fullName, String displayName, String iconUrl, double changePct, int changeAbs)
        {
            this.id = id;
            this.fullName = fullName;
            this.displayName = displayName;
            this.iconUrl = iconUrl;
            this.changePct = changePct;
            this.changeAbs = changeAbs;
        }
    }
}