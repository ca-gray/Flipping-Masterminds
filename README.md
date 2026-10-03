[![Flipping Masterminds](https://i.postimg.cc/L5837SRr/watermark.png)](https://postimg.cc/Vr2tr3JR)

# Flipping Masterminds Plugin

A RuneLite plugin that gives you a real-time, filterable view of item price movements across the OSRS Grand Exchange. Built for and by merchers.

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
| Min Volume  | Exclude items with fewer total trades in the selected window |

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

## Settings

All settings are in the RuneLite plugin configuration panel (wrench icon next to the plugin name).

| Setting                      | Default | Description                                                       |
|------------------------------|---------|-------------------------------------------------------------------|
| API Token                    | (empty) | Your Flipping Masterminds API token for GE monitoring             |
| Show Volume                  | On      | Display trade volume on each item row                             |
| Show Prices                  | On      | Display historical and current price on each item row             |
| Auto Refresh Prices          | On      | Automatically fetch latest prices on a timer                      |
| Auto Refresh Interval (mins) | 5       | How often to auto-refresh (minimum 2 minutes)                     |
| GE Price Overlay             | On      | Highlight GE offer slots by price competitiveness                 |
| GE Latest Prices             | On      | Show latest wiki buy/sell price on the GE item examine text       |

[![image.png](https://i.postimg.cc/yNrTCJxM/image.png)](https://postimg.cc/t1W6h4hk)
---

## GE Monitoring

If you provide an API token in settings, the plugin sends snapshots of your open GE slots to the Flipping Masterminds API:

- **On login** -- a snapshot is sent a few seconds after you log in
- **On slot change** -- any GE offer change triggers a debounced update
- **Buy limit data** -- tracked quantities are included in the snapshot

> GE monitoring requires a valid API token. If no token is set, no data is ever sent. Run `/generate_api_token` in the FMM Discord to get your token.

---

## Data Sources

- Price and volume data from the [OSRS Wiki Prices API](https://prices.runescape.wiki/osrs/)
- Item metadata from the [Weird Gloop item dump](https://chisel.weirdgloop.org/)

---

## Links

- **Discord:** [discord.gg/VnsS2PP4Vt](https://discord.gg/VnsS2PP4Vt)
- **GitHub:** [github.com/ca-gray/Flipping-Masterminds](https://github.com/ca-gray/Flipping-Masterminds)

---

## License

Licensed under the BSD 2-Clause License.
