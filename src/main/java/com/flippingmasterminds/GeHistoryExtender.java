package com.flippingmasterminds;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextInput;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;

import java.awt.Rectangle;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extends the in-game GE history list (interface 383) with locally archived
 * trades. Archived rows are cloned from the game's own row widgets so they
 * look identical to the native ones.
 *
 * Must only be used on the client thread.
 */
@Slf4j
class GeHistoryExtender
{
	/** The game builds each history row out of this many widgets. */
	static final int WIDGETS_PER_ROW = 6;
	/** Upper bound on archived rows shown, to keep the widget count reasonable. */
	static final int MAX_EXTRA_ROWS = 2000;
	// Positions within a row's widgets: "Bought:"/"Sold:" text, item icon, price text.
	private static final int LABEL_IDX = 2;
	private static final int ITEM_IDX  = 4;
	private static final int PRICE_IDX = 5;

	/** A number such as "1,250" in the price text. */
	private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*");
	/** Matches when the text following a number marks it as the per-item price ("= 125 each"). */
	private static final Pattern EACH_AFTER = Pattern.compile("^\\s*(coins?\\s*)?each");

	private final Client client;
	private final ClientThread clientThread;
	private final ChatboxPanelManager chatboxPanelManager;
	private final ItemManager itemManager;
	private final GeHistoryStore store;
	private final FlippingMastermindsConfig config;
	private final TooltipManager tooltipManager;
	/** Latest wiki insta-buy / insta-sell prices. */
	private final Supplier<Map<Integer, Long>> latestHigh;
	private final Supplier<Map<Integer, Long>> latestLow;

	/** Dynamic child count after the last injection; a change means the game rebuilt the list. */
	private int injectedChildCount = -1;
	/** Native child count and scroll height before injection. */
	private int nativeChildCount;
	private int nativeScrollHeight;
	/** Re-inject on the next tick without waiting for the game to rebuild the list. */
	private boolean refresh;
	private final List<Widget> injected = new ArrayList<>();

	GeHistoryExtender(Client client, ClientThread clientThread, ChatboxPanelManager chatboxPanelManager,
					  ItemManager itemManager, GeHistoryStore store, FlippingMastermindsConfig config,
					  TooltipManager tooltipManager,
					  Supplier<Map<Integer, Long>> latestHigh, Supplier<Map<Integer, Long>> latestLow)
	{
		this.client              = client;
		this.clientThread        = clientThread;
		this.chatboxPanelManager = chatboxPanelManager;
		this.itemManager         = itemManager;
		this.store               = store;
		this.config              = config;
		this.tooltipManager      = tooltipManager;
		this.latestHigh          = latestHigh;
		this.latestLow           = latestLow;
	}

	/** Start reading the account's saved history in the background. */
	void preloadHistory(long accountHash)
	{
		store.preload(accountHash);
	}

	// ── Live recording ───────────────────────────────────────────────────────

	/** Finished offers waiting to be collected, by slot. */
	private final Map<Integer, GeHistoryEntry> awaitingCollection = new HashMap<>();
	private long awaitingAccount = -1;

	/**
	 * Trades are recorded when they are collected, because that is when the game adds
	 * them to its trade history; the collection time is the time shown for the trade.
	 *
	 * @param settling true while the client is still replaying offer states after login
	 */
	void onOfferChanged(int slot, GrandExchangeOffer offer, boolean settling)
	{
		// The store is only touched when something is collected, so the login replay never
		// forces a disk read before the background preload has finished.
		long account = client.getAccountHash();
		if (account == -1) return;
		if (account != awaitingAccount)
		{
			awaitingCollection.clear();
			awaitingAccount = account;
		}

		switch (offer.getState())
		{
			case BOUGHT:
			case SOLD:
			case CANCELLED_BUY:
			case CANCELLED_SELL:
				// Finished: remember it until the slot is collected (nothing traded, nothing to record).
				int qty = offer.getQuantitySold();
				if (qty <= 0)
				{
					awaitingCollection.remove(slot);
					return;
				}
				boolean buy = offer.getState() == GrandExchangeOfferState.BOUGHT
						|| offer.getState() == GrandExchangeOfferState.CANCELLED_BUY;
				long spent = offer.getSpent();
				awaitingCollection.put(slot, new GeHistoryEntry(buy, offer.getItemId(), qty, spent, spent / qty, 0));
				return;
			case EMPTY:
				// Login replays EMPTY before each slot's real state; that isn't a collection.
				if (settling) return;
				GeHistoryEntry collected = awaitingCollection.remove(slot);
				if (collected != null && store.ensureAccount(account))
				{
					collected.timestamp = System.currentTimeMillis();
					store.recordLive(collected);
				}
				return;
			default:
				// A new offer is active in the slot.
				awaitingCollection.remove(slot);
		}
	}

	// ── History interface ────────────────────────────────────────────────────

