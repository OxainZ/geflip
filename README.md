# PocketLite

**RuneLite in your pocket.** PocketLite turns an iPhone into a purpose-built
screen and controller for the real RuneLite client running on your PC.

It is not a client. iOS has no Java runtime, the RuneLite team has said there
will be no mobile build, and the only approved third-party clients are RuneLite
and HDOS on desktop. Anything that calls itself "RuneLite mobile" is a fake or a
ban. What *is* possible, and what this project does well, is remote play: the PC
runs RuneLite exactly as Jagex approves it, and the phone receives a low-latency
video stream and sends your touches back as mouse and keyboard input.
Nothing is automated. Every click is yours. See
[docs/why-not-a-native-client.md](docs/why-not-a-native-client.md).

## What you get

| Piece | What it does | Status |
| --- | --- | --- |
| `host/Install-PocketLite.ps1` | One idempotent script sets the Windows PC up: Sunshine (the streaming host), Tailscale (play away from home), firewall rules, and "RuneLite" tiles in Sunshine that launch the Jagex Launcher or RuneLite directly. | Ready |
| `host/Verify-PocketLite.ps1` | Read-only diagnostics with a pass/fail table: service, ports, firewall, tiles, GPU encoder, Tailscale IP, phone profile. | Ready |
| `host/Remove-PocketLite.ps1` | Removes only what PocketLite added. | Ready |
| `runelite/PocketLite.properties` | An importable RuneLite profile tuned for a 6.3" screen (stretched mode, bigger infoboxes, no focus stealing). | Ready |
| `docs/` | Exact Moonlight settings for iPhone 17, an OSRS touch-control cheat sheet, remote play over Tailscale, troubleshooting. | Ready |
| `client/ios/` | Phase 2: a Moonlight fork with an OSRS control layer (F-key bar, faster right-click, tick-friendly taps). Design and build plan are written; code is not started. | Planned |

## Ten-minute setup

On the PC (Windows 10/11, as Administrator, in PowerShell):

```powershell
git clone https://github.com/OxainZ/pocketlite
cd pocketlite\host
Set-ExecutionPolicy -Scope Process Bypass
.\Install-PocketLite.ps1          # add -WhatIf first if you want to see the plan
```

Then:

1. Open `https://localhost:47990` on the PC once and set a Sunshine username and password.
2. On the iPhone, install **Moonlight Game Streaming** from the App Store.
3. In Moonlight tap **+** and enter the PC's LAN address (the installer prints it).
4. Moonlight shows a 4-digit PIN. Enter it at `https://localhost:47990/pin` on the PC.
5. Apply the settings in [docs/moonlight-settings.md](docs/moonlight-settings.md). Takes two minutes and matters.
6. Tap the **RuneLite** tile. The Jagex Launcher opens on the PC and on your phone. Tap Play.

Optional but recommended: import [runelite/PocketLite.properties](runelite/README.md)
as a second RuneLite profile and switch to it when playing on the phone.

Away from home: sign in to Tailscale on both the PC (tray icon) and the iPhone
(App Store), then add the PC's Tailscale address as a second host in Moonlight.
[docs/remote-play.md](docs/remote-play.md) covers the details and the one
gotcha (relayed connections are too slow; `tailscale ping` must say direct).

## Controls in one breath

Touchscreen mode. Tap = left click. Hold still for ~0.65 s = right click.
Drag = click-and-drag. Pinch = magnify the stream for precision. Three-finger tap
= keyboard, with a toolbar that has Esc, Tab, a Shift toggle, Ctrl and Alt.
Full cheat sheet with OSRS-specific habits in [docs/controls.md](docs/controls.md).

## Honest expectations

- **Latency.** On 5 GHz Wi-Fi or a direct Tailscale link, expect roughly 30 to 60 ms
  end to end on top of your normal ping, about one game tick's worth of feel.
  Fine for skilling, bossing with prayer flicks on a timer, flipping, questing.
  Not fine for tick-perfect PvP.
- **The PC stays on.** Sunshine streams a live desktop; there is no cloud.
- **Battery.** A 1080p60 HEVC stream draws a lot. Plan on a charger for long sessions.
- **No F-keys** in stock Moonlight's keyboard toolbar. Use Resizable-Modern layout
  and tap the tabs. Phase 2 fixes this properly.

## Requirements

- PC: Windows 10 1809+ or 11, a GPU with a hardware encoder (any NVIDIA GTX 900+,
  AMD RX 400+, Intel 6th gen+ iGPU), Ethernet to the router strongly recommended.
- Phone: iPhone 17 (any iPhone on iOS 16+ works; the iPhone 17's 120 Hz panel and
  AV1 decoder are nice to have, not required).
- RuneLite installed via the Jagex Launcher (Jagex accounts) or the RuneLite
  installer (legacy accounts).

## Development

```powershell
Install-Module Pester, PSScriptAnalyzer -Scope CurrentUser
Invoke-Pester ./tests
Invoke-ScriptAnalyzer ./host -Recurse -Settings ./PSScriptAnalyzerSettings.psd1
```

The merge logic for Sunshine's `apps.json` is unit-tested on Linux, macOS and
Windows (both PowerShell 7 and Windows PowerShell 5.1) in CI. Scripts target
5.1 as the floor because that is what a stock Windows box has.

## Not affiliated

PocketLite is not affiliated with Jagex, RuneLite, LizardByte (Sunshine) or the
Moonlight project. Old School RuneScape is a trademark of Jagex Ltd. Jagex's
rules on third-party software are theirs to set and change; read them yourself.

## License

MIT. See [LICENSE](LICENSE).
