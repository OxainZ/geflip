#Requires -Version 5.1
<#
.SYNOPSIS
    Shared logic for the PocketLite host scripts.

.DESCRIPTION
    Everything that can be unit-tested without a Windows box lives here:
    the Sunshine apps.json merge, the port table, the app definitions and
    the atomic file writer. Windows-only discovery (registry, services,
    firewall) is also here but is only *called* from the scripts, so the
    module imports cleanly on Linux/macOS for the Pester suite.

    Compatibility floor is Windows PowerShell 5.1 (what every Windows 10/11
    box ships with), so: no ternaries, no null-coalescing, no multi-child
    Join-Path, no ConvertFrom-Json -AsHashtable.
#>

Set-StrictMode -Version 2.0

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

# Names of the Sunshine app entries this project owns. Removal and
# idempotent re-install key on these, so renaming one is a breaking change.
$script:AppNameLauncher = 'RuneLite'
$script:AppNameDirect   = 'RuneLite (direct)'

$script:SunshineServiceName = 'SunshineService'
$script:SunshineDefaultBasePort = 47989
$script:SunshineWingetId  = 'LizardByte.Sunshine'
$script:TailscaleWingetId = 'Tailscale.Tailscale'

$script:OnWindows = ($PSVersionTable.PSEdition -eq 'Desktop') -or
                    (Get-Variable -Name IsWindows -ValueOnly -ErrorAction SilentlyContinue) -eq $true

function Get-PocketLiteAppNames {
    <# .SYNOPSIS Names of the Sunshine entries PocketLite manages. #>
    [CmdletBinding()]
    [OutputType([string[]])]
    param()
    return @($script:AppNameLauncher, $script:AppNameDirect)
}

function Get-SunshineServiceName {
    [CmdletBinding()]
    [OutputType([string])]
    param()
    return $script:SunshineServiceName
}

function Get-PocketLiteWingetIds {
    [CmdletBinding()]
    [OutputType([hashtable])]
    param()
    return @{ Sunshine = $script:SunshineWingetId; Tailscale = $script:TailscaleWingetId }
}

# ---------------------------------------------------------------------------
# Ports
# ---------------------------------------------------------------------------

function Get-SunshinePortTable {
    <#
    .SYNOPSIS
        The ports Sunshine listens on, derived from its base ("config") port.
    .DESCRIPTION
        Offsets come from Sunshine's source (src/nvhttp.h, src/stream.h,
        src/rtsp.h): HTTPS -5, HTTP 0, web UI +1, video +9, control +10,
        audio +11, mic +13, RTSP +21. Default base port is 47989.
    #>
    [CmdletBinding()]
    [OutputType([pscustomobject[]])]
    param(
        [ValidateRange(1029, 65514)]
        [int] $BasePort = $script:SunshineDefaultBasePort
    )
    $rows = @(
        @{ Name = 'HTTPS (pairing)';  Protocol = 'TCP'; Offset = -5;  DuringStreamOnly = $false },
        @{ Name = 'HTTP (discovery)'; Protocol = 'TCP'; Offset = 0;   DuringStreamOnly = $false },
        @{ Name = 'Web UI';           Protocol = 'TCP'; Offset = 1;   DuringStreamOnly = $false },
        @{ Name = 'RTSP';             Protocol = 'TCP'; Offset = 21;  DuringStreamOnly = $false },
        @{ Name = 'Video';            Protocol = 'UDP'; Offset = 9;   DuringStreamOnly = $true  },
        @{ Name = 'Control';          Protocol = 'UDP'; Offset = 10;  DuringStreamOnly = $true  },
        @{ Name = 'Audio';            Protocol = 'UDP'; Offset = 11;  DuringStreamOnly = $true  },
        @{ Name = 'Microphone';       Protocol = 'UDP'; Offset = 13;  DuringStreamOnly = $true  },
        @{ Name = 'RTSP (UDP)';       Protocol = 'UDP'; Offset = 21;  DuringStreamOnly = $true  }
    )
    foreach ($r in $rows) {
        [pscustomobject]@{
            Name             = $r.Name
            Protocol         = $r.Protocol
            Port             = $BasePort + $r.Offset
            DuringStreamOnly = $r.DuringStreamOnly
        }
    }
}

