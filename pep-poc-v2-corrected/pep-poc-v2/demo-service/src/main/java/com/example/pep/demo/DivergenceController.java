package com.example.pep.demo;

import com.example.pep.sdk.sync.PapDivergenceEntry;
import com.example.pep.sdk.sync.PapDivergenceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only view of STL_PEP_DIVERGENCE so the divergence cases can be inspected over HTTP. */
@RestController
@RequestMapping("/divergence")
public class DivergenceController {

    private final PapDivergenceRepository repository;

    public DivergenceController(PapDivergenceRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PapDivergenceEntry> all() {
        return repository.findAll();
    }
}
