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
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real infrastructure. Intentionally FAILS (not silently skips) if Docker is unavailable. */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class SystemIT {
    @BeforeEach void cleanJobs() {
        // Use unique incidents per test; retire only prior PENDING jobs so manual dispatch targets this test.
        db.update("UPDATE notification_jobs SET status='DEAD' WHERE status='PENDING'");
    }
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container static org.testcontainers.kafka.KafkaContainer kafkaBroker = new org.testcontainers.kafka.KafkaContainer("apache/kafka-native:3.8.0");
    @Container static GenericContainer<?> redisServer = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", kafkaBroker::getBootstrapServers);
        registry.add("spring.kafka.admin.auto-create", () -> true);
        registry.add("signaldesk.kafka.enabled", () -> true);
        registry.add("signaldesk.consumer.enabled", () -> true);
        registry.add("signaldesk.redis.enabled", () -> true);
        registry.add("spring.data.redis.host", redisServer::getHost);
        registry.add("spring.data.redis.port", () -> redisServer.getMappedPort(6379));
    }
    @Autowired JdbcTemplate db;
    @Autowired TransactionTemplate tx;
    @Autowired IncidentService incidents;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry metrics;
    @Autowired NotificationConsumer consumer;
    @Autowired RedisControls redis;
    @Autowired IncidentRepository incidentRepo;
    @Autowired AuditEventRepository audits;
    @Autowired OutboxEventRepository outbox;

    IncidentResponse incident() { return incidents.create("acme", UUID.randomUUID().toString(),
            new CreateIncidentRequest("Checkout 5xx", "checkout", "Error rate", Severity.CRITICAL, "maya", "oncall-alex")); }
    OutboxPublisher publisher() { return new OutboxPublisher(db, kafka, tx, mapper, metrics, "incident-events"); }
    NotificationDispatcher dispatcher(NotificationChannel channel) {
        return new NotificationDispatcher(db, tx, new NotificationChannels(List.of(channel)), mapper, kafka, metrics, 2);
    }
    @Test void outboxKafkaConsumerDeduplicationAndDelivery() throws Exception {
        var incident = incident();
        publisher().publish();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                db.queryForObject("SELECT COUNT(*) FROM notification_jobs WHERE incident_id=?", Integer.class, incident.id())).isEqualTo(1));
        String payload = db.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=?", String.class, incident.id());
        consumer.consume(payload); consumer.consume(payload);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM notification_jobs WHERE incident_id=?", Integer.class, incident.id())).isEqualTo(1);
        for (int i = 0; i < 20; i++) dispatcher(new LogNotificationChannel()).dispatch();
        assertThat(db.queryForObject("SELECT status FROM notification_jobs WHERE incident_id=?", String.class, incident.id())).isEqualTo("SENT");
    }
    @Test void failedProviderHasBoundedRetriesAndDurableDeadStatus() throws Exception {
        var incident = incident();
        String payload = db.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=?", String.class, incident.id());
        consumer.consume(payload);
        db.update("UPDATE notification_jobs SET channel='failure' WHERE incident_id=?", incident.id());
        var broken = new NotificationChannel() {
            public String name() { return "failure"; }
            public void send(IncidentEvent e) { throw new IllegalStateException("provider unavailable"); }
        };
        var dispatcher = dispatcher(broken);
        for (int i = 0; i < 50; i++) {
            db.update("UPDATE notification_jobs SET next_attempt_at=CURRENT_TIMESTAMP WHERE incident_id=?", incident.id());
            dispatcher.dispatch();
            if ("DEAD".equals(db.queryForObject("SELECT status FROM notification_jobs WHERE incident_id=?", String.class, incident.id()))) break;
        }
        assertThat(db.queryForObject("SELECT status FROM notification_jobs WHERE incident_id=?", String.class, incident.id())).isEqualTo("DEAD");
        assertThat(db.queryForObject("SELECT attempts FROM notification_jobs WHERE incident_id=?", Integer.class, incident.id())).isEqualTo(2);
    }
    @Test void escalationIsRecordedOnceAndAcknowledgedIncidentsAreExcluded() {
        var open = incident(); var acknowledged = incident();
        incidents.acknowledge(acknowledged.id(), "acme", new TransitionIncidentRequest("maya", null));
        db.update("UPDATE incidents SET sla_due_at=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id IN (?, ?)", open.id(), acknowledged.id());
        var worker = new EscalationWorker(redis, tx, db, incidentRepo, audits, outbox, Clock.systemUTC(), metrics);
        worker.scan(); worker.scan();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='incident.sla.breached'", Integer.class, open.id())).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND event_type='incident.sla.breached'", Integer.class, acknowledged.id())).isZero();
    }
    @Test void versionedCacheDoesNotServeStateBeforeAcknowledgement() {
        var incident = incident();
        assertThat(incidents.get(incident.id(), "acme").version()).isZero();
        assertThat(incidents.get(incident.id(), "acme").version()).isZero();
        incidents.acknowledge(incident.id(), "acme", new TransitionIncidentRequest("maya", null));
        assertThat(incidents.get(incident.id(), "acme").version()).isEqualTo(1);
        assertThat(redis.lock("test-lease")).isPresent();
        assertThat(redis.lock("test-lease")).isEmpty();
    }
    @Test void kafkaOutageKeepsOutboxPending() {
        // Unreachable producer isolates this failure from the broker used by the other tests.
        var props = new HashMap<String, Object>();
        props.put("bootstrap.servers", "127.0.0.1:1");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("max.block.ms", 500);
        var factory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(props);
        try {
            var incident = incident();
            new OutboxPublisher(db, new KafkaTemplate<>(factory), tx, mapper, metrics, "incident-events").publish();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id=? AND published_at IS NULL", Integer.class, incident.id())).isEqualTo(1);
        } finally { factory.destroy(); }
    }
}