# ---------------------------------------------------------------------------
# App definitions
# ---------------------------------------------------------------------------

function New-PocketLiteAppDefinition {
    <#
    .SYNOPSIS
        Build the Sunshine app entries for a given set of discovered paths.
    .DESCRIPTION
        Field names and defaults mirror Sunshine's parser (src/process.cpp):
        name, cmd, working-dir, auto-detach, wait-all, exit-timeout, prep-cmd.
        Entries whose executable was not found are omitted rather than
        written with a dead path, so Moonlight never shows a tile that
        cannot launch.
    #>
    [Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseShouldProcessForStateChangingFunctions', '', Justification = 'Pure function: builds/returns an object, touches no system state.')]
    [CmdletBinding()]
    [OutputType([pscustomobject[]])]
    param(
        [AllowNull()][AllowEmptyString()]
        [string] $JagexLauncherPath,

        [AllowNull()][AllowEmptyString()]
        [string] $RuneLitePath
    )

    $apps = New-Object System.Collections.Generic.List[object]

    if (-not [string]::IsNullOrWhiteSpace($JagexLauncherPath)) {
        $apps.Add([pscustomobject][ordered]@{
            'name'         = $script:AppNameLauncher
            'cmd'          = $JagexLauncherPath
            'working-dir'  = (Get-WindowsParentPath -Path $JagexLauncherPath)
            # The launcher spawns RuneLite as a child and may exit on its
            # own. wait-all keeps the Sunshine "app" alive while *any*
            # descendant (RuneLite) is still running.
            'wait-all'     = $true
            'auto-detach'  = $true
            'exit-timeout' = 5
            'prep-cmd'     = @()
        })
    }

    if (-not [string]::IsNullOrWhiteSpace($RuneLitePath)) {
        $apps.Add([pscustomobject][ordered]@{
            'name'         = $script:AppNameDirect
            'cmd'          = $RuneLitePath
            'working-dir'  = (Get-WindowsParentPath -Path $RuneLitePath)
            'wait-all'     = $true
            'auto-detach'  = $true
            'exit-timeout' = 5
            'prep-cmd'     = @()
        })
    }

    return ,$apps.ToArray()
}

# ---------------------------------------------------------------------------
# apps.json read / merge / write
# ---------------------------------------------------------------------------

function New-EmptySunshineApps {
    [Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseShouldProcessForStateChangingFunctions', '', Justification = 'Pure function: builds/returns an object, touches no system state.')]
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param()
    # Sunshine's parser does tree.get_child("env") and get_child("apps")
    # unconditionally, so both keys must exist even when empty.
    return [pscustomobject][ordered]@{
        'env'  = [pscustomobject]@{}
        'apps' = @()
    }
}

function ConvertFrom-SunshineAppsJson {
    <#
    .SYNOPSIS
        Parse apps.json text into an object with guaranteed env/apps keys.
    .DESCRIPTION
        Tolerates a UTF-8 BOM and the Windows PowerShell 5.1 quirk where a
        one-element JSON array deserialises to a bare object.
    #>
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string] $Json
    )

    $text = $Json.TrimStart([char]0xFEFF)
    if ([string]::IsNullOrWhiteSpace($text)) {
        return New-EmptySunshineApps
    }

    $obj = ConvertFrom-Json -InputObject $text
    if ($null -eq $obj) { return New-EmptySunshineApps }

    if (-not ($obj.PSObject.Properties.Name -contains 'env') -or $null -eq $obj.env) {
        $obj | Add-Member -NotePropertyName 'env' -NotePropertyValue ([pscustomobject]@{}) -Force
    }
    if (-not ($obj.PSObject.Properties.Name -contains 'apps') -or $null -eq $obj.apps) {
        $obj | Add-Member -NotePropertyName 'apps' -NotePropertyValue @() -Force
    } else {
        # Force array semantics (5.1 unwraps single-element arrays).
        $obj.apps = @($obj.apps)
    }
    return $obj
}