	/**
	 * Called every client tick; cheap unless the history list has just been (re)built.
	 * When it has: read the game's rows, sync them with the store, add archived rows
	 * underneath, then apply any active search/filter.
	 */
	void tick()
	{
		Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		if (list == null || list.isHidden())
		{
			injectedChildCount = -1;
			refresh = false;
			injected.clear();
			labels.clear();
			rows.clear();
			visibleRows.clear();
			forgetDivider();
			// Reset search state when the interface closes.
			closeSearchInput();
			query = "";
			filterDueAt = 0;
			side = Side.ALL;
			sideButtons.clear();
			searchButton = null;
			searchText = null;
			titleWidget = null;
			headerParent = null;
			searchWidgets.clear();
			return;
		}

		if (searchButton == null) createSearchButton();

		Widget[] kids = list.getDynamicChildren();
		int total = kids == null ? 0 : kids.length;
		if (total == 0 || (total == injectedChildCount && !refresh))
		{
			long now = System.currentTimeMillis();
			if (filterDueAt != 0 && now >= filterDueAt)
			{
				filterDueAt = 0;
				applyFilter(list, true);
			}
			if (!labels.isEmpty() && now - lastLabelUpdate >= LABEL_REFRESH_MS)
			{
				updateLabels();
			}
			return;
		}

		// Either the game rebuilt the list or removeInjected() took our widgets out;
		// both leave only the game's rows and its own scroll height.
		nativeChildCount   = total;
		nativeScrollHeight = list.getScrollHeight();
		refresh = false;
		injected.clear();
		labels.clear();
		rows.clear();
		visibleRows.clear();
		forgetDivider();
		// Set early so a parse failure isn't retried every frame.
		injectedChildCount = total;

		int count = nativeChildCount;
		if (client.getGameState() != GameState.LOGGED_IN) return;
		if (!store.ensureAccount(client.getAccountHash())) return;
		if (count % WIDGETS_PER_ROW != 0)
		{
			log.debug("GE history has {} widgets, expected a multiple of {}", count, WIDGETS_PER_ROW);
			return;
		}

		// 1. Read the game's rows (newest first).
		List<GeHistoryEntry> nativeRows = new ArrayList<>();
		for (int i = 0; i < count; i += WIDGETS_PER_ROW)
		{
			GeHistoryEntry e = parseRow(kids, i);
			if (e == null)
			{
				log.debug("Could not parse GE history row {}", i / WIDGETS_PER_ROW);
				return;
			}
			nativeRows.add(e);
		}

		// 2. Sync with the store: archives rows that fell off the bottom since last time.
		GeHistoryStore.SyncResult sync = store.syncNative(
				nativeRows, Math.min(MAX_EXTRA_ROWS, Math.max(0, config.extendedGeHistorySize())));
		List<GeHistoryEntry> archive = sync.archive;

		// 3. Track the game's rows so they can be filtered and given trade-age labels.
		pitch = rowPitch(kids, nativeRows.size());
		for (int r = 0; r < nativeRows.size(); r++)
		{
			addLabel(kids[r * WIDGETS_PER_ROW + LABEL_IDX], sync.nativeTimes[r]);
			Widget[] w = new Widget[WIDGETS_PER_ROW];
			System.arraycopy(kids, r * WIDGETS_PER_ROW, w, 0, WIDGETS_PER_ROW);
			GeHistoryEntry e = nativeRows.get(r).withTime(sync.nativeTimes[r]);
			rows.add(new Row(w, e, itemName(e.itemId), false));
		}

		// 4. Add archived rows below them, then apply the current search/filter.
		if (!archive.isEmpty() && pitch > 0)
		{
			inject(list, kids, nativeRows, archive);
		}
		Widget[] after = list.getDynamicChildren();
		injectedChildCount = after == null ? 0 : after.length;
		fullScrollHeight = list.getScrollHeight();
		updateLabels();
		applyFilter(list, false);
	}

	// ── Search ───────────────────────────────────────────────────────────────

	/** Search button icon. */
	private static final int SEARCH_SPRITE = SpriteID.Bankbuttons.SEARCH;
	/** Offsets from the top-right of the history frame. */
	private static final int SEARCH_X_FROM_RIGHT = 34;
	private static final int SEARCH_Y = 8;
	private static final int SEARCH_SIZE = 18;
	/** Space between the icon and the edge of its box. */
	private static final int BOX_PAD = 3;
	private static final int BOX_FILL       = 0x3e3529;
	private static final int BOX_FILL_HOVER = 0x5c5040;
	private static final int BOX_EDGE_OUTER = 0x000000;
	private static final int BOX_EDGE_INNER = 0x726451;
	/** Longest query shown in the title before it is cut short. */
	private static final int TITLE_QUERY_MAX = 18;
	private static final int FALLBACK_TEXT_COLOR = 0xff981f;
	private static final int FONT_PLAIN_11 = 494;

	/** A history row (native or archived) and its unfiltered layout. */
	private static class Row
	{
		final Widget[] widgets;
		final int[] baseY;
		final boolean[] selfHidden;
		final int[] baseColor;
		final int[] baseOpacity;
		final GeHistoryEntry entry;
		final String name;
		/** True for archived rows shown below the divider. */
		final boolean archived;
		/** Current top offset after filtering, used for hover hit-testing. */
		int top;

		Row(Widget[] widgets, GeHistoryEntry entry, String name, boolean archived)
		{
			int n = widgets.length;
			this.widgets     = widgets;
			this.entry       = entry;
			this.name        = name;
			this.archived    = archived;
			this.baseY       = new int[n];
			this.selfHidden  = new boolean[n];
			this.baseColor   = new int[n];
			this.baseOpacity = new int[n];
			for (int j = 0; j < n; j++)
			{
				baseY[j]       = widgets[j].getOriginalY();
				selfHidden[j]  = widgets[j].isSelfHidden();
				baseColor[j]   = widgets[j].getTextColor();
				baseOpacity[j] = widgets[j].getOpacity();
			}
		}
	}

