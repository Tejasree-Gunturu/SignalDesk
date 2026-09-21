package io.signaldesk.incident.application;

import io.signaldesk.incident.api.*;
import io.signaldesk.incident.domain.*;
import io.signaldesk.incident.persistence.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class IncidentService {
    private final IncidentRepository incidents; private final AuditEventRepository audits; private final OutboxEventRepository outbox; private final IdempotencyRecordRepository idempotency; private final AssignmentPolicy assignmentPolicy; private final Clock clock;
    public IncidentService(IncidentRepository incidents, AuditEventRepository audits, OutboxEventRepository outbox, IdempotencyRecordRepository idempotency, AssignmentPolicy assignmentPolicy, Clock clock) { this.incidents=incidents; this.audits=audits; this.outbox=outbox; this.idempotency=idempotency; this.assignmentPolicy=assignmentPolicy; this.clock=clock; }
    @Transactional
    public IncidentResponse create(String tenantId, String idempotencyKey, CreateIncidentRequest request) {
        if(idempotencyKey!=null&&!idempotencyKey.isBlank()) { var prior=idempotency.findByTenantIdAndIdempotencyKey(tenantId,idempotencyKey.trim()); if(prior.isPresent()) return IncidentResponse.from(requireIncident(prior.get().getIncidentId(),tenantId)); }
        Instant now=Instant.now(clock); Incident incident=Incident.open(IncidentId.newId(),tenantId,request.title().trim(),request.serviceName().trim(),request.description().trim(),request.severity(),assignmentPolicy.selectAssignee(request),now);
        incidents.save(incident); audits.save(AuditEvent.record(incident,"INCIDENT_CREATED",request.reporter().trim(),"Incident created and assigned to "+incident.getAssignedTo(),now)); outbox.save(OutboxEvent.from(incident,"incident.created",now));
        if(idempotencyKey!=null&&!idempotencyKey.isBlank()) idempotency.save(new IdempotencyRecord(tenantId,idempotencyKey.trim(),incident.getId(),now)); return IncidentResponse.from(incident);
    }
    @Transactional(readOnly=true) public IncidentResponse get(UUID id,String tenantId) { return IncidentResponse.from(requireIncident(id,tenantId)); }
    @Transactional(readOnly=true)
    public PageResponse<IncidentResponse> list(String tenantId, IncidentStatus status, Severity severity, int page, int size) {
        Pageable pageable=PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt")); Page<Incident> result;
        if(status!=null&&severity!=null) result=incidents.findByTenantIdAndStatusAndSeverity(tenantId,status,severity,pageable); else if(status!=null) result=incidents.findByTenantIdAndStatus(tenantId,status,pageable); else if(severity!=null) result=incidents.findByTenantIdAndSeverity(tenantId,severity,pageable); else result=incidents.findByTenantId(tenantId,pageable);
        return PageResponse.from(result,IncidentResponse::from);
    }
    @Transactional public IncidentResponse acknowledge(UUID id,String tenantId,TransitionIncidentRequest request) { Incident incident=requireIncident(id,tenantId); Instant now=Instant.now(clock); incident.acknowledge(request.actor().trim(),now); recordChange(incident,"INCIDENT_ACKNOWLEDGED",request.actor(),request.note(),now); return IncidentResponse.from(incident); }
    @Transactional public IncidentResponse resolve(UUID id,String tenantId,TransitionIncidentRequest request) { Incident incident=requireIncident(id,tenantId); Instant now=Instant.now(clock); incident.resolve(now); recordChange(incident,"INCIDENT_RESOLVED",request.actor(),request.note(),now); return IncidentResponse.from(incident); }
    @Transactional public IncidentResponse close(UUID id,String tenantId,TransitionIncidentRequest request) { Incident incident=requireIncident(id,tenantId); Instant now=Instant.now(clock); incident.close(now); recordChange(incident,"INCIDENT_CLOSED",request.actor(),request.note(),now); return IncidentResponse.from(incident); }
    @Transactional(readOnly=true) public List<AuditEventResponse> auditTrail(UUID id,String tenantId) { requireIncident(id,tenantId); return audits.findByIncidentIdAndTenantIdOrderByOccurredAtAsc(id,tenantId).stream().map(AuditEventResponse::from).toList(); }
    private Incident requireIncident(UUID id,String tenantId) { return incidents.findByIdAndTenantId(id,tenantId).orElseThrow(()->new IncidentNotFoundException(id)); }
    private void recordChange(Incident incident,String eventType,String actor,String note,Instant now) { String detail=(note==null||note.isBlank())?"Status changed to "+incident.getStatus():note.trim(); audits.save(AuditEvent.record(incident,eventType,actor.trim(),detail,now)); outbox.save(OutboxEvent.from(incident,eventType.toLowerCase().replace('_','.'),now)); }
}
