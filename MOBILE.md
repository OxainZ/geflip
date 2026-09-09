# geflip on the phone

OSRS mobile has no plugin API, and nothing here tries to invent one. What it does
instead is what the community has already settled on: **a second app that draws its
own window on top of the game.** The game is untouched; you just stop having to leave
it to see your numbers.

Two pieces:

| | what it is | where |
|---|---|---|
| **`?overlay=1`** | a compact layout of the existing web app — live GE offers, then the allocation (what to buy, how many, at what price) | `index.html` |
| **`android/`** | a ~400-line Java app whose whole job is to float that page over another app | `android/` |

The page is still the only flip engine. The Android app holds no market logic, no tax
math and no ranking — it opens a URL. That is deliberate: one engine means the phone can
never quietly disagree with the desktop.

## The line this does not cross

Read this before touching it, because it is the reason the thing is allowed to exist:

- **No accessibility service, no MediaProjection, no screen reading, no OCR.** It cannot
  see the game and does not try.
- **No input, ever.** It draws pixels. Same standing rule as the RuneLite plugins.
- **No client modification.** It is a separate APK; the Jagex client runs untouched
  underneath, exactly as it does when you have the wiki open in split-screen.
- **No account credentials.** It never sees a login.

Everything on screen comes from the OSRS Wiki price API and from your own RuneLite
plugin's sync — the same two sources the website uses.

## Install

No Android Studio needed.

1. Push to the repo (or run **android-overlay** from the Actions tab by hand).
2. Open the finished run **on the phone** → download the `geflip-overlay-debug` artifact.
3. Unzip, tap the `.apk`, allow install from unknown sources.
4. Open **geflip overlay** → *Allow drawing over other apps* → *Start overlay*.

To build it yourself instead: `cd android && gradle assembleDebug` with an Android SDK
(platform 34, build-tools 34) and JDK 17.

## Pointing it at your data

The **Page** field decides how much it knows:

| you enter | you get |
|---|---|
| `https://oxainz.github.io/geflip/` (default) | live wiki prices, scan, allocation |
| `http://<pc-ip>:7777/?t=<token>` | the above **plus** your real fills, session P&L and open GE offers, straight off the plugin's LAN bridge — same wifi only |
| default + a cloud sync id set once in the full app (`⤢`) | the same live data anywhere, including mobile data |

The bridge serves the very same page over http, so `?overlay=1` gets appended either way.

## Using it

Drag the title bar to move. `◢` resize · `–` shrink to a bubble · `◐` fade · `⟳` reload ·
`✕` close. Position, size and opacity are remembered.

**Tap a buy or sell price to copy it**, then paste into the Grand Exchange offer box —
that is the one thing the overlay can do that the game cannot.

`⤢` swaps the window to the full app (Config, Book, Slots, Swing) without leaving it.

It rescans every 3 minutes while visible, and stops while hidden — the wiki API asks for
restraint and it is four endpoints a scan.

## Known limits

- Android only. iOS has no equivalent of "draw over other apps"; on iPhone the PWA in
  split view or Safari alongside is as far as it goes.
- The window is *not* focusable, on purpose — that leaves the keyboard and the back
  button with the game. So there is no typing inside the overlay; use `⤢` for that.
- Some OEM battery managers (Xiaomi, Oppo, Samsung's aggressive mode) kill overlay
  services. Exempt **geflip overlay** from battery optimisation if it disappears.
- The service is a `specialUse` foreground service. That is fine for sideloading and
  would need justification for Play, which is not where this is going.
