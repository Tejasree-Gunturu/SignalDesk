package io.signaldesk.incident.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incidents")
public class Incident {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false, updatable = false, length = 100) private String tenantId;
    @Column(nullable = false, length = 180) private String title;
    @Column(name = "service_name", nullable = false, length = 100) private String serviceName;
    @Column(nullable = false, length = 4000) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Severity severity;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private IncidentStatus status;
    @Column(name = "assigned_to", nullable = false, length = 100) private String assignedTo;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;
    protected Incident() { }
    private Incident(UUID id, String tenantId, String title, String serviceName, String description, Severity severity, String assignedTo, Instant now) {
        this.id=id; this.tenantId=tenantId; this.title=title; this.serviceName=serviceName; this.description=description;
        this.severity=severity; this.status=IncidentStatus.OPEN; this.assignedTo=assignedTo; this.createdAt=now; this.updatedAt=now;
    }
    public static Incident open(IncidentId id, String tenantId, String title, String serviceName, String description, Severity severity, String assignedTo, Instant now) { return new Incident(id.value(), tenantId, title, serviceName, description, severity, assignedTo, now); }
    public void acknowledge(String actor, Instant now) { requireStatus(IncidentStatus.OPEN, "Only an open incident can be acknowledged"); status=IncidentStatus.ACKNOWLEDGED; assignedTo=actor; updatedAt=now; }
    public void resolve(Instant now) { if (status != IncidentStatus.OPEN && status != IncidentStatus.ACKNOWLEDGED) throw new InvalidTransitionException("Only an open or acknowledged incident can be resolved"); status=IncidentStatus.RESOLVED; updatedAt=now; }
    public void close(Instant now) { requireStatus(IncidentStatus.RESOLVED, "Only a resolved incident can be closed"); status=IncidentStatus.CLOSED; updatedAt=now; }
    private void requireStatus(IncidentStatus expected, String message) { if (status != expected) throw new InvalidTransitionException(message); }
    public UUID getId() { return id; } public String getTenantId() { return tenantId; } public String getTitle() { return title; } public String getServiceName() { return serviceName; } public String getDescription() { return description; } public Severity getSeverity() { return severity; } public IncidentStatus getStatus() { return status; } public String getAssignedTo() { return assignedTo; } public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; } public long getVersion() { return version; }
}
