package com.example.pep.sdk.core.annotation;

import com.example.pep.sdk.core.model.Operation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface PapOperationMode {
    Operation operation();
    CommunicationMode mode();
}
