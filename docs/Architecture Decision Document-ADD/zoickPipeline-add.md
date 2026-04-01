# Zoick Pipeline — Architecture Decision Document
## Async Incident Processing Pipeline v1.0

**Project:** zoick-pipeline  
**Connected Service:** zoick-incident-api (Trust-Aware Incident Intelligence API)  
**Stack:** Spring Boot 3.5 · Java 21 · RabbitMQ · MariaDB · Docker

> "Real distributed systems engineering is not about publishing a message and logging it. It is about designing for failure — retries, dead letter queues, poison messages, duplicate events, worker crashes — and proving each scenario works."

---

## 1. What This System Is

A distributed backend pipeline that consumes incident creation events from the Trust API asynchronously, processes them with explicit failure handling, and demonstrates real distributed systems engineering patterns — not a tutorial-level message logger.

**This system IS:**
- A production-grade event-driven processing pipeline
- A demonstration of the Transactional Outbox Pattern
- A system where every failure scenario is triggered and verified by database query
- A portfolio-grade backend infrastructure project

**This system is NOT:**
- A frontend application
- A CRUD demo
- A message logger
- A streaming system

---

## 2. System Architecture

```
zoick-incident-api (port 8080)          zoick-pipeline (port 8081)
        │                                        │
        │ HTTP: POST /api/v1/incidents            │ listens to RabbitMQ
        │                                        │
        ▼                                        ▼
 IncidentService.submit()            IncidentEventConsumer
        │                                        │
        │ same transaction                       │ delegates only
        ▼                                        ▼
 OutboxService.writeEvent()          ProcessingService
        │                                        │
        ▼                                        ├── idempotency check
 outbox_events table                 │           ├── ProcessingRecord write
        │                            │           └── ProcessingStatusService
        ▼                            │                 (REQUIRES_NEW)
 OutboxPoller (@Scheduled)           │
        │                            │           RetryHandler
        │ publishes with confirms     │           ├── retry_count++
        ▼                            │           ├── exponential backoff
 RabbitMQ                            │           └── DLQ at max attempts
 ├── incident.exchange               │
 ├── incident.processing.queue ──────┘           RetryPoller (@Scheduled)
 └── incident.dlq                               └── polls PENDING_RETRY
                                                
                                                ProcessingWatchdog (@Scheduled)
                                                └── detects stuck PROCESSING
                                                
                                                DLQConsumer
                                                └── marks DEAD
```

---

## 3. The Integration Problem — Why This Architecture Exists

When an incident is created in the Trust API, the Pipeline must be notified. Three integration approaches were evaluated.

### Approach 1: Direct RabbitMQ publish from IncidentService

Publishing to RabbitMQ directly inside `IncidentService.submit()` creates what is called the dual-write problem — two external systems (database and message broker) must both receive a write in a way that is consistent.

**Direction 1 — Publish before commit:** If the database transaction rolls back after the message is published, the Pipeline receives an event for an incident that never exists. The Pipeline processes a ghost incident.

**Direction 2 — Publish fails after commit:** If the transaction commits but the RabbitMQ publish fails (broker down, network error), the incident is in the database but the Pipeline never receives the event. The incident is silently stuck in PENDING forever.

**Verdict: Rejected.** Both failure directions are unacceptable.

### Approach 2: afterCommit callback

Publishing inside `TransactionSynchronizationManager.afterCommit()` solves Direction 1 — the message is only published after the transaction commits. It does not solve Direction 2. If the broker is down at the moment `afterCommit` fires, the message is lost with no retry and no recovery path.

**Verdict: Rejected.**

### Approach 3: Transactional Outbox Pattern (chosen)

Write an `outbox_events` row in the same database transaction as the incident INSERT. A separate scheduled poller reads unpublished rows and publishes them to RabbitMQ. Publisher confirms verify broker receipt before marking rows PUBLISHED.

**Why this solves both directions:**
- The outbox row and incident persist share one local database transaction. If the transaction rolls back, both rollback. No ghost incidents.
- If the broker is down, the poller retries on every cycle. The outbox row stays PENDING until confirmed. No silent loss.

**Verdict: Chosen. See ADR-004 for full reasoning.**

---

## 4. ADR-001 — Message Broker Choice: RabbitMQ over Kafka

**Context:** The pipeline requires a message broker supporting reliable delivery, dead letter queue routing, per-message acknowledgement, and local development without cloud infrastructure.

**Decision:** RabbitMQ.

