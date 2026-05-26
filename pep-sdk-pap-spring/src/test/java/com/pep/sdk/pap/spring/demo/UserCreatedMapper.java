package com.pep.sdk.pap.spring.demo;

import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;
import com.pep.sdk.pap.model.PolicyDataPayload;
import com.pep.sdk.pap.spring.mapper.PapPayloadMapper;
import org.springframework.stereotype.Component;

@Component
public class UserCreatedMapper implements PapPayloadMapper {

    @Override
    public PapEntity getEntity() {
        return PapEntity.USER;
    }

    @Override
    public PapEvent getEvent() {
        return PapEvent.USER_CREATED;
    }

    @Override
    public PolicyDataPayload map(Object result, Object[] args) {
        // We assume the method returned the created User object
        if (result instanceof User) {
            User user = (User) result;
            return UserCreatedPayload.builder()
                    .tenantId(user.getTenantId())
                    .userId(user.getId())
                    .username(user.getUsername())
                    .email(user.getEmail())
                    .build();
        }
        
        throw new IllegalArgumentException("Expected return value of type User");
    }
}
