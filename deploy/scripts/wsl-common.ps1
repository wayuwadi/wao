function ConvertTo-WaoWslPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = [IO.Path]::GetFullPath($Path)
    if ($fullPath -notmatch '^([A-Za-z]):\\(.*)$') {
        throw "Only local Windows drive paths can be converted to WSL paths: $fullPath"
    }

    $drive = $Matches[1].ToLowerInvariant()
    $tail = $Matches[2] -replace '\\', '/'
    return "/mnt/$drive/$tail"
}

function Invoke-WaoWsl {
    param(
        [Parameter(Mandatory = $true)][string[]]$WslArguments,
        [string]$FailureMessage = 'WSL command failed.'
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5 wraps benign WSL host warnings written to stderr as
        # NativeCommandError records. Use the process exit code as the authority.
        $ErrorActionPreference = 'Continue'
        & wsl.exe @WslArguments
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "$FailureMessage Exit code: $exitCode"
    }
}

function Invoke-WaoWslCapture {
    param(
        [Parameter(Mandatory = $true)][string[]]$WslArguments,
        [string]$FailureMessage = 'WSL command failed.'
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& wsl.exe @WslArguments)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "$FailureMessage Exit code: $exitCode"
    }
    return $output
}
