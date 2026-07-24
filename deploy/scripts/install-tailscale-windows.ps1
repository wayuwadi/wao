param(
    [Parameter(Mandatory = $true)]
    [string]$MsiPath
)

$ErrorActionPreference = 'Stop'
$installer = (Resolve-Path -LiteralPath $MsiPath).Path
$signature = Get-AuthenticodeSignature -FilePath $installer

if ($signature.Status -ne [Management.Automation.SignatureStatus]::Valid) {
    throw "Tailscale installer signature is not valid: $($signature.Status)"
}

if ($signature.SignerCertificate.Subject -notmatch 'Tailscale') {
    throw "Unexpected installer signer: $($signature.SignerCertificate.Subject)"
}

$process = Start-Process msiexec.exe `
    -ArgumentList @('/i', $installer, '/qn', '/norestart') `
    -WindowStyle Hidden `
    -Wait `
    -PassThru

if ($process.ExitCode -notin @(0, 3010)) {
    throw "Tailscale installation failed with exit code $($process.ExitCode)."
}

$tailscale = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
if (-not (Test-Path -LiteralPath $tailscale -PathType Leaf)) {
    throw "Tailscale CLI was not found: $tailscale"
}

Write-Host 'Tailscale is installed. Authenticate this node with:'
Write-Host "  &$tailscale up"

if ($process.ExitCode -eq 3010) {
    Write-Warning 'The installer requested a Windows restart.'
}
