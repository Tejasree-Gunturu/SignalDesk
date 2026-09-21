package io.signaldesk.incident.api;
import jakarta.validation.constraints.*;
public record TransitionIncidentRequest(@NotBlank @Size(max=100) String actor, @Size(max=1000) String note) { }
