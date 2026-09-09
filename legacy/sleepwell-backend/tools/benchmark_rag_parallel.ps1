<#
.SYNOPSIS
Parallel RAG benchmark runner with warm-up and per-request latency logging.

.DESCRIPTION
Runs a configurable number of POST requests per query against the RAG endpoint using
parallel execution. Performs warm-up calls per query (not logged) before measuring and
writes per-request latency metrics to a CSV with Mode=PARALLEL and optional concurrency
metadata.

.EXAMPLE
pwsh ./tools/benchmark_rag_parallel.ps1 -Endpoint "http://localhost:8080/api/v1/rag/query" -RunsPerQuery 20 -MaxConcurrency 8

.NOTES
- Requires PowerShell 7+ for ForEach-Object -Parallel.
- Uses the same query set as the sequential benchmark.
- Warm-up requests per query are executed sequentially and excluded from CSV output.
#>

param(
    [string]$Endpoint = "http://localhost:8080/api/v1/rag/query",
    [Alias("Repeat")][int]$RunsPerQuery = 10,
    [int]$MaxConcurrency = 4,
    [int]$WarmupPerQuery = 2,
    [string]$Mode = "PARALLEL",
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"

# Shared test queries (UTF-8 safe)
$queries = @(
    "잠이 안 올 때 어떻게 해야 하나요?",
    "수면 패턴을 개선하는 방법은 무엇인가요?",
    "코골이를 줄이려면 어떻게 해야 하나요?"
)

if (-not $OutputPath) {
    $OutputPath = Join-Path -Path (Get-Location) -ChildPath "rag_benchmark_parallel.csv"
}

if (Test-Path -Path $OutputPath) {
    Remove-Item -Path $OutputPath -Force
}

# Initialize CSV with consistent headers
"Mode,Run,Query,LatencyMs,Timestamp,StatusCode,MaxConcurrency" | Out-File -FilePath $OutputPath -Encoding utf8

function Invoke-RagRequest {
    param(
        [string]$QueryText
    )

    $body = @{ query = $QueryText } | ConvertTo-Json -Depth 4
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Method Post -Uri $using:Endpoint -ContentType "application/json" -Body $body -UseBasicParsing
        $statusCode = [int]$response.StatusCode
    }
    catch {
        $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { 0 }
    }
    finally {
        $sw.Stop()
    }

    [PSCustomObject]@{
        Mode = $using:Mode.ToUpperInvariant()
        Run = 0 # replaced later
        Query = $QueryText
        LatencyMs = $sw.ElapsedMilliseconds
        Timestamp = [DateTime]::UtcNow.ToString("o")
        StatusCode = $statusCode
        MaxConcurrency = $using:MaxConcurrency
    }
}

foreach ($query in $queries) {
    Write-Host "Warming up for query: $query"
    for ($w = 1; $w -le $WarmupPerQuery; $w++) {
        Invoke-RagRequest -QueryText $query | Out-Null
    }

    Write-Host "Benchmarking query: $query with $RunsPerQuery runs at concurrency $MaxConcurrency"
    $runs = 1..$RunsPerQuery
    $results = $runs | ForEach-Object -Parallel {
        $result = Invoke-RagRequest -QueryText $using:query
        $result.Run = $_
        return $result
    } -ThrottleLimit $MaxConcurrency

    $results | ForEach-Object {
        "$($_.Mode),$($_.Run),`"$($_.Query)`",$($_.LatencyMs),$($_.Timestamp),$($_.StatusCode),$($_.MaxConcurrency)" |
            Add-Content -Path $OutputPath -Encoding utf8
    }
}

Write-Host "Parallel benchmark finished. Output: $OutputPath"
