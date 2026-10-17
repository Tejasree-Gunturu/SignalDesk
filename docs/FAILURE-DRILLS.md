# Failure drills

Use a disposable local deployment. Commands assume default credentials/database names and the
project root. No drill requires removing volumes. Set any environment overrides in your shell.

1. **Kafka down:** `docker compose stop kafka`. Create an incident using the README curl command.
   It should still return 201. Check pending outbox rows with
   `docker compose exec postgres psql -U signaldesk -d signaldesk -c "SELECT count(*) FROM outbox_events WHERE published_at IS NULL;"`.
   Run `docker compose start kafka`; pending records should drain after reconnection.
2. **Redis down:** `docker compose stop redis`. Create/get/resolve still use PostgreSQL.
   Cache/rate limits bypass Redis, and SLA scans pause. Start Redis again to resume.
3. **Consumer restart:** `docker compose restart notification-worker`. Completed jobs retain SENT;
   Kafka replay creates no additional row for an existing event UUID.
4. **Duplicate event:** produce an existing complete envelope (from `outbox_events.payload`) twice
   to `incident-events`, preserving eventId. The job table should contain one row for that eventId.
5. **Poison event:**
   `printf '%s\n' '{"invalid":true}' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:19092 --topic incident-events`.
   Inspect `incident-events.DLT` using Kafka's console consumer with `--from-beginning`.
6. **Provider failure:** configure NOTIFICATION_CHANNEL=webhook and
   NOTIFICATION_WEBHOOK_URL=http://127.0.0.1:1 then recreate the notification worker.
   A new incident's job should reach 4 attempts, then DEAD after DLT acknowledgement.
   Restore channel=log and recreate the worker after this drill. Existing DEAD jobs remain inspectable.
7. **Acknowledgement SLA:** leave a CRITICAL incident OPEN for more than 5 minutes. Its audit history
   gains one SLA_BREACHED event and a notification job. Repeated scans must not add another breach.
8. **Tenant/role isolation:** try a beta token with an acme incident UUID (404), or supply a mismatched
   X-Tenant-Id (403), or create with the viewer token (403).

Inspect failed delivery envelopes with:

```sh
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:19092 --topic notification-failures.DLT --from-beginning
```

`incident-events.DLT` is for malformed/unprocessable consumer events. Investigate and repair before
replaying an event, keeping the eventId for idempotency. `notification-failures.DLT` corresponds to
a durable DEAD job. Replaying that Kafka envelope alone will not reset the job because deduplication
is deliberate. Operational retry of DEAD jobs requires a reviewed database update after the provider
issue is fixed; no unauthenticated reset endpoint is exposed.
