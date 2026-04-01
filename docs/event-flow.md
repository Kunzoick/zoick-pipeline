# Event Flow — zoick-pipeline

This document describes the complete message flow from incident creation
in the Trust API to final processing state in the Pipeline.
---

## System Topology
```
zoick-incident-api (port 8080)
└── IncidentService.submit()
    └── OutboxService.writeEvent()          ← same transaction as incident persist
        └── outbox_events table (PENDING)

OutboxPoller (@Scheduled, every 5s)
└── reads PENDING rows from outbox_events
    └── IncidentEventPublisher
        └── publishes to incident.exchange
            └── publisher confirm (ACK/NACK)
                └── marks outbox row PUBLISHED or retries

RabbitMQ
├── Exchange: incident.exchange (TopicExchange, durable)
├── Queue: incident.processing.queue (durable)
│   ├── Binding: incident.created → incident.processing.queue
│   └── Dead letter args:
│       ├── x-dead-letter-exchange: incident.exchange
│       └── x-dead-letter-routing-key: incident.dlq
└── Queue: incident.dlq (durable)
    └── Binding: incident.dlq → incident.dlq

zoick-pipeline (port 8081)
├── IncidentEventConsumer
│   ├── listens to incident.processing.queue
│   ├── deserializes String payload to IncidentCreatedEvent
│   ├── sets MDC correlationId and incidentId
│   └── delegates to ProcessingService
├── ProcessingService
│   ├── idempotency check (findByEventId)
│   ├── creates ProcessingRecord (PENDING)
│   ├── transitions to PROCESSING (REQUIRES_NEW — immediate commit)
│   └── on failure: delegates to RetryHandler
├── RetryHandler
│   ├── increments retry_count
│   ├── sets next_attempt_at (exponential backoff)
│   ├── status = PENDING_RETRY (attempts 1-2)
│   └── status = FAILED + publish to incident.dlq (attempt 3)
├── RetryPoller (@Scheduled, every 5s)
│   └── picks up PENDING_RETRY records where next_attempt_at <= NOW()
├── ProcessingWatchdog (@Scheduled, every 5s)
│   └── detects PROCESSING records older than 8s threshold
│       └── delegates to RetryHandler
└── DLQConsumer
    ├── listens to incident.dlq
    └── marks ProcessingRecord status = DEAD
```
---

## Status Transitions
```
PENDING → PROCESSING → COMPLETED (happy path)

PENDING → PROCESSING → PENDING_RETRY (attempt 1, backoff 2s)
                     → PROCESSING → PENDING_RETRY (attempt 2, backoff 4s)
                     → PROCESSING → FAILED → DLQ → DEAD

PENDING → DUPLICATE (idempotency check — already COMPLETED or DEAD)
```
---

## Exchange and Queue Configuration
### incident.exchange
- Type: `TopicExchange`
- Durable: `true`
- Auto-delete: `false`

### incident.processing.queue
- Durable: `true`
- Dead letter exchange: `incident.exchange`
- Dead letter routing key: `incident.dlq`
- Purpose: receives all new incident events for processing

### incident.dlq
- Durable: `true`
- No dead letter configuration — terminal queue
- Purpose: receives poison messages and retry-exhausted events
---

## Routing Keys

| Routing Key | Source | Destination |
|---|---|---|
| `incident.created` | Trust API outbox poller | `incident.processing.queue` |
| `incident.dlq` | RetryHandler (max retries) | `incident.dlq` |
| `incident.dlq` | IncidentEventConsumer (deserialization failure) | `incident.dlq` |

---

## Outbox Table — outbox_events

| Column | Purpose |
|---|---|
| `id` | UUID primary key |
| `event_type` | Always `INCIDENT_CREATED` |
| `aggregate_id` | The `incidentId` |
| `payload` | Full `IncidentCreatedEvent` serialized as JSON |
| `status` | `PENDING` → `PUBLISHED` or `FAILED` |
| `retry_count` | Increments on each failed publish attempt |
| `created_at` | When the outbox row was written |
| `published_at` | When broker confirmed receipt |
| `last_attempted_at` | When the poller last attempted publish |

**Recovery for FAILED outbox rows:**
```sql
UPDATE outbox_events 
SET status = 'PENDING', retry_count = 0, last_attempted_at = NULL 
WHERE id = '<outbox-row-id>';
```

---

## Correlation ID Tracing

Every event carries a `correlationId` set by the Trust API's
`CorrelationFilter` at the start of the HTTP request. This ID is:

- Written to the `outbox_events.payload` JSON
- Carried through `IncidentCreatedEvent.correlationId`
- Set in MDC at the Pipeline consumer entry point
- Present as a top-level JSON field on every log line via Logstash encoder
- Included manually in every `[PIPELINE]` log message string as a fallback

Given a single `correlationId`, the complete journey of one incident
can be traced across both services from HTTP request to final
processing state.