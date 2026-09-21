package io.signaldesk.incident.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="idempotency_records")
public class IdempotencyRecord {
    @Id private UUID id; @Column(name="tenant_id", nullable=false, updatable=false, length=100) private String tenantId; @Column(name="idempotency_key", nullable=false, updatable=false, length=200) private String idempotencyKey; @Column(name="incident_id", nullable=false, updatable=false) private UUID incidentId; @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    protected IdempotencyRecord() { }
    public IdempotencyRecord(String tenantId, String idempotencyKey, UUID incidentId, Instant createdAt) { id=UUID.randomUUID(); this.tenantId=tenantId; this.idempotencyKey=idempotencyKey; this.incidentId=incidentId; this.createdAt=createdAt; }
    public UUID getIncidentId() { return incidentId; }
}