	private final List<Row> rows = new ArrayList<>();
	private int pitch;
	/** Rows currently shown, top to bottom. */
	private final List<Row> visibleRows = new ArrayList<>();
	private int fullScrollHeight;
	private String query = "";
	/** The clickable widget on top of the button box. */
	private Widget searchButton;
	/** Header widgets (buttons, fallback text), hidden together on removal. */
	private final List<Widget> searchWidgets = new ArrayList<>();
	private Widget headerParent;
	/** Window title; shows the active search while filtering. */
	private Widget titleWidget;
	private String titleOriginal;
	/** Fallback match count, used when the title widget isn't found. */
	private Widget searchText;

	private void createSearchButton()
	{
		Widget parent = client.getWidget(InterfaceID.GeHistory.FRAME);
		if (parent == null) parent = client.getWidget(InterfaceID.GeHistory.UNIVERSE);
		if (parent == null) return;

		searchWidgets.clear();
		headerParent = parent;
		int boxX    = SEARCH_X_FROM_RIGHT - BOX_PAD;
		int boxY    = SEARCH_Y - BOX_PAD;
		int boxSize = SEARCH_SIZE + 2 * BOX_PAD;

		// Creation order is draw order: fill, outer edge, inner edge, icon, click area.
		Widget fill = box(parent, boxX, boxY, boxSize, true, BOX_FILL);
		box(parent, boxX, boxY, boxSize, false, BOX_EDGE_OUTER);
		box(parent, boxX + 1, boxY + 1, boxSize - 2, false, BOX_EDGE_INNER);

		Widget icon = parent.createChild(-1, WidgetType.GRAPHIC);
		icon.setSpriteId(SEARCH_SPRITE);
		icon.setOpacity(0);
		place(icon, SEARCH_X_FROM_RIGHT, SEARCH_Y, SEARCH_SIZE);
		icon.revalidate();
		searchWidgets.add(icon);

		Widget button = box(parent, boxX, boxY, boxSize, true, 0);
		button.setOpacity(255); // invisible, just catches the mouse
		button.setName("<col=ff9040>Trade history</col>");
		button.setAction(0, "Search");
		button.setHasListener(true);
		// Deferred: these can run scripts, which isn't allowed from inside a widget callback.
		button.setOnOpListener((JavaScriptCallback) ev ->
		{
			int op = ev.getOp();
			clientThread.invokeLater(() ->
			{
				if (op == 2) setQuery("");
				else openSearch();
			});
		});
		button.setOnMouseOverListener((JavaScriptCallback) ev -> fill.setTextColor(BOX_FILL_HOVER));
		button.setOnMouseLeaveListener((JavaScriptCallback) ev -> fill.setTextColor(BOX_FILL));
		button.revalidate();
		searchButton = button;

		// Sell and Buy toggles, left of the search box.
		sideButtons.clear();
		int sellX = boxX + boxSize + BUTTON_GAP;
		int buyX  = sellX + boxSize + BUTTON_GAP;
		createSideButton(parent, Side.SELL, SpriteID.GeIcons.MAKE_OFFER_SELL,
				SpriteID.GeIcons.MAKE_OFFER_SELL_HOVERED, sellX, boxY, boxSize);
		createSideButton(parent, Side.BUY, SpriteID.GeIcons.MAKE_OFFER_BUY,
				SpriteID.GeIcons.MAKE_OFFER_BUY_HOVERED, buyX, boxY, boxSize);
		updateSideButtons();

		findTitle(parent);
		if (titleWidget == null)
		{
			Widget text = parent.createChild(-1, WidgetType.TEXT);
			place(text, buyX + boxSize + BUTTON_GAP, boxY, boxSize);
			text.setOriginalWidth(70);
			text.setXTextAlignment(WidgetTextAlignment.RIGHT);
			text.setYTextAlignment(WidgetTextAlignment.CENTER);
			text.setFontId(FONT_PLAIN_11);
			text.setTextColor(FALLBACK_TEXT_COLOR);
			text.setTextShadowed(true);
			text.revalidate();
			searchWidgets.add(text);
			searchText = text;
		}
	}

	private Widget box(Widget parent, int xFromRight, int y, int size, boolean filled, int color)
	{
		return rect(parent, xFromRight, y, size, size, filled, color);
	}

	private Widget rect(Widget parent, int xFromRight, int y, int w, int h, boolean filled, int color)
	{
		Widget r = parent.createChild(-1, WidgetType.RECTANGLE);
		place(r, xFromRight, y, w, h);
		r.setFilled(filled);
		r.setTextColor(color);
		r.revalidate();
		searchWidgets.add(r);
		return r;
	}

	private static void place(Widget w, int xFromRight, int y, int size)
	{
		place(w, xFromRight, y, size, size);
	}

	private static void place(Widget w, int xFromRight, int y, int width, int height)
	{
		w.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
		w.setOriginalX(xFromRight);
		w.setOriginalY(y);
		w.setOriginalWidth(width);
		w.setOriginalHeight(height);
	}

	/** Find the "... Trade History" title text somewhere under the frame. */
	private void findTitle(Widget parent)
	{
		titleWidget = null;
		titleOriginal = null;
		Widget root = client.getWidget(InterfaceID.GeHistory.UNIVERSE);
		int topY = (root != null ? root : parent).getCanvasLocation().getY();
		Widget found = findText(parent, "history", 4, topY);
		if (found == null && root != null && parent != root)
		{
			found = findText(root, "history", 4, topY);
		}
		if (found != null)
		{
			titleWidget = found;
			titleOriginal = found.getText();
		}
	}

