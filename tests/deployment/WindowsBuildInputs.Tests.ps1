$ErrorActionPreference = 'Stop'
Import-Module "$PSScriptRoot/../../deploy/jenkins/WindowsBuildInputs.psm1" -Force
$root = Join-Path ([IO.Path]::GetTempPath()) ('wao-build-inputs-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$root/checkout", "$root/private/migrations" | Out-Null
function Assert-Fails([scriptblock]$Action, [string]$Message) {
    try { & $Action | Out-Null } catch { if ($_.Exception.Message -like "*$Message*") { return }; throw }
    throw "Expected rejection: $Message"
}
try {
    $resource = "$root/private/migrations/V33__task_completed_at_and_webhook_switch.sql"
    [IO.File]::WriteAllText($resource, 'SELECT 1;')
    $configuration = "$root/private/build.json"
    @{legacyMigrationsDirectory="$root/private/migrations";legacyMigrationSha256=(Get-FileHash $resource).Hash} | ConvertTo-Json | Set-Content $configuration
    $argument = Get-WaoLegacyMigrationArguments $configuration "$root/checkout"
    if ($argument -notlike '-Dwao.legacyMigrationsDirectory=*') { throw 'Valid compatibility input was not accepted' }
    [IO.File]::WriteAllText($resource, 'SELECT 2;')
    Assert-Fails { Get-WaoLegacyMigrationArguments $configuration "$root/checkout" } 'hash mismatch'
    Assert-Fails { Get-WaoLegacyMigrationArguments "$root/missing.json" "$root/checkout" } 'missing'
    @{legacyMigrationsDirectory="$root/checkout";legacyMigrationSha256=('0'*64)} | ConvertTo-Json | Set-Content $configuration
    Assert-Fails { Get-WaoLegacyMigrationArguments $configuration "$root/checkout" } 'outside'
    git -C "$root/checkout" init --quiet
    git -C "$root/checkout" -c user.name=Test -c user.email=test@example.invalid commit --quiet --allow-empty -m baseline
    $sha = (git -C "$root/checkout" rev-parse HEAD).Trim()
    $old = '1' * 40
    "old new`n$old $sha" | Set-Content "$root/private/map"
    $base = Resolve-WaoReleaseBase git "$root/checkout" $old "$root/private/map"
    if ($base -ne $sha) { throw 'Mapped version was not resolved' }
    if ((Resolve-WaoReleaseBase git "$root/checkout" $sha) -ne $sha) { throw 'Normal iteration incorrectly required the map' }
    Assert-Fails { Resolve-WaoReleaseBase git "$root/checkout" ('2'*40) "$root/private/map" } 'unique mapping'
    Assert-Fails { Resolve-WaoReleaseBase git "$root/checkout" $old "$root/missing-map" } 'commit map'
    Write-Output 'PASS: compatibility hash, missing input, private boundary, history mapping and normal iteration'
} finally {
    $resolved = [IO.Path]::GetFullPath($root)
    if (-not $resolved.StartsWith([IO.Path]::GetFullPath([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid test cleanup boundary' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