function Merge-SunshineApps {
    <#
    .SYNOPSIS
        Upsert PocketLite app entries into an existing apps.json object.
    .DESCRIPTION
        Entries are matched by exact name. Matching entries are replaced
        in place (position preserved, so Moonlight's tile order is stable);
        new ones are appended. Every other app, the env block and any
        unknown top-level key are passed through untouched. The input is
        not mutated; a new object is returned.
    #>
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory)]
        [pscustomobject] $Existing,

        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [object[]] $Apps
    )

    $result = Copy-SunshineAppsObject -Object $Existing
    $current = New-Object System.Collections.Generic.List[object]
    foreach ($a in @($result.apps)) { $current.Add($a) }

    foreach ($incoming in $Apps) {
        $idx = -1
        for ($i = 0; $i -lt $current.Count; $i++) {
            if ((Get-AppName -App $current[$i]) -ceq $incoming.name) { $idx = $i; break }
        }
        if ($idx -ge 0) {
            $merged = Merge-SingleApp -Old $current[$idx] -New $incoming
            $current[$idx] = $merged
        } else {
            $current.Add($incoming)
        }
    }

    $result.apps = @($current.ToArray())
    return $result
}

function Remove-SunshineApps {
    <#
    .SYNOPSIS
        Return a copy of the apps.json object with the named entries removed.
    #>
    [Diagnostics.CodeAnalysis.SuppressMessageAttribute('PSUseShouldProcessForStateChangingFunctions', '', Justification = 'Pure function: builds/returns an object, touches no system state.')]
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory)]
        [pscustomobject] $Existing,

        [Parameter(Mandatory)]
        [string[]] $Names
    )
    $result = Copy-SunshineAppsObject -Object $Existing
    $kept = New-Object System.Collections.Generic.List[object]
    foreach ($a in @($result.apps)) {
        if ($Names -cnotcontains (Get-AppName -App $a)) { $kept.Add($a) }
    }
    $result.apps = @($kept.ToArray())
    return $result
}

function ConvertTo-SunshineAppsJson {
    <#
    .SYNOPSIS
        Serialise the apps object to JSON text Sunshine will accept.
    .DESCRIPTION
        Depth is explicit because the 5.1 default (2) silently truncates
        prep-cmd arrays into strings. The output has no BOM and ends with
        a newline.
    #>
    [CmdletBinding()]
    [OutputType([string])]
    param(
        [Parameter(Mandatory)]
        [pscustomobject] $Object
    )
    $json = ConvertTo-Json -InputObject $Object -Depth 16
    # Windows PowerShell 5.1 escapes a handful of characters as \u00XX
    # (e.g. '<', '>', '&', '''). That is valid JSON and Sunshine reads it
    # fine, so it is deliberately left alone.
    return ($json.TrimEnd() + "`n")
}

function Read-SunshineApps {
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory)]
        [string] $Path
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return New-EmptySunshineApps
    }
    $raw = [System.IO.File]::ReadAllText($Path)
    return ConvertFrom-SunshineAppsJson -Json $raw
}

