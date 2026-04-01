# ADR-002 — Retry Strategy Design

**Status:** Accepted
**Project:** zoick-pipeline — Async Incident Processing Pipeline

---

## Context

The pipeline must handle transient processing failures without losing
messages. Three questions required explicit decisions:

1. How many retry attempts before a message is considered unrecoverable?
2. What interval strategy between attempts?
3. Where does retry state live — in RabbitMQ or in the application?
4. When is the RabbitMQ message acknowledged — before or after the
   database write?

---

## Decision

Application-managed retry with exponential backoff, maximum 3 attempts,
dead letter queue routing after max retries, and manual acknowledgement
to guarantee no processing attempt is lost on crash.

- **Max attempts:** 3
- **Backoff:** Exponential — 2^attempt seconds (attempt 1: 2s, attempt 2: 4s, attempt 3: 8s)
- **Retry state:** `processing_records` database table — not RabbitMQ
- **Acknowledgement mode:** `AcknowledgeMode.MANUAL` — message is
  acknowledged only after the database write succeeds
- **After max retries:** Route to `incident.dlq` via `RetryHandler`,
  mark `ProcessingRecord` status as `FAILED`
- **DLQ behaviour:** No automatic retry — manual intervention required
- **Malformed messages:** `basicNack` with `requeue=false` on
  deserialization failure — routes to DLQ via dead letter arguments,
  does not consume retry attempts

---

## Alternatives Considered

### Spring AMQP automatic retry (broker-managed)

Spring AMQP supports automatic retry via `RetryInterceptorBuilder`. On
failure the consumer throws, Spring AMQP requeues the message, and the
broker redelivers after a configurable interval.

Rejected because it provides no database visibility. The six failure
scenarios required by the contract must be verifiable by querying the
database — `retry_count`, `status`, `next_attempt_at`, `failure_reason`
must all be observable in real time. Broker-managed retry has none of
this visibility.

### Fixed interval retry

Fixed intervals (retry every 5 seconds regardless of attempt number)
were rejected because they cause retry storms. If many messages fail
simultaneously, fixed interval retry means all of them retry at the
same moment, creating a thundering herd that can amplify the original
failure rather than recovering from it. Exponential backoff staggers
retries and reduces load during recovery.

### Infinite retry

No maximum attempt limit was rejected because poison messages — messages
that always fail regardless of how many times they are retried — would
consume retry cycles indefinitely, blocking healthy messages and filling
logs with noise. A finite limit with DLQ routing ensures poison messages
are isolated and flagged for manual review without affecting the rest of
the system.

### Auto-acknowledgement (AcknowledgeMode.AUTO)

Spring AMQP's default `AcknowledgeMode.AUTO` acknowledges the message
immediately when the listener method returns, regardless of whether any
database writes succeeded. This creates a gap: if the application crashes
between message receipt and the database write, the message is already
acknowledged and will not be redelivered. The processing attempt is
silently lost with no retry record.

Rejected in favour of `AcknowledgeMode.MANUAL`.

---

## Manual Acknowledgement — How It Works

The consumer method signature accepts `Channel` and `deliveryTag`
parameters injected by Spring AMQP:
```java
public void consume(String rawPayload, Channel channel,
                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag)
```

Three explicit acknowledgement paths exist:

**Successful processing:**
```java
processingService.process(event);  // writes ProcessingRecord to DB
channel.basicAck(deliveryTag, false);  // acknowledge only after DB write
```

**Database failure:**
```java
channel.basicNack(deliveryTag, false, true);  // requeue=true
```

**Deserialization failure (poison message):**
```java
channel.basicNack(deliveryTag, false, false);  // requeue=false → DLQ
```

---

## The Guarantee Manual Acknowledgement Provides

If the application crashes after `processingService.process()` writes
the `ProcessingRecord` to the database but before `channel.basicAck()`
fires:

1. RabbitMQ does not receive the ACK
2. RabbitMQ redelivers the message on the next consumer connection
3. `ProcessingService` runs the idempotency check — `findByEventId()`
   finds the existing `COMPLETED` record
4. The duplicate is skipped silently — no reprocessing occurs

The idempotency check is load-bearing for this guarantee. Without it,
redelivery after a crash would cause duplicate processing. The two
mechanisms — manual acknowledgement and eventId idempotency — work
together to provide an end-to-end at-least-once processing guarantee
with no duplicates.

---

## Consequences

**Positive:**
- Full retry visibility in the database — `retry_count`, `status`,
  `next_attempt_at` observable at all times
- Exponential backoff prevents retry storms during partial outages
- Poison messages isolated to DLQ after exactly 3 attempts
- All six failure scenarios verifiable by database query
- Manual acknowledgement closes the crash-between-receive-and-write gap
- Database failures requeue correctly — message not lost, not DLQ'd
- Deserialization failures route directly to DLQ — no retry cycles wasted
- `processing-timeout-ms` and `retry-poll-interval-ms` configurable
  without code changes

**Negative:**
- Manual acknowledgement adds complexity to the consumer method signature
- A message that takes longer than the HikariCP connection timeout
  (30 seconds by default) to acknowledge could cause RabbitMQ to
  consider it unacknowledged and redeliver — mitigated by the
  idempotency check

**Timing approximation:** Backoff intervals are approximate. The
`RetryPoller` runs every 5 seconds. A 2-second backoff may actually
wait up to 7 seconds depending on when the poller last ran. This is
documented and accepted — exact timing is not a requirement for
this system.