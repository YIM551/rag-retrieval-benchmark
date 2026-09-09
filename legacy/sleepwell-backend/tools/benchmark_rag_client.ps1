<#!
.SYNOPSIS
Client-side RAG benchmark helper that labels runs without changing backend mode.

.DESCRIPTION
Sends sequential POST requests to the RAG endpoint and records latency metrics. The
ModeLabel parameter is used only for client-side reporting; it does NOT configure the
backend. To change backend execution mode, start the server via run_rag_with_mode.ps1.

.EXAMPLE
pwsh ./tools/benchmark_rag_client.ps1 -Endpoint "http://localhost:8080/api/v1/rag/query" -Runs 20 -ModeLabel "SEQUENTIAL"

.EXAMPLE
# Backend started with run_rag_with_mode.ps1 -Mode PARALLEL_OLD
pwsh ./tools/benchmark_rag_client.ps1 -Endpoint "http://localhost:8080/api/v1/rag/query" -Runs 30 -ModeLabel "PARALLEL_OLD" -ExpectedServerMode "PARALLEL_OLD"
#>

param(
    [string]$Endpoint = "http://localhost:8080/api/v1/rag/query",
    [int]$Runs = 10,
    [string]$ModeLabel = "SEQUENTIAL",
    [ValidateSet("SEQUENTIAL", "PARALLEL_OLD", "PARALLEL_SYNC")]
    [string]$ExpectedServerMode,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"

# Note: ModeLabel is purely client-side and does NOT change the backend execution mode.
if ($ExpectedServerMode -and $ExpectedServerMode.ToUpperInvariant() -ne $ModeLabel.ToUpperInvariant()) {
    Write-Warning "ModeLabel ($ModeLabel) differs from ExpectedServerMode ($ExpectedServerMode). Ensure the backend was started with the desired RAG_EXECUTION_MODE."
}

$queries = @(
    "잠이 안 올 때 어떻게 해야 하나요?",
    "수면 패턴을 개선하는 방법은 무엇인가요?",
    "코골이를 줄이려면 어떻게 해야 하나요?"
)

if (-not $OutputPath) {
    $OutputPath = Join-Path -Path (Get-Location) -ChildPath "rag_benchmark_client.csv"
}

if (Test-Path -Path $OutputPath) {
    Remove-Item -Path $OutputPath -Force
}

"ModeLabel,Run,Query,LatencyMs,Timestamp,StatusCode" | Out-File -FilePath $OutputPath -Encoding utf8

function Invoke-RagRequest {
    param([string]$QueryText)

    $body = @{ query = $QueryText } | ConvertTo-Json -Depth 4
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-WebRequest -Method Post -Uri $Endpoint -ContentType "application/json" -Body $body -UseBasicParsing
        $statusCode = [int]$response.StatusCode
    }
    catch {
        $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { 0 }
    }
    finally {
        $sw.Stop()
    }

    [PSCustomObject]@{
        ModeLabel = $ModeLabel.ToUpperInvariant()
        Query = $QueryText
        LatencyMs = $sw.ElapsedMilliseconds
        Timestamp = [DateTime]::UtcNow.ToString("o")
        StatusCode = $statusCode
    }
}

for ($run = 1; $run -le $Runs; $run++) {
    $query = $queries[($run - 1) % $queries.Length]
    Write-Host "[$run/$Runs] ModeLabel=$ModeLabel Query=$query"
    $result = Invoke-RagRequest -QueryText $query
    "$($result.ModeLabel),$run,`"$($result.Query)`",$($result.LatencyMs),$($result.Timestamp),$($result.StatusCode)" |
        Add-Content -Path $OutputPath -Encoding utf8
}

Write-Host "Benchmark complete. Output: $OutputPath"
