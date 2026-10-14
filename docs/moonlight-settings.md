# Moonlight settings for an iPhone 17

Open Moonlight > Settings (gear). Set these once. Each row explains why, so
you know what to move when your network is different.

| Setting | Value | Why |
| --- | --- | --- |
| Resolution | **1920x1080** | Matches a 16:9 monitor, which keeps Sunshine's automatic display switch working (see below). The phone shows thin black bars left and right; that is expected. Do not pick the "screen-fitted" option unless you run a virtual display (Advanced, below). |
| Frame rate | **60** (120 if your monitor is 120 Hz+) | 120 only helps if the host display actually refreshes at 120 Hz; otherwise it doubles battery drain for duplicate frames. |
| Bitrate | **20 Mbps** on home Wi-Fi, **8 to 12 Mbps** on cellular | OSRS is mostly static; HEVC at 20 Mbps is visually lossless for text. Drop it first if you see stutter. |
| Video codec | **HEVC** (or **AV1** if the PC GPU supports it: RTX 40+, RX 7000+, Arc) | H.264 needs ~2x the bitrate for the same text clarity. The iPhone 17 decodes both HEVC and AV1 in hardware. |
| HDR | **Off** | RuneLite is SDR; HDR only adds tone-mapping latency. |
| Touch mode | **Touchscreen** | Tap-where-you-want-to-click. See `controls.md`. |
| On-screen controls | **Off** | Gamepad overlays are useless for OSRS and disabled anyway in Touchscreen mode. |
| Optimize games | **On** | Required for Sunshine's display-device feature (`dd_resolution_option = auto`) to switch the PC to 1080p while you stream, then switch back. |
| Multi-controller | Off | n/a |
| Swap A/B/X/Y | Off | n/a |
| Audio on PC | **Off** | Hear the game on the phone. Turn on if you want the PC speakers instead. |
| Frame pacing | **Off** | Pacing smooths animation by adding a frame of latency. For a click-driven game, latency wins. |
| Bluetooth mouse support | Off unless you carry one | A Bluetooth mouse and keyboard turn the iPhone into a tiny laptop; worth it for long bank-standing sessions. |
| Stats overlay | **On** while tuning, off after | Shows decode time, network latency, dropped frames. The goal: host processing under 5 ms, network under 10 ms on LAN, 0 dropped frames. |

## Advanced: edge-to-edge, no black bars

The iPhone 17 panel is 19.5:9; your monitor is 16:9. Two ways to fill the screen:

1. **Accept the bars.** Honestly the best option. The visible area is still larger than the official mobile client's playfield.
2. **Virtual display.** Install a virtual display driver on the PC (search "Virtual Display Driver" by itsmikethetech / VirtualDrivers), add a 2622x1206 mode, point Sunshine's `output_name` at it in the web UI, and pick the screen-fitted resolution in Moonlight. Sunshine's `dd_mode_remapping` can map odd requests onto the virtual mode. This is a separate rabbit hole; PocketLite's installer deliberately does not automate it.

## Pairing checklist

1. PC: `https://localhost:47990`, create a user the first time.
2. Phone: Moonlight > **+** > the PC's LAN IP (`Verify-PocketLite.ps1` prints it).
3. Phone shows a PIN. PC: `https://localhost:47990/pin`, type it.
4. Pair on the LAN first even if you will mostly play remotely; iOS cannot pair across the internet.
