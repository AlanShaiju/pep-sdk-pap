package com.example.pep.demo;

import org.springframework.data.jpa.repository.JpaRepository;

interface TenantRepository     extends JpaRepository<Tenant, Long> { }
interface PipelineRepository   extends JpaRepository<Pipeline, Integer> { }
interface DeploymentRepository extends JpaRepository<Deployment, Integer> { }
interface MonitorRepository    extends JpaRepository<Monitor, Integer> { }
