package com.pep.sdk.pap.spring.client;

import com.pep.sdk.pap.model.PapRequest;
import com.pep.sdk.pap.model.PapResponse;
import com.pep.sdk.pap.parser.PapResponseParser;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring RestTemplate implementation of PapClient with circuit breaker validation.
 */
public class RestTemplatePapClient implements PapClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final CircuitBreaker circuitBreaker;

    public RestTemplatePapClient(RestTemplate restTemplate, String baseUrl, CircuitBreaker circuitBreaker) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public PapResponse execute(PapRequest request) {
        if (!circuitBreaker.isCallAllowed()) {
            return PapResponse.builder()
                    .statusCode(503)
                    .success(false)
                    .errorMessage("Circuit breaker is open. Blocking call to PAP.")
                    .build();
        }

        // Build target URI
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path(request.getResolvedPath());

        // Append query parameters
        request.getQueryParameters().forEach(builder::queryParam);
        String urlString = builder.toUriString();

        // Prepare request headers and body
        HttpHeaders headers = new HttpHeaders();
        request.getHeaders().forEach(headers::add);
        HttpEntity<Object> entity = new HttpEntity<>(request.getBody(), headers);

        try {
            ResponseEntity<String> responseEntity = restTemplate.exchange(
                    urlString,
                    HttpMethod.valueOf(request.getMethod()),
                    entity,
                    String.class
            );

            circuitBreaker.recordSuccess();

            return PapResponseParser.parse(
                    responseEntity.getStatusCode().value(),
                    responseEntity.getBody(),
                    convertHeaders(responseEntity.getHeaders())
            );

        } catch (HttpStatusCodeException ex) {
            circuitBreaker.recordFailure();
            return PapResponseParser.parse(
                    ex.getStatusCode().value(),
                    ex.getResponseBodyAsString(),
                    convertHeaders(ex.getResponseHeaders())
            );
        } catch (Exception ex) {
            circuitBreaker.recordFailure();
            return PapResponseParser.parseFailure(ex);
        }
    }

    private Map<String, List<String>> convertHeaders(HttpHeaders httpHeaders) {
        if (httpHeaders == null) {
            return new HashMap<>();
        }
        Map<String, List<String>> map = new HashMap<>();
        httpHeaders.forEach((key, values) -> map.put(key, new ArrayList<>(values)));
        return map;
    }
}
