package com.sleepwell.sleepwell_backend.rag.config;

import com.sleepwell.sleepwell_backend.rag.model.RagExecutionMode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    private static final Logger log = LoggerFactory.getLogger(RagProperties.class);

    private String executionMode = RagExecutionMode.SEQUENTIAL.name();

    public RagExecutionMode getExecutionMode() {
        return RagExecutionMode.fromProperty(executionMode);
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    @PostConstruct
    public void logRagMode() {
        log.info("RAG_EXECUTION_MODE resolved to {} (property rag.execution-mode)", getExecutionMode());
    }
}
