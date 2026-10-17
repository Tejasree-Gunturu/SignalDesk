package io.signaldesk.events;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** Real KRaft broker running in the test JVM; does not need Docker. */
class KafkaRoundTripTest {
    @Test void keyedEventSurvivesProducerAndConsumerRoundTrip() throws Exception {
        var broker = new EmbeddedKafkaKraftBroker(1, 3, "round-trip");
        try {
            broker.afterPropertiesSet();
            Properties producer = new Properties();
            producer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
            producer.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producer.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            producer.put(ProducerConfig.ACKS_CONFIG, "all");
            try (var kafka = new KafkaProducer<String, String>(producer)) {
                kafka.send(new ProducerRecord<>("round-trip", "incident-123", "event-123")).get(15, TimeUnit.SECONDS);
            }
            Properties consumer = new Properties();
            consumer.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString());
            consumer.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
            consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            consumer.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            consumer.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
            try (var kafka = new KafkaConsumer<String, String>(consumer)) {
                kafka.subscribe(List.of("round-trip"));
                List<ConsumerRecord<String, String>> records = new ArrayList<>();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (records.isEmpty() && System.nanoTime() < deadline) kafka.poll(Duration.ofMillis(500)).forEach(records::add);
                assertThat(records).hasSize(1);
                assertThat(records.getFirst().key()).isEqualTo("incident-123");
                assertThat(records.getFirst().value()).isEqualTo("event-123");
            }
        } finally { broker.destroy(); }
    }
}
