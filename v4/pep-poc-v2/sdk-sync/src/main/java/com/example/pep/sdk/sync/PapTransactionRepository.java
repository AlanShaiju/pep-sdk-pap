package com.example.pep.sdk.sync;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PapTransactionRepository extends JpaRepository<PapTransaction, Integer> {
}
