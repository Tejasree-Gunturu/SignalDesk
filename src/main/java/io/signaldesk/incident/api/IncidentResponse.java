package io.signaldesk.incident.api;
import io.signaldesk.incident.domain.*;
import java.time.Instant;
import java.util.UUID;
public record IncidentResponse(UUID id, String tenantId, String title, String serviceName, String description, Severity severity, IncidentStatus status, String assignedTo, Instant createdAt, Instant updatedAt, long version) {
    public static IncidentResponse from(Incident incident) { return new IncidentResponse(incident.getId(), incident.getTenantId(), incident.getTitle(), incident.getServiceName(), incident.getDescription(), incident.getSeverity(), incident.getStatus(), incident.getAssignedTo(), incident.getCreatedAt(), incident.getUpdatedAt(), incident.getVersion()); }
}
