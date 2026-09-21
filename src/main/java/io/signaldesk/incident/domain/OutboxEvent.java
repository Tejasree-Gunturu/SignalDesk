package io.signaldesk.incident.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
/** Durable event record. Publishing is intentionally deferred to the Kafka phase of the roadmap. */
@Entity @Table(name="outbox_events")
public class OutboxEvent {
    @Id private UUID id; @Column(name="aggregate_id", nullable=false, updatable=false) private UUID aggregateId; @Column(name="tenant_id", nullable=false, updatable=false, length=100) private String tenantId; @Column(name="event_type", nullable=false, length=100) private String eventType; @Column(nullable=false, length=4000) private String payload; @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt; @Column(name="published_at") private Instant publishedAt;
    protected OutboxEvent() { }
    private OutboxEvent(Incident incident, String eventType, String payload, Instant now) { id=UUID.randomUUID(); aggregateId=incident.getId(); tenantId=incident.getTenantId(); this.eventType=eventType; this.payload=payload; createdAt=now; }
    public static OutboxEvent from(Incident incident, String eventType, Instant now) { String payload="{\"incidentId\":\""+incident.getId()+"\",\"status\":\""+incident.getStatus()+"\",\"severity\":\""+incident.getSeverity()+"\"}"; return new OutboxEvent(incident,eventType,payload,now); }
}
