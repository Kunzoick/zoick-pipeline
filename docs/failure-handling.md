# Failure Handling — zoick-pipeline

Every failure scenario in this system is explicitly handled, not mentioned
in comments. This document describes each scenario, how it is triggered,
what the system does, and how to verify it.

---

## Failure Scenarios

### Scenario 1 — Worker Crashes Mid-Processing

**What happens:** The Pipeline application crashes or is killed while a
`ProcessingRecord` is in `PROCESSING` status.

**How the system handles it:** On restart, the `ProcessingWatchdog` runs
within 5 seconds. It queries for records in `PROCESSING` status whose
`updated_at` timestamp is older than the `processing-timeout-ms` threshold
(default 8 seconds). It delegates to `RetryHandler` which increments
`retry_count` and transitions to `PENDING_RETRY`. The `RetryPoller` picks
it up on the next cycle.

**Verification:** Set `simulate-failure-on-attempt: 2` in `application.yml`.
Submit an incident. Watch `attempt=1` fail, `attempt=2` fail, `attempt=3`
succeed with `status=COMPLETED`. Check `processing_records` — `retry_count=2`,
`status=COMPLETED`.

**Key log lines:**
```
[PIPELINE] status=PROCESSING message=Starting processing
[PIPELINE] message=Processing failed, delegating to RetryHandler
[PIPELINE] attempt=1 backoffSeconds=2 status=PENDING_RETRY
[PIPELINE] attempt=2 message=Retrying processing
[PIPELINE] status=COMPLETED message=Processing complete
```

---

### Scenario 2 — Poison Message (Always Fails)

**What happens:** A message arrives that will never succeed regardless of
how many times it is retried — corrupted business logic, invalid state
transition, unrecoverable processing error.

**How the system handles it:** `RetryHandler` increments `retry_count` on
each failure. At `retry_count >= 3` (MAX_ATTEMPTS), `RetryHandler` publishes
the event payload to `incident.exchange` with routing key `incident.dlq`,
marks `ProcessingRecord` as `FAILED`. `DLQConsumer` consumes from
`incident.dlq` and marks the record `DEAD`.

**Verification:** Set `simulate-failure: true`, `simulate-failure-on-attempt: 0`
in `application.yml`. Submit an incident. Watch three failures then
`status=FAILED`, `status=DEAD`.

**Key log lines:**
```
[PIPELINE] attempt=1 status=PENDING_RETRY message=Processing failed
[PIPELINE] attempt=2 status=PENDING_RETRY message=Processing failed
[PIPELINE] retryCount=3 status=FAILED message=Max retries exceeded, routed to DLQ
[PIPELINE] status=DEAD message=Event reached DLQ — manual intervention required
```

**Recovery:** Query `SELECT * FROM processing_records WHERE status = 'DEAD'`
to identify affected incidents. Manual investigation required before any
replay.

---

### Scenario 3 — Duplicate Event Arrives

**What happens:** The same event is delivered more than once — normal
behaviour under RabbitMQ's at-least-once delivery guarantee.

**How the system handles it:** `ProcessingService` calls
`processingRepository.findByEventId(eventId)` before any processing. If
a `COMPLETED` or `DEAD` record already exists for that `eventId`, the
message is acknowledged and the method returns immediately. No processing
occurs. No database writes occur. No side effects.

**Verification:** Find a `COMPLETED` record in `processing_records`. Reset
its corresponding outbox row to `PENDING` in `outbox_events`. The poller
republishes the same event. Watch the Pipeline log for `status=DUPLICATE`.

**Key log line:**
```
[PIPELINE] status=DUPLICATE message=Event already processed, skipping
```

**Why this works:** The `eventId` is the deduplication key — not the
`incidentId`. The same incident should never produce two events, but if
the outbox poller republishes a row, the `eventId` is the same UUID that
was originally published. The idempotency check catches it correctly.

---

### Scenario 4 — RabbitMQ Broker Goes Down

**What happens:** The RabbitMQ container stops while the Trust API is
running and processing incidents normally.

**How the system handles it:** The Trust API's HTTP response is completely
unaffected. The incident is persisted to the database and an outbox row
is written in the same transaction — before any contact with the broker.
The `OutboxPoller` attempts to publish on its next cycle, gets
`Connection refused`, logs a WARN, increments `retry_count` on the outbox
row, and retries on the next cycle. This continues until the broker
recovers or the outbox row reaches `max-retry-count` (default 5).

**Verification:** Stop the RabbitMQ container. Submit an incident via
Postman. Verify `201 Created` is returned. Watch Trust API logs for
`Connection refused` WARN on each poller cycle. Restart the container.
Watch the outbox row get published and the Pipeline consumer receive it.

**Recovery if outbox row hits FAILED:**
```sql
UPDATE outbox_events 
SET status = 'PENDING', retry_count = 0, last_attempted_at = NULL 
WHERE id = '<outbox-row-id>';
```

**Key log lines:**
```
[OUTBOX] status=PENDING message=Outbox row written
[OUTBOX] attempt=1 message=Publish failed, will retry. reason=Connection refused
[OUTBOX] status=PUBLISHED message=Event confirmed by broker  ← after recovery
```

