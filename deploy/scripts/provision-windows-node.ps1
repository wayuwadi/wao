param(
    [string]$Distribution = 'Ubuntu',
    [string]$InstallLocation = 'D:\WSL\WAO-Ubuntu',
    [string]$DeployPublicKeyPath = '',
    [switch]$DisableSshPasswordAuthentication,
    [string]$DeploymentRoot = 'D:\deploy\wao'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\wsl-common.ps1"

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell session.'
}

$windows = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion'
if ([int]$windows.CurrentBuild -lt 22000 -or $windows.EditionID -ne 'Professional') {
    throw "This node requires Windows 11 Pro (detected edition=$($windows.EditionID), build=$($windows.CurrentBuild))."
}

function Install-WaoOpenSshServer {
    param(
        [string]$PublicKeyPath,
        [switch]$DisablePasswordAuthentication
    )

    $serverCapability = Get-WindowsCapability -Online |
        Where-Object { $_.Name -like 'OpenSSH.Server*' } |
        Select-Object -First 1
    if ($null -eq $serverCapability) {
        throw 'The Windows OpenSSH Server capability is unavailable.'
    }
    if ($serverCapability.State -ne 'Installed') {
        Add-WindowsCapability -Online -Name $serverCapability.Name | Out-Null
    }

    Set-Service -Name sshd -StartupType Automatic
    Start-Service -Name sshd

    $sshFirewallName = 'WAO OpenSSH deployment access'
    if (-not (Get-NetFirewallRule -DisplayName $sshFirewallName -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule `
            -DisplayName $sshFirewallName `
            -Direction Inbound `
            -Action Allow `
            -Protocol TCP `
            -LocalPort 22 | Out-Null
    }

    if ($PublicKeyPath) {
        $resolvedPublicKey = (Resolve-Path -LiteralPath $PublicKeyPath -ErrorAction Stop).Path
        $publicKey = (Get-Content -LiteralPath $resolvedPublicKey -Raw).Trim()
        if ($publicKey -notmatch '^(ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|ssh-rsa)\s+\S+') {
            throw "DeployPublicKeyPath does not contain a supported OpenSSH public key: $resolvedPublicKey"
        }

        $authorizedKeys = Join-Path $env:ProgramData 'ssh\administrators_authorized_keys'
        $existingKeys = if (Test-Path -LiteralPath $authorizedKeys) {
            @(Get-Content -LiteralPath $authorizedKeys)
        } else {
            @()
        }
        if ($existingKeys -notcontains $publicKey) {
            $updatedKeys = @($existingKeys | Where-Object { $_.Trim() }) + $publicKey
            [IO.File]::WriteAllText(
                $authorizedKeys,
                (($updatedKeys -join "`n") + "`n"),
                [Text.UTF8Encoding]::new($false)
            )
        }
        & icacls.exe $authorizedKeys /inheritance:r /grant '*S-1-5-32-544:F' /grant 'SYSTEM:F' | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to secure administrators_authorized_keys ACLs.' }
    }

    if ($DisablePasswordAuthentication) {
        if (-not $PublicKeyPath) {
            throw 'Pass DeployPublicKeyPath when disabling SSH password authentication.'
        }
        $sshdConfig = Join-Path $env:ProgramData 'ssh\sshd_config'
        $backupPath = "$sshdConfig.wao-before-hardening"
        if (-not (Test-Path -LiteralPath $backupPath)) {
            Copy-Item -LiteralPath $sshdConfig -Destination $backupPath
        }
        $config = Get-Content -LiteralPath $sshdConfig -Raw
        foreach ($setting in @(
            @{ Name = 'PubkeyAuthentication'; Value = 'yes' },
            @{ Name = 'PasswordAuthentication'; Value = 'no' }
        )) {
            $pattern = "(?m)^\s*#?\s*$($setting.Name)\s+.*$"
            $replacement = "$($setting.Name) $($setting.Value)"
            if ($config -match $pattern) {
                $config = [regex]::Replace($config, $pattern, $replacement)
            } else {
                $config = $config.TrimEnd() + "`r`n$replacement`r`n"
            }
        }
        [IO.File]::WriteAllText($sshdConfig, $config, [Text.UTF8Encoding]::new($false))
        & (Join-Path $env:WINDIR 'System32\OpenSSH\sshd.exe') -t
        if ($LASTEXITCODE -ne 0) {
            Copy-Item -LiteralPath $backupPath -Destination $sshdConfig -Force
            throw 'The hardened sshd_config is invalid; the original configuration was restored.'
        }
        Restart-Service -Name sshd
    }
}

$restartRequired = $false
foreach ($featureName in @('Microsoft-Windows-Subsystem-Linux', 'VirtualMachinePlatform')) {
    $feature = Get-WindowsOptionalFeature -Online -FeatureName $featureName
    if ($feature.State -ne 'Enabled') {
        Enable-WindowsOptionalFeature -Online -FeatureName $featureName -All -NoRestart | Out-Null
        $restartRequired = $true
    }
}

if ($restartRequired) {
    Write-Warning 'WSL features were enabled. Restart Windows, then run this script again.'
    exit 3010
}

$wslConfigPath = Join-Path $env:USERPROFILE '.wslconfig'
if (-not (Test-Path -LiteralPath $wslConfigPath)) {
    $wslConfig = @'
[wsl2]
memory=8GB
processors=4
swap=2GB
vmIdleTimeout=-1

[experimental]
autoMemoryReclaim=gradual
'@
    [IO.File]::WriteAllText(
        $wslConfigPath,
        ($wslConfig.Replace("`r`n", "`n") + "`n"),
        [Text.UTF8Encoding]::new($false)
    )
}

& cmd.exe /d /c 'wsl.exe --version >nul 2>&1'
if ($LASTEXITCODE -ne 0) {
    & winget.exe install --id 9P9TQF7MRM4R --exact --source msstore `
        --accept-package-agreements --accept-source-agreements --silent --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw 'WSL runtime installation failed.' }
}

$installed = @(& wsl.exe --list --quiet) -replace "`0", '' | Where-Object { $_.Trim() -eq $Distribution }
if (-not $installed) {
    New-Item -ItemType Directory -Path $InstallLocation -Force | Out-Null
    & wsl.exe --install --distribution $Distribution --no-launch --web-download --location $InstallLocation
    if ($LASTEXITCODE -ne 0 -and $Distribution -eq 'Ubuntu') {
        Write-Warning 'The WSL distribution catalog is unavailable; importing the official Ubuntu 24.04 WSL rootfs.'
        $rootfsPath = Join-Path $env:TEMP 'wao-ubuntu-24.04-wsl.rootfs.tar.gz'
        try {
            & curl.exe `
                --location `
                --fail `
                --retry 3 `
                --silent `
                --show-error `
                --output $rootfsPath `
                'https://cloud-images.ubuntu.com/wsl/releases/noble/current/ubuntu-noble-wsl-amd64-wsl.rootfs.tar.gz'
            if ($LASTEXITCODE -ne 0) { throw 'Ubuntu 24.04 WSL rootfs download failed.' }
            & wsl.exe --import $Distribution $InstallLocation $rootfsPath --version 2
        } finally {
            Remove-Item -LiteralPath $rootfsPath -Force -ErrorAction SilentlyContinue
        }
    }
    if ($LASTEXITCODE -ne 0) { throw 'WSL distribution installation failed.' }
}

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LinuxProjectRoot = ConvertTo-WaoWslPath $ProjectRoot
Invoke-WaoWsl `
    -WslArguments @('-d', $Distribution, '-u', 'root', '--', 'bash', '-lc', "printf '[boot]\nsystemd=true\n' >/etc/wsl.conf") `
    -FailureMessage 'Failed to enable systemd in Ubuntu.'
Invoke-WaoWsl -WslArguments @('--shutdown') -FailureMessage 'Failed to stop WSL before enabling systemd.'
Start-Sleep -Seconds 3
Invoke-WaoWsl `
    -WslArguments @('-d', $Distribution, '-u', 'root', '--', 'bash', '-lc', "cd '$LinuxProjectRoot' && sh deploy/scripts/install-docker-engine.sh") `
    -FailureMessage 'Docker Engine installation failed.'

Install-WaoOpenSshServer `
    -PublicKeyPath $DeployPublicKeyPath `
    -DisablePasswordAuthentication:$DisableSshPasswordAuthentication

& "$PSScriptRoot\configure-windows-node-availability.ps1"

& "$PSScriptRoot\install-windows-ops-tasks.ps1" `
    -Distribution $Distribution `
    -ProjectRoot $DeploymentRoot

Write-Host 'Windows 11, WSL2, Docker Engine, OpenSSH and WAO operations tasks are ready.'
if (-not $DisableSshPasswordAuthentication) {
    Write-Warning 'SSH password authentication remains enabled. Verify the deployment key remotely, then rerun with -DeployPublicKeyPath and -DisableSshPasswordAuthentication.'
}
