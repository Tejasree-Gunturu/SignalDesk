package io.signaldesk.incident.api;
import io.signaldesk.incident.domain.Severity;
import jakarta.validation.constraints.*;
public record CreateIncidentRequest(@NotBlank @Size(max=180) String title, @NotBlank @Size(max=100) String serviceName, @NotBlank @Size(max=4000) String description, @NotNull Severity severity, @NotBlank @Size(max=100) String reporter, @Size(max=100) String assignee) { }
