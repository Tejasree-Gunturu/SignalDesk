package io.signaldesk;

import io.signaldesk.incident.api.*;
import io.signaldesk.incident.application.IncidentService;
import io.signaldesk.incident.domain.Severity;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Boots the actual application consumer, publisher and dispatcher against H2 and real embedded Kafka. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fullflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "signaldesk.kafka.enabled=true", "spring.kafka.admin.auto-create=true", "signaldesk.outbox.enabled=true",
        "signaldesk.consumer.enabled=true", "signaldesk.dispatcher.enabled=true", "signaldesk.outbox.poll-ms=200",
        "signaldesk.notifications.poll-ms=100"})
@ActiveProfiles("test")
@EmbeddedKafka(kraft = true, partitions = 3, topics = {"incident-events", "incident-events.DLT", "notification-failures.DLT"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class EmbeddedFlowTest {
    @Autowired IncidentService incidents;
    @Autowired JdbcTemplate db;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired EmbeddedKafkaBroker broker;

    @Test void completeAsyncFlowAndRedeliveryProduceOneJobPerEvent() throws Exception {
        var incident = incidents.create("acme", UUID.randomUUID().toString(),
                new CreateIncidentRequest("Real async flow", "api", "5xx", Severity.HIGH, "maya", null));
        incidents.acknowledge(incident.id(), "acme", new TransitionIncidentRequest("maya", null));
        incidents.resolve(incident.id(), "acme", new TransitionIncidentRequest("maya", "fixed"));
        incidents.close(incident.id(), "acme", new TransitionIncidentRequest("maya", null));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM notification_jobs WHERE incident_id=? AND status='SENT'", Integer.class, incident.id())).isEqualTo(4));
        String payload = db.queryForObject("SELECT payload FROM outbox_events WHERE aggregate_id=? AND event_type='incident.created'", String.class, incident.id());
        kafka.send("incident-events", incident.id().toString(), payload).get(10, TimeUnit.SECONDS);
        kafka.send("incident-events", incident.id().toString(), payload).get(10, TimeUnit.SECONDS);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM notification_jobs WHERE incident_id=?", Integer.class, incident.id())).isEqualTo(4));
    }
    @Test void malformedJsonIsRecoveredToDeadLetterTopic() throws Exception {
        String key = UUID.randomUUID().toString();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(props)) {
            consumer.subscribe(List.of("incident-events.DLT"));
            kafka.send("incident-events", key, "not-json").get(10, TimeUnit.SECONDS);
            List<String> found = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (found.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (record.key().equals(key)) found.add(record.value());
                });
            }
            assertThat(found).containsExactly("not-json");
        }
    }
}
