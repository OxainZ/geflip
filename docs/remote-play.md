# Playing away from home

PocketLite uses **Tailscale** for off-LAN play. It is a WireGuard mesh: the
iPhone and the PC get stable private addresses and talk directly, encrypted,
with no port forwarding and no exposure of Sunshine to the internet. Moonlight
on the phone just sees a second host address.

## Set up

1. PC: the installer put Tailscale in. Click the tray icon, **Log in**, pick any identity provider.
2. iPhone: install **Tailscale** from the App Store, log in with the same account, toggle it on.
3. PC: `.\Verify-PocketLite.ps1` prints the PC's Tailscale IP (100.x.y.z).
4. Phone: Moonlight > **+** > that IP. Pair once (you can do that at home).

## The one gotcha: direct, not relayed

Tailscale falls back to relay servers (DERP) when it cannot punch through a NAT.
Relayed links are rate-limited and far too slow for a video stream. Check from
the PC:

```
tailscale ping <iphone-name>
```

You want `pong ... via <ip>:<port>` which means direct. If it says `via DERP`,
the usual cause is a carrier-grade NAT on one side. Fixes in order of effort:

- Toggle the phone between Wi-Fi and cellular; many carriers only allow direct links on one.
- Enable UPnP or NAT-PMP on the home router so the PC side can open a port.
- If both sides sit behind carrier-grade NAT, direct links are impossible without help. Options: enable IPv6 on the home router if the ISP offers it (Tailscale prefers it), or stand up a small VPS as a Tailscale node and route the PC through it.

## Bitrate on cellular

Start at 8 Mbps HEVC, 1080p, 60 fps. If Stats overlay shows loss, drop
resolution to 1280x720 before dropping frame rate; text stays readable at 720p,
and 30 fps feels sluggish.

## Wake the PC remotely

Sunshine cannot wake a sleeping PC on its own, but Moonlight can send a Wake-on-LAN
packet to a host it has paired with; over Tailscale this only works if another
always-on device on your LAN relays it (a subnet router). Simplest: set the PC to
never sleep, display off after 10 minutes.

## Security posture

- Sunshine is never reachable from the public internet. Only the LAN and the tailnet.
- Pairing requires physical access to the PC for the PIN.
- Tailscale's **Key expiry** will log the PC out after 180 days; disable expiry for the PC in the Tailscale admin console or you will get a dead host at the worst moment.