function Write-SunshineApps {
    <#
    .SYNOPSIS
        Atomically write apps.json (temp file + rename), keeping one backup.
    .DESCRIPTION
        The first time PocketLite touches a given apps.json it copies the
        original to apps.json.pocketlite.bak and never overwrites that
        backup again, so a user can always get back to pre-PocketLite state.
    #>
    [CmdletBinding(SupportsShouldProcess)]
    [OutputType([void])]
    param(
        [Parameter(Mandatory)]
        [string] $Path,

        [Parameter(Mandatory)]
        [pscustomobject] $Object
    )
    $json = ConvertTo-SunshineAppsJson -Object $Object
    $dir  = Split-Path -Path $Path -Parent
    if (-not (Test-Path -LiteralPath $dir -PathType Container)) {
        throw "Sunshine config directory does not exist: $dir"
    }

    if (-not $PSCmdlet.ShouldProcess($Path, 'Write apps.json')) { return }

    $backup = "$Path.pocketlite.bak"
    if ((Test-Path -LiteralPath $Path -PathType Leaf) -and -not (Test-Path -LiteralPath $backup -PathType Leaf)) {
        Copy-Item -LiteralPath $Path -Destination $backup
    }

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $tmp = Join-Path -Path $dir -ChildPath ("apps.json.{0}.tmp" -f ([guid]::NewGuid().ToString('N')))
    try {
        [System.IO.File]::WriteAllText($tmp, $json, $utf8NoBom)
        Move-Item -LiteralPath $tmp -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue }
    }
}

# --- private helpers -------------------------------------------------------

