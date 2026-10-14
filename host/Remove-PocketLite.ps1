#Requires -Version 5.1
#Requires -RunAsAdministrator
<#
.SYNOPSIS
    Remove the PocketLite tiles from Sunshine's apps.json. Nothing else.

.DESCRIPTION
    Leaves Sunshine, Tailscale and the firewall rules in place because you
    may use them for other things. To go back to the exact pre-PocketLite
    apps.json instead, restore apps.json.pocketlite.bak next to it.

.PARAMETER RemoveFirewallRules
    Also delete the "PocketLite - Sunshine (TCP/UDP)" firewall rules this
    project created (rules created by Sunshine's own installer are untouched).

.EXAMPLE
    PS> .\Remove-PocketLite.ps1 -WhatIf
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [switch] $RemoveFirewallRules
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path -Path $PSScriptRoot -ChildPath 'lib\PocketLite.Common.psm1') -Force

if (-not (Test-PocketLiteWindows)) { throw 'Remove-PocketLite.ps1 only applies to a Windows host.' }

$sun = Find-SunshineInstall
if ($null -eq $sun) {
    Write-PocketLiteStep 'Sunshine is not installed; nothing to remove.' -Kind warn
} else {
    $svcName = Get-SunshineServiceName
    $svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue
    $wasRunning = ($null -ne $svc -and $svc.Status -eq 'Running')
    if ($wasRunning -and $PSCmdlet.ShouldProcess($svcName, 'Stop service before editing apps.json')) {
        Stop-Service -Name $svcName -Force
        $svc.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    }
    try {
        $doc = Read-SunshineApps -Path $sun.AppsJson
        $before = @($doc.apps).Count
        $pruned = Remove-SunshineApps -Existing $doc -Names (Get-PocketLiteAppNames)
        $removed = $before - @($pruned.apps).Count
        if ($removed -gt 0) {
            Write-SunshineApps -Path $sun.AppsJson -Object $pruned
            Write-PocketLiteStep "Removed $removed PocketLite tile(s) from $($sun.AppsJson)" -Kind ok
        } else {
            Write-PocketLiteStep 'No PocketLite tiles present in apps.json.' -Kind ok
        }
    } finally {
        if ($wasRunning -and $PSCmdlet.ShouldProcess($svcName, 'Start service')) {
            Start-Service -Name $svcName
        }
    }
}

if ($RemoveFirewallRules) {
    $rules = @(Get-NetFirewallRule -DisplayName 'PocketLite - Sunshine (*' -ErrorAction SilentlyContinue)
    foreach ($r in $rules) {
        if ($PSCmdlet.ShouldProcess($r.DisplayName, 'Remove firewall rule')) {
            Remove-NetFirewallRule -Name $r.Name
            Write-PocketLiteStep "Removed firewall rule $($r.DisplayName)" -Kind ok
        }
    }
    if ($rules.Count -eq 0) { Write-PocketLiteStep 'No PocketLite firewall rules found.' -Kind ok }
}
