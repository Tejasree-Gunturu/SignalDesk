package io.signaldesk.incident.application;
import io.signaldesk.incident.api.CreateIncidentRequest;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
@Component
public class RoundRobinAssignmentPolicy implements AssignmentPolicy {
    private final AtomicInteger cursor=new AtomicInteger(); private final List<String> responders=List.of("oncall-alex","oncall-sam","oncall-lee");
    public String selectAssignee(CreateIncidentRequest request) { if(request.assignee()!=null && !request.assignee().isBlank()) return request.assignee().trim(); return responders.get(Math.floorMod(cursor.getAndIncrement(), responders.size())); }
}
