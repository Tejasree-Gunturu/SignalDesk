CREATE TABLE incidents (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    title VARCHAR(180) NOT NULL,
    service_name VARCHAR(100) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    assigned_to VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT incident_severity_check CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT incident_status_check CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'CLOSED'))
);
CREATE INDEX idx_incidents_tenant_created ON incidents (tenant_id, created_at DESC);
CREATE INDEX idx_incidents_tenant_status ON incidents (tenant_id, status);
CREATE INDEX idx_incidents_tenant_severity ON incidents (tenant_id, severity);
CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incidents(id),
    tenant_id VARCHAR(100) NOT NULL,
    action VARCHAR(80) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_audit_events_incident ON audit_events (incident_id, occurred_at);
CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES incidents(id),
    tenant_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE NULL
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (published_at, created_at);
CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    incident_id UUID NOT NULL REFERENCES incidents(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_idempotency_tenant_key UNIQUE (tenant_id, idempotency_key)
);
