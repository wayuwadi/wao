[CmdletBinding()]
param(
    [string]$Distribution = 'Ubuntu',
    [string]$ProjectRoot = 'D:\deploy\wao'
)

$ErrorActionPreference = 'Stop'

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell session.'
}

$resolvedProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
$powerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$taskPrincipal = New-ScheduledTaskPrincipal `
    -UserId $identity.Name `
    -LogonType S4U `
    -RunLevel Highest
$taskSettings = New-ScheduledTaskSettingsSet `
    -StartWhenAvailable `
    -MultipleInstances IgnoreNew `
    -RestartCount 12 `
    -RestartInterval (New-TimeSpan -Minutes 1) `
    -ExecutionTimeLimit (New-TimeSpan -Hours 4)

function Register-WaoTask {
    param(
        [Parameter(Mandatory = $true)][string]$TaskName,
        [Parameter(Mandatory = $true)]$Action,
        [Parameter(Mandatory = $true)]$Trigger,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Register-ScheduledTask `
        -TaskName $TaskName `
        -Action $Action `
        -Trigger $Trigger `
        -Principal $taskPrincipal `
        -Settings $taskSettings `
        -Description $Description `
        -Force | Out-Null
}

& "$PSScriptRoot\install-wsl-keepalive.ps1" -Distribution $Distribution

$startupTrigger = New-ScheduledTaskTrigger -AtStartup
$startupTrigger.Delay = 'PT1M'
$portProxyScript = Join-Path $resolvedProjectRoot 'deploy\scripts\refresh-wsl-portproxy.ps1'
$portProxyAction = New-ScheduledTaskAction `
    -Execute $powerShell `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$portProxyScript`" -Distribution `"$Distribution`""
Register-WaoTask `
    -TaskName 'WAO WSL Portproxy Refresh' `
    -Action $portProxyAction `
    -Trigger $startupTrigger `
    -Description 'Refresh WAO Windows-to-WSL port forwarding after every boot.'

$refreshTrigger = New-ScheduledTaskTrigger `
    -Once `
    -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Minutes 5) `
    -RepetitionDuration (New-TimeSpan -Days 3650)
$refreshScript = Join-Path $resolvedProjectRoot 'deploy\scripts\refresh-public-ip.ps1'
$refreshAction = New-ScheduledTaskAction `
    -Execute $powerShell `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$refreshScript`" -Distribution `"$Distribution`""
Register-WaoTask `
    -TaskName 'WAO Public IP Refresh' `
    -Action $refreshAction `
    -Trigger $refreshTrigger `
    -Description 'Refresh the slave public IP and Nacos registration every five minutes.'

$backupTrigger = New-ScheduledTaskTrigger -Daily -At '02:00'
$backupScript = Join-Path $resolvedProjectRoot 'deploy\scripts\backup-mysql.ps1'
$backupAction = New-ScheduledTaskAction `
    -Execute $powerShell `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$backupScript`" -Distribution `"$Distribution`""
Register-WaoTask `
    -TaskName 'WAO MySQL Replica Backup' `
    -Action $backupAction `
    -Trigger $backupTrigger `
    -Description 'Create the daily WAO MySQL replica backup.'

$restoreTrigger = New-ScheduledTaskTrigger -Daily -At '03:00'
$restoreScript = Join-Path $resolvedProjectRoot 'deploy\scripts\restore-drill.ps1'
$restoreAction = New-ScheduledTaskAction `
    -Execute $powerShell `
    -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$restoreScript`" -Distribution `"$Distribution`" -OnlyOnDayOfMonth 1"
Register-WaoTask `
    -TaskName 'WAO MySQL Monthly Restore Drill' `
    -Action $restoreAction `
    -Trigger $restoreTrigger `
    -Description 'Run the WAO MySQL restore drill on the first day of every month.'

Write-Host "WAO operations tasks now target $resolvedProjectRoot and WSL distribution $Distribution."