**Why not Kafka:**
- Kafka requires ZooKeeper or KRaft, a broker cluster, and topic configuration before a single message can be published. RabbitMQ runs as a single Docker container.
- Kafka has no native dead letter queue support. DLQ in Kafka requires separate topics and explicit consumer routing code. RabbitMQ supports dead letter exchanges natively via queue arguments.
- Kafka is designed for partitioned, replicated, ordered logs at high volume with multiple consumer groups. This project has one producer and one consumer. Kafka's architecture provides no benefit and adds significant operational complexity.
- Kafka's consumer group model, partition assignment, and offset management introduce concepts irrelevant to this use case.

**Why not in-process events (Spring ApplicationEventPublisher):**
- No durability, no retry, no dead letter routing, no service decoupling. Events disappear on application crash.

**Consequences:**
- Single Docker container, running in under 30 seconds
- Native DLQ support via dead letter exchange arguments
- Management UI at port 15672 for real-time queue inspection
- AMQP per-message acknowledgement supports at-least-once delivery
- Publisher confirms available for guaranteed broker receipt verification
- RabbitMQ does not provide the persistent ordered log that Kafka offers. If message replay were required, Kafka would be correct.

---

## 5. ADR-002 — Retry Strategy Design

**Context:** Transient processing failures must be handled without losing messages. Three decisions required: how many attempts, what interval strategy, where retry state lives.

**Decision:** Application-managed retry, exponential backoff, maximum 3 attempts, DLQ after max.

**Why application-managed retry (not Spring AMQP automatic retry):**
Spring AMQP supports automatic retry via `RetryInterceptorBuilder`. On failure the consumer throws, Spring AMQP requeues, and the broker redelivers after a configurable interval. Rejected because it provides zero database visibility. The six failure scenarios required by the contract must be verifiable by querying `processing_records` — `retry_count`, `status`, `next_attempt_at`, `failure_reason` must all be observable in real time. Broker-managed retry has none of this visibility.

**Why exponential backoff (not fixed intervals):**
Fixed intervals cause retry storms. If many messages fail simultaneously, all retry at the same moment, creating a thundering herd that amplifies the original failure. Exponential backoff staggers retries and reduces load during recovery.

**Why 3 attempts (not more, not infinite):**
Infinite retry would allow poison messages to consume cycles indefinitely, blocking healthy messages. Three attempts is enough to handle transient failures (network hiccups, temporary database unavailability) while ensuring poison messages are isolated quickly.

**Known limitation — acknowledge before write gap:**
The consumer previously acknowledged the RabbitMQ message before writing retry state to the database. If the application crashed in the milliseconds between acknowledgement and the database write, that processing attempt was lost with no retry record.

**Resolution:** Implemented `AcknowledgeMode.MANUAL` in the listener container. The `IncidentEventConsumer` now calls `channel.basicAck()` only after `processingService.process()` returns successfully. If the app crashes before `basicAck()` fires, RabbitMQ redelivers the message. The idempotency check on `eventId` prevents duplicate processing. The limitation is closed.

**Timing note:** Backoff intervals are approximate. The `RetryPoller` runs every 5 seconds. A 2-second backoff may wait up to 7 seconds depending on poller timing. This is accepted — exact timing is not a requirement.

---

## 6. ADR-003 — Idempotency Approach

**Context:** RabbitMQ guarantees at-least-once delivery. The same message can arrive more than once. Without idempotency, an incident could be processed multiple times.

**Decision:** Database-backed idempotency using `eventId` as the deduplication key. UNIQUE constraint on `processing_records.event_id` as the database-level safety net.

**Why eventId and not incidentId:**
`incidentId` is the business identifier — it identifies the incident as a domain object. `eventId` is the message identifier — it identifies this specific delivery of this specific event. If the outbox poller publishes the same row twice (at-least-once delivery from the poller), two messages with the same `incidentId` but different `eventId` values arrive. Using `incidentId` would incorrectly treat the second delivery as a duplicate even if the first was never processed. Using `eventId` correctly identifies the specific message.

**Why database and not Redis cache:**
Redis is volatile. Cache eviction or Redis restart loses deduplication history. A message processed before eviction would be processed again — silently, with no error. The database never loses committed records. Additionally, a Redis failure in the idempotency path would cause duplicate processing — a much worse failure mode than cache-miss.

**Why this is load-bearing:**
The outbox poller is intentionally at-least-once. If a poller cycle crashes between publish and marking the outbox row PUBLISHED, the same row is republished on the next cycle. Without idempotency, duplicate processing is guaranteed under normal failure conditions — not just edge cases.

---

## 7. ADR-004 — Integration Pattern with Trust API

**Decision:** Transactional Outbox Pattern. See Section 3 for full alternative analysis.

