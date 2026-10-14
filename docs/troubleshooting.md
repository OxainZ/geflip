# Troubleshooting

Run `.\Verify-PocketLite.ps1` first. It tells you which layer is broken.

## Moonlight cannot find the PC / "Connection failed"

- `Verify` says port 47989/TCP not listening: the Sunshine service is down. `Start-Service SunshineService`, or open the Sunshine web UI and look at Troubleshooting > Logs.
- Port listening but the phone still fails: firewall. `Verify` reports it; re-run `Install-PocketLite.ps1` as admin to re-add the rules. Third-party firewalls (Norton, Kaspersky) need their own allow for `sunshine.exe`.
- Over Tailscale only: see `remote-play.md`, the connection is probably relayed.

## PIN page says "Pairing failed"

Clock skew or a stale certificate. Set the PC clock to automatic, then in the
Sunshine web UI > Configuration > General, remove old clients and pair again.

## The RuneLite tile launches, the screen stays black

Sunshine streams the display it considers primary. If RuneLite opened on a
second monitor, drag it to the primary, or set `output_name` in Sunshine's
Configuration > Audio/Video to the right display (the web UI lists the IDs).

Black screen with the **Jagex Launcher visible but RuneLite never appearing** is
a RuneLite problem, not a streaming problem: check `%USERPROFILE%\.runelite\logs\client.log`.
If it says `error_game_js5connect_outofdate` the game updated and RuneLite needs
to update too; let the launcher update it.

## Clicks land in the wrong place

- You are in Trackpad mode. Switch to Touchscreen in Moonlight settings.
- Stretched mode plugin plus a non-16:9 stream can offset the canvas; keep the stream at 1920x1080, or turn on "Keep aspect ratio" in Stretched Mode.

## Right click does not open

Hold stiller and a little longer. The threshold is 0.65 s with less than 1%
of the screen of movement; a thumb on a glass screen drifts. Pinch-magnify for
small targets. This is the main thing Phase 2 shortens.

## Stutter or blurry text

Stats overlay on. Then:

| Symptom | Fix |
| --- | --- |
| Network latency spikes | Move the PC to Ethernet; the phone to 5 GHz. |
| Host processing > 10 ms | Sunshine is software-encoding. Web UI > Configuration > Audio/Video, set the encoder to NVENC / AMF / QuickSync explicitly. |
| Dropped frames at steady latency | Lower bitrate 5 Mbps at a time. |
| Text soft at 20 Mbps | Codec is H.264; switch to HEVC/AV1. |

## The PC resolution did not switch back after a session

Sunshine reverts the display when the app closes. If RuneLite crashed, run
`Quit` on the tile from Moonlight (long-press the tile) or restart `SunshineService`.

## Removing everything

`.\Remove-PocketLite.ps1` removes the tiles; add `-RemoveFirewallRules` for the
rules it created. Uninstall Sunshine and Tailscale from Settings > Apps like any
other program. Delete the "Phone" profile from RuneLite's profile panel.
