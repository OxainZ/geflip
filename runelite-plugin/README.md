# Geflip — RuneLite plugin

A RuneLite side panel that brings [geflip](https://oxainz.github.io/geflip/)'s
gp/hour flip ranking **into the client**, and tracks your **own fills** for a live
session P&L — so you stop copy-pasting between the web app and the game.

> **Read-only by design.** It reads Grand Exchange offer *events* and shows
> suggestions. It never sends input, and never places, edits, or collects an offer.
> Automating GE trades is macroing and a **permanent ban** — this plugin does not,
> and must never, do that. Clicking a row only copies the item name to your
> clipboard (to paste into the GE search yourself).

## Status — this is a scaffold to build & test against the live client

The pricing/scoring logic is a faithful port of the web app and is **unit-tested**
(`./gradlew test` runs `GeflipScannerTest` — tax, margin, and trend-penalty are
locked to the same numbers the site uses, cross-checked equal to the JS). The
RuneLite wiring (panel, nav button, `onGrandExchangeOfferChanged` fill tracking) is
written to the standard external-plugin API but has **not been compiled against a
live RuneLite build here** — expect to adjust a signature or two for your RuneLite
version, then verify the panel + session P&L in-game. It is a real starting point,
not a finished hub plugin.

## What it does

- **Ranks flips by gp/hour** in a side panel — the same model as the web app: 2% GE
  tax, buy limits, thinner-leg liquidity, quote staleness, and the **long-term trend
  death-spiral filter** (pulls `data/trends.json` the site publishes; ⚠ marks a
  decliner). Auto-refreshes on a configurable interval.
- **Tracks your fills** — every BOUGHT/SOLD offer event updates a live session P&L
  (proceeds net of the 2% tax), so you see realized profit without hand-logging.
- **One-tap name copy** — click a row, the item name is on your clipboard for the GE
  search box.

## Build

Requires JDK 11 and the RuneLite maven repo (wired in `build.gradle`).

```bash
cd runelite-plugin
./gradlew test        # runs the scoring unit tests (no game needed)
./gradlew build       # produces build/libs/geflip-plugin-1.0.0.jar
```

## Run / sideload

The fastest dev loop is RuneLite's example-plugin runner (a `main()` that boots the
client with your plugin) — see the RuneLite
[example plugin](https://github.com/runelite/example-plugin) wiki. To load the built
jar into your normal client, drop it in `~/.runelite/sideloaded-plugins/` and
restart RuneLite (developer mode). Publishing to the **Plugin Hub** needs its own
repo + the [review process](https://github.com/runelite/runelite/wiki/Plugin-Hub).

## Config

Bankroll, members on/off, min 1h volume, min margin, rows shown, the death-spiral
filter, and the refresh interval. Keep the refresh polite to the wiki API.

## Files

- `GeflipScanner.java` — fetch (wiki + trends) + the gp/hour scoring. Pure logic, tested.
- `GeflipPlugin.java` — lifecycle, nav button, and the read-only fill tracker.
- `GeflipPanel.java` — the side panel (display + click-to-copy).
- `GeflipConfig.java` / `GeflipExempt.java` — settings and the tax-exempt list.

Not affiliated with Jagex.
