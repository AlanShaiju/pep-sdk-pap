package com.example.pep.demo;

import com.example.pep.sdk.core.annotation.CommunicationMode;
import com.example.pep.sdk.core.annotation.PapAttribute;
import com.example.pep.sdk.core.annotation.PapEntity;
import com.example.pep.sdk.core.annotation.PapInclude;
import com.example.pep.sdk.core.annotation.PapOperationMode;
import com.example.pep.sdk.core.annotation.PapProperty;
import com.example.pep.sdk.core.model.Operation;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * One of three local entity classes that all map to PAP entity "ResourceInstance".
 * They are distinguished on the PAP side only by resource_type_id (a @PapProperty constant).
 * All operations are SYNC.
 */
@Entity
@PapEntity(
        entity = "ResourceInstance",
        properties = {
                @PapProperty(key = "propagate",        value = "true"),
                @PapProperty(key = "resource_type_id", value = "102")
        },
        operationModes = {
                @PapOperationMode(operation = Operation.CREATE, mode = CommunicationMode.SYNC),
                @PapOperationMode(operation = Operation.UPDATE, mode = CommunicationMode.SYNC),
                @PapOperationMode(operation = Operation.DELETE, mode = CommunicationMode.SYNC)
        }
)
public class Deployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @PapAttribute(attributeName = "id")
    private Integer id;

    @PapAttribute(attributeName = "code")
    private String code;

    @PapAttribute(attributeName = "name")
    private String name;

    @PapAttribute(attributeName = "description")
    private String description;

    @ManyToOne(fetch = FetchType.EAGER)
    @PapInclude(name = "tenant_id", attribute = "id")
    private Tenant tenant;

    protected Deployment() { }

    public Deployment(String code, String name, String description, Tenant tenant) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.tenant = tenant;
    }

    public Integer getId()         { return id; }
    public String getCode()        { return code; }
    public String getName()        { return name; }
    public String getDescription() { return description; }
    public Tenant getTenant()      { return tenant; }
    public void setName(String v)        { this.name = v; }
}
