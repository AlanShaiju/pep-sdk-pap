package com.example.pep.sdk.starter;

import com.example.pep.sdk.core.annotation.CommunicationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("pap.sdk")
public class PapSdkProperties {

    private boolean enabled = true;
    private String baseUrl;
    private CommunicationMode mode = CommunicationMode.SYNC;

    private final Retry retry = new Retry();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();
    private final Timeout timeout = new Timeout();
    private final Outbox outbox = new Outbox();

    public boolean isEnabled()                  { return enabled; }
    public void setEnabled(boolean v)           { this.enabled = v; }
    public String getBaseUrl()                  { return baseUrl; }
    public void setBaseUrl(String v)            { this.baseUrl = v; }
    public CommunicationMode getMode()          { return mode; }
    public void setMode(CommunicationMode v)    { this.mode = v; }
    public Retry getRetry()                     { return retry; }
    public CircuitBreaker getCircuitBreaker()   { return circuitBreaker; }
    public Timeout getTimeout()                 { return timeout; }
    public Outbox getOutbox()                   { return outbox; }

    public static class Retry {
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(200);
        private Duration maxBackoff = Duration.ofSeconds(5);
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { this.maxAttempts = v; }
        public Duration getInitialBackoff() { return initialBackoff; }
        public void setInitialBackoff(Duration v) { this.initialBackoff = v; }
        public Duration getMaxBackoff() { return maxBackoff; }
        public void setMaxBackoff(Duration v) { this.maxBackoff = v; }
    }
    public static class CircuitBreaker {
        private int failureRateThreshold = 50;
        private int slidingWindowSize = 20;
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);
        public int getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(int v) { this.failureRateThreshold = v; }
        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int v) { this.slidingWindowSize = v; }
        public Duration getWaitDurationInOpenState() { return waitDurationInOpenState; }
        public void setWaitDurationInOpenState(Duration v) { this.waitDurationInOpenState = v; }
    }
    public static class Timeout {
        private Duration connect = Duration.ofSeconds(2);
        private Duration read = Duration.ofSeconds(10);
        public Duration getConnect() { return connect; }
        public void setConnect(Duration v) { this.connect = v; }
        public Duration getRead() { return read; }
        public void setRead(Duration v) { this.read = v; }
    }
    public static class Outbox {
        private Duration pollInterval = Duration.ofSeconds(1);
        private int batchSize = 50;
        private int maxAttempts = 10;
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration v) { this.pollInterval = v; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int v) { this.batchSize = v; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { this.maxAttempts = v; }
    }
}
