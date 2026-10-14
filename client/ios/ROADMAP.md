# Phase 2: an OSRS-shaped Moonlight

Stock Moonlight on iOS is excellent at video and good at generic touch. For
OSRS it has four specific shortcomings, each fixable in a fork. This document
is the design; code has not started. File names and constants below were read
from `moonlight-stream/moonlight-ios` master in October 2026 and should be
re-checked against the fork's pinned commit.

## The four gaps

| Gap | Where it lives upstream | Change |
| --- | --- | --- |
| No F-keys, Space, digits or Enter in the keyboard toolbar | `Limelight/Input/StreamView.m`, the `customToolbarView` built by `createButtonWithImageNamed:...keyCode:isToggleable:` | Add an **OSRS key bar**: F1 to F12 (`0x70` to `0x7B`), Esc, Space (`0x20`), 1 to 5 (`0x31` to `0x35`), Enter (`0x0D`), Shift toggle. Shown as a slim strip along the bottom edge *without* opening the text keyboard, so it is one tap. |
| Right click takes 0.65 s of perfectly still holding | `Limelight/Input/AbsoluteTouchHandler.m`: `LONG_PRESS_ACTIVATION_DELAY = 0.650f`, `LONG_PRESS_ACTIVATION_DELTA = 0.01f` | Make both settings. OSRS default 0.35 s and 2% drift tolerance. Add haptic tick on activation so you know the menu is open before looking. |
| Double-tap deadzone fights fast repeated clicks on different targets | `AbsoluteTouchHandler.m`: `DOUBLE_TAP_DEAD_ZONE_DELAY = 0.250f`, `DOUBLE_TAP_DEAD_ZONE_DELTA = 0.025f` | Setting to disable. OSRS double-clicks are rare; two quick taps on adjacent inventory slots are common. |
| No scroll in Touchscreen mode | `AbsoluteTouchHandler.m` ignores multi-touch beyond the first finger | Two-finger vertical drag sends `LiSendHighResScrollEvent` like Relative mode does, so camera zoom works without leaving Touchscreen mode. |

Everything else (video pipeline, pairing, HEVC/AV1 decode, Tailscale
compatibility) stays upstream and unforked.

## Settings surface

One new switch in `Limelight/ViewControllers/SettingsViewController.m` and
`Limelight/Base.lproj/Main.storyboard`: **OSRS mode**. On: absolute touch,
OSRS key bar visible, 0.35 s right click, deadzone off, two-finger scroll. Off:
exactly upstream behaviour. Values persisted through the existing
`TemporarySettings`/`DataManager` path so nothing else changes.

## Build and install without a Mac on the desk

The author's PC is Windows, so the pipeline must not need local Xcode.

1. Fork `moonlight-stream/moonlight-ios` (GPL-3.0; the fork stays GPL-3.0 and public).
2. GitHub Actions on `macos-15`:
   ```
   git submodule update --init --recursive
   xcodebuild -project Moonlight.xcodeproj -scheme Moonlight -configuration Release \
     -sdk iphoneos -derivedDataPath build CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO
   mkdir Payload && cp -r build/Build/Products/Release-iphoneos/Moonlight.app Payload/
   zip -r PocketLite-unsigned.ipa Payload
   ```
   Upload the IPA as a release artifact.
3. Install on the iPhone with **SideStore** or **AltStore** (AltServer runs on Windows). A free Apple ID signs for 7 days at a time and allows 3 sideloaded apps; SideStore refreshes on-device over Wi-Fi. A paid developer account ($99/yr) signs for a year, or ship through TestFlight.
4. Keep upstream as a remote and rebase the four patches on each Moonlight release; the diff is designed to stay small (a few hundred lines) for exactly this reason.

## Out of scope, permanently

- Anything that reads game state from the stream (no OCR, no pixel-reading, no auto-anything). PocketLite sends the user's own taps and nothing else.
- Running on a jailbroken device or bypassing signing.
- A Jagex Launcher workaround that skips the launcher's own login.

## Milestones

1. **M1**: fork builds unsigned in CI, installs via SideStore, streams identically to upstream.
2. **M2**: OSRS mode switch + key bar.
3. **M3**: tunable right click + deadzone + two-finger scroll, with haptics.
4. **M4**: a week of real play on an iPhone 17, timings adjusted from logs, release v0.1.
