package io.signaldesk.incident.persistence;
import io.signaldesk.incident.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> { }
