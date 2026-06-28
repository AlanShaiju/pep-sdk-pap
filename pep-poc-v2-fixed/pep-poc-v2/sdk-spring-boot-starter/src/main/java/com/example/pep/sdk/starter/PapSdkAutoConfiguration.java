package com.example.pep.sdk.starter;

import com.example.pep.sdk.client.PapClient;
import com.example.pep.sdk.core.catalog.PapCatalog;
import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.registry.MetadataLoader;
import com.example.pep.sdk.core.registry.PapEntityRegistry;
import com.example.pep.sdk.core.request.EndpointResolver;
import com.example.pep.sdk.core.request.PapRequestBuilder;
import com.example.pep.sdk.sync.PapDivergenceEntry;
import com.example.pep.sdk.sync.PapDivergenceRepository;
import com.example.pep.sdk.sync.PapTransactionRepository;
import com.example.pep.sdk.sync.PapTransactionService;
import com.example.pep.sdk.sync.PapTransactionSynchronization;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * Wires the three-transaction PEP SDK. Compared with the original single-recorder design, the
 * divergence/transaction work is now owned by {@link PapTransactionService} (TX2 + TX3), and the
 * synchronization is a thin orchestrator around it.
 */
@AutoConfiguration
@EnableConfigurationProperties(PapSdkProperties.class)
@ConditionalOnProperty(name = "pap.sdk.enabled", havingValue = "true", matchIfMissing = true)
@EntityScan(basePackageClasses = PapDivergenceEntry.class)
@EnableJpaRepositories(basePackageClasses = PapDivergenceRepository.class)
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

    @Bean public PapClient papClient(RestClient restClient, Retry retry, CircuitBreaker cb) {
        return new PapClient(restClient, retry, cb);
    }

    /**
     * Shared, transaction-aware EntityManager proxy — routes each call to whichever EntityManager
     * is bound to the active transaction, so it is safe to hold as a singleton and to use from
     * the REQUIRES_NEW TX2/TX3 methods.
     */
    @Bean @ConditionalOnMissingBean
    public EntityManager papSdkSharedEntityManager(EntityManagerFactory emf) {
        return SharedEntityManagerCreator.createSharedEntityManager(emf);
    }

    @Bean public PapTransactionService papTransactionService(PapEntityRegistry registry,
                                                             PapRequestBuilder builder,
                                                             PapClient client,
                                                             PapTransactionRepository txRepo,
                                                             PapDivergenceRepository divRepo,
                                                             EntityManager papSdkSharedEntityManager,
                                                             @Qualifier("papSdkObjectMapper") ObjectMapper om,
                                                             Clock clock) {
        return new PapTransactionService(registry, builder, client, txRepo, divRepo,
                papSdkSharedEntityManager, om, clock);
    }

    @Bean public PapTransactionSynchronization papTxnSync(PapTransactionService transactionService,
                                                          EntityManager papSdkSharedEntityManager) {
        return new PapTransactionSynchronization(transactionService, papSdkSharedEntityManager);
    }

    @Bean public PapEntityListener papEntityListener(PapEntityRegistry registry,
                                                     PapTransactionSynchronization sync) {
        return new PapEntityListener(registry, sync);
    }

    @Bean public HibernateListenerRegistrar hibernateListenerRegistrar(EntityManagerFactory emf,
                                                                       PapEntityListener listener) {
        return new HibernateListenerRegistrar(emf, listener);
    }
}
