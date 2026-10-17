# 0.2.0 — Kafka and Redis extension

Built on the existing SignalDesk repository at commit 6f2d93e.

- Kafka outbox publication, stable event envelopes, backward conversion of pending MVP payloads.
- Separate notification and SLA process profiles with durable state, retries and DLTs.
- Redis versioned response cache, per-account request limit, ownership-token SLA lock.
- Static bearer-token authentication with tenant-scoped viewer/responder/admin roles.
- Authenticated actors replace client-supplied identities in audit and acknowledgement operations.
- Concurrent idempotency replay, request fingerprint conflict checks, current response versions.
- Least-loaded assignment option alongside existing round-robin policy.
- JSON logs, correlation IDs, health/readiness, admin metrics, notification-job API.
- Docker Compose, Maven Wrapper, 20 default tests, Docker integration suite, CI, demo/load scripts and docs.

V1 schema is preserved. V2 performs additive migration. Incident endpoint paths and lifecycle remain.
Requests now require a bearer token. New deployment defaults and migration steps are in README.md.
RAG/LLM functionality is not included. See docs/VERIFICATION.md for checks performed and limitations.
