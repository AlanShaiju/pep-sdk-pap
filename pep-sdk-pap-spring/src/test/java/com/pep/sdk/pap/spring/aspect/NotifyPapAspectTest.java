package com.pep.sdk.pap.spring.aspect;

import com.pep.sdk.pap.annotation.NotifyPap;
import com.pep.sdk.pap.model.*;
import com.pep.sdk.pap.outbox.PapOutboxWriter;
import com.pep.sdk.pap.spring.client.PapClient;
import com.pep.sdk.pap.spring.context.PapResponseHolder;
import com.pep.sdk.pap.spring.mapper.PapMapperRegistry;
import com.pep.sdk.pap.spring.mapper.PapPayloadMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotifyPapAspectTest {

    private PapMapperRegistry mapperRegistry;
    private PapClient papClient;
    private PapResponseHolder responseHolder;
    private PapOutboxWriter outboxWriter;
    private PlatformTransactionManager transactionManager;
    private ExecutorService executorService;
    private NotifyPapAspect aspect;

    // Dummy test payload subclass
    static class DummyPayload extends PolicyDataPayload {
        @Override
        public PapRequest toPapRequest() {
            return PapRequest.builder()
                    .method("POST")
                    .path("/v1/tenants/test-tenant/users")
                    .body(this)
                    .build();
        }
    }

    // Target class to intercept
    static class DummyService {
        @NotifyPap(entity = PapEntity.USER, event = PapEvent.USER_CREATED, propagateResponse = true)
        public String createUser(String username) {
            return "RESULT_" + username;
        }

        @NotifyPap(entity = PapEntity.USER, event = PapEvent.USER_CREATED, failBehavior = FailBehavior.FALLBACK_TO_OUTBOX)
        public String createUserFallback(String username) {
            return "RESULT_" + username;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        papClient = mock(PapClient.class);
        responseHolder = new PapResponseHolder();
        outboxWriter = mock(PapOutboxWriter.class);
        transactionManager = mock(PlatformTransactionManager.class);
        executorService = Executors.newSingleThreadExecutor();

        // Create mock mapper for user created event
        PapPayloadMapper mapper = mock(PapPayloadMapper.class);
        when(mapper.getEntity()).thenReturn(PapEntity.USER);
        when(mapper.getEvent()).thenReturn(PapEvent.USER_CREATED);
        when(mapper.map(any(), any())).thenReturn(new DummyPayload());

        mapperRegistry = new PapMapperRegistry(Collections.singletonList(mapper));

        ObjectProvider<PapOutboxWriter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(outboxWriter);

        aspect = new NotifyPapAspect(
                mapperRegistry,
                papClient,
                responseHolder,
                provider,
                transactionManager,
                executorService
        );
    }

    @Test
    void testSynchronousNotifyPapSuccess() {
        PapResponse successResponse = PapResponse.builder()
                .statusCode(201)
                .success(true)
                .responseBody("{\"userId\":\"123\"}")
                .build();
        when(papClient.execute(any(PapRequest.class))).thenReturn(successResponse);

        // Aspect proxy setup
        DummyService target = new DummyService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        DummyService proxy = factory.getProxy();

        String result = proxy.createUser("john_doe");

        assertEquals("RESULT_john_doe", result);
        verify(papClient, times(1)).execute(any(PapRequest.class));
        assertNotNull(responseHolder.getResponse());
        assertTrue(responseHolder.getResponse().isSuccess());
        assertEquals("{\"userId\":\"123\"}", responseHolder.getResponse().getResponseBody());
    }

    @Test
    void testSynchronousNotifyPapOutboxFallback() {
        PapResponse failResponse = PapResponse.builder()
                .statusCode(503)
                .success(false)
                .errorMessage("Service Unavailable")
                .build();
        when(papClient.execute(any(PapRequest.class))).thenReturn(failResponse);

        DummyService target = new DummyService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        DummyService proxy = factory.getProxy();

        String result = proxy.createUserFallback("john_doe");

        assertEquals("RESULT_john_doe", result);
        verify(papClient, times(1)).execute(any(PapRequest.class));
        verify(outboxWriter, times(1)).write(eq(PapEntity.USER), eq(PapEvent.USER_CREATED), any(PapRequest.class), eq("Service Unavailable"));
    }
}
