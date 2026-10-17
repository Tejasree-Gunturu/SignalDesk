package io.signaldesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.signaldesk.escalation.EscalationWorker;
import io.signaldesk.events.*;
import io.signaldesk.incident.api.*;
import io.signaldesk.incident.application.IncidentService;
import io.signaldesk.incident.domain.Severity;
import io.signaldesk.incident.persistence.*;
import io.signaldesk.notifications.*;
import io.signaldesk.redis.RedisControls;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:workers;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class WorkerTest {
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired IncidentService incidents;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry metrics;
    @Autowired RedisControls redis;
    @Autowired IncidentRepository incidentRepo;
    @Autowired AuditEventRepository audits;
    @Autowired OutboxEventRepository outbox;
    KafkaTemplate<String, String> kafka;

    @BeforeEach void setup() {
        for (String table : List.of("notification_jobs", "idempotency_records", "audit_events", "outbox_events", "incidents"))
            db.update("DELETE FROM " + table);
        kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }
    IncidentResponse incident() { return incidents.create("acme", UUID.randomUUID().toString(),
            new CreateIncidentRequest("Checkout 5xx", "checkout", "Error rate", Severity.CRITICAL, "maya", "oncall-alex")); }
    NotificationConsumer consumer() { return new NotificationConsumer(mapper, db, metrics, "log"); }
    String payload(UUID id) { return db.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=?", String.class, id); }
    NotificationDispatcher dispatcher(NotificationChannel channel) {
        return new NotificationDispatcher(db, tx, new NotificationChannels(List.of(channel)), mapper, kafka, metrics, 2);
    }
    @Test void duplicateEventsCreateOneJobAndCrossTenantEventsAreRejected() throws Exception {
        var incident = incident();
        consumer().consume(payload(incident.id())); consumer().consume(payload(incident.id()));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_jobs", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> consumer().consume(payload(incident.id()).replace("acme", "beta")))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failedPublicationRollsBackAndNextAttemptPublishes() {
        var incident = incident();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        var publisher = new OutboxPublisher(db, kafka, tx, mapper, metrics, "incident-events");
        publisher.publish();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Integer.class)).isEqualTo(1);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        publisher.publish();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NOT NULL", Integer.class)).isEqualTo(1);
        verify(kafka, times(2)).send(eq("incident-events"), eq(incident.id().toString()), anyString());
    }
    @Test void exhaustedProviderDoesNotGetCalledAgainWhileDeadLetterBrokerIsDown() throws Exception {
        var incident = incident(); consumer().consume(payload(incident.id()));
        var channel = mock(NotificationChannel.class); when(channel.name()).thenReturn("log");
        doThrow(new IllegalStateException("provider failure")).when(channel).send(any());
        var worker = dispatcher(channel);
        worker.dispatch();
        db.update("UPDATE notification_jobs SET next_attempt_at=CURRENT_TIMESTAMP");
        worker.dispatch();
        db.update("UPDATE notification_jobs SET next_attempt_at=CURRENT_TIMESTAMP");
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
        worker.dispatch();
        assertThat(db.queryForObject("SELECT status FROM notification_jobs", String.class)).isEqualTo("PENDING");
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        worker.dispatch(); worker.dispatch();
        verify(channel, times(2)).send(any());
        assertThat(db.queryForObject("SELECT status FROM notification_jobs", String.class)).isEqualTo("DEAD");
    }
    @Test void deliveredJobIsNotDeliveredAgain() throws Exception {
        var incident = incident(); consumer().consume(payload(incident.id()));
        var channel = mock(NotificationChannel.class); when(channel.name()).thenReturn("log");
        var worker = dispatcher(channel); worker.dispatch(); worker.dispatch();
        verify(channel, times(1)).send(any());
        assertThat(db.queryForObject("SELECT status FROM notification_jobs", String.class)).isEqualTo("SENT");
    }
    @Test void escalationRecordsOnceAndExcludesAcknowledgedIncident() {
        var open = incident(); var acknowledged = incident();
        incidents.acknowledge(acknowledged.id(), "acme", new TransitionIncidentRequest("maya", null));
        db.update("UPDATE incidents SET sla_due_at=CURRENT_TIMESTAMP - INTERVAL '1' MINUTE");
        var worker = new EscalationWorker(redis, tx, db, incidentRepo, audits, outbox, Clock.systemUTC(), metrics);
        worker.scan(); worker.scan();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='incident.sla.breached'", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM audit_events WHERE action='SLA_BREACHED' AND incident_id=?", Integer.class, open.id())).isEqualTo(1);
    }
    @Test void existingMvpOutboxRecordsAreUpgraded() {
        var incident = incident();
        db.update("UPDATE outbox_events SET payload=? WHERE aggregate_id=?", "{\"status\":\"OPEN\",\"severity\":\"CRITICAL\"}", incident.id());
        new OutboxPublisher(db, kafka, tx, mapper, metrics, "incident-events").publish();
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("incident-events"), eq(incident.id().toString()), json.capture());
        assertThat(json.getValue()).contains("schemaVersion", "oncall-alex", "acme");
    }
}
