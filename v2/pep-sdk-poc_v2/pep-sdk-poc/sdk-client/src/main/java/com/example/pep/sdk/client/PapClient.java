package com.example.pep.sdk.client;

import com.example.pep.sdk.core.exception.PapException;
import com.example.pep.sdk.core.exception.PapRejectedException;
import com.example.pep.sdk.core.exception.PapSdkException;
import com.example.pep.sdk.core.exception.PapUnavailableException;
import com.example.pep.sdk.core.model.HttpMethod;
import com.example.pep.sdk.core.request.PapRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class PapClient {

    private static final Logger log = LoggerFactory.getLogger(PapClient.class);

    private final RestClient restClient;
    private final List<PapRequestDecorator> decorators;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public PapClient(RestClient restClient,
                     List<PapRequestDecorator> decorators,
                     Retry retry,
                     CircuitBreaker circuitBreaker) {
        this.restClient = restClient;
        this.decorators = List.copyOf(decorators);
        this.retry = retry;
        this.circuitBreaker = circuitBreaker;
    }

    public void send(PapRequest request) {
        Supplier<Void> call = () -> { doSend(request); return null; };
        Supplier<Void> retried = Retry.decorateSupplier(retry, call);
        Supplier<Void> guarded = CircuitBreaker.decorateSupplier(circuitBreaker, retried);

        try {
            guarded.get();
        } catch (PapException e) {
            throw e;
        } catch (CallNotPermittedException e) {
            throw new PapUnavailableException("Circuit open for PAP", e);
        } catch (RuntimeException e) {
            throw new PapUnavailableException("PAP call failed", e);
        }
    }

    private void doSend(PapRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 1. Catalog-declared HEADER attributes
        request.headers().forEach(headers::set);
        // 2. Decorator chain (auth, tracing, etc.)
        for (PapRequestDecorator d : decorators) d.decorate(headers, request);

        String uri = withQueryParams(request.path(), request.requestParams());

        try {
            ResponseEntity<String> response;
            RestClient.RequestBodySpec spec = restClient
                    .method(toSpring(request.method()))
                    .uri(uri)
                    .headers(h -> h.addAll(headers));

            if (request.method() == HttpMethod.DELETE) {
                response = spec.retrieve().toEntity(String.class);
            } else {
                response = spec.body(request.payload()).retrieve().toEntity(String.class);
            }

            HttpStatusCode status = response.getStatusCode();
            if (!status.is2xxSuccessful()) {
                throw new PapUnavailableException("Unexpected status " + status.value());
            }
            log.debug("PAP {} {} -> {}", request.method(), uri, status.value());
        } catch (HttpClientErrorException e) {
            log.warn("PAP rejected {} {} : {}", request.method(), uri, e.getStatusCode().value());
            throw new PapRejectedException(e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            log.warn("PAP 5xx {} {} : {}", request.method(), uri, e.getStatusCode().value());
            throw new PapUnavailableException("PAP server error " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            log.warn("PAP transport failure {} {} : {}", request.method(), uri, e.getMessage());
            throw new PapUnavailableException("PAP transport failure", e);
        } catch (PapException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new PapSdkException("Unexpected error calling PAP", e);
        }
    }

    private static String withQueryParams(String path, Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) return path;
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(path);
        queryParams.forEach(b::queryParam);
        return b.build(false).toUriString();
    }

    private static org.springframework.http.HttpMethod toSpring(HttpMethod m) {
        return switch (m) {
            case POST   -> org.springframework.http.HttpMethod.POST;
            case PATCH  -> org.springframework.http.HttpMethod.PATCH;
            case DELETE -> org.springframework.http.HttpMethod.DELETE;
            case GET    -> org.springframework.http.HttpMethod.GET;
        };
    }
}
