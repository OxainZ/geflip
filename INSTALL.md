# Running Geflip + Coach yourself

Two ways — the launcher is the reliable one.

## 1. One-click launcher (recommended)
Double-click **`Geflip + Coach`** on your Desktop (or `launch-geflip.bat` in this folder).
It opens RuneLite with the **Geflip flipper**, **Coach**, and **Jad** plugins loaded. First launch
of a session compiles for ~20s; the console window stays open while you play — close the RuneLite
window to exit. Nothing about your account/login is stored by the launcher.

If it ever fails, open `launch-geflip.bat` and check the `JAVA_HOME` path (needs JDK 11).

## 2. Sideloaded JAR (only if your RuneLite runs in developer mode)
The built plugin is copied to:
`C:\Users\Oxain\.runelite\sideloaded-plugins\geflip-plugin-1.0.0.jar`
A **developer-mode** RuneLite loads it automatically at startup. The normal launcher-installed
RuneLite does **not** load sideloaded plugins, so use the launcher (#1) unless you specifically run
RuneLite with `--developer-mode`.

To rebuild the JAR after code changes:
```
cd runelite-plugin
gradle jar
copy build\libs\geflip-plugin-1.0.0.jar %USERPROFILE%\.runelite\sideloaded-plugins\
```

## Note on the Jad plugin
The Jad Prayer Helper is against Jagex's third-party client rules, so it is **not** on the official
Plugin Hub and is bundled here for local use only — your machine, your risk. The Geflip flipper and
the Coach are rules-safe (read-only, no automation).