	/** A text widget containing {@code needle} in the window's top bar (within 30px of {@code topY}). */
	private Widget findText(Widget w, String needle, int depth, int topY)
	{
		if (w == null || depth < 0 || searchWidgets.contains(w)) return null;
		if (w.getType() == WidgetType.TEXT
				&& Text.removeTags(nullToEmpty(w.getText())).toLowerCase().contains(needle)
				&& w.getCanvasLocation().getY() - topY < 30)
		{
			return w;
		}
		for (Widget[] kids : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (kids == null) continue;
			for (Widget k : kids)
			{
				Widget hit = findText(k, needle, depth - 1, topY);
				if (hit != null) return hit;
			}
		}
		return null;
	}

	private void showSearchStatus(GeHistoryFilter filter, int shown)
	{
		boolean filtering = filter != null || side != Side.ALL;
		String what = side == Side.BUY ? "buys" : side == Side.SELL ? "sells" : "trades";
		if (titleWidget != null)
		{
			String q = query.trim();
			if (q.length() > TITLE_QUERY_MAX) q = q.substring(0, TITLE_QUERY_MAX - 3) + "...";
			titleWidget.setText(!filtering ? titleOriginal
					: "Showing " + what + (filter != null ? ": " + Text.escapeJagex(q) : "") + " (" + shown + ")");
		}
		else if (searchText != null)
		{
			searchText.setText(!filtering ? "" : shown + " " + what);
		}
	}

	// ── Buy / Sell filter buttons ────────────────────────────────────────────

	enum Side
	{
		ALL, BUY, SELL;

		boolean matches(GeHistoryEntry e)
		{
			return this == ALL || e.buy == (this == BUY);
		}
	}

	private static final int BUTTON_GAP = 4;
	private static final int BOX_FILL_ACTIVE = 0x7a6a50;

	private Side side = Side.ALL;

	private static class SideButton
	{
		final Side side;
		final Widget fill;
		final Widget icon;
		final Widget click;
		/** Normal and highlighted sprites. */
		final int sprite;
		final int brightSprite;
		boolean hover;

		SideButton(Side side, Widget fill, Widget icon, Widget click, int sprite, int brightSprite)
		{
			this.side         = side;
			this.fill         = fill;
			this.icon         = icon;
			this.click        = click;
			this.sprite       = sprite;
			this.brightSprite = brightSprite;
		}
	}

	private final List<SideButton> sideButtons = new ArrayList<>();

	/** Buy / Sell toggle button. */
	private void createSideButton(Widget parent, Side s, int sprite, int brightSprite, int xFromRight, int y, int size)
	{
		Widget fill = box(parent, xFromRight, y, size, true, BOX_FILL);
		box(parent, xFromRight, y, size, false, BOX_EDGE_OUTER);
		box(parent, xFromRight + 1, y + 1, size - 2, false, BOX_EDGE_INNER);

		Widget icon = parent.createChild(-1, WidgetType.GRAPHIC);
		icon.setSpriteId(sprite);
		icon.setOpacity(0);
		place(icon, xFromRight + BOX_PAD, y + BOX_PAD, size - 2 * BOX_PAD);
		icon.revalidate();
		searchWidgets.add(icon);

		Widget click = box(parent, xFromRight, y, size, true, 0);
		click.setOpacity(255); // invisible, just catches the mouse
		click.setName("<col=ff9040>Trade history</col>");
		click.setHasListener(true);

		SideButton b = new SideButton(s, fill, icon, click, sprite, brightSprite);
		click.setOnOpListener((JavaScriptCallback) ev -> clientThread.invokeLater(() -> toggleSide(s)));
		click.setOnMouseOverListener((JavaScriptCallback) ev ->
		{
			b.hover = true;
			updateSideButtons();
		});
		click.setOnMouseLeaveListener((JavaScriptCallback) ev ->
		{
			b.hover = false;
			updateSideButtons();
		});
		click.revalidate();
		sideButtons.add(b);
	}

	private void toggleSide(Side s)
	{
		side = side == s ? Side.ALL : s;
		Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		if (list != null && !list.isHidden()) applyFilter(list, true);
		updateSideButtons(); // applyFilter skips this when the list is empty
	}

	private void updateSideButtons()
	{
		for (SideButton b : sideButtons)
		{
			boolean active = side == b.side;
			b.fill.setTextColor(b.hover ? BOX_FILL_HOVER : active ? BOX_FILL_ACTIVE : BOX_FILL);
			b.icon.setSpriteId(active || b.hover ? b.brightSprite : b.sprite);
			b.click.setAction(0, active ? "Show all trades"
					: b.side == Side.BUY ? "Show buys only" : "Show sells only");
		}
	}

	/** Open search prompt, if any. */
	private ChatboxTextInput searchInput;

	private void openSearch()
	{
		searchInput = chatboxPanelManager.openTextInput("Search trade history (name, >100m, each<5k, 1m-5m)")
				.value(query)
				.onChanged(s -> clientThread.invokeLater(() -> setQuery(s)))
				.onDone((Consumer<String>) s -> clientThread.invokeLater(() -> setQuery(s)))
				.build();
	}

