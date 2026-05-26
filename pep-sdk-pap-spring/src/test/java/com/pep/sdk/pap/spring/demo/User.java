package com.pep.sdk.pap.spring.demo;

public class User {
    private final String id;
    private final String tenantId;
    private final String username;
    private final String email;

    public User(String id, String tenantId, String username, String email) {
        this.id = id;
        this.tenantId = tenantId;
        this.username = username;
        this.email = email;
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }
}
