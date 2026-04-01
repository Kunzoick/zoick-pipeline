# Architecture — zoick-pipeline

---

## System Overview

Two independent Spring Boot services connected through RabbitMQ. Each
service owns its own database. Neither service calls the other directly.
```
zoick-incident-api (port 8080)     zoick-pipeline (port 8081)
        │                                    │
        │ writes incidents                   │ writes processing records
        ▼                                    ▼
 zoick_incidentapi (MariaDB)      zoick_pipeline (MariaDB)
        │                                    ▲
        │ outbox pattern                     │
        ▼                                    │
  RabbitMQ (port 5672)  ──────────────────────
  Management UI (15672)
  ├── Exchange: incident.exchange
  ├── Queue: incident.processing.queue
  └── Queue: incident.dlq
```

---

## Why Two Databases

The Trust API and Pipeline are independent services. A shared database
would create coupling — a schema change in one service could break the
other without warning. Each service owns its schema exclusively. Flyway
manages migrations independently in each service. No cross-database
queries exist anywhere in the system.

---

## Component Responsibilities

### Trust API — `zoick-incident-api`

| Component | Responsibility |
|---|---|
| `IncidentService.submit()` | Persists incident, calls `OutboxService.writeEvent()` |
| `OutboxService` | Writes `outbox_events` row in same transaction as incident persist |
| `OutboxPoller` | Reads `PENDING` outbox rows, publishes to RabbitMQ, marks `PUBLISHED` |
| `IncidentEventPublisher` | Calls `RabbitTemplate.convertAndSend()`, waits for broker ACK via `CorrelationData` |
| `RabbitMQConfig` (Trust API) | Configures `RabbitTemplate` with publisher confirms and returns callback |

### Pipeline — `zoick-pipeline`

| Component | Responsibility |
|---|---|
| `IncidentEventConsumer` | Receives `String` payload, deserializes, sets MDC, delegates to `ProcessingService` |
| `ProcessingService` | Idempotency check, creates `ProcessingRecord`, delegates to `ProcessingStatusService` |
| `ProcessingStatusService` | Transitions to `PROCESSING` in `REQUIRES_NEW` transaction — immediate commit |
| `RetryHandler` | Increments retry count, sets backoff, routes to DLQ at max attempts |
| `RetryPoller` | Polls `PENDING_RETRY` records past their `next_attempt_at` timestamp |
| `ProcessingWatchdog` | Detects stuck `PROCESSING` records, delegates to `RetryHandler` |
| `DLQConsumer` | Marks `ProcessingRecord` as `DEAD`, logs ERROR with full context |
| `PipelineMetrics` | Owns Micrometer counter definitions for COMPLETED, FAILED, DEAD |
| `RabbitMQConfig` (Pipeline) | Declares exchange, queues, DLQ bindings, `RabbitTemplate` bean |

---

## Key Design Decisions

### Transactional Outbox Pattern
The incident persist and outbox row write share one database transaction.
They either both commit or both rollback. This solves the dual-write problem
in both directions — no ghost incidents, no silent loss. See ADR-004.

### Application-Managed Retry
Retry state lives in `processing_records`, not in RabbitMQ. This provides
database visibility — `retry_count`, `status`, `next_attempt_at`,
`failure_reason` are all queryable in real time. See ADR-002.

### REQUIRES_NEW for Immediate Status Visibility
`ProcessingStatusService.transitionToProcessing()` uses
`@Transactional(propagation = REQUIRES_NEW)` in a separate Spring bean.
This commits `PROCESSING` status to the database before the processing
work begins — making it visible to the `ProcessingWatchdog` on other
threads while the consumer thread is still running.

### Separate Bean for REQUIRES_NEW
Spring AOP proxies only intercept external method calls. A class calling
its own method (`this.method()`) bypasses the proxy and the
`@Transactional` annotation is silently ignored. `ProcessingStatusService`
is a separate `@Service` bean so the proxy wraps the call correctly.

### Publisher Confirms
`RabbitTemplate` uses `publisher-confirm-type: correlated` and
`publisher-returns: true`. `IncidentEventPublisher` waits for a broker
ACK via `CorrelationData.getFuture().whenComplete()` before marking an
outbox row `PUBLISHED`. Silent message drops are eliminated.

### MDC for Correlation ID Propagation
`IncidentEventConsumer` sets `MDC.put("correlationId", ...)` at the
consumer entry point — the only correct location. Logstash encoder
promotes MDC keys to top-level JSON fields on every log line for that
thread, including Spring framework internals. `MDC.clear()` runs in a
`finally` block — mandatory because Spring AMQP reuses listener threads
and ThreadLocal values leak across messages without explicit clearing.

---

## Metrics

Processing metrics are exposed via Spring Boot Actuator:
```
GET http://localhost:8081/actuator/metrics/pipeline.incidents.completed
GET http://localhost:8081/actuator/metrics/pipeline.incidents.failed
GET http://localhost:8081/actuator/metrics/pipeline.incidents.dead
```

Counters reset on application restart. For persistent metrics in
production, configure a Micrometer registry backend
(Prometheus, Datadog, etc.).

---

## Observability

Every event carries a `correlationId` set by the Trust API's
`CorrelationFilter` at the start of the HTTP request. Given a single
`correlationId`, the complete journey of one incident can be traced:

1. Trust API HTTP request log — `correlationId=xxx` in request log
2. Outbox write — `[OUTBOX] correlationId=xxx status=PENDING`
3. Outbox publish — `[OUTBOX] correlationId=xxx status=PUBLISHED`
4. Pipeline consumer — `[PIPELINE] correlationId=xxx message=Event received`
5. Processing transitions — `[PIPELINE] correlationId=xxx status=PROCESSING`
6. Final state — `[PIPELINE] correlationId=xxx status=COMPLETED`

All Pipeline logs are emitted as JSON via Logstash encoder. `correlationId`
and `incidentId` are top-level fields on every JSON log line — indexable
by any log aggregator.

---

## Technology Choices

| Technology | Decision | Rationale |
|---|---|---|
| RabbitMQ | Message broker | Native DLQ support, simple setup, AMQP per-message ACK. See ADR-001 |
| Spring AMQP | RabbitMQ client | First-class Spring Boot integration, publisher confirms, listener containers |
| Micrometer + Actuator | Metrics | Production-grade metrics API, consistent with Trust API observability |
| Logstash Logback Encoder | Structured logging | True JSON output — correlationId as indexable field, not embedded string |
| Flyway | Schema migrations | Same discipline as Trust API — versioned, immutable, auditable migrations |
| MariaDB (separate instance) | Pipeline database | Service boundary — Trust API schema changes cannot break Pipeline |