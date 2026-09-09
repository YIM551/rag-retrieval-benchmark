<#!
.SYNOPSIS
Thread scaling benchmark for RAG V2_ASYNC parallel mode.

.DESCRIPTION
For each executor thread count and client concurrency, this script:
1. Starts the backend with the specified thread pool size.
2. Waits for /actuator/health.
3. Runs benchmark_rag_parallel.ps1 against three fixed questions (10 runs each).
4. Appends per-request results to a consolidated CSV that includes mode, strategy, threads,
   concurrency, run_id, latency_ms, and question_id.
5. Stops the backend and proceeds to the next configuration.

.EXAMPLE
pwsh ./tools/benchmark_rag_thread_scaling.ps1 -ThreadCounts 1,2,4,8,16 -ConcurrentClients 1,5
#>

param(
    [string]$Mode = "PARALLEL",
    [string]$Strategy = "V2_ASYNC",
    [int[]]$ThreadCounts = @(1, 2, 4, 8, 16),
    [int[]]$ConcurrentClients = @(1, 5),
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
    $OutputPath = Join-Path $projectRoot "rag_thread_scaling_results.csv"
}
"mode,strategy,threads,concurrency,run_id,latency_ms,question_id,timestamp,status" | Out-File -FilePath $OutputPath -Encoding utf8

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
    param([int]$Threads)
    $args = @(
        "bootRun",
        "--console=plain",
        "--args=--spring.profiles.active=$Profile --rag.retrieval.executionMode=$Mode --sleepwell.rag.parallel-strategy=$Strategy --rag.retrieval.executorThreads=$Threads"
    )
    $logPath = Join-Path $projectRoot "rag_thread_${Threads}.log"
    $errPath = Join-Path $projectRoot "rag_thread_${Threads}_err.log"
    return Start-Process -FilePath "./gradlew" -ArgumentList $args -WorkingDirectory $projectRoot -RedirectStandardOutput $logPath -RedirectStandardError $errPath -PassThru
}

function Stop-RagBackend {
    param($Process)
    if ($Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force
        Wait-Process -Id $Process.Id -ErrorAction SilentlyContinue
    }
}

function Append-Requests {
    param([string]$RequestCsv, [int]$Threads, [int]$Concurrency)
    $rows = Import-Csv -Path $RequestCsv
    foreach ($row in $rows) {
        $qId = if ($questionIds.ContainsKey($row.Query)) { $questionIds[$row.Query] } else { -1 }
        $line = "{0},{1},{2},{3},{4},{5},{6},{7},{8}" -f `
            $Mode.ToUpperInvariant(),
            $Strategy.ToUpperInvariant(),
            $Threads,
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
    foreach ($threads in $ThreadCounts) {
        foreach ($cc in $ConcurrentClients) {
            Write-Host "[RUN] threads=$threads concurrency=$cc"
            $reqCsv = Join-Path $projectRoot "rag_thread_requests_${threads}_c${cc}.csv"
            if (Test-Path -Path $reqCsv) { Remove-Item -Path $reqCsv -Force }
            $proc = Start-RagBackend -Threads $threads
            $ready = Wait-ForHealth -Url $HealthUrl -TimeoutSeconds $HealthTimeoutSeconds
            if (-not $ready) {
                Write-Warning "Backend not ready for threads=$threads"
                Stop-RagBackend -Process $proc
                continue
            }
            try {
                & $benchmarkScript -Endpoint "http://localhost:8080/api/v1/rag/query" -Mode $Mode -MaxConcurrency $cc -RunsPerQuery 10 -OutputPath $reqCsv
                Append-Requests -RequestCsv $reqCsv -Threads $threads -Concurrency $cc
            } finally {
                Stop-RagBackend -Process $proc
            }
        }
    }
} finally { Pop-Location }

Write-Host "Thread scaling benchmark finished. Output: $OutputPath"
