package com.example.pep.demo;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Two endpoints, each wrapping multiple entities in ONE @Transactional method so the SDK
 * dispatches them all in one beforeCommit, in buffer order.
 *
 *  - /resources/ordered : three CREATEs in a fixed code order (Pipeline -> Deployment -> Monitor).
 *    All same operation type, so Hibernate preserves call order; PAP calls go out in that order.
 *
 *  - /resources/mixed   : a CREATE, an UPDATE, and a DELETE in one transaction. Hibernate's flush
 *    regroups these by operation type (insert -> update -> delete) regardless of code order, so
 *    the dispatch order demonstrates that reordering rather than literal call order.
 */
@RestController
@RequestMapping("/resources")
public class ResourceController {

    private final TenantRepository tenants;
    private final PipelineRepository pipelines;
    private final DeploymentRepository deployments;
    private final MonitorRepository monitors;

    public ResourceController(TenantRepository tenants, PipelineRepository pipelines,
                              DeploymentRepository deployments, MonitorRepository monitors) {
        this.tenants = tenants;
        this.pipelines = pipelines;
        this.deployments = deployments;
        this.monitors = monitors;
    }

    @PostMapping("/ordered")
    @Transactional
    public ResponseEntity<String> ordered(@RequestBody(required = false) Map<String, Object> body) {
        Tenant tenant = tenants.findById(7L).orElseThrow();
        // Fixed code order — all CREATE. Expect PAP calls in exactly this order.
        pipelines.save(new Pipeline("PIPE-1", "pipeline one", "first", tenant));
        deployments.save(new Deployment("DEPL-1", "deployment one", "second", tenant));
        monitors.save(new Monitor("MON-1", "monitor one", "third", tenant));
        return ResponseEntity.ok("created Pipeline -> Deployment -> Monitor (all SYNC, all ResourceInstance)");
    }

    @PostMapping("/mixed")
    @Transactional
    public ResponseEntity<String> mixed(@RequestBody(required = false) Map<String, Object> body) {
        Tenant tenant = tenants.findById(7L).orElseThrow();

        // Seed two rows in a separate prior step would be cleaner, but to keep one call self-contained
        // we create one, update another existing one, and delete a third existing one — so callers
        // should first hit /ordered, then /mixed.
        Pipeline p = pipelines.findAll().stream().findFirst().orElseGet(
                () -> pipelines.save(new Pipeline("PIPE-X", "seed", "seed", tenant)));
        p.setName("pipeline renamed");                 // UPDATE

        deployments.save(new Deployment("DEPL-2", "deployment two", "new", tenant)); // CREATE

        monitors.findAll().stream().findFirst().ifPresent(m -> monitors.delete(m));   // DELETE

        return ResponseEntity.ok("mixed: created Deployment, updated Pipeline, deleted Monitor "
                + "(Hibernate flushes insert->update->delete; observe PAP call order)");
    }
}
