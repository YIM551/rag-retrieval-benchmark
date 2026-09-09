<#!
.SYNOPSIS
Benchmark COARSE vs FINE parallel granularity within V2_ASYNC RAG.

.DESCRIPTION
Sets RAG_PARALLEL_GRANULARITY for each run, starts the backend, runs the parallel
benchmark with configurable concurrency, and appends per-request latencies to CSV.

.EXAMPLE
pwsh ./tools/benchmark_rag_granularity.ps1 -Granularities COARSE,FINE -ConcurrentClients 1,10
#>

param(
    [string[]]$Granularities = @("COARSE", "FINE"),
    [int[]]$ConcurrentClients = @(1, 10),
    [string]$Mode = "PARALLEL",
    [string]$Strategy = "V2_ASYNC",
    [string]$Profile = "local,rag",
    [string]$HealthUrl = "http://localhost:8080/actuator/health",
    [int]$HealthTimeoutSeconds = 120,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $PSCommandPath
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
$benchmarkScript = Join-Path $scriptDir "benchmark_rag_parallel.ps1"

$queries = @(
    "잠이 안 올 때 어떻게 해야 하나요?",
    "수면 패턴을 개선하는 방법은 무엇인가요?",
    "코골이를 줄이려면 어떻게 해야 하나요?"
)
$questionIds = @{}
for ($i = 0; $i -lt $queries.Length; $i++) { $questionIds[$queries[$i]] = $i }

if (-not $OutputPath) {
    $OutputPath = Join-Path $projectRoot "rag_granularity_results.csv"
}
"granularity,mode,strategy,concurrency,run_id,latency_ms,question_id,timestamp,status" | Out-File -FilePath $OutputPath -Encoding utf8

function Wait-ForHealth {
    param([string]$Url, [int]$TimeoutSeconds = 60, [int]$RetryDelaySeconds = 3)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        try {
            $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($resp.StatusCode -eq 200 -and $resp.Content -match '"status"\s*:\s*"UP"') { return $true }
        } catch { }
        Start-Sleep -Seconds $RetryDelaySeconds
    }
    return $false
}

function Start-RagBackend {
    param([string]$Granularity)
    $env:RAG_PARALLEL_GRANULARITY = $Granularity
    $args = @(
        "bootRun",
        "--console=plain",
        "--args=--spring.profiles.active=$Profile --rag.retrieval.executionMode=$Mode --sleepwell.rag.parallel-strategy=$Strategy --rag.parallel.granularity=$Granularity"
    )
    $logPath = Join-Path $projectRoot "rag_granularity_${Granularity}.log"
    $errPath = Join-Path $projectRoot "rag_granularity_${Granularity}_err.log"
    return Start-Process -FilePath "./gradlew" -ArgumentList $args -WorkingDirectory $projectRoot -RedirectStandardOutput $logPath -RedirectStandardError $errPath -PassThru
}

function Stop-RagBackend { param($Process)
    if ($Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force
        Wait-Process -Id $Process.Id -ErrorAction SilentlyContinue
    }
}

function Append-Requests {
    param([string]$RequestCsv, [string]$Granularity, [int]$Concurrency)
    $rows = Import-Csv -Path $RequestCsv
    foreach ($row in $rows) {
        $qId = if ($questionIds.ContainsKey($row.Query)) { $questionIds[$row.Query] } else { -1 }
        $line = "{0},{1},{2},{3},{4},{5},{6},{7},{8}" -f `
            $Granularity.ToUpperInvariant(),
            $Mode.ToUpperInvariant(),
            $Strategy.ToUpperInvariant(),
            $Concurrency,
            $row.Run,
            $row.LatencyMs,
            $qId,
            $row.Timestamp,
            $row.StatusCode
        Add-Content -Path $OutputPath -Value $line -Encoding utf8
    }
}

Push-Location $projectRoot
try {
    foreach ($granularity in $Granularities) {
        foreach ($cc in $ConcurrentClients) {
            Write-Host "[RUN] granularity=$granularity concurrency=$cc"
            $reqCsv = Join-Path $projectRoot "rag_granularity_${granularity}_c${cc}.csv"
            if (Test-Path -Path $reqCsv) { Remove-Item -Path $reqCsv -Force }
            $proc = Start-RagBackend -Granularity $granularity
            $ready = Wait-ForHealth -Url $HealthUrl -TimeoutSeconds $HealthTimeoutSeconds
            if (-not $ready) {
                Write-Warning "Backend not ready for granularity=$granularity"
                Stop-RagBackend -Process $proc
                continue
            }
            try {
                & $benchmarkScript -Endpoint "http://localhost:8080/api/v1/rag/query" -Mode $Mode -MaxConcurrency $cc -RunsPerQuery 10 -OutputPath $reqCsv
                Append-Requests -RequestCsv $reqCsv -Granularity $granularity -Concurrency $cc
            } finally { Stop-RagBackend -Process $proc }
        }
    }
} finally { Pop-Location }

Write-Host "Granularity benchmark finished. Output: $OutputPath"
