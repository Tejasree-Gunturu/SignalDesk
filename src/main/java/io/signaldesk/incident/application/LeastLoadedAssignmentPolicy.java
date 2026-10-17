package io.signaldesk.incident.application;

import io.signaldesk.incident.api.CreateIncidentRequest;
import io.signaldesk.incident.domain.IncidentStatus;
import io.signaldesk.incident.persistence.IncidentRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@ConditionalOnProperty(name = "signaldesk.assignment", havingValue = "least-loaded")
public class LeastLoadedAssignmentPolicy implements AssignmentPolicy {
    private final IncidentRepository incidents;
    public LeastLoadedAssignmentPolicy(IncidentRepository incidents) { this.incidents = incidents; }
    @Override public String selectAssignee(CreateIncidentRequest request, String tenant) {
        if (request.assignee() != null && !request.assignee().isBlank()) return request.assignee().trim();
        return List.of("oncall-alex", "oncall-sam", "oncall-lee").stream().min(Comparator.comparingLong(name ->
                incidents.countByTenantIdAndAssignedToAndStatusIn(tenant, name,
                        List.of(IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED)))).orElseThrow();
    }
}
