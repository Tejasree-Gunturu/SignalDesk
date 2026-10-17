package io.signaldesk.events;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class IncidentEventTest {
    @Test void rejectsUnknownSchemaAndMissingTenant() {
        assertThatThrownBy(() -> new IncidentEvent(99, UUID.randomUUID(), UUID.randomUUID(), "acme", "incident.created",
                "OPEN", "HIGH", "maya", Instant.now(), "test").validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IncidentEvent(1, UUID.randomUUID(), UUID.randomUUID(), "", "incident.created",
                "OPEN", "HIGH", "maya", Instant.now(), "test").validate()).isInstanceOf(IllegalArgumentException.class);
    }
}
