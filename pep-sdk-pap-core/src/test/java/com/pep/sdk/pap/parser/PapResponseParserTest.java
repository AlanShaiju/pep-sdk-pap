package com.pep.sdk.pap.parser;

import com.pep.sdk.pap.model.PapResponse;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PapResponseParserTest {

    @Test
    void testParseSuccess() {
        PapResponse response = PapResponseParser.parse(200, "{\"status\":\"ok\"}", new HashMap<>());
        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertEquals("{\"status\":\"ok\"}", response.getResponseBody());
        assertNull(response.getErrorMessage());
    }

    @Test
    void testParseError() {
        PapResponse response = PapResponseParser.parse(400, "Bad Request parameters", new HashMap<>());
        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(400, response.getStatusCode());
        assertEquals("Bad Request parameters", response.getResponseBody());
        assertNotNull(response.getErrorMessage());
        assertTrue(response.getErrorMessage().contains("400"));
    }

    @Test
    void testParseFailureWithException() {
        Exception e = new RuntimeException("Connection timed out");
        PapResponse response = PapResponseParser.parseFailure(e);
        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(503, response.getStatusCode());
        assertEquals("Connection timed out", response.getErrorMessage());
    }
}
