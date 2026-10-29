$ErrorActionPreference='Stop'
if ($env:OS -ne 'Windows_NT') { Write-Host 'DPAPI delivery test requires Windows.'; return }
Add-Type -AssemblyName System.Security
$root=Join-Path ([IO.Path]::GetTempPath()) ('wao-delivery-test-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory "$root/inbox" -Force | Out-Null
$scriptPath=Join-Path $PSScriptRoot '../../deploy/scripts/send-windows-operation-alerts.ps1'
[IO.File]::WriteAllBytes("$root/hook.dpapi",[Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes('https://open.feishu.cn/open-apis/bot/v2/hook/synthetic-test'),$null,[Security.Cryptography.DataProtectionScope]::LocalMachine))
@{inbox="$root/inbox";webhookCredentialPath="$root/hook.dpapi";policyBaseUrl='https://example.invalid/policy'}|ConvertTo-Json|Set-Content "$root/config.json"
$global:waoDeliveryAllowed=$false
$global:waoDeliveryUnavailable=$false
$global:waoDeliveryResponse=1
$global:waoDeliveryCalls=0
function Invoke-RestMethod {
    param($Uri,$Method,$Body,$ContentType,$TimeoutSec)
    if($Method -eq 'Post') { $global:waoDeliveryCalls++; return [pscustomobject]@{code=$global:waoDeliveryResponse} }
    if($global:waoDeliveryUnavailable){throw 'Synthetic policy outage'}
    return [pscustomobject]@{code=0;data=[pscustomobject]@{allowed=$global:waoDeliveryAllowed}}
}
function Write-Event($Status) {
    $id=[guid]::NewGuid().ToString()
    $path="$root/inbox/$id.json"
    @{id=$id;status=$Status;title='Synthetic test';body='Test';occurredAt=[DateTimeOffset]::UtcNow.ToString('o')}|ConvertTo-Json|Set-Content $path
    return $path
}
$file=Write-Event 'FAILURE'
& $scriptPath -ConfigPath "$root/config.json"
if(!(Test-Path $file) -or $global:waoDeliveryCalls -ne 0){throw 'Disabled policy sent a message'}
$global:waoDeliveryAllowed=$true
& $scriptPath -ConfigPath "$root/config.json"
if(!(Test-Path $file)){throw 'Unacknowledged event was lost'}
$global:waoDeliveryResponse=0
& $scriptPath -ConfigPath "$root/config.json"
if(Test-Path $file){throw 'Acknowledged event remains'}
$global:waoDeliveryUnavailable=$true
$failure=Write-Event 'FAILURE'
$recovery=Write-Event 'RECOVERY'
& $scriptPath -ConfigPath "$root/config.json"
if((Test-Path $failure) -or !(Test-Path $recovery)){throw 'Incorrect unavailable-policy fallback'}
Write-Host 'Windows group delivery tests passed.'
if ((Split-Path $root -Leaf) -like 'wao-delivery-test-*' -and (Split-Path $root -Parent) -eq ([IO.Path]::GetTempPath()).TrimEnd('\','/')) {
    Remove-Item -LiteralPath $root -Recurse -Force
}
