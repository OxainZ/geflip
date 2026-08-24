# CLAUDE.md — orient here first, every session

geflip is Jonah's (OxainZ) Old School RuneScape Grand Exchange toolkit.
Three surfaces share this repo:

1. **Web PWA** (`index.html` + `sw.js` + `manifest.webmanifest`) — one-file,
   no-build-step flip finder, live at https://oxainz.github.io/geflip/
   (GitHub Pages serves the repo root). Ranks all ~4,000 tradeables by
   expected **gp/hour** with the 2% GE tax, buy limits, liquidity, drift and
   staleness priced in. Data: OSRS Wiki real-time prices API, fetched
   client-side.
2. **RuneLite plugin trio** (`runelite-plugin/`) — Java 11 source, Gradle:
   - `com.geflip.GeflipPlugin` — in-client flipper (scanner, panel, ledger,
     local server for the phone page)
   - `com.geflip.coach.CoachPlugin` — account coach (DPS, goals, dailies,
     farm/skill plans, its own phone page via `CoachServer`)
   - `com.geflip.jad.JadPrayerPlugin` — Jad prayer helper overlay
3. **Cloudflare sync worker** (`sync-worker/src/worker.js`) — relays
   fills/flips/offers between the plugin and the phone page. CORS-open **by
   design**; the sync-id is the secret. Don't "fix" the CORS.
4. **The AI lane (2026-08-24, `ASK_THE_AI.md`)** — CoachPlugin pushes a full
   account snapshot (`account` key: skills, QP, quests, gp, net worth, CA
   tier, slayer, WOM, top goals+gaps) to the same worker every ~5 min, using
   the FLIPPER's cloudUrl/cloudId settings (nothing new to configure). One
   GET of the blob gives an AI advisor the whole picture: account + session
   P&L + fills + live GE offers + ranked flips. READ-ONLY toward the game —
   state ships out, nothing automates input; the sync-id stays the only
   secret and is never committed. Builder is pure + unit-tested
   (CoachAiSnapshotTest); unknown wealth/WOM are OMITTED, never fabricated.

## Build & test (plugin)
- `gradle build` from `runelite-plugin/` — **no wrapper is committed on
  purpose** (`.gitignore` excludes `gradlew`/`gradle/`); use system Gradle
  (8.x) despite README saying `./gradlew`.
- Lombok is pinned **1.18.34** — do not downgrade below 1.18.30 (older
  crashes under JDK 20+ javac with a JCTree$JCImport error). Source/target
  stays Java 11 (RuneLite's floor).
- Tests: `gradle test` — 10 test classes / 34 tests, all green. Keep them so.
- The jar MUST contain `runelite-plugin.properties` (repo root of
  `runelite-plugin/`) — without it the sideloader silently skips the plugin.
  It lists all three plugin classes; update it if a plugin class is
  added/renamed.
- Jonah runs it via `launch-geflip.bat` (dev-mode RuneLite; needs JDK 11 at
  the JAVA_HOME set inside the .bat). Built jar sideloads to
  `~/.runelite/sideloaded-plugins/`.

## Data
- `data/trends.json` — 30/90/180d trend snapshots, refreshed by
  `scripts/enrich_trends.py` (commit style: "data: refresh 30/90/180d
  trends (YYYY-MM-DD)"). Never hand-edit; regenerate.
- `osrs-flip.zip` / `osrsflip.pyz` — packaged artifacts, don't touch.

## Code conventions (these were audited in — keep them)
- **All gp aggregate math is `long`** — item prices × quantities overflow
  int. Per-fill `int` fields are fine (bounded by buy limits).
- GE tax: `GeflipScanner.saleTax` is the single source (2%, floored, 5m gp
  cap). Don't re-derive tax inline elsewhere.
- Client-thread state is **snapshotted before any off-thread persist**
  (copy the COW list first — never subList/iterate the live list off-thread).
- Journal writes are atomic (`.tmp` + ATOMIC_MOVE with fallback).
- Phone pages escape all remote data before `innerHTML` — keep it that way.
- Uniform null-guard style on wiki JSON (`has()` checks are deliberately
  omitted for keys the wiki API always sends; throws are caught by
  `triggerScan` and surface as "prices STALE").

## Gotchas
- `GeflipScanner` staleness decay is keyed on the **older** leg's timestamp
  (variable is named `newest` — misleading name, intentional behavior, see
  README "staleness decay").
- The service worker caches the app shell (never price data) — bump the
  `SHELL` constant in `sw.js` (e.g. `geflip-shell-v2` → `-v3`) when changing
  `index.html`/icons, or installed phones keep serving the old shell.
- Not affiliated with Jagex; prices API has usage etiquette — keep the
  user-agent header the scanner sends.
