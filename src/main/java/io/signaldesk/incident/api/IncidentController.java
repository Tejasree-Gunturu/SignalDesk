package io.signaldesk.incident.api;

import io.signaldesk.incident.application.IncidentService;
import io.signaldesk.incident.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController @Validated @RequestMapping("/api/v1/incidents")
public class IncidentController {
    private final IncidentService service;
    public IncidentController(IncidentService service) { this.service=service; }
    @PostMapping @Operation(summary="Create an incident",description="Use Idempotency-Key to safely retry a create request.") @ApiResponse(responseCode="201",description="Created")
    public ResponseEntity<IncidentResponse> create(@RequestHeader(name="X-Tenant-Id",defaultValue="demo") String tenantId,@RequestHeader(name="Idempotency-Key",required=false) String idempotencyKey,@Valid @RequestBody CreateIncidentRequest request) { IncidentResponse response=service.create(tenantId.trim(),idempotencyKey,request); return ResponseEntity.created(URI.create("/api/v1/incidents/"+response.id())).body(response); }
    @GetMapping("/{id}") @Operation(summary="Get a tenant-scoped incident") public IncidentResponse get(@PathVariable UUID id,@RequestHeader(name="X-Tenant-Id",defaultValue="demo") String tenantId) { return service.get(id,tenantId.trim()); }
    @GetMapping @Operation(summary="List incidents with optional lifecycle and severity filters")
    public PageResponse<IncidentResponse> list(@RequestHeader(name="X-Tenant-Id",defaultValue="demo") String tenantId,@RequestParam(required=false) IncidentStatus status,@RequestParam(required=false) Severity severity,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { if(page<0||size<1||size>100) throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 100"); return service.list(tenantId.trim(),status,severity,page,size); }
    @PostMapping("/{id}/acknowledge") @Operation(summary="Acknowledge an open incident") public IncidentResponse acknowledge(@PathVariable UUID id,@RequestHeader(name="X-Tenant-Id",defaultValue="demo") String tenantId,@Valid @RequestBody TransitionIncidentRequest request) { return service.acknowledge(id,tenantId.trim(),request); }
    @PostMapping("/{id}/resolve") @Operation(summary="Resolve an open or acknowledged incident") public IncidentResponse resolve(@PathVariable UUID id,@RequestHeader(name="X-Tenant-Id",defaultValue="demo") String tenantId,@Valid @RequestBody TransitionIncidentRequest request) { return service.resolve(id,tenantId.trim(),request); }
    @PostMapping("/{id}/close") @Operation(summary="Close a resolved incident") public IncidentResponse close(@PathVariable UUID id,@RequestHeader(name="X-Tenant-Id",defaultValue="demo") String tenantId,@Valid @RequestBody TransitionIncidentRequest request) { return service.close(id,tenantId.trim(),request); }
    @GetMapping("/{id}/audit") @Operation(summary="Read the immutable incident audit timeline") public List<AuditEventResponse> auditTrail(@PathVariable UUID id,@RequestHeader(name="X-Tenant-Id",defaultValue="demo") String tenantId) { return service.auditTrail(id,tenantId.trim()); }
}
