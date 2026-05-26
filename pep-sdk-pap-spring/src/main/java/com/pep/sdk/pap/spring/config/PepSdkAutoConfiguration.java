package com.pep.sdk.pap.spring.config;

import com.pep.sdk.pap.outbox.PapOutboxWriter;
import com.pep.sdk.pap.spring.aspect.NotifyPapAspect;
import com.pep.sdk.pap.spring.client.CircuitBreaker;
import com.pep.sdk.pap.spring.client.PapClient;
import com.pep.sdk.pap.spring.client.RestTemplatePapClient;
import com.pep.sdk.pap.spring.context.PapResponseHolder;
import com.pep.sdk.pap.spring.mapper.PapMapperRegistry;
import com.pep.sdk.pap.spring.mapper.PapPayloadMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spring Boot Auto Configuration for PEP SDK Spring Integration.
 */
@AutoConfiguration
public class PepSdkAutoConfiguration {

    @Value("${pep.pap.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${pep.pap.connection-timeout-ms:5000}")
    private int connectionTimeoutMs;

    @Value("${pep.pap.read-timeout-ms:5000}")
    private int readTimeoutMs;

    @Value("${pep.pap.circuit-breaker.failure-threshold:5}")
    private int cbFailureThreshold;

    @Value("${pep.pap.circuit-breaker.reset-timeout-ms:10000}")
    private long cbResetTimeoutMs;

    @Bean
    @ConditionalOnMissingBean
    public RestTemplate pepRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMillis(connectionTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreaker pepCircuitBreaker() {
        return new CircuitBreaker(cbFailureThreshold, cbResetTimeoutMs);
    }

    @Bean
    @ConditionalOnMissingBean
    public PapClient pepPapClient(RestTemplate pepRestTemplate, CircuitBreaker pepCircuitBreaker) {
        return new RestTemplatePapClient(pepRestTemplate, baseUrl, pepCircuitBreaker);
    }

    @Bean
    @ConditionalOnMissingBean
    public PapResponseHolder pepResponseHolder() {
        return new PapResponseHolder();
    }

    @Bean
    @ConditionalOnMissingBean
    public PapMapperRegistry pepMapperRegistry(List<PapPayloadMapper> mappers) {
        return new PapMapperRegistry(mappers);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "pepExecutorService")
    public ExecutorService pepExecutorService() {
        return Executors.newFixedThreadPool(10);
    }

    @Bean
    @ConditionalOnMissingBean
    public NotifyPapAspect notifyPapAspect(PapMapperRegistry mapperRegistry,
                                           PapClient papClient,
                                           PapResponseHolder responseHolder,
                                           ObjectProvider<PapOutboxWriter> outboxWriterProvider,
                                           PlatformTransactionManager transactionManager,
                                           ExecutorService pepExecutorService) {
        return new NotifyPapAspect(
                mapperRegistry,
                papClient,
                responseHolder,
                outboxWriterProvider,
                transactionManager,
                pepExecutorService
        );
    }
}
