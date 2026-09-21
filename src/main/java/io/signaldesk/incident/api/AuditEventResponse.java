package io.signaldesk.incident.api;
import io.signaldesk.incident.domain.AuditEvent;
import java.time.Instant;
import java.util.UUID;
public record AuditEventResponse(UUID id, String action, String actor, String detail, Instant occurredAt) { public static AuditEventResponse from(AuditEvent event) { return new AuditEventResponse(event.getId(), event.getAction(), event.getActor(), event.getDetail(), event.getOccurredAt()); } }
