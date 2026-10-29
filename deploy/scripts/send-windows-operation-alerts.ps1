param([Parameter(Mandatory=$true)][string]$ConfigPath)
$ErrorActionPreference='Stop'
$config=Get-Content -LiteralPath $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
if (!$config.webhookCredentialPath) { return }
$directory=Split-Path -Parent ([IO.Path]::GetFullPath($ConfigPath))
$lock=$null
try {
    $lock=[IO.File]::Open((Join-Path $directory 'delivery.lock'),'OpenOrCreate','ReadWrite','None')
    Add-Type -AssemblyName System.Security
    $encrypted=[IO.File]::ReadAllBytes($config.webhookCredentialPath)
    $plain=[Security.Cryptography.ProtectedData]::Unprotect($encrypted,$null,[Security.Cryptography.DataProtectionScope]::LocalMachine)
    $url=[Text.Encoding]::UTF8.GetString($plain)
    $uri=[uri]$url
    if ($uri.Scheme -ne 'https' -or $uri.Host -notin @('open.feishu.cn','open.larksuite.com') -or $uri.AbsolutePath -notmatch '^/open-apis/bot/v2/hook/[A-Za-z0-9_-]+$' -or $uri.Query -or $uri.UserInfo) { throw 'Invalid group webhook destination' }
    foreach ($file in @(Get-ChildItem -LiteralPath $config.inbox -Filter '*.json' -File | Sort-Object LastWriteTimeUtc | Select-Object -First 10)) {
        if ($file.Length -gt 16384 -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { continue }
        $event=Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($event.status -notin @('FAILURE','RECOVERY','TEST')) { continue }
        if ($file.Name -ne ($event.id+'.json') -or $event.id -notmatch '^[0-9a-f-]{36}$') { continue }
        # Respect the current group policy. Failure notifications alone fail open if WAO is unavailable.
        $scene=if($event.status -eq 'FAILURE'){'failure'}else{'success'}
        $allowed=$event.status -eq 'FAILURE'
        try {
            $policy=Invoke-RestMethod -Uri ($config.policyBaseUrl.TrimEnd('/')+'/jenkins-'+$scene) -TimeoutSec 5
            if ($policy.code -eq 0 -and $policy.data.allowed -is [bool]) { $allowed=$policy.data.allowed }
        } catch { }
        if (!$allowed) { continue }
        $body=@{msg_type='text';content=@{text=($event.title+"`n"+$event.body+"`n事件时间："+$event.occurredAt+"`n事件编号："+$event.id)}} | ConvertTo-Json -Depth 4 -Compress
        try {
            $reply=Invoke-RestMethod -Method Post -Uri $url -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 15
            $accepted=($reply.PSObject.Properties.Name -contains 'code' -and $reply.code -eq 0) -or ($reply.PSObject.Properties.Name -contains 'StatusCode' -and $reply.StatusCode -eq 0)
            if (!$accepted) { throw 'Group did not acknowledge delivery' }
            $receiptPath=Join-Path $directory 'delivery-receipts.jsonl'
            if ((Test-Path $receiptPath) -and (Get-Item $receiptPath).Length -gt 1048576) {
                Move-Item -LiteralPath $receiptPath -Destination ($receiptPath+'.previous') -Force
            }
            @{id=$event.id;status=$event.status;acknowledgedAt=[DateTimeOffset]::UtcNow.ToString('o')} | ConvertTo-Json -Compress | Add-Content -LiteralPath $receiptPath -Encoding UTF8
            # Only acknowledged notifications leave the persistent outbox.
            Remove-Item -LiteralPath $file.FullName
        } catch { Write-Warning 'Group delivery failed; event retained for retry.'; break }
    }
} finally {
    $url=$null; $plain=$null; $encrypted=$null
    if($lock){$lock.Dispose()}
}
