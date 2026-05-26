package com.pep.sdk.pap.outbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pep.sdk.pap.outbox.PapOutboxWriter;
import com.pep.sdk.pap.outbox.repository.PapOutboxRepository;
import com.pep.sdk.pap.outbox.writer.DefaultPapOutboxWriter;
import com.pep.sdk.pap.outbox.relay.PapOutboxRelay;
import com.pep.sdk.pap.spring.client.PapClient;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot Auto Configuration for PEP SDK Transactional Outbox.
 */
@AutoConfiguration
@EnableScheduling
@EntityScan("com.pep.sdk.pap.outbox.model")
@EnableJpaRepositories("com.pep.sdk.pap.outbox.repository")
public class PepOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PapOutboxWriter pepOutboxWriter(PapOutboxRepository repository, ObjectMapper objectMapper) {
        return new DefaultPapOutboxWriter(repository, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public PapOutboxRelay pepOutboxRelay(PapOutboxRepository repository, PapClient pepPapClient) {
        return new PapOutboxRelay(repository, pepPapClient);
    }
}
