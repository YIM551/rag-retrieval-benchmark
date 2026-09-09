#!/usr/bin/env pwsh
<#!
Mode x Concurrency Sweep Script
PowerShell 7 script to benchmark RAG backend across execution modes and concurrency levels.
!>

#region Helper Functions
function Get-GradleCommand {
    if (Test-Path "./gradlew.bat") { return "./gradlew.bat" }
    elseif (Test-Path "./gradlew") { return "./gradlew" }
    else { throw "gradlew executable not found." }
}

$script:BackendProcess = $null
$script:RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $script:RepoRoot

function Stop-RagBackend {
    if ($script:BackendProcess -and -not $script:BackendProcess.HasExited) {
        try { Stop-Process -Id $script:BackendProcess.Id -Force -ErrorAction Stop; Start-Sleep -Seconds 2 } catch { Write-Warning "Failed to stop backend process: $_" }
    }
    $script:BackendProcess = $null
}

function Start-RagBackend {
    param(
        [string]$ExecutionMode,
        [string]$Granularity,
        [int]$Threads
    )
    Stop-RagBackend
    $env:RAG_RETRIEVAL_EXECUTION_MODE = $ExecutionMode
    $env:RAG_PARALLEL_GRANULARITY = $Granularity
    $env:RAG_RETRIEVAL_EXECUTOR_THREADS = $Threads

    $gradle = Get-GradleCommand
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $gradle
    $psi.Arguments = "bootRun"
    $psi.WorkingDirectory = $script:RepoRoot
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $script:BackendProcess = [System.Diagnostics.Process]::Start($psi)
    Write-Host "Started backend (PID=$($script:BackendProcess.Id)) with mode=$ExecutionMode, granularity=$Granularity, threads=$Threads"
}

function Wait-ForHealth {
    param(
        [int]$TimeoutSec = 60,
        [int]$IntervalSec = 1,
        [string]$HealthUrl = "http://localhost:8080/actuator/health"
    )
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSec) {
        try {
            $resp = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 5
            if ($resp.Content -match '"status"\s*:\s*"UP"') { return $true }
        } catch { }
        Start-Sleep -Seconds $IntervalSec
    }
    return $false
}

function Invoke-RagRequest {
    param(
        [string]$Uri,
        [hashtable]$Body,
        [int]$RepeatCount = 1,
        [int]$Concurrency = 1,
        [string]$Mode,
        [int]$Threads,
        [string]$Granularity
    )
    $jobs = @()
    for ($i = 0; $i -lt $RepeatCount; $i += $Concurrency) {
        $batch = [Math]::Min($Concurrency, $RepeatCount - $i)
        for ($j = 0; $j -lt $batch; $j++) {
            $jobs += Start-ThreadJob -ScriptBlock {
                param($u,$payload,$mode,$threads,$gran,$batchSize)
                $sw = [System.Diagnostics.Stopwatch]::StartNew()
                try {
                    Invoke-WebRequest -Method Post -Uri $u -ContentType "application/json" -Body ($payload | ConvertTo-Json -Depth 5) -UseBasicParsing | Out-Null
                    $latency = $sw.Elapsed.TotalMilliseconds
                } catch { $latency = [double]::NaN }
                [pscustomobject]@{
                    timestamp  = (Get-Date).ToString("o")
                    mode       = $mode
                    granularity= $gran
                    threads    = $threads
                    concurrency= $batchSize
                    latency_ms = [math]::Round($latency,2)
                }
            } -ArgumentList $Uri, $Body, $Mode, $Threads, $Granularity, $batch
        }
        if ($jobs.Count -gt 0) {
            Receive-Job -Job $jobs -Wait -AutoRemoveJob
            $jobs = @()
        }
    }
}

function Ensure-ResultPath {
    param([string]$ExperimentName,[string]$FileName)
    $datePart = (Get-Date).ToString('yyyyMMdd')
    $dir = Join-Path -Path $script:RepoRoot -ChildPath "rag_results/$ExperimentName/$datePart"
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    return Join-Path -Path $dir -ChildPath $FileName
}
#endregion Helper Functions

#region Experiment Parameters
$ExperimentName = "modes_concurrency"
$Modes = @("SEQUENTIAL", "PARALLEL_V1_OLD", "PARALLEL_V2_ASYNC")
$ConcurrencyList = @(1,2,5,10)
$RequestsPerCondition = 10
$QueryUri = "http://localhost:8080/api/rag/query"
$RequestBody = @{ query = "Benchmark query"; topK = 3 }
$Granularity = "FINE"
$Threads = 8
#endregion

#region Execution
foreach ($mode in $Modes) {
    foreach ($conc in $ConcurrencyList) {
        Start-RagBackend -ExecutionMode $mode -Granularity $Granularity -Threads $Threads
        if (-not (Wait-ForHealth)) {
            Write-Warning "Backend failed to become healthy for mode=$mode concurrency=$conc. Skipping."
            continue
        }
        $fileName = "mode_${mode}_conc_${conc}.csv"
        $outputPath = Ensure-ResultPath -ExperimentName $ExperimentName -FileName $fileName
        Write-Host "Running mode=$mode concurrency=$conc threads=$Threads granularity=$Granularity -> $outputPath"
        $results = Invoke-RagRequest -Uri $QueryUri -Body $RequestBody -RepeatCount $RequestsPerCondition -Concurrency $conc -Mode $mode -Threads $Threads -Granularity $Granularity
        $results | Export-Csv -NoTypeInformation -Path $outputPath
    }
}
Stop-RagBackend
#endregion
