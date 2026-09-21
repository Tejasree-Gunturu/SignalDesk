package io.signaldesk.incident.persistence;
import io.signaldesk.incident.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> { List<AuditEvent> findByIncidentIdAndTenantIdOrderByOccurredAtAsc(UUID incidentId, String tenantId); }
