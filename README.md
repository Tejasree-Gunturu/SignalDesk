# SignalDesk — Kafka & Redis extension

Extends commit `6f2d93ec315c22e0c521f0e536b2b0f40b23b9e8` from
[Tejasree-Gunturu/SignalDesk](https://github.com/Tejasree-Gunturu/SignalDesk).
Java 21 / Spring Boot 3.4.4 / PostgreSQL 16 / Kafka KRaft / Redis 7.4.
No RAG or LLM integration.

## Start with Docker (recommended)

Install and start Docker Desktop with at least 6 GB memory allocated.
From this folder:

```sh
docker compose up --build -d
docker compose logs -f app notification-worker escalation-worker
```

The first build downloads Maven dependencies and container images.
Open http://localhost:8080/swagger-ui/index.html and click **Authorize**.
Paste `dev-acme-admin-change-me` (without the word Bearer).

- API: http://localhost:8080/api/v1/incidents
- Health: http://localhost:8080/actuator/health
- Readiness: http://localhost:8080/actuator/health/readiness
- Metrics: http://localhost:8080/actuator/prometheus (admin bearer token required)
- Services: API, notification worker, escalation worker, PostgreSQL, Kafka, Redis.
- Notifications use a **log-only fake provider** by default. Check worker logs.
- All published ports bind to localhost. The brokers use local development configuration.

Stop without deleting data: `docker compose down`.
Docker volumes persist PostgreSQL, Kafka and Redis data.
Do not use `down -v` unless you intend to erase that data.

## Demo credentials and access

| Bearer token | User | Tenant | Role |
| --- | --- | --- | --- |
| dev-acme-admin-change-me | maya | acme | ADMIN |
| dev-acme-viewer-change-me | reader | acme | VIEWER |
| dev-beta-responder-change-me | responder | beta | RESPONDER |

These are sample service tokens, not passwords or JWTs. Copy `.env.example` to `.env`
and replace all three token values for a shared environment.
ADMIN and RESPONDER can change incidents in their tenant; VIEWER can only read.
Only ADMIN can read metrics. This version has tenant roles, not a separate team-membership management UI.

**API compatibility change:** incident paths and request shapes remain, but all API calls now need
`Authorization: Bearer TOKEN`. The token chooses the tenant. An optional `X-Tenant-Id`
must match that tenant. The existing `reporter` and `actor` request fields are accepted and
validated for compatibility, but audit identity and acknowledgement assignee come from the token.

## Create, acknowledge, resolve, close

```sh
curl -i http://localhost:8080/api/v1/incidents \
  -H 'Authorization: Bearer dev-acme-admin-change-me' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: checkout-demo-1' \
  -d '{"title":"Checkout 5xx","serviceName":"checkout","description":"Error rate above 10%","severity":"CRITICAL","reporter":"maya"}'
```

Copy the returned UUID in place of INCIDENT_ID:

```sh
curl http://localhost:8080/api/v1/incidents/INCIDENT_ID/acknowledge \
  -H 'Authorization: Bearer dev-acme-admin-change-me' \
  -H 'Content-Type: application/json' -d '{"actor":"maya"}'
curl http://localhost:8080/api/v1/incidents/INCIDENT_ID/resolve \
  -H 'Authorization: Bearer dev-acme-admin-change-me' \
  -H 'Content-Type: application/json' -d '{"actor":"maya","note":"Rolled back deployment"}'
curl http://localhost:8080/api/v1/incidents/INCIDENT_ID/close \
  -H 'Authorization: Bearer dev-acme-admin-change-me' \
  -H 'Content-Type: application/json' -d '{"actor":"maya"}'
curl http://localhost:8080/api/v1/incidents/INCIDENT_ID/audit \
  -H 'Authorization: Bearer dev-acme-admin-change-me'
curl http://localhost:8080/api/v1/incidents/INCIDENT_ID/notifications \
  -H 'Authorization: Bearer dev-acme-admin-change-me'
```

Run a full demo with Python 3 (standard library only):

```sh
python3 scripts/smoke.py
```

## Implemented extensions

- Transactional outbox publisher waits for Kafka acknowledgement before marking a row published.
- Three-partition `incident-events` topic, keyed by incident UUID.
- Separate notification worker: durable job per event UUID, duplicate event suppression, bounded
  provider retries (1/2/4-second delays at default four attempts), and `notification-failures.DLT`.
- Consumer-processing retries with bounded exponential backoff; invalid/poison messages go to
  `incident-events.DLT`. Failed DLT publication does not acknowledge the source record.
- Configurable log/webhook notification adapters; HTTP connect/request timeouts and event-ID
  idempotency headers. The webhook is a generic JSON receiver, not a direct Slack/email adapter.
- Separate escalation worker: overdue OPEN incidents generate one acknowledgement-SLA breach event.
  Deadlines: CRITICAL 5 min, HIGH 15 min, MEDIUM 60 min, LOW 4 hours.
  Acknowledging the incident satisfies this SLA. Repeated scans do not create repeated breaches.
- Redis incident response cache (60-second TTL), atomic per-user/tenant rate limiter
  (120 requests per minute), and ownership-checked SLA worker lock.
- Cache reads check the incident's current database version first. Stale cache fills cannot be
  served as a newer version. This still performs a small database query on a cache hit.
- PostgreSQL idempotency protects concurrent retries and rejects reuse of a key with different content.
  Existing pre-upgrade keys have no fingerprint and retain their prior replay behaviour.
- Round-robin and optional least-loaded assignment (`ASSIGNMENT_POLICY=least-loaded` in `.env`
  for Compose or in your IDE environment). Both use a sample responder roster.
- Structured JSON logs, correlation IDs, HTTP/Micrometer metrics, secured Prometheus endpoint.
- Unit/API/worker tests, a real embedded Kafka test, Docker integration tests, and GitHub Actions.

## Tests and development

JDK 21 required. Maven Wrapper downloads Maven on first use.

```sh
./mvnw test
./mvnw -Pintegration verify  # Requires Docker; real PostgreSQL + Kafka + Redis
```

Windows: use `mvnw.cmd` instead. GitHub Actions runs both test suites.

For IDE use:
```sh
docker compose up -d postgres kafka redis
./mvnw spring-boot:run
```
In two other terminals:
```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=notification-worker -Dspring-boot.run.arguments=--server.port=8081
./mvnw spring-boot:run -Dspring-boot.run.profiles=escalation-worker -Dspring-boot.run.arguments=--server.port=8082
```

## Existing database

V1 is untouched. V2 adds worker tables, request fingerprints, and SLA fields.
To reuse your old Compose database, stop the old application, copy this project to a separate folder,
and run Compose with your **old Compose project name**, for example
`docker compose -p signaldesk-mvp up --build -d`. Check the old volume/project name first;
the old database volume must have used the `signaldesk-postgres` key. Otherwise restore a
database backup into the new database. The default `signaldesk-extended` project starts fresh.

Pending MVP outbox rows are upgraded to the new envelope when published.
Historical assignees were not present in old payloads, so their current assignee is used.
Older OPEN incidents receive a 60-minute acknowledgement deadline relative to creation.

## Reliability boundaries

Delivery is **at least once**. Database deduplication prevents duplicate jobs, but a crash after an
external webhook succeeds and before the local transaction commits can repeat the webhook.
The receiver must honour the event-ID `Idempotency-Key` for external deduplication.

Kafka downtime leaves the outbox pending; incident writes continue. Redis downtime bypasses
cache and rate limiting, and pauses SLA scans until Redis recovers. PostgreSQL downtime fails
writes and pauses workers. A Redis lock is an efficiency control; database row locks and the
escalation marker enforce correctness.

This is a local learning deployment with one broker and static credentials. External identity
providers, real team rosters, multiple escalation stages, broker TLS/ACLs, HA, production token
rotation, and RAG remain separate work. See [architecture](docs/ARCHITECTURE.md),
[failure drills](docs/FAILURE-DRILLS.md), and [verification](docs/VERIFICATION.md).
