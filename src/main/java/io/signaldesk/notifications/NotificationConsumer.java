package io.signaldesk.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.signaldesk.events.IncidentEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;

@Component
@ConditionalOnProperty(name = "signaldesk.consumer.enabled", havingValue = "true")
public class NotificationConsumer {
    private final ObjectMapper mapper;
    private final JdbcTemplate db;
    private final MeterRegistry metrics;
    private final String channel;
    public NotificationConsumer(ObjectMapper mapper, JdbcTemplate db, MeterRegistry metrics,
                                @Value("${signaldesk.notifications.channel:log}") String channel) {
        this.mapper = mapper; this.db = db; this.metrics = metrics; this.channel = channel;
    }
    @Transactional
    @KafkaListener(topics = "${signaldesk.kafka.topic}", groupId = "signaldesk-notifications-v1")
    public void consume(String json) throws Exception {
        IncidentEvent event = mapper.readValue(json, IncidentEvent.class); event.validate();
        Integer owners = db.queryForObject("SELECT COUNT(*) FROM incidents WHERE id=? AND tenant_id=?",
                Integer.class, event.incidentId(), event.tenantId());
        if (owners == null || owners != 1) throw new IllegalArgumentException("Event incident/tenant mismatch");
        // The job IS the durable deduplication receipt. Kafka offset is committed only after this transaction.
        int inserted = db.update("""
                INSERT INTO notification_jobs(event_id, incident_id, tenant_id, event_type, recipient, payload,
                    channel, status, attempts, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?) ON CONFLICT DO NOTHING
                """, event.eventId(), event.incidentId(), event.tenantId(), event.type(), event.assignee(), json,
                channel, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        metrics.counter(inserted == 0 ? "signaldesk.consumer.duplicate" : "signaldesk.consumer.accepted").increment();
    }
}
