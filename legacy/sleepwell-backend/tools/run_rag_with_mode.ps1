param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("SEQUENTIAL", "PARALLEL_OLD", "PARALLEL_SYNC")]
    [string]$Mode,
    [string]$Profile = "rag",
    [string]$LogPath,
    [string]$ErrorLogPath
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $PSCommandPath
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")

Write-Host "Starting RAG backend with RAG_EXECUTION_MODE=$Mode"

$env:RAG_EXECUTION_MODE = $Mode

$arguments = @(
    "bootRun",
    "--console=plain",
    "--args=--spring.profiles.active=$Profile"
)

if ($LogPath) {
    $logDir = Split-Path -Parent $LogPath
    if (-not [string]::IsNullOrWhiteSpace($logDir) -and -not (Test-Path -Path $logDir)) {
        New-Item -ItemType Directory -Path $logDir | Out-Null
    }
}

if ($ErrorLogPath) {
    $errDir = Split-Path -Parent $ErrorLogPath
    if (-not [string]::IsNullOrWhiteSpace($errDir) -and -not (Test-Path -Path $errDir)) {
        New-Item -ItemType Directory -Path $errDir | Out-Null
    }
}

$startInfo = @{
    FilePath = "./gradlew"
    ArgumentList = $arguments
    WorkingDirectory = $projectRoot
    PassThru = $true
}

if ($LogPath) {
    $startInfo.RedirectStandardOutput = $LogPath
}

if ($ErrorLogPath) {
    $startInfo.RedirectStandardError = $ErrorLogPath
}

Start-Process @startInfo
