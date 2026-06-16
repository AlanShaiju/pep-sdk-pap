package com.example.pep.sdk.starter;

import com.example.pep.sdk.async.DefaultDivergenceRecorder;
import com.example.pep.sdk.async.DefaultOutboxAppender;
import com.example.pep.sdk.async.PapDivergenceRepository;
import com.example.pep.sdk.async.PapOutboxConsumer;
import com.example.pep.sdk.async.PapOutboxEntry;
import com.example.pep.sdk.async.PapOutboxRepository;
import com.example.pep.sdk.client.PapClient;
import com.example.pep.sdk.client.PapRequestDecorator;
import com.example.pep.sdk.core.catalog.PapCatalog;
import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.registry.MetadataLoader;
import com.example.pep.sdk.core.registry.PapEntityRegistry;
import com.example.pep.sdk.core.request.EndpointResolver;
import com.example.pep.sdk.core.request.PapRequestBuilder;
import com.example.pep.sdk.sync.DivergenceRecorder;
import com.example.pep.sdk.sync.OutboxAppender;
import com.example.pep.sdk.sync.PapTransactionSynchronization;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@AutoConfiguration
@EnableConfigurationProperties(PapSdkProperties.class)
@ConditionalOnProperty(name = "pap.sdk.enabled", havingValue = "true", matchIfMissing = true)
@EnableScheduling
@EntityScan(basePackageClasses = PapOutboxEntry.class)
@EnableJpaRepositories(basePackageClasses = PapOutboxRepository.class)
public class PapSdkAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public Clock papSdkClock() { return Clock.systemUTC(); }

    @Bean @ConditionalOnMissingBean(name = "papSdkObjectMapper")
    public ObjectMapper papSdkObjectMapper() { return new ObjectMapper(); }

    @Bean public PapCatalog papCatalog() { return new PapCatalog(); }

    @Bean public EndpointResolver endpointResolver() { return new EndpointResolver(); }

    @Bean public PapEntityRegistry papEntityRegistry(PapSdkProperties props) {
        return new MetadataLoader(props.getMode()).load(Thread.currentThread().getContextClassLoader());
    }

    @Bean public PapRequestBuilder papRequestBuilder(PapCatalog catalog, EndpointResolver resolver) {
        return new PapRequestBuilder(catalog, resolver);
    }

    @Bean public Retry papSdkRetry(PapSdkProperties p) {
        return Resilience4jFactory.buildRetry(p.getRetry());
    }
    @Bean public CircuitBreaker papSdkCircuitBreaker(PapSdkProperties p) {
        return Resilience4jFactory.buildCircuitBreaker(p.getCircuitBreaker());
    }

    @Bean public RestClient papSdkRestClient(PapSdkProperties props) {
        if (props.getBaseUrl() == null || props.getBaseUrl().isBlank()) {
            throw new PapSdkException("pap.sdk.base-url is required");
        }
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) props.getTimeout().getConnect().toMillis());
        rf.setReadTimeout((int) props.getTimeout().getRead().toMillis());
        return RestClient.builder().baseUrl(props.getBaseUrl()).requestFactory(rf).build();
    }

    @Bean public PapClient papClient(RestClient restClient,
                                     ObjectProvider<PapRequestDecorator> decorators,
                                     Retry retry, CircuitBreaker cb) {
        return new PapClient(restClient, decorators.orderedStream().toList(), retry, cb);
    }

    @Bean public OutboxAppender outboxAppender(PapOutboxRepository repo,
                                                PapRequestBuilder builder,
                                                @org.springframework.beans.factory.annotation.Qualifier("papSdkObjectMapper") ObjectMapper om,
                                                Clock clock) {
        return new DefaultOutboxAppender(repo, builder, om, clock);
    }

    @Bean public DivergenceRecorder divergenceRecorder(PapDivergenceRepository repo,
                                                        @PersistenceContext EntityManager entityManager,
                                                        @org.springframework.beans.factory.annotation.Qualifier("papSdkObjectMapper") ObjectMapper om,
                                                        Clock clock) {
        return new DefaultDivergenceRecorder(repo, entityManager, om, clock);
    }

    @Bean public PapTransactionSynchronization papTxnSync(PapEntityRegistry registry,
                                                          PapRequestBuilder builder,
                                                          PapClient client,
                                                          OutboxAppender appender,
                                                          DivergenceRecorder divergenceRecorder,
                                                          @PersistenceContext EntityManager entityManager) {
        return new PapTransactionSynchronization(registry, builder, client, appender, divergenceRecorder, entityManager);
    }

    @Bean public PapEntityListener papEntityListener(PapEntityRegistry registry,
                                                     PapTransactionSynchronization sync) {
        return new PapEntityListener(registry, sync);
    }

    @Bean public HibernateListenerRegistrar hibernateListenerRegistrar(EntityManagerFactory emf,
                                                                       PapEntityListener listener) {
        return new HibernateListenerRegistrar(emf, listener);
    }

    @Bean public TransactionTemplate papTransactionTemplate(PlatformTransactionManager tm) {
        return new TransactionTemplate(tm);
    }

    @Bean public PapOutboxConsumer papOutboxConsumer(PapOutboxRepository repo,
                                                      PapClient client,
                                                      PapCatalog catalog,
                                                      EndpointResolver resolver,
                                                      @org.springframework.beans.factory.annotation.Qualifier("papSdkObjectMapper") ObjectMapper om,
                                                      TransactionTemplate tt,
                                                      Clock clock,
                                                      PapSdkProperties props) {
        return new PapOutboxConsumer(repo, client, catalog, resolver, om, tt, clock,
                props.getOutbox().getBatchSize(), props.getOutbox().getMaxAttempts());
    }

    @Bean public OutboxScheduler outboxScheduler(PapOutboxConsumer consumer) {
        return new OutboxScheduler(consumer);
    }

    /** Wraps the consumer's @Scheduled annotation so the consumer itself stays free of it. */
    static class OutboxScheduler {
        private final PapOutboxConsumer consumer;
        OutboxScheduler(PapOutboxConsumer consumer) { this.consumer = consumer; }
        @Scheduled(fixedDelayString = "${pap.sdk.outbox.poll-interval:1s}")
        public void tick() { consumer.tick(); }
    }
}
