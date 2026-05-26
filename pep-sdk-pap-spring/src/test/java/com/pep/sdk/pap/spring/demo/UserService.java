package com.pep.sdk.pap.spring.demo;

import com.pep.sdk.pap.annotation.NotifyPap;
import com.pep.sdk.pap.model.FailBehavior;
import com.pep.sdk.pap.model.PapEntity;
import com.pep.sdk.pap.model.PapEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    
    // In-memory mock database
    private final Map<String, User> userDatabase = new HashMap<>();

    /**
     * Creates a user in the local database and triggers policy synchronization to PAP.
     */
    @Transactional
    @NotifyPap(
            entity = PapEntity.USER,
            event = PapEvent.USER_CREATED,
            failBehavior = FailBehavior.FALLBACK_TO_OUTBOX,
            propagateResponse = true
    )
    public User registerUser(String id, String tenantId, String username, String email) {
        log.info("Registering user in local DB: {}", username);
        User user = new User(id, tenantId, username, email);
        userDatabase.put(id, user);
        return user;
    }

    public User getUser(String id) {
        return userDatabase.get(id);
    }
}
