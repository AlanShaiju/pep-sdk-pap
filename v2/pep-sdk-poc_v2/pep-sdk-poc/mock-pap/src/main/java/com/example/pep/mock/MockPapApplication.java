package com.example.pep.mock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Mock PAP matching the v9 catalog: /pap/v1/resource-instances, tenant via header,
 * snake_case body fields, 'propagate' as a query param on UPDATE/DELETE.
 */
@SpringBootApplication
public class MockPapApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPapApplication.class, args);
    }

    @RestController
    @RequestMapping("/pap/v1/resource-instances")
    public static class ResourceInstanceController {
        private static final Logger log = LoggerFactory.getLogger(ResourceInstanceController.class);

        @PostMapping
        public ResponseEntity<String> create(
                @RequestHeader("tenant_id") String tenantId,
                @RequestBody Map<String, Object> body) {
            log.info("[MOCK-PAP] CREATE tenant_id(header)={} body={}", tenantId, body);
            return ResponseEntity.ok("{\"status\":\"created\"}");
        }

        @PatchMapping("/{id}")
        public ResponseEntity<String> update(
                @PathVariable String id,
                @RequestHeader("tenant_id") String tenantId,
                @RequestParam(value = "propagate", required = false) String propagate,
                @RequestBody Map<String, Object> body) {
            log.info("[MOCK-PAP] UPDATE id={} tenant_id(header)={} propagate(query)={} body={}",
                    id, tenantId, propagate, body);
            return ResponseEntity.ok("{}");
        }

        @DeleteMapping("/{id}")
        public ResponseEntity<Void> delete(
                @PathVariable String id,
                @RequestHeader("tenant_id") String tenantId,
                @RequestParam(value = "propagate", required = false) String propagate) {
            log.info("[MOCK-PAP] DELETE id={} tenant_id(header)={} propagate(query)={}",
                    id, tenantId, propagate);
            return ResponseEntity.ok().build();
        }
    }
}
