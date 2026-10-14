#Requires -Version 5.1
#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Set up a Windows PC as a PocketLite host: Sunshine + Tailscale + RuneLite
    app tiles, ready to pair from Moonlight on the iPhone.

.DESCRIPTION
    Idempotent. Run it again after moving RuneLite, reinstalling Sunshine or
    changing the Jagex Launcher location and it converges to the right state.

    What it does, in order:
      1. Installs Sunshine (LizardByte.Sunshine) via winget if missing.
      2. Installs Tailscale (Tailscale.Tailscale) via winget unless -NoTailscale.
      3. Ensures inbound firewall rules for sunshine.exe (TCP + UDP), the same
         shape as Sunshine's own scripts\add-firewall-rule.bat.
      4. Finds Jagex Launcher and RuneLite.exe and writes/updates the
         "RuneLite" and "RuneLite (direct)" tiles in Sunshine's apps.json,
         preserving every other entry and keeping a one-time backup.
      5. Restarts SunshineService so the tiles appear in Moonlight.
      6. Prints the pairing checklist: web UI URL, LAN IP, Tailscale IP.

    It never touches RuneLite's own settings; see ..\runelite\README.md for the
    optional phone profile.

.PARAMETER NoTailscale
    Skip the Tailscale install (LAN-only play).

.PARAMETER NoWinget
    Do not try to install anything; fail if Sunshine is not already installed.

.PARAMETER JagexLauncherPath
    Override auto-detection of JagexLauncher.exe.

.PARAMETER RuneLitePath
    Override auto-detection of RuneLite.exe.

.EXAMPLE
    PS> Set-ExecutionPolicy -Scope Process Bypass; .\Install-PocketLite.ps1

.EXAMPLE
    PS> .\Install-PocketLite.ps1 -NoTailscale -WhatIf
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [switch] $NoTailscale,
    [switch] $NoWinget,
    [string] $JagexLauncherPath,
    [string] $RuneLitePath
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path -Path $PSScriptRoot -ChildPath 'lib\PocketLite.Common.psm1') -Force

if (-not (Test-PocketLiteWindows)) {
    throw 'Install-PocketLite.ps1 configures a Windows host. Run it on the PC that runs RuneLite.'
}

$ids = Get-PocketLiteWingetIds
$script:InstallsAllowed = -not [bool]$NoWinget

function Invoke-Winget {
    [CmdletBinding(SupportsShouldProcess)]
    param([Parameter(Mandatory)][string[]] $Arguments, [Parameter(Mandatory)][string] $What)
    if (-not $script:InstallsAllowed) { throw "$What is not installed and -NoWinget was given." }
    $winget = Get-Command -Name winget.exe -ErrorAction SilentlyContinue
    if ($null -eq $winget) {
        throw "$What is not installed and winget.exe was not found. Install 'App Installer' from the Microsoft Store, or install $What manually and re-run."
    }
    if (-not $PSCmdlet.ShouldProcess($What, "winget $($Arguments -join ' ')")) { return }
    Write-PocketLiteStep "Installing $What via winget (this can take a minute)..."
    & $winget.Source @Arguments
    # winget returns 0 on success and a documented set of codes otherwise;
    # -1978335189 (0x8A15002B) is "no applicable upgrade", harmless here.
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne -1978335189) {
        throw "winget exited with code $LASTEXITCODE while installing $What."
    }
}

# --- 1. Sunshine -----------------------------------------------------------
$sun = Find-SunshineInstall
if ($null -eq $sun) {
    Invoke-Winget -What 'Sunshine' -Arguments @(
        'install', '--id', $ids.Sunshine, '--exact', '--silent',
        '--accept-package-agreements', '--accept-source-agreements'
    )
    $sun = Find-SunshineInstall
    if ($null -eq $sun -and -not $WhatIfPreference) {
        throw 'Sunshine was installed but sunshine.exe could not be located. Open the Sunshine web UI once (https://localhost:47990), then re-run.'
    }
}
if ($null -ne $sun) {
    Write-PocketLiteStep "Sunshine: $($sun.Exe)" -Kind ok
} else {
    Write-PocketLiteStep 'Sunshine: (would be installed)' -Kind info
}

# --- 2. Tailscale ----------------------------------------------------------
if (-not $NoTailscale) {
    $ts = Get-Command -Name tailscale.exe -ErrorAction SilentlyContinue
    $tsDefault = Join-Path -Path $env:ProgramFiles -ChildPath 'Tailscale\tailscale.exe'
    if ($null -eq $ts -and (Test-Path -LiteralPath $tsDefault -PathType Leaf)) { $ts = Get-Item -LiteralPath $tsDefault }
    if ($null -eq $ts) {
        Invoke-Winget -What 'Tailscale' -Arguments @(
            'install', '--id', $ids.Tailscale, '--exact', '--silent',
            '--accept-package-agreements', '--accept-source-agreements'
        )
        Write-PocketLiteStep 'Tailscale installed. Sign in from the tray icon (or run "tailscale up") and install Tailscale on the iPhone too.' -Kind warn
    } else {
        Write-PocketLiteStep "Tailscale: present" -Kind ok
    }
} else {
    Write-PocketLiteStep 'Tailscale: skipped (-NoTailscale). Remote play off-LAN will not work until you add a VPN/overlay.' -Kind warn
}

