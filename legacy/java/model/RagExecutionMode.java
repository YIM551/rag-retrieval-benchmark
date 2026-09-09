package com.sleepwell.sleepwell_backend.rag.model;

public enum RagExecutionMode {
    SEQUENTIAL,
    PARALLEL_OLD,
    PARALLEL_SYNC;

    public static RagExecutionMode fromProperty(String value) {
        if (value == null || value.isBlank()) {
            return SEQUENTIAL;
        }

        String normalized = value.trim().toUpperCase();
        if ("ASYNC".equals(normalized) || "PARALLEL".equals(normalized) || "PARALLEL_V2_ASYNC".equals(normalized)) {
            return PARALLEL_SYNC;
        }
        if ("PARALLEL_V1_OLD".equals(normalized)) {
            return PARALLEL_OLD;
        }

        try {
            return RagExecutionMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            return SEQUENTIAL;
        }
    }
}
