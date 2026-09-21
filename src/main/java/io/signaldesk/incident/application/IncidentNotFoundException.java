package io.signaldesk.incident.application;
import java.util.UUID;
public class IncidentNotFoundException extends RuntimeException { public IncidentNotFoundException(UUID id) { super("Incident " + id + " was not found for this tenant"); } }
