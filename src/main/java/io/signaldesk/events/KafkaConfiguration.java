package io.signaldesk.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
@ConditionalOnProperty(name = "signaldesk.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfiguration {
    @Bean NewTopic incidentsTopic(@Value("${signaldesk.kafka.topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }
    @Bean NewTopic deadLetters(@Value("${signaldesk.kafka.topic}") String topic) {
        return TopicBuilder.name(topic + ".DLT").partitions(3).replicas(1).build();
    }
    @Bean NewTopic notificationDeadLetters() {
        return TopicBuilder.name("notification-failures.DLT").partitions(3).replicas(1).build();
    }
    @Bean DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                               @Value("${signaldesk.kafka.topic}") String topic) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, error) -> new TopicPartition(topic + ".DLT", record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        var backoff = new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(500); backoff.setMultiplier(2); backoff.setMaxInterval(5000);
        return new DefaultErrorHandler(recoverer, backoff);
    }
}