function Get-WindowsParentPath {
    <#
    .SYNOPSIS
        Parent directory of a Windows path, computed the same way on every OS.
    .DESCRIPTION
        Split-Path rewrites separators on Linux/macOS, which breaks the
        cross-platform test-suite; this keeps the path exactly as given.
    #>
    [CmdletBinding()]
    [OutputType([string])]
    param([Parameter(Mandatory)][string] $Path)
    $idx = [Math]::Max($Path.LastIndexOf('\'), $Path.LastIndexOf('/'))
    if ($idx -le 0) { return '' }
    $parent = $Path.Substring(0, $idx)
    # "C:" -> "C:\" so a root-level exe gets a usable working dir.
    if ($parent -match '^[A-Za-z]:$') { $parent += '\' }
    return $parent
}

function Get-AppName {
    [CmdletBinding()]
    [OutputType([string])]
    param([Parameter(Mandatory)] $App)
    if ($App.PSObject.Properties.Name -contains 'name') { return [string]$App.name }
    return ''
}

function Merge-SingleApp {
    # Replace the fields PocketLite owns, keep anything else Sunshine or the
    # user added to that entry (uuid, image-path, output, custom prep-cmd...).
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param(
        [Parameter(Mandatory)] $Old,
        [Parameter(Mandatory)] $New
    )
    $merged = Copy-SunshineAppsObject -Object $Old
    foreach ($p in $New.PSObject.Properties) {
        if ($p.Name -eq 'prep-cmd') {
            # Only seed prep-cmd when the user has none; never clobber theirs.
            $hasOwn = ($merged.PSObject.Properties.Name -contains 'prep-cmd') -and
                      (@($merged.'prep-cmd').Count -gt 0)
            if ($hasOwn) { continue }
        }
        $merged | Add-Member -NotePropertyName $p.Name -NotePropertyValue $p.Value -Force
    }
    return $merged
}

function Copy-SunshineAppsObject {
    # Deep copy via JSON round-trip; cheap at this size and avoids mutating
    # the caller's object (important for the idempotency tests).
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param([Parameter(Mandatory)] $Object)
    $json = ConvertTo-Json -InputObject $Object -Depth 16
    $copy = ConvertFrom-Json -InputObject $json
    if ($copy.PSObject.Properties.Name -contains 'apps' -and $null -ne $copy.apps) {
        $copy.apps = @($copy.apps)
    }
    return $copy
}

# ---------------------------------------------------------------------------
# Windows-only discovery. Each returns $null when not found; none throws.
# ---------------------------------------------------------------------------

function Test-PocketLiteWindows {
    [CmdletBinding()]
    [OutputType([bool])]
    param()
    return [bool]$script:OnWindows
}

function Test-PocketLiteAdmin {
    [CmdletBinding()]
    [OutputType([bool])]
    param()
    if (-not $script:OnWindows) { return $false }
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($id)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Find-SunshineInstall {
    <#
    .SYNOPSIS
        Locate sunshine.exe and its config directory.
    .DESCRIPTION
        Sunshine (Windows) keeps its config next to the executable in a
        "config" folder (src/platform/windows/misc.cpp: appdata()). We try
        the uninstall registry first, then the default MSI location.
    #>
    [CmdletBinding()]
    [OutputType([pscustomobject])]
    param()
    if (-not $script:OnWindows) { return $null }

    $candidates = New-Object System.Collections.Generic.List[string]

    $uninstallRoots = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall'
    )
    foreach ($root in $uninstallRoots) {
        $items = Get-ChildItem -Path $root -ErrorAction SilentlyContinue
        foreach ($item in $items) {
            $props = Get-ItemProperty -Path $item.PSPath -ErrorAction SilentlyContinue
            if ($null -eq $props) { continue }
            $display = [string]($props.PSObject.Properties['DisplayName'].Value)
            if ($display -like 'Sunshine*') {
                $loc = [string]($props.PSObject.Properties['InstallLocation'].Value)
                if (-not [string]::IsNullOrWhiteSpace($loc)) {
                    $candidates.Add((Join-Path -Path $loc -ChildPath 'sunshine.exe'))
                }
            }
        }
    }

    $pf = $env:ProgramFiles
    if (-not [string]::IsNullOrWhiteSpace($pf)) {
        $candidates.Add((Join-Path -Path (Join-Path -Path $pf -ChildPath 'Sunshine') -ChildPath 'sunshine.exe'))
    }

    foreach ($exe in $candidates) {
        if (Test-Path -LiteralPath $exe -PathType Leaf) {
            $dir = Split-Path -Path $exe -Parent
            return [pscustomobject]@{
                Exe       = $exe
                Dir       = $dir
                ConfigDir = (Join-Path -Path $dir -ChildPath 'config')
                AppsJson  = (Join-Path -Path (Join-Path -Path $dir -ChildPath 'config') -ChildPath 'apps.json')
                Conf      = (Join-Path -Path (Join-Path -Path $dir -ChildPath 'config') -ChildPath 'sunshine.conf')
            }
        }
    }
    return $null
}

function Find-JagexLauncher {
    <#
    .SYNOPSIS
        Locate JagexLauncher.exe (default: %ProgramFiles(x86)%\Jagex Launcher).
    #>
    [CmdletBinding()]
    [OutputType([string])]
    param()
    if (-not $script:OnWindows) { return $null }

    $roots = @()
    $pf86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    if (-not [string]::IsNullOrWhiteSpace($pf86)) { $roots += $pf86 }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) { $roots += $env:ProgramFiles }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $roots += (Join-Path -Path $env:LOCALAPPDATA -ChildPath 'Programs')
    }

    foreach ($root in $roots) {
        $exe = Join-Path -Path (Join-Path -Path $root -ChildPath 'Jagex Launcher') -ChildPath 'JagexLauncher.exe'
        if (Test-Path -LiteralPath $exe -PathType Leaf) { return $exe }
    }

    # Fall back to the uninstall registry (custom install dir).
    $uninstallRoots = @(
        'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall',
        'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall'
    )
    foreach ($root in $uninstallRoots) {
        $items = Get-ChildItem -Path $root -ErrorAction SilentlyContinue
        foreach ($item in $items) {
            $props = Get-ItemProperty -Path $item.PSPath -ErrorAction SilentlyContinue
            if ($null -eq $props) { continue }
            $display = [string]($props.PSObject.Properties['DisplayName'].Value)
            if ($display -like 'Jagex Launcher*') {
                $loc = [string]($props.PSObject.Properties['InstallLocation'].Value)
                if (-not [string]::IsNullOrWhiteSpace($loc)) {
                    $exe = Join-Path -Path $loc -ChildPath 'JagexLauncher.exe'
                    if (Test-Path -LiteralPath $exe -PathType Leaf) { return $exe }
                }
            }
        }
    }
    return $null
}

function Find-RuneLiteExe {
    <#
    .SYNOPSIS
        Locate RuneLite.exe (RuneLite installer default: %LOCALAPPDATA%\RuneLite).
    #>
    [CmdletBinding()]
    [OutputType([string])]
    param()
    if (-not $script:OnWindows) { return $null }

    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidates += (Join-Path -Path (Join-Path -Path $env:LOCALAPPDATA -ChildPath 'RuneLite') -ChildPath 'RuneLite.exe')
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
        $candidates += (Join-Path -Path (Join-Path -Path $env:ProgramFiles -ChildPath 'RuneLite') -ChildPath 'RuneLite.exe')
    }
    foreach ($exe in $candidates) {
        if (Test-Path -LiteralPath $exe -PathType Leaf) { return $exe }
    }
    return $null
}

