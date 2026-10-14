# Changelog

## Unreleased

### Added
- `host/Install-PocketLite.ps1`: idempotent Windows host setup (Sunshine, Tailscale, firewall, RuneLite tiles).
- `host/Verify-PocketLite.ps1`: read-only diagnostics with exit codes.
- `host/Remove-PocketLite.ps1`: removes only what PocketLite added.
- `host/lib/PocketLite.Common.psm1`: tested merge/write logic for Sunshine's `apps.json`.
- `runelite/PocketLite.properties`: importable phone profile.
- Docs: Moonlight settings for iPhone 17, OSRS touch controls, Tailscale remote play, troubleshooting, rationale.
- `client/ios/ROADMAP.md`: Phase 2 design for an OSRS-mode Moonlight fork.
- CI: lint + Pester on Linux/macOS/Windows and under Windows PowerShell 5.1.
