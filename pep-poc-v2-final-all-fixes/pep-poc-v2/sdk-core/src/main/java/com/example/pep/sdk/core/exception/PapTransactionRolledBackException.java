package com.example.pep.sdk.core.exception;

/**
 * Thrown from {@code beforeCommit} after TX2 has durably recorded a PAP failure, purely to force
 * the business transaction (TX1) to roll back. By the time this propagates, STL_PEP_TRANSACTION
 * is already FAILED and every STL_PEP_DIVERGENCE row is committed — this exception carries no new
 * state, it only flips TX1's outcome to rollback.
 */
public class PapTransactionRolledBackException extends PapException {
    private final int transactionId;

    public PapTransactionRolledBackException(int transactionId, String reason) {
        super("PAP invocation failed for transaction " + transactionId + "; rolling back. " + reason);
        this.transactionId = transactionId;
    }

    public int getTransactionId() { return transactionId; }
}
