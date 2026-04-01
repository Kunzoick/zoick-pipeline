# zoick-pipeline — Async Incident Processing Pipeline

Zoick · Spring Boot 3.5 · Java 21 · RabbitMQ · MariaDB · Docker

A distributed backend service that consumes incident creation events 
from the Trust API, processes them asynchronously with full failure 
handling, and demonstrates real distributed systems engineering — 
retries, dead letter queues, idempotency, poison messages, and 
graceful degradation under infrastructure failure.

---

## What This System Does

When an incident is submitted to the Trust API, a RabbitMQ event is 
published via the Transactional Outbox Pattern. This pipeline service 
consumes that event, tracks its processing state through explicit status 
transitions, handles failures with exponential backoff retry, and routes 
unrecoverable messages to a dead letter queue.

Every failure scenario is explicitly handled and verifiable by database query.

---

## Architecture
```
zoick-incident-api (port 8080)
└── Transactional Outbox Pattern
    └── OutboxPoller publishes to RabbitMQ

RabbitMQ (Docker — port 5672, management UI port 15672)
├── Exchange: incident.exchange
├── Queue:    incident.processing.queue
└── Queue:    incident.dlq

zoick-pipeline (port 8081)
├── IncidentEventConsumer   — receives event, delegates only
├── ProcessingService       — idempotency check, status transitions
├── RetryHandler            — exponential backoff, DLQ routing
├── RetryPoller             — polls PENDING_RETRY records
├── ProcessingWatchdog      — detects stuck PROCESSING records
└── DLQConsumer             — marks DEAD, logs for manual review
```

Full event flow: [`docs/event-flow.md`](docs/event-flow.md)

---

## Failure Scenarios — All Verified

| Scenario | Mechanism | Outcome |
|---|---|---|
| Worker crashes mid-processing | Watchdog detects stuck PROCESSING | Retried, eventually COMPLETED or DEAD |
| Poison message (always fails) | RetryHandler at max attempts | Routed to DLQ, marked DEAD |
| Duplicate event | eventId idempotency check | Acknowledged and skipped silently |
| RabbitMQ broker goes down | Outbox poller retries with publisher confirms | No data loss, recovered on restart |
| Processing takes too long | Watchdog timeout threshold (8s) | Transitioned to PENDING_RETRY |
| Database unavailable | DataAccessException caught, not DLQ | Message requeued, retried on recovery |

Full failure documentation: [`docs/failure-handling.md`](docs/failure-handling.md)

---

## Prerequisites

- Java 21+
- Docker (for RabbitMQ)
- MariaDB running locally on port 3306
- zoick-incident-api running on port 8080

---

## Setup

### 1. Start RabbitMQ
```bash
cd docker
docker compose up -d
```

Verify the management UI at `http://localhost:15672`  
Default credentials: `zoick` / `zoick123`

> **WSL2 note:** If running Docker inside WSL2, use the WSL2 IP instead 
> of localhost. Find it with `ip addr show eth0 | grep 'inet '`.

### 2. Configure environment
```bash
cp .env.example .env
```

Edit `.env`:
```
DB_USERNAME=your_db_user
DB_PASSWORD=your_db_password
RABBITMQ_HOST=localhost
RABBITMQ_USERNAME=zoick
RABBITMQ_PASSWORD=zoick123
```

### 3. Run the application
```bash
mvn spring-boot:run
```

Flyway runs all migrations automatically on startup. The 
`zoick_pipeline` database is created if it does not exist.

### 4. Verify
```
GET http://localhost:8081/actuator/health
```

Expected: `{"status":"UP"}`

---

## Processing Metrics

Live counters exposed via Actuator:
```
GET http://localhost:8081/actuator/metrics/pipeline.incidents.completed
GET http://localhost:8081/actuator/metrics/pipeline.incidents.failed
GET http://localhost:8081/actuator/metrics/pipeline.incidents.dead
```

---

## Retry Configuration

All configurable in `application.yml`:

| Property | Default | Purpose |
|---|---|---|
| `pipeline.retry-poll-interval-ms` | 5000 | How often the RetryPoller runs |
| `pipeline.processing-timeout-ms` | 8000 | Watchdog stuck-record threshold |

Retry strategy is fixed: 3 max attempts, exponential backoff (2s, 4s, 8s). 
See [ADR-002](docs/adr/ADR-002-retry-strategy.md).

---

## Failure Simulation Flags

For testing failure scenarios locally:
```yaml
pipeline:
  simulate-failure: false              # always fail processing
  simulate-slow-processing-ms: 0      # add artificial delay (ms)
  simulate-failure-on-attempt: 0      # fail only on attempts < this value
```

Set these in `application.yml` and restart. Reset all to `false`/`0` 
after testing.

---

## Database Schema
```
zoick_pipeline
└── processing_records
    ├── id               UUID primary key
    ├── event_id         UUID unique — idempotency key
    ├── incident_id      UUID — business identifier
    ├── correlation_id   UUID — end-to-end trace key
    ├── status           PENDING | PROCESSING | PENDING_RETRY | 
    │                    COMPLETED | FAILED | DEAD
    ├── retry_count      increments on each failure
    ├── failure_reason   last failure message
    ├── next_attempt_at  when the RetryPoller will next pick this up
    ├── created_at       when the record was created
    ├── updated_at       last status transition timestamp
    └── completed_at     when processing succeeded
```

---

## Observability

Every log line carries a `correlationId` set by the Trust API at 
incident creation time. All logs are emitted as JSON via Logstash 
encoder — `correlationId` and `incidentId` are top-level fields on 
every line.

Given a single `correlationId`, the full journey of one incident is 
traceable across both services from HTTP request to final processing state.

---

## Docs

| Document | Contents |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | Component responsibilities, design decisions |
| [`docs/event-flow.md`](docs/event-flow.md) | Exchange, queues, bindings, status transitions |
| [`docs/failure-handling.md`](docs/failure-handling.md) | All 6 scenarios, retry strategy, recovery procedures |
| [`docs/adr/ADR-001-broker-choice.md`](docs/adr/ADR-001-broker-choice.md) | Why RabbitMQ over Kafka |
| [`docs/adr/ADR-002-retry-strategy.md`](docs/adr/ADR-002-retry-strategy.md) | Retry design decisions |
| [`docs/adr/ADR-003-idempotency.md`](docs/adr/ADR-003-idempotency.md) | Idempotency approach |
| [`docs/adr/ADR-004-trust-api-integration.md`](docs/adr/ADR-004-trust-api-integration.md) | Outbox pattern rationale |

---

## Connected Service

[https://github.com/Kunzoick/zoick-incident-api) — Trust-Aware Incident 
Intelligence API (port 8080). The Trust API publishes incident creation 
events via the Transactional Outbox Pattern. This pipeline service is 
the consumer.
```

---
