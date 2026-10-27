[![Flipping Masterminds](https://i.postimg.cc/L5837SRr/watermark.png)](https://postimg.cc/Vr2tr3JR)

# Flipping Masterminds Plugin

A RuneLite plugin that gives you a real-time, filterable view of item price movements across the OSRS Grand Exchange. Built for Merchers by the Merchers.

[![Plugin Overview](https://i.postimg.cc/8c2BYxyb/image.png)](https://postimg.cc/H8tycv1J)
---

## Features

### Market View

Browse items ranked by price movement over any time window. Switch between **Top Performers** (rising) and **Underperformers** (falling) to spot opportunities from either side of the market.

**Filters** apply instantly as you type or select:

| Filter      | Description                                                  |
|-------------|--------------------------------------------------------------|
| Time Range  | Day / Week / Month / Year                                    |
| Performance | Top Performers (rising) or Underperformers (falling)         |
| Min Price   | Exclude items below this GE price                            |
| Max Price   | Exclude items above this GE price                            |
| Min 24h Vol | Exclude items with fewer trades in the last 24 hours          |

Each item row shows percentage change and absolute GP change (colour-coded green/red). Optional detail lines for **trade volume** and **historical vs current price** can be toggled from the plugin settings.

---

### Price Alerts

Set alerts to be notified when an item's price crosses a target. Alerts trigger as in-game chat messages and are shown in the Alerts tab.

- Search items by name and set a target GP, direction (above/below), and expiry
- Shorthand price input supported (e.g. `10k`, `1.5m`, `2.1b`)
- Duration input supported (e.g. `12h`, `3d`, `1w`)
- Paste `/track_price` commands from the FMM Discord bot to add alerts directly
- Alert history tracks triggered and expired alerts

[![Alerts Tab](https://i.postimg.cc/9XbBtNt7/image.png)](https://postimg.cc/py98PCSW)
---

### Buy Limit Tracking

Automatically tracks your 4-hour GE buy limit windows. When you buy items on the GE, the plugin records quantities and shows countdown timers until each limit resets.

- Tracked separately for each account, with a dropdown to view the current account, all accounts at once, or any single account
- In-game chat notification when a buy limit window expires
- Tab notification dot when limits reset while viewing another tab

[![Buy_limit_overview](https://i.postimg.cc/jjYNrz4R/image.png)](https://postimg.cc/WdStGqyy)
---

### GE Price Overlay

Highlights your active GE offer slots based on how your offer price compares to the latest wiki prices:

| Colour | Meaning                                             |
|--------|-----------------------------------------------------|
| Green  | Your offer is competitive (at or better than market) |
| Yellow | Your offer exactly matches the latest wiki price     |
| Red    | Your offer is outside the current market range       |

The highlight also appears on the confirm button when setting up a new offer.

[![Ge slot colours](https://i.postimg.cc/L6fLQCjC/image.png)](https://postimg.cc/bsyZsRCk)
---

### GE Latest Prices

When examining an item in the GE offer setup screen, the plugin appends the latest wiki buy/sell price to the item description text. This works alongside RuneLite's built-in Grand Exchange plugin without conflict.

[![GE Lastest](https://i.postimg.cc/CM2sQnXS/image.png)](https://postimg.cc/hh9mJvK5)
---

### Extended GE Trade History

The in-game GE trade history only keeps your most recent trades. Flipping Masterminds saves older ones and shows them directly below the game's own list, in the same style, with a divider marking where the saved trades begin.

- **Search** with the magnifying glass in the history window: item names, total value (`>100m`, `<5k`, `1m-5m`), price per item (`each>1m`), or a mix (`whip >1m`)
- **Buy / Sell filters** next to the search to show only buys or only sells
- **Trade age** under Bought/Sold, e.g. `30m ago`, `21h ago`, `10d ago`
- **Hover details**: exact date and time, price per item, and today's wiki price with the change since the trade

[![image.png](https://i.postimg.cc/bJ4xc7F0/image.png)](https://postimg.cc/dkmkGxFD)

Trades are saved locally per account in `.runelite/plugin-data/flipping-masterminds/`. Trades made outside RuneLite (e.g. mobile) are picked up from the game's list the next time you open the history in RuneLite. Trade times are when you collected the trade in RuneLite, and are only shown when known.

---

## Settings

All settings are in the RuneLite plugin configuration panel (wrench icon next to the plugin name), grouped into sections.

| Section          | Setting                       | Default | Description                                                     |
|------------------|-------------------------------|---------|-----------------------------------------------------------------|
| Account          | API Token                     | (empty) | Your Flipping Masterminds API token for GE monitoring           |
| Market Panel     | Show Volume                   | On      | Display 24h trade volume on each item row                       |
| Market Panel     | Show Prices                   | On      | Display historical and current price on each item row           |
| Market Panel     | Auto Refresh Prices           | On      | Automatically fetch latest prices on a timer                    |
| Market Panel     | Auto Refresh Interval         | 5 mins  | How often to auto-refresh (minimum 2 minutes)                   |
| Grand Exchange   | Offer Slot Highlights         | On      | Highlight GE offer slots by price competitiveness               |
| Grand Exchange   | Latest Prices in Examine Text | On      | Show latest wiki buy/sell price on the GE item examine text     |
| Grand Exchange   | Full Values on Offer Slots    | On      | Show exact amounts (130,311,121) instead of shortened (130.3M)  |
| GE Trade History | Extended History              | On      | Save older trades and show them below the in-game history       |
| GE Trade History | Extra Rows to Keep            | 500     | How many older trades to keep (max 2000)                        |
| GE Trade History | Show Trade Age                | On      | Show how long ago each trade happened                           |
| GE Trade History | Trade Details on Hover        | On      | Show time, price per item and today's price when hovering a row |
| Notifications    | Price Alerts                  | On      | Desktop notification when a price alert triggers or expires     |
| Notifications    | Buy Limit Resets              | Off     | Desktop notification when a buy limit window resets             |

---

## GE Monitoring

If you provide an API token in settings, the plugin sends snapshots of your open GE slots to the Flipping Masterminds API (`api.flippingmasterminds.net`):

- **On login** -- a snapshot is sent a few seconds after you log in
- **On slot change** -- any GE offer change triggers a debounced update

Each snapshot contains your character name, RuneLite account hash, your GE slots (item, price, quantities, state) and your tracked buy limit quantities.

> GE monitoring requires a valid API token. If no token is set, no data is ever sent to the Flipping Masterminds API. Run `/generate_api_token` in the FMM Discord to get your token.

Your extended trade history is never sent anywhere; it stays in `.runelite/plugin-data/flipping-masterminds/` on your computer.

---

## Data Sources

- Prices, volumes and item names from the [OSRS Wiki Prices API](https://prices.runescape.wiki/osrs/)
- Item icons from the game cache via RuneLite

---

## Links

- **Discord:** [discord.gg/VnsS2PP4Vt](https://discord.gg/VnsS2PP4Vt)
- **GitHub:** [github.com/ca-gray/Flipping-Masterminds](https://github.com/ca-gray/Flipping-Masterminds)

---

## License

Licensed under the BSD 2-Clause License.
