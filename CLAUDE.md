# geflip — OSRS workspace brief

**This is the RuneScape workspace. It is SEPARATE from the trading bot**
(`C:\Users\Oxain\trading_bot`, `C:\Users\Oxain\stock_bot`). Nothing here touches money,
brokers, or market code. Don't import trading context into RuneScape decisions or the reverse.

Repo: `C:\Users\Oxain\geflip` · git `main` · origin `github.com/OxainZ/geflip`
Owner: Oxain (Jonah). Web app live at **https://oxainz.github.io/geflip/**

`README.md` explains what the tool does for a *user*. This file is the *operational* brief:
how to run it, what breaks, and where things live.

---

## The three deliverables in this repo

1. **Web app (PWA)** — `index.html`, `manifest.webmanifest`, `sw.js`, icons. One file, no build
   step, installs to the phone home screen. Ranks every tradeable item by expected **gp/hour**
   (2% GE tax, buy limits, liquidity, drift and quote staleness priced in). Deployed to GitHub Pages.
2. **RuneLite plugins** — `runelite-plugin/` (Java 11). Three plugins in one jar:
   - **Geflip** — flip finder, live fills + P&L tracking, phone bridge.
   - **Coach** — progression advisor: goal-graph, farm runs, Risk+max-hit, best-ranged-upgrade
     finder. **Reads the LIVE account, so RuneScape must be open and logged in** or it can't answer.
   - **Jad prayer helper** — see the ToS note below.
3. **sync-worker/** — LAN/cloud sync so the phone sees the same state as the PC.

Plugins are **READ-ONLY**: they observe game state and advise. They do not automate play.

---

## Running it

**Just launch it:** `launch-geflip.bat` (repo root, also a desktop shortcut). First launch of a
session compiles ~20s, then the RuneLite window opens. Closing the window exits.

It pins its own toolchain — the system Java/Gradle are NOT used:

```
JAVA_HOME = C:\Users\Oxain\tools\jdk\jdk-11.0.31+11     (JDK 11 — the client needs 11)
GRADLE    = C:\Users\Oxain\tools\gradle\gradle-7.6.4\bin\gradle.bat
cwd       = C:\Users\Oxain\geflip\runelite-plugin
```

Gradle tasks (`runelite-plugin/build.gradle`):
- `run` — launch real RuneLite with the plugins registered via `loadBuiltin` (`GeflipPluginTest`).
  Needs `-ea`; RuneLite's `loadBuiltin()` refuses to run without assertions.
- `runCli` — headless `GeflipCli` against the live wiki API. **No game needed.** This is the
  canonical flip engine — anything else that reports flips should call this, not reimplement it.
- `runBridge` — local bridge server with mock state, no game needed.

**Phone bridge:** the plugin serves on **port 7777** — open `http://<pc-ip>:7777` on the phone.
The log line `geflip bridge on port 7777` at startup confirms it's up.

---

## Known breakages (check these first)

**Black screen / stuck client = `error_game_js5connect_outofdate`.** This is NOT a GPU, driver, or
117HD problem — don't go down that road (we wasted a session on it once). It means the **pinned
client version is stale** because Jagex shipped an update. Fix:

```
runelite-plugin/build.gradle line ~19:  def runeLiteVersion = '1.12.36'
```

Bump it to current, rebuild, kill any old client, relaunch. Verify recovery in the log by
`117 HD started successfully!` and the absence of any `js5connect` line.

The version is pinned deliberately — `latest.release` made builds non-reproducible and let an
upstream release silently change the artifact. Bump it **on purpose**, not automatically.

**Log:** `C:\Users\Oxain\.runelite\logs\client.log`. Routine `worldhopper ping` warnings are noise.
A `GeflipScanner.httpGet` stack trace = the wiki price API blipped; it self-recovers.

---

## Where the data actually lives

Runtime state is **outside the repo** (RuneLite's profile dir), so cloning the repo does not carry it:

```
C:\Users\Oxain\.runelite\geflip\
  fills.json                    live buys/sells — holdings = net (bought - sold)
  fills_prehistory_backup.json  pre-tracking backup; don't overwrite
  flip_stats.csv                realized flip outcomes
  ge_history_dump.json          GE history scrape
  session.json / snapshot.json  current session state
```

In-repo data: `data/trends.json` (built by `scripts/enrich_trends.py`).
Item names resolve via the OSRS Wiki mapping API.

---

## Cross-project dependency (do not break)

The trading bot's Telegram bridge exposes a `geflip(action=flips|holdings|coach)` tool
(`trading_bot/scripts/hermes_chat.py`) so the phone can ask RuneScape questions. It:
- runs **this repo's** `gradle runCli` for flips (cached ~10 min) — canonical, no divergence,
- parses `.runelite/geflip/fills.json` for holdings,
- reads `trading_bot/user_data/geflip_context.md` for background.

So: **renaming `runCli`, changing its output shape, or moving `fills.json` breaks the phone.**
If you change either, update `hermes_chat.py` in the same pass.

---

## Rules

- **Jad prayer helper is against Jagex ToS.** It exists as a local, user's-risk build at Jonah's
  explicit decision. Never ship it publicly, never bundle it into the Pages deploy, and don't
  quietly re-enable it if it's been turned off. It is his call, not yours.
- Plugins stay **read-only** — no input automation, no botting. That's the line.
- The pinned JDK/Gradle/client versions are load-bearing. Don't "modernise" them casually.
- Not affiliated with Jagex. Prices come from the OSRS Wiki real-time prices API.
