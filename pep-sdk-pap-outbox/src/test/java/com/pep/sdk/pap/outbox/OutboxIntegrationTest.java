package com.pep.sdk.pap.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;
import com.pep.sdk.pap.model.PapRequest;
import com.pep.sdk.pap.model.PapResponse;
import com.pep.sdk.pap.outbox.model.OutboxRecord;
import com.pep.sdk.pap.outbox.model.OutboxStatus;
import com.pep.sdk.pap.outbox.repository.PapOutboxRepository;
import com.pep.sdk.pap.outbox.writer.DefaultPapOutboxWriter;
import com.pep.sdk.pap.outbox.relay.PapOutboxRelay;
import com.pep.sdk.pap.spring.client.PapClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = OutboxIntegrationTest.TestConfig.class)
class OutboxIntegrationTest {

    @Configuration
    @SpringBootApplication(scanBasePackages = "com.pep.sdk.pap.outbox")
    static class TestConfig {
        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private PapOutboxRepository repository;

    @Autowired
    private DefaultPapOutboxWriter writer;

    @Autowired
    private PapOutboxRelay relay;

    @MockBean
    private PapClient papClient;

    @BeforeEach
    void cleanDb() {
        repository.deleteAll();
    }

    @Test
    void testWriteToOutboxPersistsRecord() {
        PapRequest request = PapRequest.builder()
                .method("POST")
                .path("/v1/tenants/tenant-A/users")
                .body("{\"username\":\"alice\"}")
                .build();

        writer.write(PapEntity.USER, PapEvent.USER_CREATED, request, "Initial Connection Failure");

        List<OutboxRecord> records = repository.findAll();
        assertEquals(1, records.size());
        
        OutboxRecord record = records.get(0);
        assertEquals(PapEntity.USER, record.getEntity());
        assertEquals(PapEvent.USER_CREATED, record.getEvent());
        assertEquals("{\"username\":\"alice\"}", record.getPayloadJson());
        assertEquals("POST", record.getHttpMethod());
        assertEquals("/v1/tenants/tenant-A/users", record.getEndpointPath());
        assertEquals(OutboxStatus.PENDING, record.getStatus());
        assertEquals(0, record.getRetryCount());
    }

    @Test
    void testRelayProcessesAndDeletesSuccessfulRecord() {
        // Setup existing record
        PapRequest request = PapRequest.builder()
                .method("POST")
                .path("/v1/tenants/tenant-B/users")
                .body("{\"username\":\"bob\"}")
                .build();
        writer.write(PapEntity.USER, PapEvent.USER_CREATED, request, "Timeout");

        // Mock client success
        PapResponse successResponse = PapResponse.builder()
                .statusCode(200)
                .success(true)
                .build();
        when(papClient.execute(any(PapRequest.class))).thenReturn(successResponse);

        // Run relay
        relay.pollAndRelay();

        // Verify record is deleted
        List<OutboxRecord> records = repository.findAll();
        assertTrue(records.isEmpty());
        verify(papClient, times(1)).execute(any(PapRequest.class));
    }

    @Test
    void testRelayHandlesRejectedDataOnClientError() {
        PapRequest request = PapRequest.builder()
                .method("POST")
                .path("/v1/tenants/tenant-C/users")
                .body("{\"username\":\"malformed_user\"}")
                .build();
        writer.write(PapEntity.USER, PapEvent.USER_CREATED, request, "Internal error");

        // Mock 400 Bad Request
        PapResponse badRequestResponse = PapResponse.builder()
                .statusCode(400)
                .success(false)
                .errorMessage("Invalid username pattern")
                .build();
        when(papClient.execute(any(PapRequest.class))).thenReturn(badRequestResponse);

        relay.pollAndRelay();

        // Verify record updated to REJECTED_DATA
        List<OutboxRecord> records = repository.findAll();
        assertEquals(1, records.size());
        
        OutboxRecord record = records.get(0);
        assertEquals(OutboxStatus.REJECTED_DATA, record.getStatus());
        assertEquals(1, record.getRetryCount());
        assertEquals("Invalid username pattern", record.getErrorMessage());
    }
}
