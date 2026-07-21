
[![Flipping Masterminds](https://i.postimg.cc/L5837SRr/watermark.png)](https://postimg.cc/Vr2tr3JR)

# Flipping Masterminds Plugin

The **Flipping Masterminds Plugin** is developed and maintained by the Admins and Owners of the **Flipping Masterminds Discord community**.  
It provides data-driven insights into the **Old School RuneScape (OSRS)** market to help users make informed flipping and merching decisions.

---

## Overview

The Flipping Masterminds Plugin gives you a real-time, filterable view of item price movements across the OSRS Grand Exchange. Whether you're identifying the next big flip or trimming underperformers from your portfolio, the plugin surfaces exactly what you need without leaving the RuneLite sidebar.

![Plugin Overview](https://i.postimg.cc/PrBkZ6XP/image.png)

---

## Features

### 📈 Top Performers & Underperformers

Browse items ranked by their price movement over any time window. Switch between **Top Performers** (rising items) and **Underperformers** (falling items) to quickly spot opportunities from either side of the market.

---

### 🕐 Time Range Selection

Analyse price changes across four time windows - **Day**, **Week**, **Month**, or **Year**. The price movement and trade volume figures update instantly when you switch window.

| Window | Data Source      | Best For                          |
|--------|------------------|-----------------------------------|
| Day    | 5m interval      | Short-term flips                  |
| Week   | 1h interval      | Medium-term trends                |
| Month  | 6h interval      | Seasonal patterns                 |
| Year   | 24h interval     | Long-term investment targets      |

---

### 🔍 Filters

Narrow down the item list without leaving the panel. All filters apply instantly as you type or select.

| Filter       | Description                                                              |
|--------------|--------------------------------------------------------------------------|
| Time Range   | Day / Week / Month / Year price window                                   |
| Performance  | Top Performers (rising) or Underperformers (falling)                     |
| Min Price    | Exclude items below this GE price (at start of window)                   |
| Max Price    | Exclude items above this GE price (at start of window)                   |
| Min Volume   | Exclude items with fewer total trades in the selected window             |

![Filter Panel](https://i.postimg.cc/wBN5MjWk/image.png)

---

### 💰 Per-Item Price & Volume Details

Each row in the list can show optional detail lines controlled from the RuneLite settings panel:

- **% Change + absolute GP change** - always visible, colour-coded green/red
- **Show Prices** - displays the historical snapshot price and the current price side-by-side (e.g. `150K → 210K gp`)
- **Show Volume** - displays total trade count in the selected window (e.g. `Vol: 24.3K`)

![Item Row Detail](https://i.postimg.cc/fRtmqbhQ/image.png)

---

### ⚙️ Settings Panel Toggles

**Show Volume** and **Show Prices** are toggled from the RuneLite plugin settings panel (wrench icon), not from the sidebar. Changes apply to the item list immediately with no need to refresh.

![Settings Panel](https://i.postimg.cc/6397KWgD/image.png)

---

### 🔗 Wiki Price Links

Every item row has a globe button (🌐) on the right side. Clicking it opens that item's page on the OSRS Wiki Prices site in your browser. The button highlights on hover and darkens on click so it's always clear it's interactive.

---

### ⟳ Manual Refresh

Hit the **⟳ Refresh** button at any time to re-fetch all price and volume data from the Wiki API. The button shows **Fetching…** while the request is in flight and stamps the time of the last successful load next to it.

---

### 📊 Grand Exchange Monitoring

If you provide an API token in settings, the plugin automatically sends a snapshot of your open GE slots to the Flipping Masterminds API:

- **On login** - a snapshot is sent a few seconds after you log in (once the client has fully loaded)
- **On slot change** - any change to a GE offer triggers a debounced update (batched within 200 ms)
- **Buy limit tracking** - the plugin tracks quantities bought per item to help you monitor 4-hour buy limits

> **Note:** GE monitoring requires a valid API token in the plugin settings. If no token is set, GE data is never sent. To get an API key, you need to use /generate_api_token in the FMM Discord.


---

## Acknowledgements

Inspired by the Flipping Utilities plugin and the broader OSRS flipping community.
Price data is sourced from the OSRS Wiki Prices API - a free community resource.

---

## License

Licensed under the BSD 2-Clause License.
© Flipping Masterminds - built with love by the community, for the community.

---

Join us on Discord for support, market tips, and influence new features:
https://discord.gg/VnsS2PP4Vt