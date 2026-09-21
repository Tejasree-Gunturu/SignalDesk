package io.signaldesk.incident.persistence;
import io.signaldesk.incident.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, UUID> { Optional<IdempotencyRecord> findByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey); }
