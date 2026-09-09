<#
.SYNOPSIS
Extract RAG timing metrics from Spring Boot logs into a CSV compatible with the
benchmark analyzer.

.DESCRIPTION
Parses one or more log files for RAG_TIMING entries and emits a CSV containing
mode/strategy plus the per-stage millisecond breakdowns. When Mode is not present in
the log line, a constant Mode value can be supplied via -Mode.

.EXAMPLE
pwsh ./tools/extract_rag_timing.ps1 -LogPath ./logs/spring.log -OutputPath ./rag_timing_metrics.csv -Mode PARALLEL
#>

param(
    [Parameter(Mandatory = $true)][string[]]$LogPath,
    [Alias("OutPath")][string]$OutputPath = ".\\rag_timing_metrics.csv",
    [string]$Mode,
    [string]$Strategy
)

$ErrorActionPreference = "Stop"

$jsonPattern = '^(?<timestamp>\S+)\s+.*\[RAG_TIMING\]\s+(?<json>\{.*\})'
$legacyPattern = '^(?<timestamp>\S+)\s+.*RAG_TIMING mode=(?<mode>[^\s]+) strategy=(?<strategy>[^\s]+) qexp_ms=(?<qexp>\d+) dense_ms=(?<dense>\d+) sparse_ms=(?<sparse>\d+) merge_ms=(?<merge>\d+) ctx_ms=(?<ctx>\d+) llm_ms=(?<llm>\d+) total_ms=(?<total>\d+)'

$normalizedMode = if ([string]::IsNullOrWhiteSpace($Mode)) { $null } else { $Mode.Trim().ToUpperInvariant() }
$normalizedStrategy = if ([string]::IsNullOrWhiteSpace($Strategy)) { $null } else { $Strategy.Trim().ToUpperInvariant() }

function Get-ValueOrDefault {
    param(
        [object]$JsonObj,
        [string]$PropertyName,
        [object]$Default = $null
    )
    if ($null -eq $JsonObj) { return $Default }
    if ($JsonObj.PSObject.Properties.Name -contains $PropertyName) {
        return $JsonObj.$PropertyName
    }
    return $Default
}

$rows = @()

foreach ($path in $LogPath) {
    if (-not (Test-Path -Path $path)) {
        Write-Warning "Log file not found: $path"
        continue
    }

    $found = $false
    foreach ($line in Get-Content -Path $path | Where-Object { $_ -match 'RAG_TIMING' }) {
        if ($line -match $jsonPattern) {
            $timestamp = $Matches['timestamp']
            $payload = $Matches['json'] | ConvertFrom-Json
            $lineMode = if ($normalizedMode) { $normalizedMode } else { (Get-ValueOrDefault -JsonObj $payload -PropertyName 'mode' -Default (Get-ValueOrDefault -JsonObj $payload -PropertyName 'executionMode')) }
            $lineStrategy = if ($normalizedStrategy) { $normalizedStrategy } else { (Get-ValueOrDefault -JsonObj $payload -PropertyName 'strategy' -Default (Get-ValueOrDefault -JsonObj $payload -PropertyName 'parallelStrategy')) }
            if (-not [string]::IsNullOrWhiteSpace($lineMode)) { $lineMode = $lineMode.ToUpperInvariant() }
            if (-not [string]::IsNullOrWhiteSpace($lineStrategy)) { $lineStrategy = $lineStrategy.ToUpperInvariant() }

            $found = $true
            $rows += [PSCustomObject]@{
                Timestamp           = $timestamp
                Mode                = $lineMode
                Strategy            = $lineStrategy
                TotalMs             = Get-ValueOrDefault -JsonObj $payload -PropertyName 'totalMs'
                QExpMs              = Get-ValueOrDefault -JsonObj $payload -PropertyName 'queryExpansionMs'
                RetrievalMs         = Get-ValueOrDefault -JsonObj $payload -PropertyName 'retrievalMs'
                DenseMs             = Get-ValueOrDefault -JsonObj $payload -PropertyName 'denseMs'
                SparseMs            = Get-ValueOrDefault -JsonObj $payload -PropertyName 'sparseMs'
                MergeMs             = Get-ValueOrDefault -JsonObj $payload -PropertyName 'mergeMs'
                RerankMs            = Get-ValueOrDefault -JsonObj $payload -PropertyName 'rerankMs'
                ContextMs           = Get-ValueOrDefault -JsonObj $payload -PropertyName 'contextMs'
                LlmMs               = Get-ValueOrDefault -JsonObj $payload -PropertyName 'llmMs'
                GuardrailMs         = Get-ValueOrDefault -JsonObj $payload -PropertyName 'guardrailMs'
                ThreadPoolSize      = Get-ValueOrDefault -JsonObj $payload -PropertyName 'threadPoolSize'
                ParallelGranularity = Get-ValueOrDefault -JsonObj $payload -PropertyName 'parallelGranularity'
                NestedDenseSparse   = Get-ValueOrDefault -JsonObj $payload -PropertyName 'nestedDenseSparse'
            }
        }
        elseif ($line -match $legacyPattern) {
            $lineMode = $Matches['mode'].Trim().ToUpperInvariant()
            $lineStrategy = $Matches['strategy'].Trim().ToUpperInvariant()
            if ($normalizedMode) { $lineMode = $normalizedMode }
            if ($normalizedStrategy) { $lineStrategy = $normalizedStrategy }

            $found = $true
            $rows += [PSCustomObject]@{
                Timestamp           = $Matches['timestamp']
                Mode                = $lineMode
                Strategy            = $lineStrategy
                TotalMs             = $Matches['total']
                QExpMs              = $Matches['qexp']
                RetrievalMs         = $null
                DenseMs             = $Matches['dense']
                SparseMs            = $Matches['sparse']
                MergeMs             = $Matches['merge']
                RerankMs            = $null
                ContextMs           = $Matches['ctx']
                LlmMs               = $Matches['llm']
                GuardrailMs         = $null
                ThreadPoolSize      = $null
                ParallelGranularity = $null
                NestedDenseSparse   = $null
            }
        }
    }

    if (-not $found) {
        Write-Warning "No RAG_TIMING entries found in $path"
    }
}

$rows | Export-Csv -Path $OutputPath -NoTypeInformation -Encoding utf8
Write-Host "Extracted timing metrics to $OutputPath"
