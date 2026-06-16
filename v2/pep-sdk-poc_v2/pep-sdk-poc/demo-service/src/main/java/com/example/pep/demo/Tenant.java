package com.example.pep.demo;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * Plain JPA entity. Carries no PEP-SDK annotations — Pipeline pulls values from it
 * via @PapInclude(attribute = "..."), referencing Java field names directly.
 */
@Entity
public class Tenant {

    @Id
    private Long id;

    private String code;

    private String name;

    protected Tenant() { }

    public Tenant(Long id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }

    public Long getId()      { return id; }
    public String getCode()  { return code; }
    public String getName()  { return name; }
}
