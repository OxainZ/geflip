# RuneLite phone profile

`PocketLite.properties` is an importable RuneLite profile tuned for a 6.3" screen
streamed at 1080p. It is optional; PocketLite works without it. It is a separate
profile, so your desk setup is untouched.

## Import (once)

1. In RuneLite, open the configuration panel (wrench icon).
2. Click the profile icon at the top of the panel.
3. Click **Import Profile** and select `runelite/PocketLite.properties`.
4. RuneLite names it **Imported Profile**. Rename it to **Phone**.

Switch to **Phone** when you pick up the iPhone and back when you sit down.
RuneLite can also pin a profile to a specific RuneScape account if you use one
account only from the phone.

## What it changes and why

| Setting | Value | Why |
| --- | --- | --- |
| Stretched Mode plugin | on, scaling 65% | Renders the game canvas nearer its fixed 765x503 size and upscales. Text and menus become legible on a phone. Tune the percentage in the plugin panel. |
| Window size lock | off | Sunshine may switch the desktop to 1080p when the phone connects; RuneLite must be free to resize. |
| Infobox size | 50 px | Bigger timers and status icons. |
| Tooltip position | above cursor | A tooltip under the cursor is under your finger. |
| Notification focus/flash | off | The PC may be in another room; nothing should steal focus mid-stream. |

Everything else (plugins, keybinds, your Menu Entry Swapper setup) is inherited
from nothing: a fresh profile starts with RuneLite defaults. If you want your
normal plugin set on the phone too, export your desk profile, append the lines
from `PocketLite.properties` to the exported file, and import that instead.

## In-game (OSRS) settings worth changing on the phone

These live in the game's own settings, not in RuneLite, so they cannot ship in
the profile. They are the same ones the official mobile client defaults to:

- **Resizable - Modern layout.** Side panel tabs stay on screen, so you tap them
  instead of pressing F-keys (the Moonlight keyboard toolbar has no F-keys).
- **Camera zoom slider** (Settings > Display). Touchscreen mode in Moonlight has
  no scroll gesture, so set zoom here rather than with the wheel.
- **Shift-click drop** stays usable: the Moonlight keyboard toolbar has a
  toggleable Shift.
- Turn **"Mouse buttons: one button"** off; right-click menus work via long press.
