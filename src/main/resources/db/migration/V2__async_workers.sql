ALTER TABLE idempotency_records ADD COLUMN request_hash VARCHAR(64);
ALTER TABLE incidents ADD COLUMN sla_due_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE incidents ADD COLUMN escalated_at TIMESTAMP WITH TIME ZONE;
-- Existing incidents receive a conservative default; new ones use severity-specific policies.
UPDATE incidents SET sla_due_at = created_at + INTERVAL '60' MINUTE WHERE sla_due_at IS NULL;
CREATE INDEX idx_incident_sla ON incidents (status, sla_due_at);

CREATE TABLE worker_locks (name VARCHAR(80) PRIMARY KEY);
INSERT INTO worker_locks(name) VALUES ('outbox');
CREATE TABLE notification_jobs (
    event_id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents(id),
    tenant_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    recipient VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    channel VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    delivered_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500),
    CONSTRAINT notification_status_check CHECK (status IN ('PENDING', 'SENT', 'DEAD'))
);
CREATE INDEX idx_notification_due ON notification_jobs (status, next_attempt_at);
CREATE INDEX idx_notification_tenant_incident ON notification_jobs (tenant_id, incident_id);
