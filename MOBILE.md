# geflip on the phone

Neither mobile client has a plugin API, and nothing here invents one. What each platform
*does* allow is different enough that the two hosts share nothing but the page.

| | how it floats | interactive? | install |
|---|---|---|---|
| **[iOS](#ios)** — `ios/` | Picture in Picture. The only surface a third-party app can keep on screen over another app. | **No** — a PiP window is a video | sideload an unsigned `.ipa` with AltStore |
| **[Android](#android)** — `android/` | `TYPE_APPLICATION_OVERLAY`, a real window over the game | Yes — tap, scroll, copy | tap an `.apk` |

Both read `?overlay=1` on the same `index.html`, and **neither contains any market logic**.
The page stays the only flip engine, so the phone can never quietly disagree with the
desktop.

## The line neither host crosses

Read this before touching either one, because it is the reason they are allowed to exist:

- **No screen reading.** No accessibility service, no MediaProjection, no OCR. They cannot
  see the game and do not try.
- **No input, ever.** They draw pixels. Same standing rule as the RuneLite plugins.
- **No client modification.** Separate apps; the Jagex client runs untouched underneath,
  exactly as it does when you have the wiki open beside it.
- **No account credentials.** Neither ever sees a login.

Everything on screen comes from the OSRS Wiki price API and from your own RuneLite
plugin's sync — the same two sources the website uses.

---

## iOS

iOS has no "draw over other apps" — no entitlement, no private door, jailbreak or nothing.
The single exception is **Picture in Picture**, so `ios/` is a live video stream whose every
frame it paints itself from the page's numbers.

That buys you a window that survives you swiping to OSRS. It costs you interaction: **a PiP
window cannot be tapped, scrolled, or copied from.** It is a read-out — your open GE offers,
then what to buy at what price — and you type the numbers yourself.

### Before you invest any time in this

**Test whether PiP survives OSRS on your phone, it takes 30 seconds:** start any video in
PiP (YouTube, Safari), swipe to OSRS, see whether the window stays up and keeps playing.
If iOS or the game kills it, this whole approach is dead and no amount of code fixes it.

### Install (no Mac needed)

1. Push, or run **ios-float** from the Actions tab. It builds on a macOS runner.
2. Download the `geflip-float-unsigned-ipa` artifact and unzip it to get `GeflipFloat.ipa`.
3. Install **AltServer** on the Windows PC and **AltStore** on the iPhone (`altstore.io`).
4. AltStore → **+** → pick the `.ipa`. It re-signs with your own free Apple ID.

The `.ipa` is unsigned on purpose: AltStore signs it, so no certificate or provisioning
secret ever lives in this repo. A free Apple ID means **the app expires after 7 days** —
AltServer refreshes it automatically whenever the phone is on the same wifi as the PC.

### Using it

Open **geflip float** → check the preview at the bottom → **Float** → swipe to OSRS.

The PiP transport controls are the only input it has, so they are mapped to what you would
actually want: **pause** stops the auto-refresh, **skip** forces a rescan now. It re-scans
every 3 minutes on its own and says plainly when data has gone stale rather than showing
old prices as if they were live.

Tap **Page** to point it at the plugin bridge instead (below).

---

## Android

`android/` is ~450 lines of Java with zero third-party dependencies: a real overlay window
hosting a WebView on the `?overlay=1` page. Because it is a window and not a video, it is
fully interactive.

### Install

1. Push, or run **android-overlay** from the Actions tab.
2. Open the finished run **on the phone** → download the `geflip-overlay-debug` artifact.
3. Unzip, tap the `.apk`, allow install from unknown sources.
4. Open **geflip overlay** → *Allow drawing over other apps* → *Start overlay*.

To build it yourself: `cd android && gradle assembleDebug`, with an Android SDK
(platform 34, build-tools 34) and JDK 17.

### Using it

Drag the title bar to move. `◢` resize · `–` shrink to a bubble · `◐` fade · `⟳` reload ·
`✕` close. Position, size and opacity are remembered.

**Tap a buy or sell price to copy it**, then paste into the Grand Exchange offer box.
`⤢` swaps the window to the full app (Config, Book, Slots, Swing) without leaving it.

---

## Pointing either one at your data

The **Page** setting decides how much the window knows:

| you enter | you get |
|---|---|
| `https://oxainz.github.io/geflip/` (default) | live wiki prices, scan, allocation |
| `http://<pc-ip>:7777/?t=<token>` | the above **plus** your real fills, session P&L and open GE offers, straight off the plugin's LAN bridge — same wifi, PC on |
| default + a cloud sync id set once in the full app | the same live data anywhere, including mobile data |

The bridge serves the very same page over http, so `?overlay=1` gets appended either way.
Both hosts keep any `?t=` token you paste — losing it would silently drop you to
hosted-only data.

## Known limits

- **iOS is read-only.** No copy button, no tapping a row. That is PiP, not a shortcut taken.
- **iOS re-signing.** 7 days on a free Apple ID; AltServer on the PC handles it, but the app
  dies if the phone never sees that PC.
- **Android battery managers** (Xiaomi, Oppo, aggressive Samsung modes) kill overlay
  services. Exempt **geflip overlay** from battery optimisation if it vanishes.
- The Android service is a `specialUse` foreground service — fine for sideloading, and it
  would need justification for Play, which is not where this is going.
