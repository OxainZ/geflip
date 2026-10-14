#Requires -Version 5.1
<#
.SYNOPSIS
    Diagnose a PocketLite host. Read-only. Exit code 0 = everything green.

.DESCRIPTION
    Checks, in order: Sunshine install and service, listening ports,
    firewall rules, the PocketLite tiles and whether their executables still
    exist, GPU encoder availability, Tailscale state, and whether the optional
    RuneLite phone profile has been imported. Prints a table and a verdict.

    Does not need elevation; a few checks degrade to WARN when run as a
    normal user (firewall enumeration can need admin on some builds).

.EXAMPLE
    PS> .\Verify-PocketLite.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Continue'

Import-Module (Join-Path -Path $PSScriptRoot -ChildPath 'lib\PocketLite.Common.psm1') -Force

if (-not (Test-PocketLiteWindows)) {
    Write-PocketLiteStep 'Verify-PocketLite.ps1 inspects a Windows host; nothing to check here.' -Kind warn
    exit 2
}

$results = New-Object System.Collections.Generic.List[object]
function Add-Result {
    param([string] $Check, [ValidateSet('ok', 'warn', 'fail')][string] $Status, [string] $Detail)
    $results.Add([pscustomobject]@{ Check = $Check; Status = $Status.ToUpper(); Detail = $Detail })
    Write-PocketLiteStep "$Check - $Detail" -Kind $Status
}

# --- Sunshine binary & service --------------------------------------------
$sun = Find-SunshineInstall
if ($null -eq $sun) {
    Add-Result 'Sunshine installed' 'fail' 'sunshine.exe not found. Run Install-PocketLite.ps1.'
} else {
    Add-Result 'Sunshine installed' 'ok' $sun.Exe
    $svcName = Get-SunshineServiceName
    $svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue
    if ($null -eq $svc) {
        Add-Result 'Sunshine service' 'warn' "$svcName not registered (portable/lite install?). Sunshine must be running manually."
    } elseif ($svc.Status -ne 'Running') {
        Add-Result 'Sunshine service' 'fail' "$svcName is $($svc.Status). Start-Service $svcName"
    } else {
        Add-Result 'Sunshine service' 'ok' "$svcName running (start type: $($svc.StartType))"
    }
}

# --- Ports ---------------------------------------------------------------
$basePort = if ($null -ne $sun) { Get-SunshineBasePort -ConfPath $sun.Conf } else { 47989 }
$ports = Get-SunshinePortTable -BasePort $basePort
$tcpListeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty LocalPort)
foreach ($p in ($ports | Where-Object { $_.Protocol -eq 'TCP' -and -not $_.DuringStreamOnly })) {
    if ($tcpListeners -contains $p.Port) {
        Add-Result "Port $($p.Port)/TCP ($($p.Name))" 'ok' 'listening'
    } else {
        $kind = if ($p.Name -eq 'RTSP') { 'warn' } else { 'fail' }
        Add-Result "Port $($p.Port)/TCP ($($p.Name))" $kind 'not listening'
    }
}
Add-Result 'UDP stream ports' 'ok' (($ports | Where-Object Protocol -eq 'UDP' | ForEach-Object Port | Sort-Object -Unique) -join ', ') + ' (open only during a stream)'

# --- Firewall ------------------------------------------------------------
if ($null -ne $sun) {
    try {
        $rules = @(Get-NetFirewallRule -Direction Inbound -Action Allow -Enabled True -ErrorAction Stop)
        foreach ($proto in @('TCP', 'UDP')) {
            $hit = $rules | Where-Object {
                $app = $_ | Get-NetFirewallApplicationFilter -ErrorAction SilentlyContinue
                $pf  = $_ | Get-NetFirewallPortFilter -ErrorAction SilentlyContinue
                $null -ne $app -and $null -ne $pf -and $app.Program -ieq $sun.Exe -and ($pf.Protocol -eq $proto -or $pf.Protocol -eq 'Any')
            } | Select-Object -First 1
            if ($null -ne $hit) {
                Add-Result "Firewall inbound $proto" 'ok' $hit.DisplayName
            } else {
                Add-Result "Firewall inbound $proto" 'fail' 'no allow rule for sunshine.exe. Re-run Install-PocketLite.ps1 as admin.'
            }
        }
    } catch {
        Add-Result 'Firewall' 'warn' "could not enumerate rules ($($_.Exception.Message)). Re-run as admin."
    }
}

