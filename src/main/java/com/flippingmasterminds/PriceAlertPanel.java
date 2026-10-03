package com.flippingmasterminds;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PriceAlertPanel extends JPanel
{
    private PriceAlertTracker tracker;
    private Map<Integer, ItemMeta> meta;
    private Map<Integer, Long> currentPrices;

    private JTextField searchField;
    private JPanel searchResultsPanel;
    private JTextField targetPriceField;
    private JComboBox<PriceAlertTracker.Direction> directionDropdown;
    private JTextField expiresField;
    private JButton addButton;
    private JPanel alertListPanel;
    private JScrollPane alertScroll;

    private JTextField discordCommandField;
    private JLabel discordStatus;

    private int selectedItemId = -1;
    private String selectedItemName = "";
    private boolean suppressSearchListener = false;

    private final Timer countdownTimer;

    private final ConcurrentMap<Integer, ImageIcon> imageCache;
    private final Set<Integer> loadingSet;
    private final ExecutorService imageLoader;
    private final ImageIcon placeholderIcon;

    private Border defaultFieldBorder;
    private Border invalidFieldBorder;

    private static final int ICON_SIZE = 32;
    private static final Color TRIGGERED_BG     = new Color(25, 60, 25);
    private static final Color UNTRIGGERED_BG   = new Color(34, 34, 34);
    private static final Color TRIGGERED_BORDER = new Color(50, 140, 50);
    private static final Color STALE_BG         = new Color(60, 50, 20);
    private static final Color STALE_BORDER     = new Color(180, 140, 40);

    private static final Pattern SHORTHAND_PATTERN =
            Pattern.compile("^([0-9]+\\.?[0-9]*)\\s*([kmb])?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("^([0-9]+\\.?[0-9]*)\\s*([hdw])$", Pattern.CASE_INSENSITIVE);

    public PriceAlertPanel(ConcurrentMap<Integer, ImageIcon> imageCache,
                           Set<Integer> loadingSet,
                           ExecutorService imageLoader,
                           ImageIcon placeholderIcon)
    {
        this.imageCache      = imageCache;
        this.loadingSet      = loadingSet;
        this.imageLoader     = imageLoader;
        this.placeholderIcon = placeholderIcon;

        setLayout(new BorderLayout());
        add(createFormPanel(), BorderLayout.NORTH);

        alertListPanel = new JPanel();
        alertListPanel.setLayout(new BoxLayout(alertListPanel, BoxLayout.Y_AXIS));

        JPanel listWrapper = new JPanel(new BorderLayout());
        listWrapper.add(alertListPanel, BorderLayout.NORTH);

        alertScroll = new JScrollPane(listWrapper);
        alertScroll.setBorder(null);
        alertScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JScrollBar vsb = alertScroll.getVerticalScrollBar();
        vsb.setPreferredSize(new Dimension(8, 0));
        vsb.setUnitIncrement(16);

        add(alertScroll, BorderLayout.CENTER);
        add(createBottomBar(), BorderLayout.SOUTH);

        countdownTimer = new Timer(60_000, e -> rebuildAlertList());
        countdownTimer.start();
    }

    public void stopTimer()
    {
        countdownTimer.stop();
    }

    public void setTracker(PriceAlertTracker tracker)
    {
        this.tracker = tracker;
        rebuildAlertList();
    }

    public void updateMeta(Map<Integer, ItemMeta> meta)
    {
        this.meta = meta;
    }

    public void updatePrices(Map<Integer, Long> currentPrices)
    {
        this.currentPrices = currentPrices;
        rebuildAlertList();
    }

    // ── Form ─────────────────────────────────────────────────────────────────

    private JPanel createFormPanel()
    {
        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel title = new JLabel("Add Price Alert");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(title);
        form.add(Box.createVerticalStrut(6));

        JLabel searchLabel = new JLabel("Item:");
        searchLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(searchLabel);

        searchField = new JTextField();
        searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        searchField.setToolTipText("Type an item name to search");
        form.add(searchField);

        defaultFieldBorder = searchField.getBorder();
        Insets d = defaultFieldBorder.getBorderInsets(searchField);
        invalidFieldBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 60, 60), 2),
                BorderFactory.createEmptyBorder(
                        Math.max(0, d.top - 2), Math.max(0, d.left - 2),
                        Math.max(0, d.bottom - 2), Math.max(0, d.right - 2)));

        searchResultsPanel = new JPanel();
        searchResultsPanel.setLayout(new BoxLayout(searchResultsPanel, BoxLayout.Y_AXIS));
        searchResultsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchResultsPanel.setVisible(false);
        form.add(searchResultsPanel);

        form.add(Box.createVerticalStrut(6));

        JPanel row = new JPanel(new GridBagLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints lbl = new GridBagConstraints();
        lbl.anchor = GridBagConstraints.WEST;
        lbl.insets = new Insets(2, 0, 2, 4);
        lbl.gridx = 0; lbl.gridy = 0;
        lbl.weightx = 0;

        GridBagConstraints fld = new GridBagConstraints();
        fld.anchor = GridBagConstraints.WEST;
        fld.insets = new Insets(2, 0, 2, 0);
        fld.gridx = 1; fld.gridy = 0;
        fld.fill = GridBagConstraints.HORIZONTAL;
        fld.weightx = 1.0;

        row.add(new JLabel("Target GP:"), lbl);
        targetPriceField = new JTextField();
        targetPriceField.setToolTipText("e.g. 10k, 10.4m, 1.4b, or 50000");
        row.add(targetPriceField, fld);

        lbl.gridy++; fld.gridy++;
        row.add(new JLabel("Condition:"), lbl);
        directionDropdown = new JComboBox<>(PriceAlertTracker.Direction.values());
        row.add(directionDropdown, fld);

        lbl.gridy++; fld.gridy++;
        row.add(new JLabel("Expires:"), lbl);
        expiresField = new JTextField("24h");
        expiresField.setToolTipText("e.g. 12h, 24h, 3d, 7d, 30d");
        row.add(expiresField, fld);

        form.add(row);
        form.add(Box.createVerticalStrut(6));

        addButton = new JButton("+ Add Alert");
        addButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        addButton.setFocusPainted(false);
        addButton.setEnabled(false);
        addButton.addActionListener(e -> onAddAlert());
        form.add(addButton);

        form.add(Box.createVerticalStrut(6));
        form.add(new JSeparator());
        form.add(Box.createVerticalStrut(6));

        // Discord command input
        JLabel discordLabel = new JLabel("Or paste Discord command:");
        discordLabel.setFont(discordLabel.getFont().deriveFont(Font.BOLD, 11f));
        discordLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(discordLabel);
        form.add(Box.createVerticalStrut(2));

        JLabel discordHint = new JLabel("Use /track_price in the FMM Discord to generate a command");
        discordHint.setFont(discordHint.getFont().deriveFont(Font.ITALIC, 9f));
        discordHint.setForeground(new Color(130, 130, 150));
        discordHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(discordHint);
        form.add(Box.createVerticalStrut(4));

        discordCommandField = new JTextField();
        discordCommandField.setAlignmentX(Component.LEFT_ALIGNMENT);
        discordCommandField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        discordCommandField.setToolTipText("e.g. /track_price price:346465 movement:Rises To item_id:28834 fs_hours:24");
        form.add(discordCommandField);
        form.add(Box.createVerticalStrut(4));

        JButton parseBtn = new JButton("Parse & Add");
        parseBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        parseBtn.setFocusPainted(false);
        parseBtn.addActionListener(e -> onParseDiscordCommand());
        form.add(parseBtn);

        discordStatus = new JLabel(" ");
        discordStatus.setFont(discordStatus.getFont().deriveFont(10f));
        discordStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(discordStatus);

        form.add(Box.createVerticalStrut(4));
        form.add(new JSeparator());

        searchField.getDocument().addDocumentListener(new DocumentListener()
        {
            public void insertUpdate(DocumentEvent e)  { onSearchChanged(); }
            public void removeUpdate(DocumentEvent e)  { onSearchChanged(); }
            public void changedUpdate(DocumentEvent e) { onSearchChanged(); }
        });

        DocumentListener buttonUpdater = new DocumentListener()
        {
            public void insertUpdate(DocumentEvent e)  { updateAddButton(); }
            public void removeUpdate(DocumentEvent e)  { updateAddButton(); }
            public void changedUpdate(DocumentEvent e) { updateAddButton(); }
        };
        targetPriceField.getDocument().addDocumentListener(buttonUpdater);
        expiresField.getDocument().addDocumentListener(buttonUpdater);

        return form;
    }

    private JPanel createBottomBar()
    {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(60, 60, 60)));

        JButton clearCompleted = new JButton("Clear Completed");
        clearCompleted.setFocusPainted(false);
        clearCompleted.setToolTipText("Remove all triggered and expired alerts");
        clearCompleted.addActionListener(e -> {
            if (tracker != null)
            {
                tracker.clearCompleted();
                rebuildAlertList();
            }
        });
        bar.add(clearCompleted);

        JButton clearHistory = new JButton("Clear History");
        clearHistory.setFocusPainted(false);
        clearHistory.setToolTipText("Remove all alert history entries");
        clearHistory.addActionListener(e -> {
            if (tracker != null)
            {
                tracker.clearHistory();
                rebuildAlertList();
            }
        });
        bar.add(clearHistory);

        return bar;
    }

    // ── Search ───────────────────────────────────────────────────────────────

    private void onSearchChanged()
    {
        if (suppressSearchListener) return;

        String query = searchField.getText().trim().toLowerCase();

        if (selectedItemId > 0 && !selectedItemName.equalsIgnoreCase(query))
        {
            selectedItemId = -1;
            selectedItemName = "";
        }

        updateAddButton();
        searchResultsPanel.removeAll();

        if (query.length() < 2 || meta == null)
        {
            searchResultsPanel.setVisible(false);
            searchResultsPanel.revalidate();
            return;
        }

        List<ItemMeta> matches = new ArrayList<>();
        for (ItemMeta im : meta.values())
        {
            if (im.name.toLowerCase().contains(query))
            {
                matches.add(im);
                if (matches.size() >= 8) break;
            }
        }

        if (matches.isEmpty())
        {
            searchResultsPanel.setVisible(false);
            searchResultsPanel.revalidate();
            return;
        }

        for (ItemMeta im : matches)
        {
            JPanel resultRow = new JPanel(new BorderLayout());
            resultRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            resultRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            String priceStr = "";
            if (currentPrices != null && currentPrices.containsKey(im.id))
            {
                priceStr = " (" + formatGp(currentPrices.get(im.id)) + ")";
            }
            JLabel nameLabel = new JLabel(im.name + priceStr);
            nameLabel.setFont(nameLabel.getFont().deriveFont(11f));
            resultRow.add(nameLabel, BorderLayout.CENTER);

            resultRow.addMouseListener(new MouseAdapter()
            {
                @Override
                public void mouseClicked(MouseEvent e)
                {
                    selectedItemId = im.id;
                    selectedItemName = im.name;
                    suppressSearchListener = true;
                    searchField.setText(im.name);
                    suppressSearchListener = false;
                    searchResultsPanel.setVisible(false);
                    searchResultsPanel.revalidate();
                    updateAddButton();
                }

                @Override
                public void mouseEntered(MouseEvent e)
                {
                    resultRow.setBackground(new Color(50, 50, 55));
                    resultRow.setOpaque(true);
                }

                @Override
                public void mouseExited(MouseEvent e)
                {
                    resultRow.setOpaque(false);
                }
            });

            searchResultsPanel.add(resultRow);
        }

        searchResultsPanel.setVisible(true);
        searchResultsPanel.revalidate();
        searchResultsPanel.repaint();
    }

    // ── Add button / price parsing ───────────────────────────────────────────

    private void updateAddButton()
    {
        boolean hasItem   = selectedItemId > 0;
        boolean hasPrice  = parseShorthandPrice(targetPriceField.getText()) > 0;
        boolean hasExpiry = parseDuration(expiresField.getText()) > 0;
        addButton.setEnabled(hasItem && hasPrice && hasExpiry);

        String searchText = searchField.getText().trim();
        searchField.setBorder(!searchText.isEmpty() && !hasItem ? invalidFieldBorder : defaultFieldBorder);

        String priceText = targetPriceField.getText().trim();
        targetPriceField.setBorder(!priceText.isEmpty() && !hasPrice ? invalidFieldBorder : defaultFieldBorder);

        String expiryText = expiresField.getText().trim();
        expiresField.setBorder(!expiryText.isEmpty() && !hasExpiry ? invalidFieldBorder : defaultFieldBorder);
    }

    private void onAddAlert()
    {
        if (tracker == null || selectedItemId <= 0) return;

        long targetPrice = parseShorthandPrice(targetPriceField.getText());
        if (targetPrice <= 0) return;

        long expiryMs = parseDuration(expiresField.getText());
        if (expiryMs <= 0) return;

        PriceAlertTracker.Direction dir =
                (PriceAlertTracker.Direction) directionDropdown.getSelectedItem();

        tracker.addAlert(selectedItemId, selectedItemName, targetPrice, dir, expiryMs);

        suppressSearchListener = true;
        searchField.setText("");
        suppressSearchListener = false;
        targetPriceField.setText("");
        expiresField.setText("24h");
        selectedItemId = -1;
        selectedItemName = "";
        addButton.setEnabled(false);

        rebuildAlertList();
    }

    // ── Discord command parser ───────────────────────────────────────────────

    private void onParseDiscordCommand()
    {
        String raw = discordCommandField.getText().trim();
        if (raw.isEmpty())
        {
            discordStatus.setText("Paste a /track_price command above.");
            discordStatus.setForeground(new Color(200, 100, 100));
            return;
        }

        // Strip leading slash-command name if present
        String cmd = raw.replaceFirst("^/track_price\\s*", "");

        Long price    = extractLong(cmd, "price");
        String movement = extractMovement(cmd);
        Integer itemId  = extractInt(cmd, "item_id");
        String itemName = extractString(cmd, "item_name");
        Long fsHours    = extractLong(cmd, "fs_hours");

        if (price == null || price <= 0)
        {
            discordStatus.setText("Missing or invalid price.");
            discordStatus.setForeground(new Color(200, 100, 100));
            return;
        }

        if (movement == null)
        {
            discordStatus.setText("Missing movement (Rises To / Falls To).");
            discordStatus.setForeground(new Color(200, 100, 100));
            return;
        }

        PriceAlertTracker.Direction dir = "falls".equals(movement)
                ? PriceAlertTracker.Direction.BELOW
                : PriceAlertTracker.Direction.ABOVE;

        long expiryMs = fsHours != null && fsHours > 0
                ? fsHours * 60 * 60 * 1000L
                : 30L * 24 * 60 * 60 * 1000L;

        // Resolve item
        int resolvedId = -1;
        String resolvedName = "";

        if (itemId != null && itemId > 0)
        {
            resolvedId = itemId;
            if (meta != null && meta.containsKey(itemId))
            {
                resolvedName = meta.get(itemId).name;
            }
            else
            {
                resolvedName = "Item " + itemId;
            }
        }
        else if (itemName != null && !itemName.isEmpty() && meta != null)
        {
            String lower = itemName.toLowerCase();
            ItemMeta bestMatch = null;
            for (ItemMeta im : meta.values())
            {
                if (im.name.equalsIgnoreCase(itemName))
                {
                    bestMatch = im;
                    break;
                }
                if (bestMatch == null && im.name.toLowerCase().contains(lower))
                {
                    bestMatch = im;
                }
            }
            if (bestMatch != null)
            {
                resolvedId = bestMatch.id;
                resolvedName = bestMatch.name;
            }
        }

        if (resolvedId <= 0)
        {
            discordStatus.setText("Could not resolve item. Provide item_id or item_name.");
            discordStatus.setForeground(new Color(200, 100, 100));
            return;
        }

        if (tracker == null)
        {
            discordStatus.setText("Tracker not ready.");
            discordStatus.setForeground(new Color(200, 100, 100));
            return;
        }

        tracker.addAlert(resolvedId, resolvedName, price, dir, expiryMs);
        discordCommandField.setText("");
        discordStatus.setText("Added: " + resolvedName + " " + (dir == PriceAlertTracker.Direction.BELOW ? "falls to" : "rises to") + " " + formatGp(price));
        discordStatus.setForeground(new Color(80, 200, 80));
        rebuildAlertList();
    }

    private static Long extractLong(String cmd, String key)
    {
        Matcher m = Pattern.compile(key + ":(\\d+)", Pattern.CASE_INSENSITIVE).matcher(cmd);
        if (m.find())
        {
            try { return Long.parseLong(m.group(1)); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static Integer extractInt(String cmd, String key)
    {
        Long val = extractLong(cmd, key);
        return val != null ? val.intValue() : null;
    }

    private static String extractString(String cmd, String key)
    {
        Matcher m = Pattern.compile(key + ":([^\\s:]+(?:\\s+[^\\s:]+)*?)(?=\\s+\\w+:|$)",
                Pattern.CASE_INSENSITIVE).matcher(cmd);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    private static String extractMovement(String cmd)
    {
        Matcher m = Pattern.compile("movement:(rises(?:\\s+to)?|falls(?:\\s+to)?)",
                Pattern.CASE_INSENSITIVE).matcher(cmd);
        if (m.find())
        {
            String val = m.group(1).toLowerCase();
            return val.startsWith("falls") ? "falls" : "rises";
        }
        return null;
    }

    static long parseShorthandPrice(String input)
    {
        if (input == null) return -1;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return -1;

        Matcher m = SHORTHAND_PATTERN.matcher(trimmed);
        if (!m.matches()) return -1;

        double num = Double.parseDouble(m.group(1));
        String suffix = m.group(2);

        if (suffix != null)
        {
            switch (suffix.toLowerCase())
            {
                case "k": num *= 1_000;         break;
                case "m": num *= 1_000_000;     break;
                case "b": num *= 1_000_000_000; break;
            }
        }

        return Math.round(num);
    }

    static long parseDuration(String input)
    {
        if (input == null) return -1;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return -1;

        Matcher m = DURATION_PATTERN.matcher(trimmed);
        if (!m.matches()) return -1;

        double num = Double.parseDouble(m.group(1));
        String unit = m.group(2).toLowerCase();

        switch (unit)
        {
            case "h": return Math.round(num * 60 * 60 * 1000L);
            case "d": return Math.round(num * 24 * 60 * 60 * 1000L);
            case "w": return Math.round(num * 7 * 24 * 60 * 60 * 1000L);
            default:  return -1;
        }
    }

    // ── Alert list ───────────────────────────────────────────────────────────

    void rebuildAlertList()
    {
        alertListPanel.removeAll();

        if (tracker == null || tracker.getAlerts().isEmpty())
        {
            JPanel empty = new JPanel(new GridBagLayout());
            JLabel emptyLabel = new JLabel("No price alerts set.");
            emptyLabel.setForeground(Color.GRAY);
            empty.add(emptyLabel);
            alertListPanel.add(empty);
        }
        else
        {
            List<PriceAlertTracker.PriceAlert> alerts = tracker.getAlerts();
            for (int i = 0; i < alerts.size(); i++)
            {
                alertListPanel.add(makeAlertRow(alerts.get(i), i));
                alertListPanel.add(Box.createVerticalStrut(4));
            }
        }

        // Alert history
        buildHistorySection();

        alertListPanel.revalidate();
        alertListPanel.repaint();
    }

    private JPanel makeAlertRow(PriceAlertTracker.PriceAlert alert, int index)
    {
        Color borderColor = alert.triggered ? TRIGGERED_BORDER
                          : alert.stale     ? STALE_BORDER
                          : new Color(44, 47, 58);
        Color bgColor     = alert.triggered ? TRIGGERED_BG
                          : alert.stale     ? STALE_BG
                          : UNTRIGGERED_BG;

        JPanel row = new JPanel(new BorderLayout(6, 4))
        {
            @Override
            protected void paintComponent(Graphics g)
            {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(borderColor);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
            }
        };
        row.setOpaque(false);
        row.setBackground(bgColor);
        row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));

        // Item icon on the left
        JLabel iconLabel = new JLabel();
        ImageIcon cached = imageCache.get(alert.itemId);
        iconLabel.setIcon(cached != null ? cached : placeholderIcon);
        if (cached == null)
        {
            scheduleIconLoad(alert.itemId);
        }
        row.add(iconLabel, BorderLayout.WEST);

        // Text in the center
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        String name = alert.itemName;
        if (name.length() > 20) name = name.substring(0, 20) + "...";
        JLabel nameLabel = new JLabel(name);
        nameLabel.setForeground(Color.WHITE);
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
        nameLabel.setToolTipText(alert.itemName);
        textPanel.add(nameLabel);

        // Direction line with graph emoji
        String dirEmoji = alert.direction == PriceAlertTracker.Direction.BELOW
                ? "📉 " : "📈 ";
        String dirText = alert.direction == PriceAlertTracker.Direction.BELOW
                ? "Below " : "Above ";
        JLabel condLabel = new JLabel(dirEmoji + dirText + formatGp(alert.targetPrice));
        condLabel.setForeground(alert.direction == PriceAlertTracker.Direction.BELOW
                ? new Color(220, 140, 140) : new Color(140, 200, 140));
        condLabel.setFont(condLabel.getFont().deriveFont(11f));
        textPanel.add(condLabel);

        if (alert.triggered)
        {
            JLabel trigLabel = new JLabel("✅ Triggered at " + formatGp(alert.triggeredPrice));
            trigLabel.setForeground(new Color(80, 200, 80));
            trigLabel.setFont(trigLabel.getFont().deriveFont(Font.BOLD, 10f));
            textPanel.add(trigLabel);
        }
        else if (alert.stale)
        {
            JLabel staleLabel = new JLabel("⚠ Expired after " + formatDuration(alert.expiryMs) + " — never reached target");
            staleLabel.setForeground(new Color(220, 170, 50));
            staleLabel.setFont(staleLabel.getFont().deriveFont(Font.BOLD, 10f));
            textPanel.add(staleLabel);
        }
        else
        {
            long elapsed   = System.currentTimeMillis() - alert.createdAt;
            long daysActive = elapsed / (24 * 60 * 60 * 1000L);
            long msLeft     = alert.expiryMs - elapsed;
            long hoursLeft  = msLeft / (60 * 60 * 1000L);
            long minsLeft   = msLeft / (60 * 1000L);
            String ageText = daysActive == 0 ? "today" : daysActive + "d ago";
            String timeLeft;
            if (minsLeft <= 0)        timeLeft = "expiring soon";
            else if (minsLeft < 60)   timeLeft = minsLeft + "m left";
            else if (hoursLeft < 24)  timeLeft = hoursLeft + "h left";
            else                      timeLeft = (hoursLeft / 24) + "d left";

            if (currentPrices != null && currentPrices.containsKey(alert.itemId))
            {
                long cur = currentPrices.get(alert.itemId);
                JLabel curLabel = new JLabel("Current: " + formatGp(cur) + "  ·  Set " + ageText + "  ·  " + timeLeft);
                curLabel.setForeground(new Color(140, 140, 140));
                curLabel.setFont(curLabel.getFont().deriveFont(10f));
                textPanel.add(curLabel);
            }
            else
            {
                JLabel ageLabel = new JLabel("Set " + ageText + "  ·  " + timeLeft);
                ageLabel.setForeground(new Color(140, 140, 140));
                ageLabel.setFont(ageLabel.getFont().deriveFont(10f));
                textPanel.add(ageLabel);
            }
        }

        row.add(textPanel, BorderLayout.CENTER);

        // Remove button
        JButton removeBtn = new JButton("✖");
        removeBtn.setFocusPainted(false);
        removeBtn.setContentAreaFilled(false);
        removeBtn.setBorderPainted(false);
        removeBtn.setForeground(new Color(180, 80, 80));
        removeBtn.setToolTipText("Remove alert");
        removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeBtn.setPreferredSize(new Dimension(28, 28));
        removeBtn.addActionListener(e -> {
            tracker.removeAlert(index);
            rebuildAlertList();
        });
        row.add(removeBtn, BorderLayout.EAST);

        return row;
    }

    // ── Alert history section ───────────────────────────────────────────────

    private void buildHistorySection()
    {
        if (tracker == null) return;

        List<PriceAlertTracker.AlertHistoryEntry> hist = tracker.getHistory();
        if (hist.isEmpty()) return;

        alertListPanel.add(Box.createVerticalStrut(8));
        JLabel header = new JLabel("📜 Alert History");
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12f));
        header.setForeground(new Color(180, 180, 180));
        header.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 0));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        alertListPanel.add(header);
        alertListPanel.add(Box.createVerticalStrut(4));

        for (int idx = 0; idx < hist.size(); idx++)
        {
            PriceAlertTracker.AlertHistoryEntry h = hist.get(idx);
            final int histIndex = idx;
            JPanel hRow = new JPanel(new BorderLayout(6, 0))
            {
                @Override
                protected void paintComponent(Graphics g)
                {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(getBackground());
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                    g2.setColor(new Color(44, 47, 58));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                    g2.dispose();
                }
            };
            hRow.setOpaque(false);
            hRow.setBackground(new Color(28, 30, 38));
            hRow.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            hRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

            JLabel iconLbl = new JLabel();
            ImageIcon cached = imageCache.get(h.itemId);
            iconLbl.setIcon(cached != null ? cached : placeholderIcon);
            if (cached == null) scheduleIconLoad(h.itemId);
            hRow.add(iconLbl, BorderLayout.WEST);

            JPanel textPanel = new JPanel();
            textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
            textPanel.setOpaque(false);

            String shortName = h.itemName.length() > 20 ? h.itemName.substring(0, 20) + "..." : h.itemName;
            String dirText = h.direction == PriceAlertTracker.Direction.BELOW ? "Below " : "Above ";
            JLabel nameLine = new JLabel(shortName + " — " + dirText + formatGp(h.targetPrice));
            nameLine.setFont(nameLine.getFont().deriveFont(10f));
            nameLine.setForeground(new Color(160, 160, 160));
            nameLine.setToolTipText(h.itemName);
            textPanel.add(nameLine);

            boolean triggered = "TRIGGERED".equals(h.outcome);
            String outcomeText = triggered
                    ? "✅ Hit " + formatGp(h.triggeredPrice)
                    : "⚠ Expired";
            long ago = (System.currentTimeMillis() - h.completedAt) / (60 * 60 * 1000L);
            String agoText = ago < 1 ? "just now" : ago < 24 ? ago + "h ago" : (ago / 24) + "d ago";
            JLabel outLine = new JLabel(outcomeText + "  ·  " + agoText);
            outLine.setFont(outLine.getFont().deriveFont(10f));
            outLine.setForeground(triggered ? new Color(80, 160, 80) : new Color(180, 140, 50));
            textPanel.add(outLine);

            hRow.add(textPanel, BorderLayout.CENTER);

            JButton removeBtn = new JButton("✖");
            removeBtn.setFocusPainted(false);
            removeBtn.setContentAreaFilled(false);
            removeBtn.setBorderPainted(false);
            removeBtn.setForeground(new Color(120, 80, 80));
            removeBtn.setToolTipText("Remove from history");
            removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            removeBtn.setPreferredSize(new Dimension(24, 24));
            removeBtn.addActionListener(e -> {
                tracker.removeHistoryEntry(histIndex);
                rebuildAlertList();
            });
            hRow.add(removeBtn, BorderLayout.EAST);

            alertListPanel.add(hRow);
            alertListPanel.add(Box.createVerticalStrut(3));
        }
    }

    // ── Image loading ────────────────────────────────────────────────────────

    private void scheduleIconLoad(int itemId)
    {
        if (meta == null) return;
        ItemMeta im = meta.get(itemId);
        if (im == null) return;
        FlippingMastermindsPanel.loadIconAsync(itemId, im.iconUrl, ICON_SIZE,
                imageCache, loadingSet, imageLoader, this::rebuildAlertList);
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    private static String formatGp(long gp)
    {
        return FlippingMastermindsPanel.formatGp(gp);
    }

    private static String formatDuration(long ms)
    {
        long hours = ms / (60 * 60 * 1000L);
        if (hours < 24)  return hours + "h";
        long days = hours / 24;
        if (days < 7)    return days + "d";
        long weeks = days / 7;
        return weeks + "w";
    }
}
