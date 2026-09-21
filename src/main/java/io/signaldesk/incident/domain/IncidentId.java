package io.signaldesk.incident.domain;
import java.util.UUID;
/** A small value object that prevents treating arbitrary UUIDs as incident identifiers. */
public record IncidentId(UUID value) { public static IncidentId newId() { return new IncidentId(UUID.randomUUID()); } }
