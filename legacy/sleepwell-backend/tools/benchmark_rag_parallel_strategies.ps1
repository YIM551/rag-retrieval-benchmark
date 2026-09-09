#
# Benchmark three RAG retrieval strategies (SEQUENTIAL, V1_OLD, ASYNC) in one run.
# For each strategy, the backend is started with the appropriate environment variables,
# warmed up, benchmarked, and stopped. Logs are written per strategy and timing metrics
# are extracted into CSV files for later analysis.

param(
    [string]$Profile = "local,rag",
    [string]$HealthUrl = "http://localhost:8080/actuator/health",
    [int]$HealthTimeoutSeconds = 120,
    [int]$RunsPerQuery = 10,
    [int]$WarmupPerQuery = 2,
    [int]$ParallelConcurrency = 4,
    [string]$LogDir = "./logs"
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $PSCommandPath
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
$benchmarkScript = Join-Path $scriptDir "benchmark_rag_parallel.ps1"
$runScript = Join-Path $scriptDir "run_rag_with_mode.ps1"
$extractScript = Join-Path $scriptDir "extract_rag_timing.ps1"
$ragEndpoint = "http://localhost:8080/api/v1/rag/query"

$strategies = @(
    @{ ExecMode = "SEQUENTIAL"; Strategy = "SEQUENTIAL"; Name = "sequential"; Concurrency = 1 },
    @{ ExecMode = "PARALLEL";  Strategy = "V1_OLD";     Name = "v1_old";    Concurrency = $ParallelConcurrency },
    @{ ExecMode = "PARALLEL";  Strategy = "ASYNC";      Name = "async";     Concurrency = $ParallelConcurrency }
)

function Wait-ForHealth {
    param(
        [string]$Url,
        [int]$TimeoutSeconds = 60,
        [int]$RetryDelaySeconds = 3
    )

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200 -and $response.Content -match '"status"\s*:\s*"UP"') {
                return $true
            }
        }
        catch {
            # Ignore and retry
        }
        Start-Sleep -Seconds $RetryDelaySeconds
    }
    return $false
}

if (-not (Test-Path -Path $LogDir)) {
    New-Item -ItemType Directory -Path $LogDir | Out-Null
}

Push-Location $projectRoot
try {
    foreach ($entry in $strategies) {
        $name = $entry.Name
        $execMode = $entry.ExecMode
        $strategy = $entry.Strategy
        $logPath = Join-Path $LogDir "rag_${name}.log"
        $errPath = Join-Path $LogDir "rag_${name}_err.log"
        $requestCsv = Join-Path $LogDir "rag_requests_${name}.csv"
        $timingCsv = Join-Path $LogDir "rag_timing_${name}.csv"

        foreach ($path in @($logPath, $errPath, $requestCsv, $timingCsv)) {
            if (Test-Path -Path $path) { Remove-Item -Path $path -Force }
        }

        Write-Host "Starting backend for mode=$execMode strategy=$strategy" -ForegroundColor Cyan
        $process = & $runScript -ExecMode $execMode -Strategy $strategy -Profile $Profile -LogPath $logPath -ErrorLogPath $errPath

        $ready = Wait-ForHealth -Url $HealthUrl -TimeoutSeconds $HealthTimeoutSeconds
        if (-not $ready) {
            Write-Warning "Backend failed to become ready for strategy=$strategy"
            if ($process -and -not $process.HasExited) {
                Stop-Process -Id $process.Id -Force
                Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
            }
            continue
        }

        try {
            & $benchmarkScript -Endpoint $ragEndpoint -Mode $execMode -OutputPath $requestCsv -MaxConcurrency $entry.Concurrency -RunsPerQuery $RunsPerQuery -WarmupPerQuery $WarmupPerQuery
        }
        catch {
            Write-Warning "Benchmark failed for strategy=$strategy: $($_.Exception.Message)"
        }
        finally {
            if ($process -and -not $process.HasExited) {
                Stop-Process -Id $process.Id -Force
                Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
            }
        }

        try {
            & $extractScript -LogPath $logPath -OutputPath $timingCsv
        }
        catch {
            Write-Warning "Failed to extract timing metrics for strategy=$strategy: $($_.Exception.Message)"
        }
    }
}
finally {
    Pop-Location
}
