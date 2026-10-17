package io.signaldesk.escalation;

import io.micrometer.core.instrument.MeterRegistry;
import io.signaldesk.incident.domain.*;
import io.signaldesk.incident.persistence.*;
import io.signaldesk.redis.RedisControls;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "signaldesk.escalation.enabled", havingValue = "true")
public class EscalationWorker {
    private static final Logger log = LoggerFactory.getLogger(EscalationWorker.class);
    private final RedisControls redis; private final TransactionTemplate tx; private final JdbcTemplate db;
    private final IncidentRepository incidents; private final AuditEventRepository audits; private final OutboxEventRepository outbox;
    private final Clock clock; private final MeterRegistry metrics;
    public EscalationWorker(RedisControls redis, TransactionTemplate tx, JdbcTemplate db, IncidentRepository incidents,
            AuditEventRepository audits, OutboxEventRepository outbox, Clock clock, MeterRegistry metrics) {
        this.redis = redis; this.tx = tx; this.db = db; this.incidents = incidents; this.audits = audits;
        this.outbox = outbox; this.clock = clock; this.metrics = metrics;
    }
    @Scheduled(fixedDelayString = "${signaldesk.escalation.poll-ms:10000}")
    public void scan() {
        var token = redis.lock("sla-scan");
        if (token.isEmpty()) return;
        try { tx.executeWithoutResult(status -> {
            // The database locks + marker remain authoritative if the Redis lease expires during work.
            var ids = db.queryForList("""
                    SELECT id FROM incidents WHERE status='OPEN' AND escalated_at IS NULL AND sla_due_at <= CURRENT_TIMESTAMP
                    ORDER BY sla_due_at LIMIT 50 FOR UPDATE SKIP LOCKED
                    """, UUID.class);
            for (UUID id : ids) {
                Incident incident = incidents.findById(id).orElseThrow();
                Instant now = Instant.now(clock);
                if (incident.escalate(now)) {
                    audits.save(AuditEvent.record(incident, "SLA_BREACHED", "system", "Acknowledgement SLA exceeded; notify on-call", now));
                    outbox.save(OutboxEvent.from(incident, "incident.sla.breached", now));
                    metrics.counter("signaldesk.escalation.created").increment();
                }
            }
        }); } catch (Exception e) { log.warn("Escalation rolled back: {}", e.getClass().getSimpleName()); }
        finally { redis.unlock("sla-scan", token.get()); }
    }
}