**The outbox guarantee:**
The outbox row and the incident persist share one local database transaction. They either both commit or both rollback. The poller publishes independently of the HTTP request lifecycle. If the broker is down, the event is retried until delivery is confirmed by publisher confirms.

**Recovery procedure for FAILED outbox rows:**
```sql
UPDATE outbox_events 
SET status = 'PENDING', retry_count = 0, last_attempted_at = NULL 
WHERE id = '<outbox-row-id>';
```
The poller picks up the reset row on the next cycle.

**Why this is the most important ADR:**
This decision is what makes the architecture event-driven rather than a polling system with extra steps. Every other decision in this project builds on the guarantee that if an incident is created, the Pipeline will eventually process it — regardless of broker availability at the time of creation.

---

## 8. Key Implementation Decisions

### REQUIRES_NEW for Immediate Status Visibility

`ProcessingStatusService.transitionToProcessing()` uses `@Transactional(propagation = REQUIRES_NEW)` in a separate Spring bean. This commits `PROCESSING` status to the database before processing begins — making it visible to the `ProcessingWatchdog` on other threads while the consumer thread is still running.

Without immediate commit: the watchdog queries the database during the slow processing simulation and sees the old status — it cannot detect the stuck record.

### Separate Bean for REQUIRES_NEW

Spring AOP proxies only intercept external method calls. A class calling its own method (`this.method()`) bypasses the proxy and the `@Transactional` annotation is silently ignored. `ProcessingStatusService` is a separate `@Service` bean so the proxy wraps the call correctly.

### Publisher Confirms

`RabbitTemplate` uses `publisher-confirm-type: correlated` and `publisher-returns: true`. `IncidentEventPublisher` waits for a broker ACK via `CorrelationData.getFuture().whenComplete()` before marking an outbox row PUBLISHED. Silent message drops are eliminated. Template-level callbacks are set once at bean construction — never per publish call.

### Consumer Receives String, Not Typed Object

The Trust API publishes a pre-serialized JSON String. RabbitMQ stamps the message with `__TypeId__=java.lang.String`. The consumer receives `String rawPayload` and deserializes manually using `objectMapper.readValue()`. This gives full control over error handling — deserialization failures are distinguished from processing failures.

### MDC for Correlation ID Propagation

`IncidentEventConsumer` sets `MDC.put("correlationId", ...)` at the consumer entry point. Logstash encoder promotes MDC keys to top-level JSON fields on every log line for that thread. `MDC.clear()` runs in a `finally` block — mandatory because Spring AMQP reuses listener threads and ThreadLocal values leak across messages without explicit clearing.

### Manual Acknowledgement

`AcknowledgeMode.MANUAL` is configured on the listener container factory. The consumer calls `channel.basicAck()` only after successful database write. Deserialization failures use `basicNack(requeue=false)` — routes to DLQ. Database and processing failures use `basicNack(requeue=true)` — requeues for retry.

---

## 9. Failure Scenario Matrix — All Verified

| Scenario | Mechanism | Outcome | Verified |
|---|---|---|---|
| Worker crashes mid-processing | Watchdog detects stuck PROCESSING | Retried, eventually COMPLETED or DEAD | ✅ |
| Poison message (always fails) | RetryHandler at max attempts | Routed to DLQ, marked DEAD | ✅ |
| Duplicate event | eventId idempotency check | Acknowledged and skipped | ✅ |
| RabbitMQ broker goes down | Outbox poller retries with publisher confirms | No data loss, recovered on restart | ✅ |
| Processing takes too long | Watchdog timeout threshold (8s default) | Transitioned to PENDING_RETRY | ✅ |
| Database unavailable | DataAccessException + basicNack(requeue=true) | Message requeued, retried on recovery | ✅ |

---

## 10. Processing State Machine

```
                    ┌─────────┐
                    │ PENDING │
                    └────┬────┘
                         │ idempotency check passes
                         ▼
                  ┌────────────┐
                  │ PROCESSING │ ◄─── REQUIRES_NEW commit
                  └─────┬──────┘      visible to watchdog
                        │
              ┌─────────┴─────────┐
              │                   │
              ▼                   ▼
        ┌──────────┐      ┌───────────────┐
        │COMPLETED │      │ PENDING_RETRY │
        └──────────┘      └───────┬───────┘
                                  │ retry_count >= MAX_ATTEMPTS
                                  ▼
                             ┌────────┐
                             │ FAILED │ → published to DLQ
                             └────┬───┘
                                  │ DLQConsumer
                                  ▼
                             ┌──────┐
                             │ DEAD │
                             └──────┘
```

---

## 11. Distributed Systems Concepts Demonstrated

