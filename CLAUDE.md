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
3. **Android overlay** (`android/`) — Java 11, AGP 8.5.2, ZERO third-party deps. A
   `TYPE_APPLICATION_OVERLAY` window hosting a WebView on the PWA's `?overlay=1`
   layout, so the flip list floats over the OSRS **mobile** client (which has no
   plugin API and is not modified in any way — see MOBILE.md for the line this does
   not cross). The app holds NO market logic: the page stays the only flip engine, so
   phone and desktop cannot disagree. Built by `.github/workflows/android.yml` into a
   sideloadable debug APK — Jonah installs it off the Actions artifact, no Android
   Studio. `Prefs.overlayUrl` is the only real logic and is unit-tested (8 tests).
4. **Cloudflare sync worker** (`sync-worker/src/worker.js`) — relays
   fills/flips/offers between the plugin and the phone page. CORS-open **by
   design**; the sync-id is the secret. Don't "fix" the CORS.
5. **The AI lane (2026-08-24, `ASK_THE_AI.md`)** — CoachPlugin pushes a full
   account snapshot (`account` key: skills, QP, quests, gp, net worth, CA
   tier, slayer, WOM, top goals+gaps) to the same worker every ~5 min, using
   the FLIPPER's cloudUrl/cloudId settings (nothing new to configure). One
   GET of the blob gives an AI advisor the whole picture: account + session
   P&L + fills + live GE offers + ranked flips. **v2 adds BANK CONTENTS
   ([[id,qty],...], omitted until the bank is opened once per session), the
   coach's daily lines, and minutes-since-last-farm-run.** The consuming
   side lives in FidelityTrades `scripts/osrs_advisor.py` (phone-reachable
   via Telegram/claude.ai), which layers LIVE wiki prices/volumes/limits/
   alch-margins and official hiscores on top of this snapshot. READ-ONLY toward the game —
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
- Tests: `gradle test` — 11 test classes / 38 tests, all green. Keep them so.
- The jar MUST contain `runelite-plugin.properties` (repo root of
  `runelite-plugin/`) — without it the sideloader silently skips the plugin.
  It lists all three plugin classes; update it if a plugin class is
  added/renamed.
- Jonah runs it via `launch-geflip.bat` (dev-mode RuneLite; needs JDK 11 at
  the JAVA_HOME set inside the .bat). Built jar sideloads to
  `~/.runelite/sideloaded-plugins/`.

## Build & test (Android overlay)
- `cd android && gradle assembleDebug` — needs an Android SDK (platform 34,
  build-tools 34) and **JDK 17** (AGP 8.5.2's floor; the plugin's JDK 11 will not do).
  No wrapper committed, same rule as `runelite-plugin/`.
- `gradle testDebugUnitTest` — 1 test class / 8 tests. Keep them green.
- CI does both and uploads the APK; nothing here needs Android Studio.
- The overlay layout lives in `index.html` behind `?overlay=1` — changing it means
  bumping `SHELL` in `sw.js` like any other index.html change.

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

## Launching it, and what to do when it won't start
Jonah launches with **`launch-geflip.bat`** (repo root / desktop shortcut). It pins its own
toolchain — the system Java is NOT used:
```
JAVA_HOME = C:\Users\Oxain\tools\jdk\jdk-11.0.31+11     (JDK 11 — the client's floor)
GRADLE    = C:\Users\Oxain\tools\gradle\gradle-7.6.4\bin\gradle.bat
cwd       = C:\Users\Oxain\geflip\runelite-plugin  ->  gradle run --no-daemon
```
First launch of a session compiles ~20s. Closing the RuneLite window exits.

**Black screen / client stuck = `error_game_js5connect_outofdate`. It is NOT a GPU, driver,
or 117HD problem — do not go down that road (a whole session was lost to it once).** It means
the pinned client version went stale after a Jagex update. Fix: bump `runeLiteVersion` in
`runelite-plugin/build.gradle` (~line 19), rebuild, kill the old client, relaunch. Confirm
recovery in `C:\Users\Oxain\.runelite\logs\client.log`: `117 HD started successfully!` present,
no `js5connect` line. The version is pinned deliberately (`latest.release` made builds
non-reproducible) — bump it on purpose, never automatically.

Log noise that is NOT a bug: `worldhopper ping` warnings; a `GeflipScanner.httpGet` stack
trace means the wiki price API blipped and it self-recovers.

## Gradle tasks beyond build/test
- `run` — launch real RuneLite with the trio registered via `loadBuiltin` (`GeflipPluginTest`);
  needs `-ea`, since `loadBuiltin()` refuses to run without assertions.
- `runCli` — headless `GeflipCli` against the live wiki API, **no game needed**. This is the
  **canonical flip engine**: anything reporting flips should call it, not reimplement the scan.
- `runBridge` — local bridge server with mock state, no game needed.

The in-client plugin also serves the phone on **port 7777** (`http://<pc-ip>:7777`); the startup
log line `geflip bridge on port 7777` confirms it. This is the LAN path; the Cloudflare
sync-worker above is the remote path.

## Runtime data lives OUTSIDE the repo
Cloning the repo does not carry any of this — it's in RuneLite's profile dir:
```
C:\Users\Oxain\.runelite\geflip\
  fills.json                    live buys/sells — holdings = net (bought - sold)
  fills_prehistory_backup.json  pre-tracking backup; don't overwrite
  flip_stats.csv                realized flip outcomes
  ge_history_dump.json          GE history scrape
  session.json / snapshot.json  current session state
```

## Cross-project dependency (do not break)
Separate from Jonah's trading bot (`C:\Users\Oxain\trading_bot`, `stock_bot`) — no shared code,
no money here. The one live tie: the trading bot's Telegram bridge
(`trading_bot/scripts/hermes_chat.py`) exposes `geflip(action=flips|holdings|coach)`, which runs
**this repo's `gradle runCli`** for flips (cached ~10 min) and parses `.runelite\geflip\fills.json`
for holdings. **Renaming `runCli`, changing its output shape, or moving `fills.json` breaks the
phone** — update `hermes_chat.py` in the same pass. (See also `osrs_advisor.py` on the AI lane.)

## Standing rules
- **The Jad prayer helper is against Jagex ToS.** It exists as a local, user's-risk build at
  Jonah's explicit decision. Never ship it publicly, never bundle it into the Pages deploy, and
  don't quietly disable or re-enable it on your own. His call, not yours.
- Plugins are **read-only** toward the game — observe and advise, never automate input.
- The Android overlay only **draws on top**. Never add an accessibility service,
  MediaProjection/screen capture, OCR of the client, or input injection — that is the
  difference between a second app and a banned client mod, and it is not a line to
  experiment with.
- The pinned JDK / Gradle / client versions are load-bearing. Don't "modernise" them casually.
