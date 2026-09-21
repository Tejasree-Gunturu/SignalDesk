package io.signaldesk.incident.application;
import io.signaldesk.incident.api.CreateIncidentRequest;
/** Strategy seam: new policies (least-loaded, skill-based) can replace the starter policy without changing the use case. */
public interface AssignmentPolicy { String selectAssignee(CreateIncidentRequest request); }
