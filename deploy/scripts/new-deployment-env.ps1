param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^100\.')]
    [string]$MasterTailscaleIp,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^100\.')]
    [string]$SlaveTailscaleIp,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^100\.')]
    [string]$CloudTailscaleIp,

    [string]$OutputPath = (Join-Path (Split-Path -Parent $PSScriptRoot) '.env')
)

$ErrorActionPreference = 'Stop'

function New-RandomSecret {
    param([int]$ByteCount = 32)

    $bytes = New-Object byte[] $ByteCount
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $generator.GetBytes($bytes)
    }
    finally {
        $generator.Dispose()
    }
    [Convert]::ToBase64String($bytes)
}

$values = [ordered]@{
    NACOS_SERVER_ADDR            = "${MasterTailscaleIp}:8848"
    NACOS_USERNAME               = 'nacos'
    NACOS_PASSWORD               = (New-RandomSecret)
    NACOS_AUTH_TOKEN             = (New-RandomSecret -ByteCount 48)
    NACOS_AUTH_IDENTITY_KEY      = [Guid]::NewGuid().ToString('N')
    NACOS_AUTH_IDENTITY_VALUE    = [Guid]::NewGuid().ToString('N')
    DB_NAME                      = 'wao'
    DB_USER                      = 'wao_app'
    DB_PASS                      = (New-RandomSecret)
    DB_ROOT_PASS                 = (New-RandomSecret)
    MYSQL_REPLICATION_USER       = 'wao_repl'
    MYSQL_REPLICATION_PASSWORD   = (New-RandomSecret)
    REDIS_PASS                   = (New-RandomSecret)
    REDIS_MASTER_NAME            = 'wao-master'
    REDIS_MASTER_HOST            = $MasterTailscaleIp
    REDIS_SENTINEL_NODES         = "${MasterTailscaleIp}:26379,${SlaveTailscaleIp}:26379,${CloudTailscaleIp}:26379"
    MASTER_TAILSCALE_IP          = $MasterTailscaleIp
    SLAVE_TAILSCALE_IP           = $SlaveTailscaleIp
    CLOUD_TAILSCALE_IP           = $CloudTailscaleIp
    GATEWAY_SSL_ENABLED          = 'true'
    GATEWAY_SSL_PASS             = (New-RandomSecret)
    JWT_SECRET                   = (New-RandomSecret -ByteCount 48)
    WAO_IMAGE_TAG                = 'latest'
    ALERT_WEBHOOK_URL            = ''
}

$lines = $values.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }
$destination = [IO.Path]::GetFullPath($OutputPath)
$parent = Split-Path -Parent $destination
New-Item -ItemType Directory -Path $parent -Force | Out-Null
[IO.File]::WriteAllText(
    $destination,
    (($lines -join "`n") + "`n"),
    [Text.UTF8Encoding]::new($false)
)

Write-Host "Created private deployment configuration at $destination"
Write-Host 'Copy this same file to deploy/.env on all three nodes over an authenticated encrypted channel.'