### Transactional Outbox Pattern
The outbox row and incident persist share one local database transaction. Atomicity eliminates both directions of the dual-write problem. The poller publishes independently with publisher confirms.

### At-Least-Once Delivery + Idempotency
RabbitMQ guarantees at-least-once delivery — duplicates are possible under normal failure conditions, not just edge cases. The `eventId` idempotency check on the consumer makes duplicates harmless. These two properties are complementary and load-bearing.

### Exponential Backoff
Retry intervals of 2s, 4s, 8s prevent retry storms. If all failed messages retried at fixed intervals simultaneously, the spike could amplify the original failure.

### Publisher Confirms
The outbox poller does not consider a message published until the broker sends an explicit ACK. Silent message drops — the broker accepting a connection but silently discarding the message — are impossible with confirms enabled.

### Correlation ID Tracing
Every event carries a `correlationId` generated by the Trust API's `CorrelationFilter` before any processing. This ID is present as a top-level JSON field on every log line across both services via MDC and Logstash encoder. Given one `correlationId`, the complete journey of one incident is traceable from HTTP request to final processing state.

### Backpressure
The `RetryPoller` processes records in batches of 50. If 500 incidents arrive simultaneously, the poller drains them at a controlled rate. Exponential backoff staggers retry timing, preventing failed records from all retrying simultaneously.

### Dead Letter Queues
Unrecoverable messages are routed to `incident.dlq` — either by `RetryHandler` at max attempts (3) or by Spring AMQP on deserialization failure. `DLQConsumer` marks records DEAD and logs at ERROR level. No automatic retry from DLQ — manual intervention required.

### Graceful Degradation
Six infrastructure failures (broker down, database down, slow processing, worker crash, poison message, duplicate delivery) are all handled without crashing the system, corrupting data, or returning errors to the HTTP caller.

### Manual Acknowledgement Guarantee
`channel.basicAck()` is called only after the database write is confirmed. If the app crashes between the database write and the acknowledgement, RabbitMQ redelivers the message. The idempotency check handles the redelivery safely. At-least-once processing is now guaranteed, not just at-least-once delivery.

---

## 12. Build Discoveries and Problems Resolved

These are real problems encountered during the build that required diagnosis and resolution.

### Dual-Write Problem — Both Directions
**Problem:** Direct RabbitMQ publish inside `IncidentService` had two failure modes — ghost incidents on transaction rollback, and silent loss on broker failure after commit.
**Resolution:** Transactional Outbox Pattern. See ADR-004.

### Publisher Confirms Not Enabled
**Problem:** `RabbitTemplate.convertAndSend()` returned without error even when the broker silently dropped the message (exchange not found). The outbox marked rows PUBLISHED when messages were actually lost.
**Resolution:** Enabled `publisher-confirm-type: correlated` and `publisher-returns: true`. `IncidentEventPublisher` uses `CountDownLatch` to block until the broker sends ACK or NACK.

### RabbitTemplate Singleton Callback Mutation
**Problem:** Setting `setReturnsCallback()` and `setConfirmCallback()` inside the `publish()` method — which is called on every poll cycle — triggered `Only one ReturnCallback is supported by each RabbitTemplate`. The shared bean's callbacks were overwritten on every call.
**Resolution:** Template-level callbacks set once at construction in `RabbitMQConfig`. Per-message confirms use `CorrelationData.getFuture().whenComplete()`.

### TypeId Deserialization Failure
**Problem:** The Trust API published a pre-serialized JSON String. RabbitMQ stamped `__TypeId__=java.lang.String`. The Pipeline's `Jackson2JsonMessageConverter` tried to deserialize a String into `IncidentCreatedEvent` and failed.
**Resolution:** Consumer receives `String rawPayload` and calls `objectMapper.readValue()` manually.

### Spring Proxy Self-Invocation — REQUIRES_NEW Silently Ignored
**Problem:** `transitionToProcessing()` was a method on `ProcessingService` annotated with `@Transactional(REQUIRES_NEW)`. Calling it as `this.transitionToProcessing()` bypassed Spring's CGLIB proxy. The annotation was silently ignored and the method ran inside the existing transaction.
**Resolution:** Extracted to `ProcessingStatusService` — a separate `@Service` bean. External calls go through the proxy and `REQUIRES_NEW` is honoured.

### Duplicate Primary Key on processing_records
**Problem:** `ProcessingService.process()` was `@Transactional`. The outer session queued an INSERT when `processingRepository.save(record)` was called. The `REQUIRES_NEW` inner transaction committed the same record. When the outer transaction flushed, Hibernate tried to INSERT the same primary key again — constraint violation.
**Resolution:** Removed `@Transactional` from `ProcessingService.process()`. Each `save()` call uses Spring Data JPA's default per-operation transaction.

