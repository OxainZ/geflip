# OSRS touch controls on Moonlight

Moonlight has two touch modes. PocketLite recommends **Touchscreen** (absolute)
for OSRS. Gesture timings below come from Moonlight's iOS source
(`Limelight/Input/AbsoluteTouchHandler.m`, `RelativeTouchHandler.m`,
`StreamView.m`), not from guesswork.

## Touchscreen mode (recommended)

| You do | Game sees | OSRS habit |
| --- | --- | --- |
| Tap | Left click at that spot | Walk, attack, take, talk. Works on tick; no debounce. |
| Hold still ~0.65 s | Right click at that spot (menu opens) | Any "Use", "Examine", "Withdraw-X". Move more than ~1% of the screen and the hold cancels, so keep the finger planted. |
| Tap, then drag | Left-button drag | Move items in the inventory and bank. Camera rotation is middle-mouse in OSRS, which touch cannot send; rotate with the arrow keys from the keyboard or tap the compass to reset. |
| Pinch | Magnify the *stream* (not the game) | Zoom into the bank or a crowded inventory, tap precisely, pinch back. |
| Two-finger drag | Pan the magnified stream | Only useful while pinched in. |
| Three-finger tap | Toggle the on-screen keyboard | Chat, bank PIN, "Withdraw-X" amounts, spacebar for dialogue. |
| Swipe from left edge | Disconnect (stream stays running on the PC) | Reconnect from the tile; RuneLite is where you left it. |

Second tap within 0.25 s near the first does **not** move the cursor; that is
Moonlight's double-click deadzone, which also makes double-tap on the same spot
reliable.

There is **no scroll gesture** in Touchscreen mode. Set camera zoom with the
in-game slider (Settings > Display) rather than the wheel.

## The keyboard toolbar

The three-finger keyboard comes with a toolbar: **Done, Win, Tab, Shift
(toggle), Esc, Ctrl (toggle), Alt (toggle), Delete.** Plus the normal iOS keys.

- **Shift toggle** on, then tap items to shift-drop. Toggle it off after.
- **Esc** closes the bank and most interfaces (set "Esc closes current interface" in game).
- **Space** advances dialogue. **1 to 5** pick dialogue options.
- **Enter** sends chat.
- There are **no F-keys**. Use Resizable-Modern layout so the tabs are on screen,
  and tap them. (Phase 2 of PocketLite adds an F-key bar; see `client/ios/ROADMAP.md`.)

## Trackpad mode (alternative)

| You do | Game sees |
| --- | --- |
| Swipe | Cursor moves relative to the swipe |
| Tap | Left click |
| Two-finger tap | Right click |
| Hold ~0.65 s then move | Left drag |
| Two-finger vertical drag | Scroll wheel (camera zoom works here) |

Trackpad mode wins when you want scroll-zoom or a cursor you can watch before
committing. It loses on speed: every click is two actions (position, then tap).
Switch per session in Moonlight > Settings.

## Habits that make it feel good

- Play **Resizable - Modern**. Everything you would hit with a hotkey is a visible tab.
- Put the **inventory and prayer on the same side** as your thumb (Settings > Interfaces > side panels).
- Pinch-magnify for bank sessions, pinch out before fighting.
- Use RuneLite's **Menu Entry Swapper** so left-click does the thing you want 95% of the time; it halves your long presses.
- For prayer switching, the "Prayer" tab plus taps is one action per prayer; on a phone that is workable for bosses with a predictable rotation and not for anything that needs 1-tick flicks.