# --- 3. Firewall -----------------------------------------------------------
if ($null -ne $sun) {
    foreach ($proto in @('TCP', 'UDP')) {
        $existing = @(Get-NetFirewallRule -Direction Inbound -Action Allow -Enabled True -ErrorAction SilentlyContinue |
            Where-Object {
                $app = $_ | Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue
                $pf  = $_ | Get-NetFirewallPortFilter -ErrorAction SilentlyContinue
                $null -ne $app -and $null -ne $pf -and
                $app.Program -ieq $sun.Exe -and $pf.Protocol -eq $proto
            })
        if ($existing.Count -gt 0) {
            Write-PocketLiteStep "Firewall: inbound $proto rule for sunshine.exe present ($($existing[0].DisplayName))" -Kind ok
            continue
        }
        if ($PSCmdlet.ShouldProcess("Windows Firewall", "Add inbound $proto allow rule for $($sun.Exe)")) {
            New-NetFirewallRule -DisplayName "PocketLite - Sunshine ($proto)" -Direction Inbound -Action Allow `
                -Program $sun.Exe -Protocol $proto -Profile Any -Enabled True | Out-Null
            Write-PocketLiteStep "Firewall: added inbound $proto rule for sunshine.exe" -Kind ok
        }
    }
}

# --- 4. App tiles ----------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($JagexLauncherPath)) { $JagexLauncherPath = Find-JagexLauncher }
if ([string]::IsNullOrWhiteSpace($RuneLitePath))      { $RuneLitePath      = Find-RuneLiteExe }

foreach ($pair in @(@('Jagex Launcher', $JagexLauncherPath), @('RuneLite.exe', $RuneLitePath))) {
    if ([string]::IsNullOrWhiteSpace($pair[1])) {
        Write-PocketLiteStep "$($pair[0]): not found (tile skipped). Pass -$(if ($pair[0] -like 'Jagex*') { 'JagexLauncherPath' } else { 'RuneLitePath' }) to override." -Kind warn
    } elseif (-not (Test-Path -LiteralPath $pair[1] -PathType Leaf)) {
        throw "$($pair[0]) path does not exist: $($pair[1])"
    } else {
        Write-PocketLiteStep "$($pair[0]): $($pair[1])" -Kind ok
    }
}

if ([string]::IsNullOrWhiteSpace($JagexLauncherPath) -and [string]::IsNullOrWhiteSpace($RuneLitePath)) {
    throw 'Neither Jagex Launcher nor RuneLite.exe was found. Install RuneLite (via the Jagex Launcher for Jagex accounts) and re-run.'
}

$apps = New-PocketLiteAppDefinition -JagexLauncherPath $JagexLauncherPath -RuneLitePath $RuneLitePath

if ($null -ne $sun) {
    if (-not (Test-Path -LiteralPath $sun.ConfigDir -PathType Container)) {
        if ($PSCmdlet.ShouldProcess($sun.ConfigDir, 'Create Sunshine config directory')) {
            New-Item -ItemType Directory -Path $sun.ConfigDir | Out-Null
        }
    }
    $svcName = Get-SunshineServiceName
    $svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue
    $wasRunning = ($null -ne $svc -and $svc.Status -eq 'Running')

    if ($wasRunning -and $PSCmdlet.ShouldProcess($svcName, 'Stop service before editing apps.json')) {
        Stop-Service -Name $svcName -Force
        $svc.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    }
    try {
        $current = Read-SunshineApps -Path $sun.AppsJson
        $merged  = Merge-SunshineApps -Existing $current -Apps $apps
        Write-SunshineApps -Path $sun.AppsJson -Object $merged
        Write-PocketLiteStep "apps.json: $($apps.Count) PocketLite tile(s) written to $($sun.AppsJson)" -Kind ok
    } finally {
        if ($null -ne $svc -and $PSCmdlet.ShouldProcess($svcName, 'Start service')) {
            Start-Service -Name $svcName
            Write-PocketLiteStep "$svcName restarted" -Kind ok
        } elseif ($null -eq $svc) {
            Write-PocketLiteStep 'Sunshine is not installed as a service; restart Sunshine by hand so it reloads apps.json.' -Kind warn
        }
    }
}

# --- 5. Pairing checklist --------------------------------------------------
$basePort = if ($null -ne $sun) { Get-SunshineBasePort -ConfPath $sun.Conf } else { 47989 }
$webPort  = ($basePort + 1)
$lanIps = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
    Select-Object -ExpandProperty IPAddress)
$tsIp = $null
$tsCmd = Get-Command -Name tailscale.exe -ErrorAction SilentlyContinue
if ($null -ne $tsCmd) {
    try { $tsIp = (& $tsCmd.Source ip -4 2>$null | Select-Object -First 1) } catch { $tsIp = $null }
}

Write-Host ''
Write-Host '================  PocketLite host is ready  ================' -ForegroundColor Green
Write-Host " 1. On the PC, open  https://localhost:$webPort  once and set a Sunshine username/password."
Write-Host ' 2. On the iPhone, install "Moonlight Game Streaming" from the App Store.'
Write-Host " 3. In Moonlight, tap + and enter one of these addresses:"
foreach ($ip in $lanIps) { Write-Host "      LAN:        $ip" }
if ($tsIp) { Write-Host "      Tailscale:  $tsIp   (works away from home)" }
else       { Write-Host "      Tailscale:  (sign in to Tailscale on the PC, then run Verify-PocketLite.ps1 to see it)" }
Write-Host " 4. Moonlight shows a 4-digit PIN; type it at  https://localhost:$webPort/pin  on the PC."
Write-Host ' 5. Tap the "RuneLite" tile. Settings to use are in docs\moonlight-settings.md.'
Write-Host ''
Write-Host ' Verify any time with:  .\Verify-PocketLite.ps1'
Write-Host '============================================================' -ForegroundColor Green