---

### Scenario 5 — Processing Takes Too Long

**What happens:** A message takes longer than the configured timeout
threshold to process — network call to an external service, slow query,
or resource contention.

**How the system handles it:** `ProcessingStatusService.transitionToProcessing()`
commits `PROCESSING` status to the database immediately in its own
`REQUIRES_NEW` transaction — visible to other threads before the processing
begins. The `ProcessingWatchdog` runs every 5 seconds and queries for
records in `PROCESSING` status whose `updated_at` is older than
`processing-timeout-ms` (default 8000ms). It delegates to `RetryHandler`.
The original processing thread detects via `updatedAt` comparison that the
record was touched externally and aborts silently.

**Verification:** Set `simulate-slow-processing-ms: 30000` in `application.yml`.
Submit an incident. Watch watchdog fire at 8 seconds, retry count increment,
eventually `status=DEAD` after 3 timeout-exhausted retries.

**Key log lines:**
```
[PIPELINE] message=Simulating slow processing for 30000ms
[PIPELINE] Watchdog found 1 stuck records
[PIPELINE] message=Processing timed out after 8000ms — delegating to RetryHandler
[PIPELINE] message=Processing aborted — watchdog changed state to PROCESSING
```

**Why REQUIRES_NEW matters here:** Without an immediate commit of
`PROCESSING` status, the watchdog queries the database mid-sleep and sees
the previous status — it cannot detect the stuck record. `REQUIRES_NEW`
opens a new transaction, commits the status immediately, and closes — making
it visible to the watchdog while the processing thread is still running.

---

### Scenario 6 — Database Unavailable

**What happens:** The database goes down while the Pipeline is running and
processing messages.

**How the system handles it:** `IncidentEventConsumer` distinguishes between
two failure types. Deserialization failures throw
`AmqpRejectAndDontRequeueException` — Spring AMQP routes to DLQ (poison
message). Database failures (`DataAccessException`) throw plain
`RuntimeException` — Spring AMQP requeues the message. HikariCP holds the
connection attempt for up to 30 seconds before timing out. If the database
recovers within that window, the connection succeeds and processing completes.

**Verification:** Stop MySQL while the Pipeline is running. Publish a
message manually via the RabbitMQ management UI to `incident.processing.queue`.
Watch HikariCP connection validation warnings. Restart MySQL. Watch the
message process successfully.

**Key log lines:**
```
HikariPool-1 - Failed to validate connection (No operations allowed after connection closed)
HikariPool-1 - Connection is not available, request timed out after 30016ms
[PIPELINE] message=Database unavailable — requeuing message
```

---

## Retry Strategy

| Parameter | Value | Configurable |
|---|---|---|
| Max attempts | 3 | No — hardcoded in `RetryHandler.MAX_ATTEMPTS` |
| Backoff | Exponential: 2^attempt seconds | No |
| Attempt 1 backoff | 2 seconds | — |
| Attempt 2 backoff | 4 seconds | — |
| Attempt 3 backoff | 8 seconds | — |
| Poller interval | 5 seconds | `pipeline.retry-poll-interval-ms` |
| Timeout threshold | 8 seconds | `pipeline.processing-timeout-ms` |

Backoff intervals are approximate — actual wait time depends on when the
`RetryPoller` last ran. A 2-second backoff may wait up to 7 seconds if
the poller ran just before the backoff was set.

---

## Dead Letter Queue Behaviour

Messages reach `incident.dlq` through two paths:

**Path 1 — Max retries exceeded:** `RetryHandler` publishes directly to
`incident.exchange` with routing key `incident.dlq` after `retry_count >= 3`.

**Path 2 — Deserialization failure:** `IncidentEventConsumer` throws
`AmqpRejectAndDontRequeueException`. Spring AMQP rejects the message and
the dead letter arguments on `incident.processing.queue` route it to
`incident.exchange` with routing key `incident.dlq` automatically.

No automatic retry occurs from the DLQ. Manual intervention is required.

**Finding DEAD records:**
```sql
SELECT event_id, incident_id, correlation_id, failure_reason, updated_at
FROM processing_records
WHERE status = 'DEAD'
ORDER BY updated_at DESC;
```

---

## Idempotency

Every `IncidentCreatedEvent` carries a unique `eventId` UUID generated at
publish time. The `processing_records` table has a `UNIQUE` constraint on
`event_id`. The application-level check happens before any processing:
```java
Optional<ProcessingRecord> existing = processingRepository.findByEventId(eventId);
if (existing.isPresent() && 
    (status == COMPLETED || status == DEAD)) {
    return; // duplicate — skip silently
}
```

The UNIQUE constraint is the database-level safety net — if two threads
somehow pass the application check simultaneously, the database rejects
the second INSERT with a constraint violation rather than creating a
duplicate record.

**Why this is load-bearing:** The outbox poller is at-least-once by design.
If a poller cycle crashes between publish and marking the outbox row
`PUBLISHED`, the same row is republished on the next cycle. Without
idempotency, duplicate processing is guaranteed under normal failure
conditions — not just edge cases.