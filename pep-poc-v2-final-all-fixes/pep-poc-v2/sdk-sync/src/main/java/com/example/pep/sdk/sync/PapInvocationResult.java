package com.example.pep.sdk.sync;

/**
 * Outcome of TX2 ({@link PapTransactionService#invokeAndRecord}). Returned — never thrown — so
 * that TX2 commits its audit trail (STL_PEP_TRANSACTION + STL_PEP_DIVERGENCE) before the caller
 * decides TX1's fate. The caller inspects {@link #failed()} and, if true, throws to roll TX1 back;
 * by then TX2 has already durably committed.
 *
 * @param transactionId the id assigned to this transaction (always valid, even on failure)
 * @param failed        true if any PAP call failed (or was skipped due to an earlier failure)
 * @param reason        human-readable failure reason when {@code failed}, else null
 */
public record PapInvocationResult(int transactionId, boolean failed, String reason) {

    public static PapInvocationResult success(int transactionId) {
        return new PapInvocationResult(transactionId, false, null);
    }

    public static PapInvocationResult failure(int transactionId, String reason) {
        return new PapInvocationResult(transactionId, true, reason);
    }
}