# --- Tiles ---------------------------------------------------------------
if ($null -ne $sun) {
    try {
        $doc = Read-SunshineApps -Path $sun.AppsJson
        foreach ($name in (Get-PocketLiteAppNames)) {
            $tile = @($doc.apps) | Where-Object { $_.PSObject.Properties.Name -contains 'name' -and $_.name -ceq $name } | Select-Object -First 1
            if ($null -eq $tile) {
                $kind = if ($name -eq 'RuneLite') { 'fail' } else { 'warn' }
                Add-Result "Tile '$name'" $kind 'missing from apps.json'
                continue
            }
            $cmd = [string]$tile.cmd
            if ([string]::IsNullOrWhiteSpace($cmd)) {
                Add-Result "Tile '$name'" 'fail' 'has no cmd'
            } elseif (-not (Test-Path -LiteralPath $cmd -PathType Leaf)) {
                Add-Result "Tile '$name'" 'fail' "points at a missing file: $cmd (re-run Install-PocketLite.ps1)"
            } else {
                Add-Result "Tile '$name'" 'ok' $cmd
            }
        }
    } catch {
        Add-Result 'apps.json' 'fail' "unreadable: $($_.Exception.Message)"
    }
}

# --- GPU / encoder hint ----------------------------------------------------
$gpus = @(Get-CimInstance -ClassName Win32_VideoController -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
if ($gpus.Count -eq 0) {
    Add-Result 'GPU' 'warn' 'could not enumerate'
} else {
    $vendorHint = switch -Regex ($gpus -join ' | ') {
        'NVIDIA'          { 'NVENC available (H.264/HEVC; AV1 on RTX 40+)'; break }
        'AMD|Radeon'      { 'AMF available (H.264/HEVC; AV1 on RX 7000+)'; break }
        'Intel'           { 'QuickSync available (check HEVC in Sunshine web UI > Troubleshooting)'; break }
        default           { 'unknown vendor; Sunshine may fall back to software encoding (laggy)' }
    }
    Add-Result 'GPU' 'ok' (($gpus -join ' | ') + ' - ' + $vendorHint)
}

# --- Tailscale -------------------------------------------------------------
$tsCmd = Get-Command -Name tailscale.exe -ErrorAction SilentlyContinue
if ($null -eq $tsCmd) {
    $tsDefault = Join-Path -Path $env:ProgramFiles -ChildPath 'Tailscale\tailscale.exe'
    if (Test-Path -LiteralPath $tsDefault -PathType Leaf) { $tsCmd = Get-Item -LiteralPath $tsDefault }
}
if ($null -eq $tsCmd) {
    Add-Result 'Tailscale' 'warn' 'not installed - LAN-only. Install-PocketLite.ps1 without -NoTailscale adds it.'
} else {
    $ip = $null
    try { $ip = (& $tsCmd.Source ip -4 2>$null | Select-Object -First 1) } catch { $ip = $null }
    if ([string]::IsNullOrWhiteSpace($ip)) {
        Add-Result 'Tailscale' 'warn' 'installed but not signed in (tray icon > Log in).'
    } else {
        Add-Result 'Tailscale' 'ok' "IP $ip - use this address in Moonlight away from home"
    }
}

# --- LAN address -----------------------------------------------------------
$lanIps = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
    Select-Object -ExpandProperty IPAddress)
Add-Result 'LAN IPv4' $(if ($lanIps.Count -gt 0) { 'ok' } else { 'warn' }) $(if ($lanIps.Count -gt 0) { $lanIps -join ', ' } else { 'none found' })

# --- RuneLite phone profile ---------------------------------------------------
$profilesDir = Get-RuneLiteProfilesDir
if ($null -ne $profilesDir -and (Test-Path -LiteralPath $profilesDir -PathType Container)) {
    $imported = @(Get-ChildItem -Path $profilesDir -Filter '*.properties' -ErrorAction SilentlyContinue |
        Where-Object { Select-String -LiteralPath $_.FullName -Pattern '^pocketlite\.marker=' -Quiet })
    if ($imported.Count -gt 0) {
        Add-Result 'RuneLite phone profile' 'ok' "imported as '$($imported[0].BaseName -replace '-\d+$','')' - switch to it in RuneLite's profile panel when playing on the phone"
    } else {
        Add-Result 'RuneLite phone profile' 'warn' 'not imported (optional). See runelite\README.md.'
    }
} else {
    Add-Result 'RuneLite phone profile' 'warn' 'RuneLite profile directory not found; RuneLite has never been launched for this user?'
}

# --- Verdict ---------------------------------------------------------------
Write-Host ''
$results | Format-Table -AutoSize -Wrap | Out-String -Width 200 | Write-Host
$fails = @($results | Where-Object Status -eq 'FAIL').Count
$warns = @($results | Where-Object Status -eq 'WARN').Count
if ($fails -gt 0) {
    Write-Host "$fails check(s) failed, $warns warning(s)." -ForegroundColor Red
    exit 1
}
Write-Host "All checks passed ($warns warning(s))." -ForegroundColor Green
exit 0
