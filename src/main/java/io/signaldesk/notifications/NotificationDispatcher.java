package io.signaldesk.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.signaldesk.events.IncidentEvent;
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
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "signaldesk.dispatcher.enabled", havingValue = "true")
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final NotificationChannels channels;
    private final ObjectMapper mapper;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry metrics;
    private final int maxAttempts;
    public NotificationDispatcher(JdbcTemplate db, TransactionTemplate tx, NotificationChannels channels,
            ObjectMapper mapper, KafkaTemplate<String, String> kafka, MeterRegistry metrics,
            @Value("${signaldesk.notifications.max-attempts:4}") int maxAttempts) {
        this.db = db; this.tx = tx; this.channels = channels; this.mapper = mapper; this.kafka = kafka;
        this.metrics = metrics; this.maxAttempts = maxAttempts;
    }
    @Scheduled(fixedDelayString = "${signaldesk.notifications.poll-ms:500}")
    public void dispatch() {
        try { tx.executeWithoutResult(status -> dispatchOne()); }
        catch (Exception e) { log.warn("Notification transaction retained for retry: {}", e.getClass().getSimpleName()); }
    }
    private void dispatchOne() {
        var rows = db.queryForList("""
                SELECT * FROM notification_jobs WHERE status='PENDING' AND next_attempt_at <= CURRENT_TIMESTAMP
                ORDER BY next_attempt_at, event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """);
        if (rows.isEmpty()) return;
        var job = rows.getFirst();
        int attempts = ((Number) job.get("attempts")).intValue();
        if (attempts >= maxAttempts) { deadLetter(job.get("event_id"), job.get("incident_id").toString(), (String) job.get("payload")); return; }
        try {
            var event = mapper.readValue((String) job.get("payload"), IncidentEvent.class);
            try (var ignored = MDC.putCloseable("correlationId", event.correlationId())) {
                channels.get((String) job.get("channel")).send(event);
            }
            db.update("UPDATE notification_jobs SET status='SENT', attempts=?, delivered_at=?, last_error=NULL WHERE event_id=?",
                    attempts + 1, Timestamp.from(Instant.now()), job.get("event_id"));
            metrics.counter("signaldesk.notification.sent").increment();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            db.update("UPDATE notification_jobs SET attempts=?, next_attempt_at=?, last_error=? WHERE event_id=?",
                    attempts + 1, Timestamp.from(Instant.now().plusSeconds(Math.min(60, 1L << Math.min(attempts, 6)))),
                    e.getClass().getSimpleName(), job.get("event_id"));
            metrics.counter("signaldesk.notification.failure").increment();
        }
    }
    private void deadLetter(Object id, String incidentId, String payload) {
        try {
            kafka.send("notification-failures.DLT", incidentId, payload).get(8, TimeUnit.SECONDS);
            db.update("UPDATE notification_jobs SET status='DEAD' WHERE event_id=?", id);
            metrics.counter("signaldesk.notification.dead").increment();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Do not call the provider again after exhaustion, even if Kafka is down.
            throw new IllegalStateException("DLT unconfirmed", e);
        }
    }
}
