package com.pep.sdk.pap.spring.aspect;

import com.pep.sdk.pap.annotation.NotifyPap;
import com.pep.sdk.pap.model.FailBehavior;
import com.pep.sdk.pap.model.PapRequest;
import com.pep.sdk.pap.model.PapResponse;
import com.pep.sdk.pap.model.PolicyDataPayload;
import com.pep.sdk.pap.outbox.PapOutboxWriter;
import com.pep.sdk.pap.spring.client.PapClient;
import com.pep.sdk.pap.spring.context.PapResponseHolder;
import com.pep.sdk.pap.spring.mapper.PapPayloadMapper;
import com.pep.sdk.pap.spring.mapper.PapMapperRegistry;
import com.pep.sdk.pap.exception.PapSyncException;
import com.pep.sdk.pap.exception.PapClientException;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

/**
 * Aspect handling AOP interception for methods annotated with @NotifyPap.
 */
@Aspect
public class NotifyPapAspect {

    private static final Logger log = LoggerFactory.getLogger(NotifyPapAspect.class);

    private final PapMapperRegistry mapperRegistry;
    private final PapClient papClient;
    private final PapResponseHolder responseHolder;
    private final ObjectProvider<PapOutboxWriter> outboxWriterProvider;
    private final PlatformTransactionManager transactionManager;
    private final ExecutorService executorService;

    @Value("${pep.pap.async-enabled:false}")
    private boolean asyncEnabled;

    public NotifyPapAspect(PapMapperRegistry mapperRegistry,
                           PapClient papClient,
                           PapResponseHolder responseHolder,
                           ObjectProvider<PapOutboxWriter> outboxWriterProvider,
                           PlatformTransactionManager transactionManager,
                           ExecutorService executorService) {
        this.mapperRegistry = mapperRegistry;
        this.papClient = papClient;
        this.responseHolder = responseHolder;
        this.outboxWriterProvider = outboxWriterProvider;
        this.transactionManager = transactionManager;
        this.executorService = executorService;
    }

    @Around("@annotation(notifyPap)")
    public Object intercept(ProceedingJoinPoint joinPoint, NotifyPap notifyPap) throws Throwable {
        // Execute business method
        Object result = joinPoint.proceed();

        try {
            executeLifecycle(joinPoint, notifyPap, result);
        } catch (Exception e) {
            log.error("Error executing @NotifyPap lifecycle mapping/transport", e);
            throw e;
        }

        return result;
    }

    private void executeLifecycle(ProceedingJoinPoint joinPoint, NotifyPap notifyPap, Object result) {
        Object[] args = joinPoint.getArgs();
        PapPayloadMapper mapper = mapperRegistry.getMapper(notifyPap.entity(), notifyPap.event());
        
        PolicyDataPayload payload = mapper.map(result, args);
        if (payload == null) {
            throw new PapClientException("Mapper returned null payload for " + notifyPap.entity() + " - " + notifyPap.event());
        }

        PapRequest request = payload.toPapRequest();

        if (asyncEnabled) {
            handleAsynchronousLifecycle(joinPoint, notifyPap, request);
        } else {
            handleSynchronousLifecycle(joinPoint, notifyPap, request);
        }
    }

    private void handleSynchronousLifecycle(ProceedingJoinPoint joinPoint, NotifyPap notifyPap, PapRequest request) {
        log.debug("Executing synchronous PAP notification for {} - {}", notifyPap.entity(), notifyPap.event());
        PapResponse response = papClient.execute(request);

        if (response.isSuccess()) {
            if (notifyPap.propagateResponse()) {
                responseHolder.setResponse(response);
            }
        } else {
            applyFailureBehavior(joinPoint, notifyPap, request, response);
        }
    }

    private void handleAsynchronousLifecycle(ProceedingJoinPoint joinPoint, NotifyPap notifyPap, PapRequest request) {
        log.debug("Queueing asynchronous PAP notification for {} - {}", notifyPap.entity(), notifyPap.event());

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executorService.submit(() -> executeAsynchronousCall(joinPoint, notifyPap, request));
                }
            });
        } else {
            executorService.submit(() -> executeAsynchronousCall(joinPoint, notifyPap, request));
        }
    }

    private void executeAsynchronousCall(ProceedingJoinPoint joinPoint, NotifyPap notifyPap, PapRequest request) {
        try {
            PapResponse response = papClient.execute(request);
            if (!response.isSuccess()) {
                applyFailureBehavior(joinPoint, notifyPap, request, response);
            }
        } catch (Exception ex) {
            log.error("Async execution failed unexpectedly", ex);
            PapResponse response = PapResponse.builder()
                    .statusCode(500)
                    .success(false)
                    .errorMessage(ex.getMessage())
                    .build();
            applyFailureBehavior(joinPoint, notifyPap, request, response);
        }
    }

    private void applyFailureBehavior(ProceedingJoinPoint joinPoint, NotifyPap notifyPap, PapRequest request, PapResponse response) {
        FailBehavior behavior = notifyPap.failBehavior();
        log.warn("PAP synchronization failed. Behavior: {}, Error: {}", behavior, response.getErrorMessage());

        switch (behavior) {
            case FALLBACK_TO_OUTBOX:
                writeToOutbox(notifyPap, request, response.getErrorMessage());
                break;
            case COMPENSATE:
                executeCompensation(joinPoint, notifyPap.compensateWith());
                // Throw exception in synchronous mode to rollback active transaction
                if (!asyncEnabled) {
                    throw new PapSyncException("PAP sync failed: " + response.getErrorMessage(), response);
                }
                break;
            case LOG_AND_CONTINUE:
            default:
                log.info("Continuing execution ignoring PAP failure as per failBehavior.");
                break;
        }
    }

    private void writeToOutbox(NotifyPap notifyPap, PapRequest request, String errorMsg) {
        PapOutboxWriter outboxWriter = outboxWriterProvider.getIfAvailable();
        if (outboxWriter == null) {
            log.error("Outbox fallback requested but no PapOutboxWriter bean found. Data will be lost!");
            throw new PapSyncException("Outbox writer bean not available for fallback", null);
        }
        outboxWriter.write(notifyPap.entity(), notifyPap.event(), request, errorMsg);
    }

    private void executeCompensation(ProceedingJoinPoint joinPoint, String methodName) {
        if (methodName == null || methodName.trim().isEmpty()) {
            log.error("Compensate behavior requested but no compensateWith method specified.");
            return;
        }

        Object target = joinPoint.getTarget();
        log.info("Executing compensation method '{}' on bean {}", methodName, target.getClass().getSimpleName());

        // Wrap execution in a NEW transaction
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        template.executeWithoutResult(status -> {
            try {
                // Try parameterless method first
                Method method;
                try {
                    method = target.getClass().getMethod(methodName);
                    method.invoke(target);
                } catch (NoSuchMethodException e) {
                    // Fall back to method with original parameters
                    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
                    method = target.getClass().getMethod(methodName, signature.getParameterTypes());
                    method.invoke(target, joinPoint.getArgs());
                }
            } catch (Exception ex) {
                log.error("Failed to execute compensation method: " + methodName, ex);
                throw new PapClientException("Compensation invocation failed", ex);
            }
        });
    }
}
