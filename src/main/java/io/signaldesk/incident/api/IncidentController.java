package io.signaldesk.incident.api;

import io.signaldesk.incident.application.IncidentService;
import io.signaldesk.incident.domain.*;
import io.signaldesk.security.ApiPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {
    private final IncidentService service;
    public IncidentController(IncidentService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<IncidentResponse> create(@AuthenticationPrincipal ApiPrincipal user,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateIncidentRequest request) {
        if (key != null && (key.isBlank() || key.length() > 200))
            throw new IllegalArgumentException("Idempotency-Key must contain 1-200 characters");
        var response = service.create(user.tenantId(), key, new CreateIncidentRequest(
                request.title(), request.serviceName(), request.description(), request.severity(), user.username(), request.assignee()));
        return ResponseEntity.created(URI.create("/api/v1/incidents/" + response.id())).body(response);
    }
    @GetMapping("/{id}")
    public IncidentResponse get(@PathVariable UUID id, @AuthenticationPrincipal ApiPrincipal user) {
        return service.get(id, user.tenantId());
    }
    @GetMapping
    public PageResponse<IncidentResponse> list(@AuthenticationPrincipal ApiPrincipal user,
            @RequestParam(required = false) IncidentStatus status, @RequestParam(required = false) Severity severity,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100");
        return service.list(user.tenantId(), status, severity, page, size);
    }
    @PostMapping("/{id}/acknowledge")
    public IncidentResponse acknowledge(@PathVariable UUID id, @AuthenticationPrincipal ApiPrincipal user,
            @Valid @RequestBody TransitionIncidentRequest request) {
        return service.acknowledge(id, user.tenantId(), new TransitionIncidentRequest(user.username(), request.note()));
    }
    @PostMapping("/{id}/resolve")
    public IncidentResponse resolve(@PathVariable UUID id, @AuthenticationPrincipal ApiPrincipal user,
            @Valid @RequestBody TransitionIncidentRequest request) {
        return service.resolve(id, user.tenantId(), new TransitionIncidentRequest(user.username(), request.note()));
    }
    @PostMapping("/{id}/close")
    public IncidentResponse close(@PathVariable UUID id, @AuthenticationPrincipal ApiPrincipal user,
            @Valid @RequestBody TransitionIncidentRequest request) {
        return service.close(id, user.tenantId(), new TransitionIncidentRequest(user.username(), request.note()));
    }
    @GetMapping("/{id}/audit")
    public List<AuditEventResponse> audit(@PathVariable UUID id, @AuthenticationPrincipal ApiPrincipal user) {
        return service.auditTrail(id, user.tenantId());
    }
}
