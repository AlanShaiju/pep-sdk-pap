package com.example.pep.sdk.core.catalog;

import com.example.pep.sdk.core.model.Operation;
import java.util.Map;

public record EndpointSpec(Map<Operation, OperationSpec> operations) {

    public OperationSpec forOperation(Operation op) {
        return operations.get(op);
    }
}
