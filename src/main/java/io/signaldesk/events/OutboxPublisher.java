package io.signaldesk.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "signaldesk.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate db;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final String topic;
    public OutboxPublisher(JdbcTemplate db, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                           ObjectMapper mapper, MeterRegistry metrics, @Value("${signaldesk.kafka.topic}") String topic) {
        this.db = db; this.kafka = kafka; this.tx = tx; this.mapper = mapper; this.metrics = metrics; this.topic = topic;
    }
    @Scheduled(fixedDelayString = "${signaldesk.outbox.poll-ms:1000}")
    public void publish() {
        try { tx.executeWithoutResult(status -> publishBatch()); }
        catch (Exception e) { metrics.counter("signaldesk.outbox.failure").increment(); log.warn("Outbox retained for retry: {}", e.getClass().getSimpleName()); }
    }
    private void publishBatch() {
        // One database-elected publisher. A second instance skips this batch instead of reordering it.
        if (db.queryForList("SELECT name FROM worker_locks WHERE name='outbox' FOR UPDATE SKIP LOCKED").isEmpty()) return;
        var rows = db.queryForList("SELECT * FROM outbox_events WHERE published_at IS NULL ORDER BY created_at, id LIMIT 20");
        for (var row : rows) {
            try {
                String payload = (String) row.get("payload");
                // Upgrade pending MVP payloads at publication while retaining their event UUID.
                if (!mapper.readTree(payload).has("schemaVersion")) {
                    var old = mapper.readTree(payload);
                    String assignee = db.queryForObject("SELECT assigned_to FROM incidents WHERE id=?", String.class, row.get("aggregate_id"));
                    payload = mapper.writeValueAsString(new IncidentEvent(1, (UUID) row.get("id"),
                            (UUID) row.get("aggregate_id"), (String) row.get("tenant_id"), (String) row.get("event_type"),
                            old.path("status").asText(), old.path("severity").asText(), assignee,
                            db.queryForObject("SELECT created_at FROM outbox_events WHERE id=?", Timestamp.class, row.get("id")).toInstant(),
                            row.get("id").toString()));
                }
                kafka.send(topic, row.get("aggregate_id").toString(), payload).get(8, TimeUnit.SECONDS);
                db.update("UPDATE outbox_events SET published_at=? WHERE id=?", Timestamp.from(Instant.now()), row.get("id"));
                metrics.counter("signaldesk.outbox.published").increment();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("Publish unconfirmed; rollback batch", e);
            }
        }
    }
}