	/** Close the search prompt if it is still the active chatbox input. */
	private void closeSearchInput()
	{
		if (searchInput == null) return;
		if (chatboxPanelManager.getCurrentInput() == searchInput)
		{
			chatboxPanelManager.close();
		}
		searchInput = null;
	}

	/** Filtering lays out every row, so it waits for a pause in typing (applied in tick()). */
	private static final long FILTER_DEBOUNCE_MS = 150;
	private long filterDueAt;

	private void setQuery(String q)
	{
		query = q == null ? "" : q;
		filterDueAt = System.currentTimeMillis() + FILTER_DEBOUNCE_MS;
	}

	/** Hide non-matching rows, pack the rest to the top and re-stripe the backgrounds. */
	private void applyFilter(Widget list, boolean scrollToTop)
	{
		if (rows.isEmpty() || pitch <= 0) return;
		GeHistoryFilter filter = GeHistoryFilter.parse(query);

		Row[] stripes = {rows.get(0), rows.size() > 1 ? rows.get(1) : rows.get(0)};

		int contentBottom = 0;
		for (Row row : rows)
		{
			for (int j = 0; j < WIDGETS_PER_ROW; j++)
			{
				contentBottom = Math.max(contentBottom, row.baseY[j] + row.widgets[j].getOriginalHeight());
			}
		}
		int padding = Math.max(0, fullScrollHeight - contentBottom);

		visibleRows.clear();
		int shown = 0;
		int shownNative = 0;
		boolean anyArchivedShown = false;
		int bottom = 0;
		for (int r = 0; r < rows.size(); r++)
		{
			Row row = rows.get(r);
			boolean visible = side.matches(row.entry)
					&& (filter == null || filter.matches(row.entry, row.name));
			if (visible)
			{
				if (row.archived) anyArchivedShown = true;
				else shownNative++;
			}
			for (int j = 0; j < WIDGETS_PER_ROW; j++)
			{
				Widget w = row.widgets[j];
				if (!visible)
				{
					w.setHidden(true);
					continue;
				}
				w.setHidden(row.selfHidden[j]);
				w.setOriginalY(row.baseY[j] + (shown - r) * pitch);
				if (w.getType() == WidgetType.RECTANGLE)
				{
					// Keep shades alternating after rows move.
					Row shade = (shown - r) % 2 == 0 ? row : stripes[shown % 2];
					w.setTextColor(shade.baseColor[j]);
					w.setOpacity(shade.baseOpacity[j]);
				}
				w.revalidate();
				bottom = Math.max(bottom, w.getOriginalY() + w.getOriginalHeight());
			}
			if (visible)
			{
				int top = Integer.MAX_VALUE;
				for (Widget w : row.widgets) top = Math.min(top, w.getOriginalY());
				row.top = top;
				visibleRows.add(row);
				shown++;
			}
		}

		// Archived rows are offset by DIVIDER_H; the divider sits in that gap.
		layoutDivider(list, rowTop() + shownNative * pitch, anyArchivedShown);

		boolean filtering = filter != null || side != Side.ALL;
		list.setScrollHeight(filtering ? bottom + padding : fullScrollHeight);
		if (scrollToTop) list.setScrollY(0);
		list.revalidateScroll();
		client.runScript(ScriptID.UPDATE_SCROLLBAR,
				InterfaceID.GeHistory.SCROLLBAR, InterfaceID.GeHistory.LIST, list.getScrollY());

		if (searchButton != null)
		{
			searchButton.setAction(1, filter == null ? null : "Clear search");
		}
		updateSideButtons();
		showSearchStatus(filter, shown);
	}

	// ── Hover details ────────────────────────────────────────────────────────

	private static final DateTimeFormatter TRADE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
	private static final String DIM   = "9f9f9f";
	private static final String UP    = "00ff00";
	private static final String DOWN  = "ff3030";

	/** Called every frame before rendering; tooltips have to be re-added each frame. */
	void addHoverTooltip()
	{
		if (!config.showTradeDetails() || visibleRows.isEmpty() || client.isMenuOpen()) return;

		Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		if (list == null || list.isHidden()) return;

		Point mouse = client.getMouseCanvasPosition();
		Rectangle view = list.getBounds();
		if (mouse == null || view == null || !view.contains(mouse.getX(), mouse.getY())) return;

		int y = mouse.getY() - view.y + list.getScrollY();
		for (Row row : visibleRows)
		{
			if (y < row.top) return; // divider gap
			if (y < row.top + pitch)
			{
				tooltipManager.add(new Tooltip(tooltipText(row.entry, row.name,
						latestHigh.get().get(row.entry.itemId), latestLow.get().get(row.entry.itemId),
						ZoneId.systemDefault())));
				return;
			}
		}
	}

	/** Tooltip for a history row. "Now" uses the wiki high for buys and the wiki low for sales. */
	static String tooltipText(GeHistoryEntry e, String name, Long high, Long low, ZoneId zone)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(name == null || name.isEmpty() ? "Item " + e.itemId : name);

		sb.append("</br>").append(e.buy ? "Bought " : "Sold ")
				.append(QuantityFormatter.formatNumber(e.quantity)).append(" x ")
				.append(QuantityFormatter.formatNumber(e.each)).append(" ea");
		if (e.quantity > 1)
		{
			sb.append(" = ").append(QuantityFormatter.formatNumber(e.total));
		}

