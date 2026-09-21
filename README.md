# SignalDesk MVP

SignalDesk is an API-first incident-management starter built from the 60-day roadmap. This first runnable model covers the reliable core before Kafka, Redis, authentication, and RAG are added: incident creation and lifecycle changes, tenant-scoped queries, audit history, idempotent creates, optimistic locking, assignment strategy, and a transactional outbox.

## Run it

Prerequisite: Docker Desktop (or Docker Engine with Compose).

```bash
docker compose up --build
```

The API is ready at `http://localhost:8080`. Swagger UI is at `http://localhost:8080/swagger-ui/index.html`; health is at `http://localhost:8080/actuator/health`.

To stop while preserving the database: `docker compose down`. To reset the local database completely: `docker compose down -v`.

For an IDE workflow, use JDK 21 and run `mvn spring-boot:run` with a PostgreSQL database available on port 5432. `docker compose up postgres` is sufficient for that option.

## Try the primary flow

Create an incident. `Idempotency-Key` makes retrying the same request safe within a tenant.

```bash
curl -i -X POST http://localhost:8080/api/v1/incidents \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: acme' \
  -H 'Idempotency-Key: checkout-5xx-001' \
  -d '{"title":"Checkout API is returning 5xx errors","serviceName":"checkout-api","description":"Checkout error rate has exceeded 10%.","severity":"CRITICAL","reporter":"maya"}'
```

Copy the `id` from the response, then acknowledge and resolve it:

```bash
curl -X POST http://localhost:8080/api/v1/incidents/INCIDENT_ID/acknowledge -H 'Content-Type: application/json' -H 'X-Tenant-Id: acme' -d '{"actor":"maya"}'
curl -X POST http://localhost:8080/api/v1/incidents/INCIDENT_ID/resolve -H 'Content-Type: application/json' -H 'X-Tenant-Id: acme' -d '{"actor":"maya","note":"Rolled back the faulty deployment."}'
curl http://localhost:8080/api/v1/incidents/INCIDENT_ID/audit -H 'X-Tenant-Id: acme'
```

## Design notes

```
REST API -> Incident application service -> PostgreSQL (source of truth)
                                           |- incident audit history
                                           `- outbox_events (same transaction)
```

- **State rules:** OPEN -> ACKNOWLEDGED -> RESOLVED -> CLOSED. Resolution is allowed from OPEN or ACKNOWLEDGED for pragmatic incident handling.
- **Concurrency:** JPA's `@Version` detects lost updates; conflict responses use HTTP 409.
- **Retry safety:** a unique tenant/idempotency-key record returns the original incident for a repeated create request.
- **Outbox:** every creation and lifecycle change creates an unpublished event in the same PostgreSQL transaction. A future worker can safely claim and publish these to Kafka.
- **Tenant boundary:** all reads and mutations require a matching `X-Tenant-Id` (defaults to `demo` for local exploration).

## What comes next

1. Add a scheduled outbox publisher and Kafka topics/consumers.
2. Add idempotent notification and escalation workers, retry policy, and dead-letter handling.
3. Add Redis caching, rate limiting, and distributed locks.
4. Add authentication/RBAC and tenant-aware RAG ingestion/retrieval with cited answers.
