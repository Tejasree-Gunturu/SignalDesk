package io.signaldesk.incident.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "audit_events")
public class AuditEvent {
    @Id private UUID id; @Column(name="incident_id", nullable=false, updatable=false) private UUID incidentId; @Column(name="tenant_id", nullable=false, updatable=false, length=100) private String tenantId; @Column(nullable=false, length=80) private String action; @Column(nullable=false, length=100) private String actor; @Column(nullable=false, length=1000) private String detail; @Column(name="occurred_at", nullable=false, updatable=false) private Instant occurredAt;
    protected AuditEvent() { }
    private AuditEvent(UUID incidentId, String tenantId, String action, String actor, String detail, Instant occurredAt) { this.id=UUID.randomUUID(); this.incidentId=incidentId; this.tenantId=tenantId; this.action=action; this.actor=actor; this.detail=detail; this.occurredAt=occurredAt; }
    public static AuditEvent record(Incident incident, String action, String actor, String detail, Instant now) { return new AuditEvent(incident.getId(), incident.getTenantId(), action, actor, detail, now); }
    public UUID getId(){return id;} public UUID getIncidentId(){return incidentId;} public String getTenantId(){return tenantId;} public String getAction(){return action;} public String getActor(){return actor;} public String getDetail(){return detail;} public Instant getOccurredAt(){return occurredAt;}
}