		sb.append("</br><col=").append(DIM).append(">")
				.append(e.timestamp > 0
						? TRADE_TIME.format(Instant.ofEpochMilli(e.timestamp).atZone(zone))
						: "Time unknown")
				.append("</col>");

		Long now = e.buy ? high : low;
		if (now != null && now > 0)
		{
			sb.append("</br>Now: ").append(QuantityFormatter.formatNumber(now));
			if (e.each > 0)
			{
				double change = (now - e.each) * 100.0 / e.each;
				String col = change > 0.05 ? UP : change < -0.05 ? DOWN : DIM;
				sb.append(" <col=").append(col).append(">(")
						.append(String.format(Locale.ROOT, "%+.1f%%", change)).append(")</col>");
			}
		}
		if (high != null || low != null)
		{
			sb.append("</br><col=").append(DIM).append(">Wiki buy ")
					.append(high != null ? QuantityFormatter.formatNumber(high) : "-")
					.append(" / sell ")
					.append(low != null ? QuantityFormatter.formatNumber(low) : "-")
					.append("</col>");
		}
		return sb.toString();
	}

	// ── Divider between native and archived rows ─────────────────────────────

	private static final int DIVIDER_H = 18;
	private static final int DIVIDER_SIDE_PAD = 6;
	private static final int DIVIDER_TEXT_GAP = 6;
	private static final String DIVIDER_TEXT = "Older trades - saved by Flipping Masterminds";
	private static final int DIVIDER_TEXT_COLOR = 0xff981f;
	private static final int DIVIDER_LINE_COLOR = 0x726451;

	private Widget dividerText;
	private Widget dividerLeft;
	private Widget dividerRight;

	private void createDivider(Widget list)
	{
		dividerLeft  = dividerLine(list);
		dividerRight = dividerLine(list);
		dividerRight.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);

		dividerText = list.createChild(-1, WidgetType.TEXT);
		dividerText.setText(DIVIDER_TEXT);
		dividerText.setFontId(FONT_PLAIN_11);
		dividerText.setTextColor(DIVIDER_TEXT_COLOR);
		dividerText.setTextShadowed(true);
		dividerText.setXTextAlignment(WidgetTextAlignment.CENTER);
		dividerText.setYTextAlignment(WidgetTextAlignment.CENTER);
		dividerText.setWidthMode(WidgetSizeMode.MINUS);
		dividerText.setOriginalWidth(0);
		dividerText.setOriginalHeight(DIVIDER_H);
		injected.add(dividerText);
	}

	private Widget dividerLine(Widget list)
	{
		Widget w = list.createChild(-1, WidgetType.RECTANGLE);
		w.setFilled(true);
		w.setTextColor(DIVIDER_LINE_COLOR);
		w.setOriginalX(DIVIDER_SIDE_PAD);
		w.setOriginalHeight(1);
		injected.add(w);
		return w;
	}

	/** Place the divider with its top at {@code top}, lines either side of the centred text. */
	private void layoutDivider(Widget list, int top, boolean visible)
	{
		if (dividerText == null) return;
		int textWidth = dividerText.getFont() != null
				? dividerText.getFont().getTextWidth(DIVIDER_TEXT) : 220;
		int lineWidth = Math.max(0, (list.getWidth() - textWidth) / 2 - DIVIDER_TEXT_GAP - DIVIDER_SIDE_PAD);

		dividerText.setOriginalY(top);
		for (Widget line : new Widget[]{dividerLeft, dividerRight})
		{
			line.setOriginalY(top + DIVIDER_H / 2);
			line.setOriginalWidth(lineWidth);
		}
		for (Widget w : new Widget[]{dividerText, dividerLeft, dividerRight})
		{
			w.setHidden(!visible);
			w.revalidate();
		}
	}

	/** Top offset of the first native row. */
	private int rowTop()
	{
		int top = Integer.MAX_VALUE;
		for (int y : rows.get(0).baseY) top = Math.min(top, y);
		return top;
	}

	private void forgetDivider()
	{
		dividerText = dividerLeft = dividerRight = null;
	}

	/** Restore native rows to their original layout. */
	private void resetLayout()
	{
		for (Row row : rows)
		{
			if (row.archived) continue;
			for (int j = 0; j < WIDGETS_PER_ROW; j++)
			{
				Widget w = row.widgets[j];
				w.setOriginalY(row.baseY[j]);
				w.setHidden(row.selfHidden[j]);
				w.setTextColor(row.baseColor[j]);
				w.setOpacity(row.baseOpacity[j]);
				w.revalidate();
			}
		}
	}

	// ── Trade age labels ─────────────────────────────────────────────────────

	private static final String AGE_COLOR = "9f9f9f";
	private static final long LABEL_REFRESH_MS = 30_000;

	private static class Label
	{
		final Widget widget;
		final String base;
		final long time;

		Label(Widget widget, String base, long time)
		{
			this.widget = widget;
			this.base   = base;
			this.time   = time;
		}
	}

	private final List<Label> labels = new ArrayList<>();
	private long lastLabelUpdate;

	private void addLabel(Widget w, long time)
	{
		labels.add(new Label(w, stripAge(w.getText()), time));
	}

	/** Label text without the appended age line. */
	private static String stripAge(String text)
	{
		text = nullToEmpty(text);
		int br = text.indexOf("<br>");
		return br < 0 ? text : text.substring(0, br);
	}

	private void updateLabels()
	{
		lastLabelUpdate = System.currentTimeMillis();
		boolean show = config.showTradeAge();
		for (Label l : labels)
		{
			String text = l.base;
			if (show && l.time > 0)
			{
				text += "<br><col=" + AGE_COLOR + ">" + formatAge(lastLabelUpdate - l.time) + "</col>";
			}
			if (!text.equals(l.widget.getText())) l.widget.setText(text);
		}
	}

	private void restoreLabels()
	{
		for (Label l : labels)
		{
			l.widget.setText(l.base);
		}
		labels.clear();
	}

	static String formatAge(long ms)
	{
		long mins = Math.max(0, ms) / 60_000;
		if (mins < 1)   return "just now";
		if (mins < 60)  return mins + "m ago";
		long hours = mins / 60;
		if (hours < 24) return hours + "h ago";
		long days = hours / 24;
		if (days < 365) return days + "d ago";
		return (days / 365) + "y ago";
	}

	/** Hide all injected widgets. If the feature is still enabled, the next tick re-injects. */
	void removeInjected()
	{
		Widget list = client.getWidget(InterfaceID.GeHistory.LIST);
		if (list != null && injectedChildCount >= 0)
		{
			resetLayout();
			rows.clear();
			visibleRows.clear();
			hideInjected(list);
			forgetDivider();
			restoreLabels();
			refresh = true;
		}
		closeSearchInput();
		for (Widget w : searchWidgets) w.setHidden(true);
		if (headerParent != null) removeTrailing(headerParent, searchWidgets);
		headerParent = null;
		searchWidgets.clear();
		sideButtons.clear();
		if (titleWidget != null) titleWidget.setText(titleOriginal);
		titleWidget = null;
		searchButton = null;
		searchText = null;
	}

	private void hideInjected(Widget list)
	{
		for (Widget w : injected)
		{
			w.setHidden(true);
		}
		removeTrailing(list, injected);
		injected.clear();
		list.setScrollHeight(nativeScrollHeight);
		list.revalidateScroll();
		client.runScript(ScriptID.UPDATE_SCROLLBAR,
				InterfaceID.GeHistory.SCROLLBAR, InterfaceID.GeHistory.LIST,
				Math.min(list.getScrollY(), Math.max(0, nativeScrollHeight - list.getHeight())));
	}

	/**
	 * Drop our widgets from the end of {@code parent}'s dynamic children so they don't pile
	 * up across re-injections. Only trailing ones are removed, so other children keep their
	 * indexes; anything of ours that isn't at the end simply stays hidden.
	 */
	private static void removeTrailing(Widget parent, List<Widget> ours)
	{
		Widget[] all = parent.getChildren();
		if (all == null || ours.isEmpty()) return;

		Set<Widget> mine = Collections.newSetFromMap(new IdentityHashMap<>());
		mine.addAll(ours);
		int keep = all.length;
		while (keep > 0 && (all[keep - 1] == null || mine.contains(all[keep - 1]))) keep--;
		if (keep < all.length) parent.setChildren(Arrays.copyOf(all, keep));
	}

	static GeHistoryEntry parseRow(Widget[] kids, int start)
	{
		Widget label = kids[start + LABEL_IDX];
		Widget item  = kids[start + ITEM_IDX];
		Widget price = kids[start + PRICE_IDX];
		if (label == null || item == null || price == null) return null;

		String labelText = Text.removeTags(nullToEmpty(label.getText())).trim();
		boolean buy;
		if (labelText.startsWith("Bought")) buy = true;
		else if (labelText.startsWith("Sold")) buy = false;
		else return null;

		int itemId = item.getItemId();
		int qty    = item.getItemQuantity();
		if (itemId <= 0 || qty <= 0) return null;

		long[] prices = parsePrices(price.getText(), qty);
		if (prices == null) return null;

		return new GeHistoryEntry(buy, itemId, qty, prices[0], prices[1], 0);
	}

	/** @return {total, each} parsed from the price text, or null */
	static long[] parsePrices(String rawText, int qty)
	{
		String text = Text.removeTags(nullToEmpty(rawText).replace("<br>", " "));
		Long total = null;
		Long each  = null;
		Matcher m = NUMBER.matcher(text);
		while (m.find())
		{
			long v;
			try
			{
				v = Long.parseLong(m.group().replace(",", ""));
			}
			catch (NumberFormatException e)
			{
				return null;
			}
			if (EACH_AFTER.matcher(text.substring(m.end())).find()) each = v;
			else if (total == null) total = v;
		}
		if (total == null && each == null) return null;
		if (total == null) total = each * qty;
		if (each == null)  each = qty > 0 ? total / qty : total;
		return new long[]{total, each};
	}

	// ── Injection ────────────────────────────────────────────────────────────

	/**
	 * Add one row per archived trade below the game's rows. Each is a copy of a native row
	 * with the item, quantity, label and prices swapped for the archived trade.
	 */
	private void inject(Widget list, Widget[] kids, List<GeHistoryEntry> nativeRows,
						List<GeHistoryEntry> archive)
	{
		int n = nativeRows.size();
		int bottom = nativeScrollHeight;
		createDivider(list);
		layoutDivider(list, rowTop() + n * pitch, true);
		for (int m = 0; m < archive.size(); m++)
		{
			GeHistoryEntry entry = archive.get(m);
			int row = n + m;
			int t = pickTemplate(nativeRows, row, entry);
			GeHistoryEntry template = nativeRows.get(t);
			String templateName = itemName(template.itemId);
			String newName = itemName(entry.itemId);
			int dy = (row - t) * pitch + DIVIDER_H;
			Widget[] created = new Widget[WIDGETS_PER_ROW];

			for (int j = 0; j < WIDGETS_PER_ROW; j++)
			{
				Widget src = kids[t * WIDGETS_PER_ROW + j];
				Widget dst = list.createChild(-1, src.getType());
				created[j] = dst;
				copyWidget(src, dst, dy);

				if (src.getItemId() > 0 && src.getItemId() == template.itemId)
				{
					dst.setItemId(entry.itemId);
					dst.setItemQuantity(entry.quantity);
				}

				String text = src.getText();
				if (text != null && !text.isEmpty())
				{
					if (j == LABEL_IDX)
					{
						text = stripAge(text);
						if (template.buy != entry.buy)
						{
							text = entry.buy ? text.replace("Sold", "Bought") : text.replace("Bought", "Sold");
						}
					}
					else if (j == PRICE_IDX)
					{
						text = rewritePrices(text, entry);
					}
					else if (!templateName.isEmpty() && text.contains(templateName))
					{
						text = text.replace(templateName, newName);
					}
					dst.setText(text);
				}

				dst.revalidate();
				injected.add(dst);
				if (j == LABEL_IDX) addLabel(dst, entry.timestamp);
				bottom = Math.max(bottom, dst.getOriginalY() + dst.getOriginalHeight());
			}
			rows.add(new Row(created, entry, newName, true));
		}

		list.setScrollHeight(bottom);
		list.revalidateScroll();
		client.runScript(ScriptID.UPDATE_SCROLLBAR,
				InterfaceID.GeHistory.SCROLLBAR, InterfaceID.GeHistory.LIST, list.getScrollY());
	}

	/** Vertical distance between rows: the gap between the first two, or one row's height. */
	private static int rowPitch(Widget[] kids, int rows)
	{
		if (rows >= 2)
		{
			return kids[WIDGETS_PER_ROW].getOriginalY() - kids[0].getOriginalY();
		}
		int top = Integer.MAX_VALUE, bottom = Integer.MIN_VALUE;
		for (int j = 0; j < WIDGETS_PER_ROW; j++)
		{
			top    = Math.min(top, kids[j].getOriginalY());
			bottom = Math.max(bottom, kids[j].getOriginalY() + kids[j].getOriginalHeight());
		}
		return bottom - top;
	}

	/**
	 * Pick the native row to clone. Rows alternate background shade, so match the
	 * row parity first, then buy/sell and whether the price shows an "each" line.
	 */
	static int pickTemplate(List<GeHistoryEntry> nativeRows, int row, GeHistoryEntry entry)
	{
		int best = -1, bestScore = -1;
		for (int i = nativeRows.size() - 1; i >= 0; i--)
		{
			GeHistoryEntry r = nativeRows.get(i);
			int score = 0;
			if (i % 2 == row % 2) score += 4;
			if (r.buy == entry.buy) score += 2;
			if ((r.quantity > 1) == (entry.quantity > 1)) score += 1;
			if (score > bestScore)
			{
				best = i;
				bestScore = score;
				if (score == 7) break;
			}
		}
		return best;
	}

	/** Swap the numbers in the price text, leaving tags and wording untouched. */
	static String rewritePrices(String raw, GeHistoryEntry entry)
	{
		// "each" may follow in the same text run or after a tag.
		return TaggedText.replace(raw, NUMBER, (m, after) -> QuantityFormatter.formatNumber(
				EACH_AFTER.matcher(Text.removeTags(after)).find() ? entry.each : entry.total));
	}

	/** Copy a game widget's look onto one of ours, moved down by {@code dy}. Listeners are not copied. */
	private static void copyWidget(Widget src, Widget dst, int dy)
	{
		dst.setContentType(src.getContentType());
		dst.setOriginalX(src.getOriginalX());
		dst.setOriginalY(src.getOriginalY() + dy);
		dst.setOriginalWidth(src.getOriginalWidth());
		dst.setOriginalHeight(src.getOriginalHeight());
		dst.setXPositionMode(src.getXPositionMode());
		dst.setYPositionMode(src.getYPositionMode());
		dst.setWidthMode(src.getWidthMode());
		dst.setHeightMode(src.getHeightMode());
		dst.setTextColor(src.getTextColor());
		dst.setFontId(src.getFontId());
		dst.setTextShadowed(src.getTextShadowed());
		dst.setXTextAlignment(src.getXTextAlignment());
		dst.setYTextAlignment(src.getYTextAlignment());
		dst.setLineHeight(src.getLineHeight());
		dst.setOpacity(src.getOpacity());
		dst.setFilled(src.isFilled());
		dst.setSpriteId(src.getSpriteId());
		dst.setSpriteTiling(src.getSpriteTiling());
		dst.setBorderType(src.getBorderType());
		dst.setItemId(src.getItemId());
		dst.setItemQuantity(src.getItemQuantity());
		dst.setItemQuantityMode(src.getItemQuantityMode());
		dst.setName(src.getName());
		dst.setHidden(src.isSelfHidden());
	}

	private String itemName(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (Exception e)
		{
			return "";
		}
	}

	private static String nullToEmpty(String s)
	{
		return s == null ? "" : s;
	}
}
