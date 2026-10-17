package io.signaldesk.notifications;

import io.signaldesk.incident.application.IncidentService;
import io.signaldesk.security.ApiPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/incidents/{id}/notifications")
public class NotificationController {
    private final JdbcTemplate db; private final IncidentService incidents;
    public NotificationController(JdbcTemplate db, IncidentService incidents) { this.db = db; this.incidents = incidents; }
    @GetMapping public List<Map<String, Object>> list(@PathVariable UUID id, @AuthenticationPrincipal ApiPrincipal user) {
        incidents.get(id, user.tenantId());
        return db.queryForList("""
                SELECT event_id, event_type, recipient, channel, status, attempts, created_at, delivered_at, last_error
                FROM notification_jobs WHERE incident_id=? AND tenant_id=? ORDER BY created_at LIMIT 100
                """, id, user.tenantId());
    }
}