### Watchdog Retry Count Not Incrementing — Infinite Loop
**Problem:** The original watchdog manually set `status = PENDING_RETRY` but never incremented `retryCount`. The `RetryPoller` picked up the record, saw `retryCount=0`, logged `attempt=1`, and the cycle repeated indefinitely.
**Resolution:** Watchdog calls `retryHandler.handleFailure()` — the single source of truth for retry logic. `RetryHandler` increments `retryCount`, checks `MAX_ATTEMPTS`, routes to DLQ when exhausted.

### Race Condition — Both Threads Completing
**Problem:** After the watchdog transitioned a record to `PENDING_RETRY` and the `RetryPoller` started a retry, the original consumer thread woke from its sleep, saw `status=PROCESSING` (set by the retry), assumed it was still the legitimate owner, and also completed. Two `status=COMPLETED` writes for one record.
**Resolution:** Added `updatedAt` comparison to the stale check — the original thread captures `processingStartedAt` before sleeping. After waking, it re-fetches and checks both `status == PROCESSING` AND `updatedAt == processingStartedAt`. A changed `updatedAt` means an external actor touched the record — the thread aborts.

### Acknowledge-Before-Write Gap
**Problem:** In `AcknowledgeMode.AUTO`, Spring AMQP acknowledged messages automatically before the database write was confirmed. A crash between acknowledgement and database write lost the processing attempt permanently.
**Resolution:** Switched to `AcknowledgeMode.MANUAL`. `channel.basicAck()` is called only after `processingService.process()` returns successfully.

---

## 13. Known Limitations and Evolution Paths

### Approximate Backoff Timing
Retry backoff intervals (2s, 4s, 8s) are approximate. The `RetryPoller` runs every 5 seconds. Actual wait time may be up to 5 seconds longer than the configured backoff. Accepted — exact timing is not a requirement for this system.

### Outbox Row FAILED Requires Manual Reset
An outbox row that reaches `max-retry-count` (default 5) is marked FAILED and stops being polled. This happens when the broker is down for longer than 5 × poll-interval seconds. Recovery requires manual SQL reset. Documented in ADR-004.

### In-Memory Metrics Reset on Restart
Micrometer counters (COMPLETED, FAILED, DEAD) reset on application restart. For persistent metrics in production, configure a registry backend (Prometheus, Datadog, etc.).

### No Distributed Tracing
This project implements logging and metrics. Full distributed tracing (OpenTelemetry, Zipkin, Jaeger) — with span propagation across services — is a documented evolution path. The `correlationId` provides manual tracing capability via log search.

---

## 14. Metrics and Observability

### Processing Metrics
```
GET http://localhost:8081/actuator/metrics/pipeline.incidents.completed
GET http://localhost:8081/actuator/metrics/pipeline.incidents.failed
GET http://localhost:8081/actuator/metrics/pipeline.incidents.dead
```

### Structured Logging
All Pipeline logs are emitted as JSON via Logstash Logback Encoder. `correlationId` and `incidentId` are top-level JSON fields on every log line — promoted from MDC automatically. `"service":"zoick-pipeline"` is stamped on every line for log aggregator identification.

### End-to-End Tracing
Given a `correlationId`, the complete journey of one incident is traceable:
1. Trust API: `[OUTBOX] correlationId=xxx status=PENDING`
2. Trust API: `[OUTBOX] correlationId=xxx status=PUBLISHED`
3. Pipeline: `[PIPELINE] correlationId=xxx message=Event received`
4. Pipeline: `[PIPELINE] correlationId=xxx status=PROCESSING`
5. Pipeline: `[PIPELINE] correlationId=xxx status=COMPLETED`

---

## 15. Technology Stack

| Technology | Decision | Rationale |
|---|---|---|
| RabbitMQ | Message broker | Native DLQ, simple setup, AMQP per-message ACK. See ADR-001 |
| Spring AMQP | RabbitMQ client | First-class Spring Boot integration, publisher confirms, listener containers |
| Micrometer + Actuator | Metrics | Production-grade metrics API, consistent with Trust API observability |
| Logstash Logback Encoder | Structured logging | True JSON output — correlationId as indexable top-level field |
| Flyway | Schema migrations | Versioned, immutable, auditable migrations. Same discipline as Trust API |
| MariaDB (separate database) | Pipeline state | Service boundary — Trust API schema changes cannot affect Pipeline |
| Docker | RabbitMQ infrastructure | Local broker without cloud dependency |

---

*Zoick — Async Incident Processing Pipeline Architecture Decision Document v1.0*