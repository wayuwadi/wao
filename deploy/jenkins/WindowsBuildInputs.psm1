Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-WaoReleaseBase {
    param([Parameter(Mandatory)][string]$Git, [Parameter(Mandatory)][string]$SourceRoot,
          [Parameter(Mandatory)][string]$PreviousSha, [string]$CommitMap)
    if ($PreviousSha -notmatch '^[0-9a-f]{40}$') { throw 'Invalid previous release SHA' }
    $savedErrorAction = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $Git -C $SourceRoot cat-file -e ($PreviousSha + '^{commit}') 2>$null; $exists = $LASTEXITCODE -eq 0 }
    finally { $ErrorActionPreference = $savedErrorAction }
    if ($exists) { return $PreviousSha }
    if (-not $CommitMap -or -not (Test-Path -LiteralPath $CommitMap -PathType Leaf)) {
        throw 'Previous release is missing from Git; provide the protected migration commit map'
    }
    $mappedLines = @(Get-Content -LiteralPath $CommitMap | Where-Object { $_ -match ('^' + $PreviousSha + ' [0-9a-f]{40}$') })
    if ($mappedLines.Count -ne 1) { throw 'Previous release SHA has no unique mapping' }
    $mapped = ($mappedLines[0] -split ' ')[1]
    $savedErrorAction = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $Git -C $SourceRoot cat-file -e ($mapped + '^{commit}') 2>$null; $exists = $LASTEXITCODE -eq 0 }
    finally { $ErrorActionPreference = $savedErrorAction }
    if (-not $exists) { throw 'Mapped release is not present in this checkout' }
    return $mapped
}

function Get-WaoLegacyMigrationArguments {
    param([Parameter(Mandatory)][string]$ConfigurationPath, [Parameter(Mandatory)][string]$SourceRoot)
    if (-not (Test-Path -LiteralPath $ConfigurationPath -PathType Leaf)) { throw 'Protected production build configuration is missing' }
    $configuration = Get-Content -LiteralPath $ConfigurationPath -Raw | ConvertFrom-Json
    if ($configuration.legacyMigrationSha256 -notmatch '^[0-9A-Fa-f]{64}$') { throw 'Invalid legacy migration SHA256' }
    $directory = [IO.Path]::GetFullPath($configuration.legacyMigrationsDirectory)
    $checkout = [IO.Path]::GetFullPath($SourceRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($directory -eq $checkout.TrimEnd([IO.Path]::DirectorySeparatorChar) -or $directory.StartsWith($checkout, [StringComparison]::OrdinalIgnoreCase)) { throw 'Legacy migration resources must stay outside the source checkout' }
    $resource = Join-Path $directory 'V33__task_completed_at_and_webhook_switch.sql'
    if (-not (Test-Path -LiteralPath $resource -PathType Leaf)) { throw 'Protected legacy migration resource is missing' }
    if ((Get-FileHash -LiteralPath $resource -Algorithm SHA256).Hash -ne $configuration.legacyMigrationSha256) {
        throw 'Protected legacy migration hash mismatch; production deployment is blocked'
    }
    return "-Dwao.legacyMigrationsDirectory=$directory"
}

Export-ModuleMember -Function Resolve-WaoReleaseBase, Get-WaoLegacyMigrationArguments
