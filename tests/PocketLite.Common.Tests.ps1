#Requires -Modules Pester
<#
    Unit tests for host/lib/PocketLite.Common.psm1.

    These run on any OS (CI runs them on Ubuntu and Windows). Everything
    that needs a real Windows box (registry, services, firewall) is not
    exercised here; those paths are covered by Verify-PocketLite.ps1 on
    the host itself.
#>

BeforeAll {
    $modulePath = Join-Path -Path $PSScriptRoot -ChildPath '..' |
        Join-Path -ChildPath 'host' |
        Join-Path -ChildPath 'lib' |
        Join-Path -ChildPath 'PocketLite.Common.psm1'
    Import-Module $modulePath -Force

    # A realistic apps.json as Sunshine 2025+ writes it: env present, the
    # default Desktop entry, a user's own Steam entry with a uuid and a
    # prep-cmd, plus an unknown top-level key we must not drop.
    $script:SampleJson = @'
{
  "env": { "PATH": "$(PATH);C:\\Tools" },
  "apps": [
    { "name": "Desktop", "image-path": "desktop.png", "uuid": "11111111-1111-1111-1111-111111111111" },
    {
      "name": "Steam Big Picture",
      "detached": ["steam://open/bigpicture"],
      "image-path": "steam.png",
      "prep-cmd": [ { "do": "echo hi", "undo": "echo bye", "elevated": false } ],
      "uuid": "22222222-2222-2222-2222-222222222222"
    }
  ],
  "future-key": { "keep": true }
}
'@
}

Describe 'Get-SunshinePortTable' {
    It 'derives the documented default ports from base 47989' {
        $t = Get-SunshinePortTable
        ($t | Where-Object Protocol -eq 'TCP' | Select-Object -ExpandProperty Port | Sort-Object) |
            Should -Be @(47984, 47989, 47990, 48010)
        ($t | Where-Object Protocol -eq 'UDP' | Select-Object -ExpandProperty Port | Sort-Object) |
            Should -Be @(47998, 47999, 48000, 48002, 48010)
    }

    It 'shifts every port when the base port changes' {
        $t = Get-SunshinePortTable -BasePort 50000
        ($t | Where-Object Name -eq 'Web UI').Port | Should -Be 50001
        ($t | Where-Object Name -eq 'HTTPS (pairing)').Port | Should -Be 49995
    }

    It 'rejects a base port outside the range Sunshine allows' {
        { Get-SunshinePortTable -BasePort 80 } | Should -Throw
    }
}

Describe 'New-PocketLiteAppDefinition' {
    It 'creates both entries when both executables are known' {
        $apps = New-PocketLiteAppDefinition `
            -JagexLauncherPath 'C:\Program Files (x86)\Jagex Launcher\JagexLauncher.exe' `
            -RuneLitePath 'C:\Users\Oxain\AppData\Local\RuneLite\RuneLite.exe'
        $apps.Count | Should -Be 2
        $apps[0].name | Should -Be 'RuneLite'
        $apps[0].cmd | Should -Be 'C:\Program Files (x86)\Jagex Launcher\JagexLauncher.exe'
        $apps[0].'working-dir' | Should -Be 'C:\Program Files (x86)\Jagex Launcher'
        $apps[0].'wait-all' | Should -BeTrue
        $apps[0].'auto-detach' | Should -BeTrue
        $apps[0].'exit-timeout' | Should -Be 5
        $apps[1].name | Should -Be 'RuneLite (direct)'
    }

    It 'omits an entry whose executable was not found instead of writing a dead path' {
        $apps = New-PocketLiteAppDefinition -JagexLauncherPath 'C:\JL\JagexLauncher.exe' -RuneLitePath $null
        @($apps).Count | Should -Be 1
        @($apps)[0].name | Should -Be 'RuneLite'

        $apps2 = New-PocketLiteAppDefinition -JagexLauncherPath '' -RuneLitePath ''
        @($apps2).Count | Should -Be 0
    }

    It 'uses exactly the names Get-PocketLiteAppNames advertises' {
        $apps = New-PocketLiteAppDefinition -JagexLauncherPath 'C:\a\JagexLauncher.exe' -RuneLitePath 'C:\b\RuneLite.exe'
        ($apps | ForEach-Object name) | Should -Be (Get-PocketLiteAppNames)
    }
}

