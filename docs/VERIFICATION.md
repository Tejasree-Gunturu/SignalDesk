# Verification record

Verified on 2026-09-23 using Temurin JDK 21 and Maven Wrapper 3.9.9 on macOS arm64.
Source base: upstream commit `6f2d93ec315c22e0c521f0e536b2b0f40b23b9e8`.

## Passed

`./mvnw clean verify` completed successfully: **20 tests, 0 failures, 0 errors, 0 skipped**.
The executable Spring Boot JAR was built successfully. Unit/default-suite database tests use H2
in PostgreSQL compatibility mode. Kafka tests start real in-process KRaft brokers.

| Suite | Tests | Evidence |
| --- | ---: | --- |
| IncidentControllerTest | 8 | API lifecycle, validation, authenticated audit identity, role/tenant denial, pagination, request-fingerprint conflict, simultaneous idempotent creates, actual JPA stale-write rejection |
| WorkerTest | 6 | Job deduplication, tenant checks, outbox rollback/recovery, bounded delivery attempts, DLT outage, one-time escalation, legacy event conversion |
| RedisControlsTest | 2 | Fail-open rate/cache behavior, fail-closed lock acquisition, over-budget response (mock Redis connection) |
| IncidentEventTest | 1 | Invalid schema and missing tenant rejected |
| KafkaRoundTripTest | 1 | Real broker producer/consumer keyed message round trip |
| EmbeddedFlowTest | 2 | Actual scheduled outbox + listener + dispatcher deliver four lifecycle events; replay creates no duplicate jobs; malformed JSON reaches DLT |

Additional checks:

- Main application started with the test database on localhost:18080.
- `/v3/api-docs` returned 200, health returned UP, authenticated create returned an OPEN incident.
- Unauthenticated API request returned 401. Admin Prometheus returned 200; viewer returned 403.
- Compose and application YAML parsed; Python demo/load scripts parsed.
- V1 database migration matches the upstream file byte-for-byte.
- Archive integrity and per-file SHA-256 manifest were checked during packaging.

## Limited local HTTP baseline

Ran `scripts/load.py --requests 50 --concurrency 5` against one seeded incident, using a live
Spring Boot HTTP server, test credentials and in-memory H2. Redis/Kafka/workers were disabled
for this measurement. This is a small development sanity check, **not a PostgreSQL/Kafka production
capacity result**, and includes cold request effects.

| Result | Observed |
| --- | ---: |
| HTTP 200 | 50 / 50 |
| Elapsed | 0.148 seconds |
| Successful reads/second | 337.8 |
| p50 | 5.19 ms |
| p95 | 89.80 ms |

Use the same script against Docker to measure the real deployment. The default rate limit is
120 requests/minute per account; 429 responses are reported separately rather than counted as success.

## Not verified here

The `-Pintegration` suite was attempted and could not start: Testcontainers reported
`Could not find a valid Docker environment` because Docker and `/var/run/docker.sock` are absent.
Consequently, container startup, real PostgreSQL execution, Redis Lua behavior against a real
server, multi-process worker deployment and full infrastructure-outage drills have **not** passed
on this machine. H2 tests are not a substitute for those checks.

`SystemIT` contains five Docker integration tests for real PostgreSQL/Kafka/Redis: async flow and
deduplication, provider exhaustion, SLA behavior, versioned cache/lock behavior, and broker-outage
outbox retention. It intentionally fails when Docker is unavailable instead of silently skipping.
Run `./mvnw -Pintegration verify` with Docker running. GitHub Actions is configured to run it on Ubuntu;
the workflow has not been pushed or run on GitHub by this task.

The real-webhook adapter has compile coverage and a timeout/retry implementation; no external
notification endpoint was contacted. The default logging provider was used in the live Kafka flow.
