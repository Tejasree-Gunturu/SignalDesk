package io.signaldesk.incident.persistence;
import io.signaldesk.incident.domain.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface IncidentRepository extends JpaRepository<Incident, UUID> {
    Optional<Incident> findByIdAndTenantId(UUID id, String tenantId);
    Page<Incident> findByTenantIdAndStatusAndSeverity(String tenantId, IncidentStatus status, Severity severity, Pageable pageable);
    Page<Incident> findByTenantIdAndStatus(String tenantId, IncidentStatus status, Pageable pageable);
    Page<Incident> findByTenantIdAndSeverity(String tenantId, Severity severity, Pageable pageable);
    Page<Incident> findByTenantId(String tenantId, Pageable pageable);
}