Describe 'New-PocketLiteAppDefinition working-dir' {
    It 'keeps Windows separators regardless of the OS the tests run on' {
        (New-PocketLiteAppDefinition -JagexLauncherPath 'C:\x\y\JagexLauncher.exe')[0].'working-dir' | Should -Be 'C:\x\y'
        (New-PocketLiteAppDefinition -JagexLauncherPath 'C:\JagexLauncher.exe')[0].'working-dir' | Should -Be 'C:\'
        (New-PocketLiteAppDefinition -JagexLauncherPath 'D:/mixed/sep/JagexLauncher.exe')[0].'working-dir' | Should -Be 'D:/mixed/sep'
    }
}

Describe 'ConvertFrom-SunshineAppsJson' {
    It 'parses a real-world file and forces apps to an array' {
        $o = ConvertFrom-SunshineAppsJson -Json $script:SampleJson
        @($o.apps).Count | Should -Be 2
        $o.env.PATH | Should -Be '$(PATH);C:\Tools'
    }

    It 'tolerates a UTF-8 BOM' {
        $o = ConvertFrom-SunshineAppsJson -Json ([char]0xFEFF + $script:SampleJson)
        @($o.apps).Count | Should -Be 2
    }

    It 'returns a valid empty document for empty input' {
        $o = ConvertFrom-SunshineAppsJson -Json ''
        $o.PSObject.Properties.Name | Should -Contain 'env'
        $o.PSObject.Properties.Name | Should -Contain 'apps'
        @($o.apps).Count | Should -Be 0
    }

    It 'adds missing env/apps keys (Sunshine requires both to exist)' {
        $o = ConvertFrom-SunshineAppsJson -Json '{ "apps": [ { "name": "Only" } ] }'
        $o.PSObject.Properties.Name | Should -Contain 'env'
        @($o.apps).Count | Should -Be 1
        @($o.apps)[0].name | Should -Be 'Only'
    }

    It 'throws on malformed JSON rather than silently returning an empty file' {
        { ConvertFrom-SunshineAppsJson -Json '{ not json' } | Should -Throw
    }
}

Describe 'Merge-SunshineApps' {
    BeforeEach {
        $script:Existing = ConvertFrom-SunshineAppsJson -Json $script:SampleJson
        $script:Ours = New-PocketLiteAppDefinition `
            -JagexLauncherPath 'C:\Program Files (x86)\Jagex Launcher\JagexLauncher.exe' `
            -RuneLitePath 'C:\Users\Oxain\AppData\Local\RuneLite\RuneLite.exe'
    }

    It 'appends our entries after the existing ones' {
        $m = Merge-SunshineApps -Existing $script:Existing -Apps $script:Ours
        ($m.apps | ForEach-Object name) | Should -Be @('Desktop', 'Steam Big Picture', 'RuneLite', 'RuneLite (direct)')
    }

    It 'preserves env, other apps verbatim and unknown top-level keys' {
        $m = Merge-SunshineApps -Existing $script:Existing -Apps $script:Ours
        $m.env.PATH | Should -Be '$(PATH);C:\Tools'
        $m.'future-key'.keep | Should -BeTrue
        $steam = $m.apps | Where-Object name -eq 'Steam Big Picture'
        $steam.uuid | Should -Be '22222222-2222-2222-2222-222222222222'
        @($steam.detached)[0] | Should -Be 'steam://open/bigpicture'
        @($steam.'prep-cmd')[0].do | Should -Be 'echo hi'
    }

    It 'does not mutate the input object' {
        $before = ConvertTo-SunshineAppsJson -Object $script:Existing
        $null = Merge-SunshineApps -Existing $script:Existing -Apps $script:Ours
        (ConvertTo-SunshineAppsJson -Object $script:Existing) | Should -Be $before
    }

    It 'is idempotent: merging twice equals merging once' {
        $once  = Merge-SunshineApps -Existing $script:Existing -Apps $script:Ours
        $twice = Merge-SunshineApps -Existing $once -Apps $script:Ours
        (ConvertTo-SunshineAppsJson -Object $twice) | Should -Be (ConvertTo-SunshineAppsJson -Object $once)
        @($twice.apps).Count | Should -Be 4
    }

    It 'updates a stale path in place and keeps the position and Sunshine-owned fields' {
        $once = Merge-SunshineApps -Existing $script:Existing -Apps $script:Ours
        # Simulate Sunshine having assigned a uuid and the user a box art.
        $rl = $once.apps | Where-Object name -eq 'RuneLite'
        $rl | Add-Member -NotePropertyName 'uuid' -NotePropertyValue 'abc' -Force
        $rl | Add-Member -NotePropertyName 'image-path' -NotePropertyValue 'C:\art\rl.png' -Force

        $moved = New-PocketLiteAppDefinition -JagexLauncherPath 'D:\Games\Jagex Launcher\JagexLauncher.exe' -RuneLitePath $null
        $again = Merge-SunshineApps -Existing $once -Apps $moved
        ($again.apps | ForEach-Object name) | Should -Be @('Desktop', 'Steam Big Picture', 'RuneLite', 'RuneLite (direct)')
        $rl2 = $again.apps | Where-Object name -eq 'RuneLite'
        $rl2.cmd | Should -Be 'D:\Games\Jagex Launcher\JagexLauncher.exe'
        $rl2.'working-dir' | Should -Be 'D:\Games\Jagex Launcher'
        $rl2.uuid | Should -Be 'abc'
        $rl2.'image-path' | Should -Be 'C:\art\rl.png'
    }

    It 'never clobbers a prep-cmd the user added to our entry' {
        $once = Merge-SunshineApps -Existing $script:Existing -Apps $script:Ours
        $rl = $once.apps | Where-Object name -eq 'RuneLite'
        $rl.'prep-cmd' = @([pscustomobject]@{ do = 'custom'; undo = ''; elevated = $false })
        $again = Merge-SunshineApps -Existing $once -Apps $script:Ours
        $rl2 = $again.apps | Where-Object name -eq 'RuneLite'
        @($rl2.'prep-cmd').Count | Should -Be 1
        @($rl2.'prep-cmd')[0].do | Should -Be 'custom'
    }

    It 'matches names case-sensitively so a user app called "runelite" is left alone' {
        $json = '{ "env": {}, "apps": [ { "name": "runelite", "cmd": "mine.exe" } ] }'
        $ex = ConvertFrom-SunshineAppsJson -Json $json
        $m = Merge-SunshineApps -Existing $ex -Apps $script:Ours
        ($m.apps | ForEach-Object name) | Should -Be @('runelite', 'RuneLite', 'RuneLite (direct)')
    }

    It 'works on an empty document' {
        $m = Merge-SunshineApps -Existing (New-EmptySunshineApps) -Apps $script:Ours
        @($m.apps).Count | Should -Be 2
        $m.PSObject.Properties.Name | Should -Contain 'env'
    }
}

