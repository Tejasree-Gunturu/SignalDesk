package io.signaldesk.events;

import java.time.Instant;
import java.util.UUID;

public record IncidentEvent(int schemaVersion, UUID eventId, UUID incidentId, String tenantId,
                            String type, String status, String severity, String assignee,
                            Instant occurredAt, String correlationId) {
    public void validate() {
        if (schemaVersion != 1 || eventId == null || incidentId == null || occurredAt == null
                || tenantId == null || tenantId.isBlank() || tenantId.length() > 100
                || type == null || !type.matches("incident\\.(created|acknowledged|resolved|closed|sla\\.breached)")
                || assignee == null || assignee.isBlank() || assignee.length() > 100)
            throw new IllegalArgumentException("Invalid incident event envelope");
    }
}
