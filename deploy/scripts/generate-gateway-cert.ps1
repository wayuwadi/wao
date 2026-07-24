param(
    [string]$KeytoolPath = 'keytool.exe'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$envFile = Join-Path $projectRoot 'deploy\.env'
$certDirectory = Join-Path $projectRoot 'deploy\certs'
$certificatePath = Join-Path $certDirectory 'gateway.p12'

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw "Missing $envFile"
}
if (Test-Path -LiteralPath $certificatePath) {
    throw "Certificate already exists: $certificatePath"
}

$passwordLine = Get-Content -LiteralPath $envFile -Encoding UTF8 |
    Where-Object { $_.StartsWith('GATEWAY_SSL_PASS=') } |
    Select-Object -First 1
if (-not $passwordLine) { throw 'GATEWAY_SSL_PASS is missing from deploy/.env.' }

$gatewayPassword = $passwordLine.Substring('GATEWAY_SSL_PASS='.Length)
if (-not $gatewayPassword) { throw 'GATEWAY_SSL_PASS must not be empty.' }

New-Item -ItemType Directory -Path $certDirectory -Force | Out-Null
$previousPassword = [Environment]::GetEnvironmentVariable('GATEWAY_SSL_PASS', 'Process')
[Environment]::SetEnvironmentVariable('GATEWAY_SSL_PASS', $gatewayPassword, 'Process')
try {
    & $KeytoolPath `
        -genkeypair `
        -noprompt `
        -alias wao-gateway `
        -keyalg RSA `
        -keysize 2048 `
        -validity 825 `
        -storetype PKCS12 `
        -keystore $certificatePath `
        -storepass:env GATEWAY_SSL_PASS `
        -keypass:env GATEWAY_SSL_PASS `
        -dname 'CN=wao-gateway, OU=WAO, O=Wayuwa, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed to generate the Gateway certificate.' }
}
finally {
    [Environment]::SetEnvironmentVariable('GATEWAY_SSL_PASS', $previousPassword, 'Process')
}

Write-Host "Generated $certificatePath"
