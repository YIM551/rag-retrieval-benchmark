<#
.SYNOPSIS
Sequential RAG benchmark runner writing results with Mode=SEQUENTIAL.

.DESCRIPTION
Sends a configurable number of sequential POST requests per query to the RAG endpoint
and writes per-request latency metrics to a CSV with a fixed Mode column. Useful for
baseline latency measurements before comparing with parallel runs.

.EXAMPLE
pwsh ./tools/benchmark_rag_sequential.ps1 -Endpoint "http://localhost:8080/api/v1/rag/query" -RunsPerQuery 5

.NOTES
- Runs strictly sequentially (no parallelism) and records Mode=SEQUENTIAL.
- CSV output defaults to rag_benchmark_sequential.csv under the current directory.
- Queries are defined in the $queries array below for easy editing.
#>

param(
    [string]$Endpoint = "http://localhost:8080/api/v1/rag/query",
    [Alias("Repeat")][int]$RunsPerQuery = 10,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"

# Define the test queries (UTF-8 safe, editable)
$queries = @(
    "잠이 안 올 때 어떻게 해야 하나요?",
    "수면 패턴을 개선하는 방법은 무엇인가요?",
    "코골이를 줄이려면 어떻게 해야 하나요?"
)

if (-not $OutputPath) {
    $OutputPath = Join-Path -Path (Get-Location) -ChildPath "rag_benchmark_sequential.csv"
}

# Initialize CSV with consistent headers
"Mode,Run,Query,LatencyMs,Timestamp,StatusCode" | Out-File -FilePath $OutputPath -Encoding utf8

for ($run = 1; $run -le $RunsPerQuery; $run++) {
    foreach ($query in $queries) {
        $body = @{ query = $query } | ConvertTo-Json -Depth 4
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $response = Invoke-WebRequest -Method Post -Uri $Endpoint -ContentType "application/json" -Body $body
            $statusCode = [int]$response.StatusCode
        }
        catch {
            $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode.value__ } else { 0 }
        }
        finally {
            $sw.Stop()
        }

        $latency = $sw.ElapsedMilliseconds
        $timestamp = [DateTime]::UtcNow.ToString("o")
        "SEQUENTIAL,$run,`"$query`",$latency,$timestamp,$statusCode" | Add-Content -Path $OutputPath -Encoding utf8
    }
}

Write-Host "Benchmark finished. Output: $OutputPath"