Describe 'Remove-SunshineApps' {
    It 'removes only the named entries and keeps everything else' {
        $ex = ConvertFrom-SunshineAppsJson -Json $script:SampleJson
        $ours = New-PocketLiteAppDefinition -JagexLauncherPath 'C:\a\JagexLauncher.exe' -RuneLitePath 'C:\b\RuneLite.exe'
        $m = Merge-SunshineApps -Existing $ex -Apps $ours
        $r = Remove-SunshineApps -Existing $m -Names (Get-PocketLiteAppNames)
        ($r.apps | ForEach-Object name) | Should -Be @('Desktop', 'Steam Big Picture')
        $r.env.PATH | Should -Be '$(PATH);C:\Tools'
        $r.'future-key'.keep | Should -BeTrue
    }

    It 'is a no-op when nothing matches' {
        $ex = ConvertFrom-SunshineAppsJson -Json $script:SampleJson
        $r = Remove-SunshineApps -Existing $ex -Names (Get-PocketLiteAppNames)
        (ConvertTo-SunshineAppsJson -Object $r) | Should -Be (ConvertTo-SunshineAppsJson -Object $ex)
    }
}

Describe 'ConvertTo-SunshineAppsJson' {
    It 'round-trips through a strict parser with backslashes and booleans intact' {
        $ours = New-PocketLiteAppDefinition -JagexLauncherPath 'C:\Program Files (x86)\Jagex Launcher\JagexLauncher.exe' -RuneLitePath $null
        $m = Merge-SunshineApps -Existing (New-EmptySunshineApps) -Apps $ours
        $json = ConvertTo-SunshineAppsJson -Object $m
        $json | Should -Not -Match ([regex]::Escape([string][char]0xFEFF))
        $json.EndsWith("`n") | Should -BeTrue

        $back = ConvertFrom-Json -InputObject $json
        @($back.apps)[0].cmd | Should -Be 'C:\Program Files (x86)\Jagex Launcher\JagexLauncher.exe'
        @($back.apps)[0].'wait-all' | Should -BeOfType [bool]
        $et = @($back.apps)[0].'exit-timeout'
        (($et -is [int]) -or ($et -is [long])) | Should -BeTrue   # 5.1 gives int, 7.x gives long
        $et | Should -Be 5
        # prep-cmd must survive as an array, not be truncated to a string by a shallow depth.
        @($back.apps)[0].'prep-cmd' | Should -BeNullOrEmpty
        $json | Should -Match '"prep-cmd":\s*\[\s*\]'
    }
}

