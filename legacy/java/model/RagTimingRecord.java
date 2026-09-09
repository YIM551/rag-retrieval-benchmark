package com.sleepwell.sleepwell_backend.rag.model;

public class RagTimingRecord {
    private String executionMode;
    private String parallelStrategy;
    private long totalMs;
    private long queryExpansionMs;
    private long retrievalMs;
    private long denseMs;
    private long sparseMs;
    private long mergeMs;
    private long rerankMs;
    private long contextBuildMs;
    private long llmMs;
    private long guardrailMs;
    private int threadPoolSize;
    private String parallelGranularity;
    private boolean nestedDenseSparse;

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getParallelStrategy() {
        return parallelStrategy;
    }

    public void setParallelStrategy(String parallelStrategy) {
        this.parallelStrategy = parallelStrategy;
    }

    public long getTotalMs() {
        return totalMs;
    }

    public void setTotalMs(long totalMs) {
        this.totalMs = totalMs;
    }

    public long getQueryExpansionMs() {
        return queryExpansionMs;
    }

    public void setQueryExpansionMs(long queryExpansionMs) {
        this.queryExpansionMs = queryExpansionMs;
    }

    public long getRetrievalMs() {
        return retrievalMs;
    }

    public void setRetrievalMs(long retrievalMs) {
        this.retrievalMs = retrievalMs;
    }

    public long getDenseMs() {
        return denseMs;
    }

    public void setDenseMs(long denseMs) {
        this.denseMs = denseMs;
    }

    public long getSparseMs() {
        return sparseMs;
    }

    public void setSparseMs(long sparseMs) {
        this.sparseMs = sparseMs;
    }

    public long getMergeMs() {
        return mergeMs;
    }

    public void setMergeMs(long mergeMs) {
        this.mergeMs = mergeMs;
    }

    public long getRerankMs() {
        return rerankMs;
    }

    public void setRerankMs(long rerankMs) {
        this.rerankMs = rerankMs;
    }

    public long getContextBuildMs() {
        return contextBuildMs;
    }

    public void setContextBuildMs(long contextBuildMs) {
        this.contextBuildMs = contextBuildMs;
    }

    public long getLlmMs() {
        return llmMs;
    }

    public void setLlmMs(long llmMs) {
        this.llmMs = llmMs;
    }

    public long getGuardrailMs() {
        return guardrailMs;
    }

    public void setGuardrailMs(long guardrailMs) {
        this.guardrailMs = guardrailMs;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    public String getParallelGranularity() {
        return parallelGranularity;
    }

    public void setParallelGranularity(String parallelGranularity) {
        this.parallelGranularity = parallelGranularity;
    }

    public boolean isNestedDenseSparse() {
        return nestedDenseSparse;
    }

    public void setNestedDenseSparse(boolean nestedDenseSparse) {
        this.nestedDenseSparse = nestedDenseSparse;
    }
}
