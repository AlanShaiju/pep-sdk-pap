package com.example.pep.demo;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/pipelines")
public class PipelineController {

    private final PipelineRepository pipelines;
    private final TenantRepository tenants;

    public PipelineController(PipelineRepository pipelines, TenantRepository tenants) {
        this.pipelines = pipelines;
        this.tenants = tenants;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Pipeline> create(@RequestBody Map<String, Object> req) {
        Long tenantId = Long.valueOf(String.valueOf(req.getOrDefault("tenantId", "1")));
        Tenant tenant = tenants.findById(tenantId).orElseThrow();
        Pipeline p = new Pipeline(
                (String) req.get("code"),
                (String) req.get("name"),
                (String) req.getOrDefault("description", null),
                tenant);
        return ResponseEntity.ok(pipelines.save(p));
    }

    @PatchMapping("/{id}")
    @Transactional
    public ResponseEntity<Pipeline> update(@PathVariable Integer id, @RequestBody Map<String, Object> req) {
        Pipeline p = pipelines.findById(id).orElseThrow();
        if (req.containsKey("name"))        p.setName((String) req.get("name"));
        if (req.containsKey("description")) p.setDescription((String) req.get("description"));
        return ResponseEntity.ok(pipelines.save(p));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Integer id) {
        pipelines.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<Pipeline> list() {
        return pipelines.findAll();
    }
}
