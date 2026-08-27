param(
    [Parameter(Mandatory = $true)]
    [string]$PublicKeyPath
)

$ErrorActionPreference = 'Stop'
$key = (Get-Content -LiteralPath $PublicKeyPath -Raw).Trim()
if ($key -notmatch '^ssh-ed25519 [A-Za-z0-9+/=]+(?: .*)?$') {
    throw 'Invalid JumpServer public key.'
}

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $target = Join-Path $env:ProgramData 'ssh\administrators_authorized_keys'
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
} else {
    $target = Join-Path $env:USERPROFILE '.ssh\authorized_keys'
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
}

$existing = if (Test-Path -LiteralPath $target) { Get-Content -LiteralPath $target } else { @() }
if ($existing -notcontains $key) {
    Add-Content -LiteralPath $target -Value $key -Encoding ascii
}
if ($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    & icacls.exe $target /inheritance:r /grant '*S-1-5-32-544:F' /grant 'SYSTEM:F' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to secure administrators_authorized_keys ACLs.'
    }
}
Write-Output 'JumpServer dedicated public key installed.'
