<#!
.SYNOPSIS
Benchmark RAG execution modes across varying client concurrency levels.

.DESCRIPTION
Iterates over modes (SEQUENTIAL, PARALLEL_V1_OLD, PARALLEL_V2_ASYNC) and client counts,
starts the backend with appropriate flags, runs benchmark_rag_parallel.ps1 to generate load,
and writes per-request latency rows to a consolidated CSV.

.EXAMPLE
pwsh ./tools/benchmark_rag_modes_concurrency.ps1 -ConcurrentClients 1,5,10,20
#>

param(
    [string[]]$Modes = @("SEQUENTIAL", "PARALLEL_V1_OLD", "PARALLEL_V2_ASYNC"),
    [int[]]$ConcurrentClients = @(1, 5, 10, 20),
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
    $OutputPath = Join-Path $projectRoot "rag_modes_concurrency_results.csv"
}
"mode,strategy,concurrency,run_id,latency_ms,question_id,timestamp,status" | Out-File -FilePath $OutputPath -Encoding utf8

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

function Resolve-ModeConfig {
    param([string]$ModeInput)
    switch ($ModeInput.ToUpperInvariant()) {
        "PARALLEL_V1_OLD" { return @{ exec="PARALLEL"; strategy="V1_OLD" } }
        "PARALLEL_V2_ASYNC" { return @{ exec="PARALLEL"; strategy="V2_ASYNC" } }
        default { return @{ exec="SEQUENTIAL"; strategy="V2_ASYNC" } }
    }
}

function Start-RagBackend {
    param([string]$ExecMode, [string]$Strategy)
    $args = @(
        "bootRun",
        "--console=plain",
        "--args=--spring.profiles.active=$Profile --rag.retrieval.executionMode=$ExecMode --sleepwell.rag.parallel-strategy=$Strategy"
    )
    $logPath = Join-Path $projectRoot "rag_modes_${ExecMode}_${Strategy}.log"
    $errPath = Join-Path $projectRoot "rag_modes_${ExecMode}_${Strategy}_err.log"
    return Start-Process -FilePath "./gradlew" -ArgumentList $args -WorkingDirectory $projectRoot -RedirectStandardOutput $logPath -RedirectStandardError $errPath -PassThru
}

function Stop-RagBackend { param($Process)
    if ($Process -and -not $Process.HasExited) {
        Stop-Process -Id $Process.Id -Force
        Wait-Process -Id $Process.Id -ErrorAction SilentlyContinue
    }
}

function Append-Requests {
    param([string]$RequestCsv, [string]$ModeLabel, [string]$StrategyLabel, [int]$Concurrency)
    $rows = Import-Csv -Path $RequestCsv
    foreach ($row in $rows) {
        $qId = if ($questionIds.ContainsKey($row.Query)) { $questionIds[$row.Query] } else { -1 }
        $line = "{0},{1},{2},{3},{4},{5},{6},{7}" -f `
            $ModeLabel,
            $StrategyLabel,
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
    foreach ($modeLabel in $Modes) {
        $config = Resolve-ModeConfig -ModeInput $modeLabel
        foreach ($cc in $ConcurrentClients) {
            Write-Host "[RUN] mode=$modeLabel concurrency=$cc"
            $reqCsv = Join-Path $projectRoot "rag_modes_${modeLabel}_c${cc}.csv"
            if (Test-Path -Path $reqCsv) { Remove-Item -Path $reqCsv -Force }
            $proc = Start-RagBackend -ExecMode $config.exec -Strategy $config.strategy
            $ready = Wait-ForHealth -Url $HealthUrl -TimeoutSeconds $HealthTimeoutSeconds
            if (-not $ready) {
                Write-Warning "Backend not ready for mode=$modeLabel"
                Stop-RagBackend -Process $proc
                continue
            }
            try {
                & $benchmarkScript -Endpoint "http://localhost:8080/api/v1/rag/query" -Mode $modeLabel -MaxConcurrency $cc -RunsPerQuery 10 -OutputPath $reqCsv
                Append-Requests -RequestCsv $reqCsv -ModeLabel $modeLabel -StrategyLabel $config.strategy -Concurrency $cc
            } finally { Stop-RagBackend -Process $proc }
        }
    }
} finally { Pop-Location }

Write-Host "Modes x concurrency benchmark finished. Output: $OutputPath"
