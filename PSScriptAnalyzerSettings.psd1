@{
    Severity     = @('Error', 'Warning')
    ExcludeRules = @(
        # The scripts are interactive installers; coloured console output is the point.
        'PSAvoidUsingWriteHost',
        # "Apps" is the noun Sunshine itself uses (apps.json); singularising it would mislead.
        'PSUseSingularNouns'
    )
    Rules        = @{
        PSUseCompatibleSyntax = @{
            Enable         = $true
            # Windows PowerShell 5.1 is the floor: it is what a stock Windows box has.
            TargetVersions = @('5.1', '7.0')
        }
    }
}
