package com.example.pep.demo;

import com.example.pep.sdk.sync.PapTransaction;
import com.example.pep.sdk.sync.PapTransactionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only view of STL_PEP_TRANSACTION so the effective transaction state can be inspected. */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final PapTransactionRepository repository;

    public TransactionController(PapTransactionRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PapTransaction> all() {
        return repository.findAll();
    }
}
