# Why PocketLite streams instead of running on the phone

The question everyone asks first. Three independent walls.

## 1. The runtime wall

RuneLite is a Java 11 Swing application that hosts Jagex's own game client (the
"gamepack", also Java) inside it and injects into it with a bytecode mixin
system at startup. iOS has no JVM, forbids JIT compilation for third-party apps,
and forbids loading executable code at runtime. Porting means rewriting both
RuneLite and the gamepack. The RuneLite team's FAQ answers "Will there be a
RuneLite mobile client?" with one word: No.

## 2. The rules wall

Jagex approves exactly two third-party clients, RuneLite and HDOS, for desktop.
An iOS app that implements the game protocol itself is an unapproved client. The
2023 Third Party Client update made the position explicit, and Jagex's own
attempt to bring plugins to mobile (announced 2025) was paused in January 2026
in favour of building eight RuneLite-style features into the official app.

Streaming your own PC is a different thing. The only process talking to Jagex
servers is the real RuneLite on your Windows machine. The phone is a display
and an input device, the same as a remote desktop session. Nothing on the phone
reads game memory, nothing automates input, nothing changes how RuneLite
behaves. Jagex has not published a rule that addresses remote desktop
specifically; PocketLite's author's reading is that it is the same as playing
on the PC, because it is. Read Jagex's rules yourself and make your own call.

## 3. The quality wall

Even if the first two walls fell, a native port would ship with none of your
plugins, none of your profiles and a years-long catch-up to RuneLite's plugin
hub. Streaming gives you *your* RuneLite, every plugin, today.

## What streaming costs you

One game tick of input latency on a good link. Battery. A PC that stays on.
And a touch layer designed for Steam games, not OSRS, which is the gap Phase 2
of PocketLite closes with a Moonlight fork (`client/ios/ROADMAP.md`).

## Things that look like alternatives and are not

- **"RuneLite Mobile" APK/IPA downloads.** Fakes. Credential stealers at best.
- **Running the Jagex launcher inside a Windows emulator on iOS.** UTM-style
  emulation has no GPU passthrough and no JIT; RuneLite starts in minutes and
  runs at a frame every few seconds.
- **Java-in-the-browser (CheerpJ) ports.** The gamepack needs raw TCP sockets
  to Jagex, which browsers cannot open without a relay that would itself be an
  unapproved client sitting in the middle of your session.
