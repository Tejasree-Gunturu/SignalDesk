package io.signaldesk.incident.domain;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.signaldesk.events.IncidentEvent;
import jakarta.persistence.*;
import org.slf4j.MDC;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id private UUID id;
    @Column(name = "aggregate_id", nullable = false) private UUID aggregateId;
    @Column(name = "tenant_id", nullable = false, length = 100) private String tenantId;
    @Column(name = "event_type", nullable = false, length = 100) private String eventType;
    @Column(nullable = false, length = 4000) private String payload;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;
    protected OutboxEvent() { }

    public static OutboxEvent from(Incident incident, String type, Instant now) {
        OutboxEvent event = new OutboxEvent();
        event.id = UUID.randomUUID(); event.aggregateId = incident.getId();
        event.tenantId = incident.getTenantId(); event.eventType = type; event.createdAt = now;
        String correlation = MDC.get("correlationId");
        var envelope = new IncidentEvent(1, event.id, incident.getId(), incident.getTenantId(), type,
                incident.getStatus().name(), incident.getSeverity().name(), incident.getAssignedTo(), now,
                correlation == null ? event.id.toString() : correlation);
        try { event.payload = JsonMapper.builder().findAndAddModules().build().writeValueAsString(envelope); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize domain event", e); }
        return event;
    }
}
