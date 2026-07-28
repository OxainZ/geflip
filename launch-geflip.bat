@echo off
title Geflip + Coach  (RuneLite)
REM ===========================================================================
REM  One-click launcher for the Geflip flipper + Coach + Jad plugins.
REM  Double-click this (or the desktop shortcut) to open RuneLite with them
REM  loaded. First launch of a session compiles (~20s); after that it's quick.
REM  Close the RuneLite window to exit. No account/login is stored here.
REM ===========================================================================
set "JAVA_HOME=C:\Users\Oxain\tools\jdk\jdk-11.0.31+11"
set "GRADLE=C:\Users\Oxain\tools\gradle\gradle-7.6.4\bin\gradle.bat"
cd /d "C:\Users\Oxain\geflip\runelite-plugin"
echo.
echo   Launching RuneLite with Geflip + Coach ...
echo   (first launch compiles for ~20s - this window stays open while you play)
echo.
call "%GRADLE%" run --no-daemon
if errorlevel 1 (
  echo.
  echo   Launch failed. Make sure the JDK path above is correct, then try again.
  pause
)
