package io.signaldesk.incident.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.signaldesk.incident.api.*;
import io.signaldesk.incident.domain.*;
import io.signaldesk.incident.persistence.*;
import io.signaldesk.redis.RedisControls;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;

@Service
public class IncidentService {
    private final IncidentRepository incidents;
    private final AuditEventRepository audits;
    private final OutboxEventRepository outbox;
    private final IdempotencyRecordRepository idempotency;
    private final AssignmentPolicy assignmentPolicy;
    private final Clock clock;
    private final RedisControls redis;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public IncidentService(IncidentRepository incidents, AuditEventRepository audits, OutboxEventRepository outbox,
            IdempotencyRecordRepository idempotency, AssignmentPolicy assignmentPolicy, Clock clock,
            RedisControls redis, TransactionTemplate tx, ObjectMapper mapper) {
        this.incidents = incidents; this.audits = audits; this.outbox = outbox; this.idempotency = idempotency;
        this.assignmentPolicy = assignmentPolicy; this.clock = clock; this.redis = redis; this.tx = tx; this.mapper = mapper;
    }
    public IncidentResponse create(String tenantId, String key, CreateIncidentRequest raw) {
        var request = new CreateIncidentRequest(raw.title().trim(), raw.serviceName().trim(), raw.description().trim(),
                raw.severity(), raw.reporter().trim(), raw.assignee() == null ? null : raw.assignee().trim());
        String normalizedKey = key == null ? null : key.trim();
        String fingerprint = fingerprint(request);
        try {
            return tx.execute(status -> {
                var existing = replay(tenantId, normalizedKey, fingerprint);
                if (existing != null) return existing;
                Instant now = Instant.now(clock);
                Incident incident = Incident.open(IncidentId.newId(), tenantId, request.title(), request.serviceName(),
                        request.description(), request.severity(), assignmentPolicy.selectAssignee(request, tenantId), now);
                incidents.save(incident);
                audits.save(AuditEvent.record(incident, "INCIDENT_CREATED", request.reporter(),
                        "Incident created and assigned to " + incident.getAssignedTo(), now));
                outbox.save(OutboxEvent.from(incident, "incident.created", now));
                if (normalizedKey != null) idempotency.save(new IdempotencyRecord(
                        tenantId, normalizedKey, incident.getId(), now, fingerprint));
                incidents.flush();
                return IncidentResponse.from(incident);
            });
        } catch (DataIntegrityViolationException conflict) {
            // A concurrent create may have won the unique key. Read after the failed transaction rolled back.
            var existing = tx.execute(status -> replay(tenantId, normalizedKey, fingerprint));
            if (existing != null) return existing;
            throw conflict;
        }
    }
    private IncidentResponse replay(String tenant, String key, String fingerprint) {
        if (key == null) return null;
        var prior = idempotency.findByTenantIdAndIdempotencyKey(tenant, key);
        if (prior.isEmpty()) return null;
        String stored = prior.get().getRequestHash();
        if (stored != null && !stored.equals(fingerprint))
            throw new InvalidTransitionException("Idempotency-Key already used with a different request");
        return IncidentResponse.from(requireIncident(prior.get().getIncidentId(), tenant));
    }
    private String fingerprint(CreateIncidentRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(request))); }
        catch (Exception e) { throw new IllegalStateException("Cannot fingerprint request", e); }
    }
    @Transactional(readOnly = true)
    public IncidentResponse get(UUID id, String tenant) {
        long version = incidents.findVersion(id, tenant).orElseThrow(() -> new IncidentNotFoundException(id));
        return redis.get(tenant, id, version).orElseGet(() -> {
            var response = IncidentResponse.from(requireIncident(id, tenant));
            redis.put(response);
            return response;
        });
    }
    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> list(String tenant, IncidentStatus status, Severity severity, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Page<Incident> result;
        if (status != null && severity != null) result = incidents.findByTenantIdAndStatusAndSeverity(tenant, status, severity, pageable);
        else if (status != null) result = incidents.findByTenantIdAndStatus(tenant, status, pageable);
        else if (severity != null) result = incidents.findByTenantIdAndSeverity(tenant, severity, pageable);
        else result = incidents.findByTenantId(tenant, pageable);
        return PageResponse.from(result, IncidentResponse::from);
    }
    @Transactional
    public IncidentResponse acknowledge(UUID id, String tenant, TransitionIncidentRequest request) {
        Incident incident = requireIncident(id, tenant); Instant now = Instant.now(clock);
        incident.acknowledge(request.actor().trim(), now);
        return recordChange(incident, "INCIDENT_ACKNOWLEDGED", request.actor(), request.note(), now);
    }
    @Transactional
    public IncidentResponse resolve(UUID id, String tenant, TransitionIncidentRequest request) {
        Incident incident = requireIncident(id, tenant); Instant now = Instant.now(clock);
        incident.resolve(now);
        return recordChange(incident, "INCIDENT_RESOLVED", request.actor(), request.note(), now);
    }
    @Transactional
    public IncidentResponse close(UUID id, String tenant, TransitionIncidentRequest request) {
        Incident incident = requireIncident(id, tenant); Instant now = Instant.now(clock);
        incident.close(now);
        return recordChange(incident, "INCIDENT_CLOSED", request.actor(), request.note(), now);
    }
    @Transactional(readOnly = true)
    public List<AuditEventResponse> auditTrail(UUID id, String tenant) {
        requireIncident(id, tenant);
        return audits.findByIncidentIdAndTenantIdOrderByOccurredAtAsc(id, tenant).stream().map(AuditEventResponse::from).toList();
    }
    private Incident requireIncident(UUID id, String tenant) {
        return incidents.findByIdAndTenantId(id, tenant).orElseThrow(() -> new IncidentNotFoundException(id));
    }
    private IncidentResponse recordChange(Incident incident, String type, String actor, String note, Instant now) {
        long oldVersion = incident.getVersion();
        audits.save(AuditEvent.record(incident, type, actor.trim(),
                note == null || note.isBlank() ? "Status changed to " + incident.getStatus() : note.trim(), now));
        outbox.save(OutboxEvent.from(incident, type.toLowerCase(Locale.ROOT).replace('_', '.'), now));
        incidents.flush(); // Return the committed version rather than the previous one.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { redis.invalidate(incident.getTenantId(), incident.getId(), oldVersion); }
        });
        return IncidentResponse.from(incident);
    }
}