function Get-RuneLiteProfilesDir {
    <#
    .SYNOPSIS
        RuneLite's profile store: %USERPROFILE%\.runelite\profiles2
    #>
    [CmdletBinding()]
    [OutputType([string])]
    param()
    $userHome = [Environment]::GetFolderPath('UserProfile')
    if ([string]::IsNullOrWhiteSpace($userHome)) { return $null }
    return (Join-Path -Path (Join-Path -Path $userHome -ChildPath '.runelite') -ChildPath 'profiles2')
}

function Get-SunshineBasePort {
    <#
    .SYNOPSIS
        Read "port = N" from sunshine.conf; default 47989 when unset.
    #>
    [CmdletBinding()]
    [OutputType([int])]
    param(
        [AllowNull()][AllowEmptyString()]
        [string] $ConfPath
    )
    if ([string]::IsNullOrWhiteSpace($ConfPath) -or -not (Test-Path -LiteralPath $ConfPath -PathType Leaf)) {
        return $script:SunshineDefaultBasePort
    }
    foreach ($line in [System.IO.File]::ReadAllLines($ConfPath)) {
        $t = $line.Trim()
        if ($t.StartsWith('#')) { continue }
        if ($t -match '^port\s*=\s*(\d+)\s*$') {
            $p = [int]$Matches[1]
            if ($p -ge 1029 -and $p -le 65514) { return $p }
        }
    }
    return $script:SunshineDefaultBasePort
}

function Write-PocketLiteStep {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string] $Message,
        [ValidateSet('info', 'ok', 'warn', 'fail')]
        [string] $Kind = 'info'
    )
    $prefix = switch ($Kind) {
        'ok'   { '[ OK ]' }
        'warn' { '[WARN]' }
        'fail' { '[FAIL]' }
        default { '[....]' }
    }
    $color = switch ($Kind) {
        'ok'   { 'Green' }
        'warn' { 'Yellow' }
        'fail' { 'Red' }
        default { 'Cyan' }
    }
    Write-Host ("{0} {1}" -f $prefix, $Message) -ForegroundColor $color
}

Export-ModuleMember -Function @(
    'Get-PocketLiteAppNames',
    'Get-SunshineServiceName',
    'Get-PocketLiteWingetIds',
    'Get-SunshinePortTable',
    'New-PocketLiteAppDefinition',
    'New-EmptySunshineApps',
    'ConvertFrom-SunshineAppsJson',
    'Merge-SunshineApps',
    'Remove-SunshineApps',
    'ConvertTo-SunshineAppsJson',
    'Read-SunshineApps',
    'Write-SunshineApps',
    'Test-PocketLiteWindows',
    'Test-PocketLiteAdmin',
    'Find-SunshineInstall',
    'Find-JagexLauncher',
    'Find-RuneLiteExe',
    'Get-RuneLiteProfilesDir',
    'Get-SunshineBasePort',
    'Write-PocketLiteStep'
)