Describe 'Read-SunshineApps / Write-SunshineApps' {
    BeforeEach {
        $script:Dir = Join-Path -Path ([System.IO.Path]::GetTempPath()) -ChildPath ("pocketlite-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $script:Dir | Out-Null
        $script:Path = Join-Path -Path $script:Dir -ChildPath 'apps.json'
    }
    AfterEach {
        Remove-Item -LiteralPath $script:Dir -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'reads a missing file as an empty document' {
        $o = Read-SunshineApps -Path $script:Path
        @($o.apps).Count | Should -Be 0
    }

    It 'writes without a BOM, keeps one backup of the original, and is atomic (no temp files left)' {
        [System.IO.File]::WriteAllText($script:Path, $script:SampleJson)
        $ex = Read-SunshineApps -Path $script:Path
        $ours = New-PocketLiteAppDefinition -JagexLauncherPath 'C:\a\JagexLauncher.exe' -RuneLitePath $null
        Write-SunshineApps -Path $script:Path -Object (Merge-SunshineApps -Existing $ex -Apps $ours)

        $bytes = [System.IO.File]::ReadAllBytes($script:Path)
        ($bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) | Should -BeFalse
        (Get-ChildItem -Path $script:Dir -Filter '*.tmp').Count | Should -Be 0

        $backup = "$($script:Path).pocketlite.bak"
        Test-Path $backup | Should -BeTrue
        [System.IO.File]::ReadAllText($backup) | Should -Be $script:SampleJson

        # Second write must not overwrite the pristine backup.
        Write-SunshineApps -Path $script:Path -Object (New-EmptySunshineApps)
        [System.IO.File]::ReadAllText($backup) | Should -Be $script:SampleJson

        (Read-SunshineApps -Path $script:Path).apps.Count | Should -Be 0
    }

    It 'honours -WhatIf' {
        Write-SunshineApps -Path $script:Path -Object (New-EmptySunshineApps) -WhatIf
        Test-Path $script:Path | Should -BeFalse
    }

    It 'throws when the config directory does not exist' {
        $bad = Join-Path -Path $script:Dir -ChildPath 'nope' | Join-Path -ChildPath 'apps.json'
        { Write-SunshineApps -Path $bad -Object (New-EmptySunshineApps) } | Should -Throw
    }
}

Describe 'Get-SunshineBasePort' {
    It 'returns the default when there is no conf file' {
        Get-SunshineBasePort -ConfPath $null | Should -Be 47989
        Get-SunshineBasePort -ConfPath (Join-Path ([System.IO.Path]::GetTempPath()) 'definitely-missing.conf') | Should -Be 47989
    }

    It 'reads port = N and ignores comments' {
        $f = Join-Path -Path ([System.IO.Path]::GetTempPath()) -ChildPath ("pl-" + [guid]::NewGuid().ToString('N') + '.conf')
        try {
            [System.IO.File]::WriteAllText($f, "# port = 11111`nsunshine_name = PC`nport = 50000`n")
            Get-SunshineBasePort -ConfPath $f | Should -Be 50000
        } finally { Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue }
    }

    It 'falls back to default on an out-of-range value' {
        $f = Join-Path -Path ([System.IO.Path]::GetTempPath()) -ChildPath ("pl-" + [guid]::NewGuid().ToString('N') + '.conf')
        try {
            [System.IO.File]::WriteAllText($f, "port = 80`n")
            Get-SunshineBasePort -ConfPath $f | Should -Be 47989
        } finally { Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue }
    }
}

Describe 'Windows-only discovery on non-Windows' -Skip:(($PSVersionTable.PSEdition -eq 'Desktop') -or ($IsWindows -eq $true)) {
    It 'returns null instead of throwing' {
        Find-SunshineInstall | Should -BeNullOrEmpty
        Find-JagexLauncher | Should -BeNullOrEmpty
        Find-RuneLiteExe | Should -BeNullOrEmpty
        Test-PocketLiteAdmin | Should -BeFalse
    }
}
