package com.example.pep.demo;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    /** Seed a Tenant so @PapInclude has something to walk into. */
    @Bean
    CommandLineRunner seed(TenantRepository tenants) {
        return args -> tenants.save(new Tenant(7L, "ACME", "Acme Corp"));
    }
}
