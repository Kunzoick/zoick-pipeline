# ADR-004 — Integration Pattern with Trust API

**Status:** Accepted   
**Project:** zoick-pipeline — Async Incident Processing Pipeline
---

## Context
The Pipeline must receive notification when an incident is created in
the Trust API. Two services, two databases, running independently. The
integration pattern determines the coupling between them, the failure
modes, and the guarantees the system can make about message delivery.

This is the most important architectural decision in the project. It
determines whether the architecture is genuinely event-driven or a
polling system with extra steps.
---

## Decision
Event publishing via the Transactional Outbox Pattern.

The Trust API writes an `outbox_events` row in the same database
transaction as the incident `INSERT`. A scheduled poller reads unpublished
rows and publishes them to RabbitMQ. Publisher confirms verify broker
receipt before marking rows `PUBLISHED`.
---

## Alternatives Considered

### Direct RabbitMQ publish from IncidentService

Publishing to RabbitMQ directly inside `IncidentService.submit()` —
after `entityManager.persist(incident)` but before the transaction commits —
was the initial approach.

Rejected because it creates a dual-write problem in two directions:
**Direction 1 — Publish before commit:** If the transaction rolls back
after the message is published, the Pipeline receives an event for an
incident that never exists in the database. The Pipeline would process
a ghost incident.

**Direction 2 — Publish fails after commit:** If the transaction commits
successfully but the RabbitMQ publish fails (broker down, network error),
the incident exists in the database but the Pipeline never receives the
event. The incident is silently stuck in PENDING forever with no processing
record and no error visible to an operator.

Neither failure mode is acceptable in a system claiming reliable
event-driven processing.

### afterCommit publish (TransactionSynchronizationManager)
Publishing inside a `TransactionSynchronizationManager.registerSynchronization()`
`afterCommit()` callback was considered. This solves Direction 1 — the
message is only published after the transaction commits successfully.

Rejected because it does not solve Direction 2. If the broker is down
at the moment `afterCommit` fires, the message is lost. There is no
retry, no persistence, no recovery path.

### Pipeline polling the Trust API database directly
The Pipeline could poll the Trust API's `incidents` table for new
`PENDING` incidents on a schedule.

Rejected because it creates direct database coupling between two
independent services. Schema changes in the Trust API break the Pipeline
without warning. It also inverts the dependency — the Trust API would
need to expose its database to the Pipeline, violating service boundary
principles. This is not event-driven architecture — it is database sharing
with a scheduler.

### Pipeline polling the Trust API via REST
The Pipeline could call `GET /api/v1/incidents?status=PENDING` on a
schedule.

Rejected because it creates tight HTTP coupling, adds load to the Trust
API on every poll cycle regardless of whether new incidents exist, and
requires the Trust API to expose a polling-friendly endpoint that was
never part of its contract.
---

## The Outbox Pattern — How It Solves Both Directions
The outbox row and the incident persist are written in the same local
database transaction. They either both commit or both rollback. There
is no window where one succeeds and the other fails.

The poller is a separate process that runs independently of the HTTP
request lifecycle. If the broker is down, the poller retries on every
poll cycle. The outbox row stays `PENDING` until the broker accepts
the message and confirms receipt via publisher confirms.

This gives two guarantees that neither direct publish nor afterCommit
can provide:
1. No ghost incidents — the outbox row only exists if the incident exists
2. No silent loss — if the broker is down, the event is retried until
   delivery is confirmed

---

## Consequences
**Positive:**
- Both directions of the dual-write problem solved
- Broker outage does not cause data loss — events are retried from the outbox
- Publisher confirms provide delivery verification — silent drops eliminated
- Incident creation HTTP response is never affected by broker availability
- Outbox table provides an audit trail of all published events

**Negative:**
- Additional complexity — `OutboxEvent` entity, `OutboxService`,
  `OutboxPoller`, `IncidentEventPublisher` all required
- Outbox rows that hit max retry count (5 attempts) are marked `FAILED`
  and require manual intervention to republish
- The poller introduces approximate delivery timing — events are published
  within `outbox.poll-interval-ms` of commit, not instantaneously

**Recovery procedure for FAILED outbox rows:**
```sql
UPDATE outbox_events 
SET status = 'PENDING', retry_count = 0, last_attempted_at = NULL 
WHERE id = '<outbox-row-id>';
```
The poller picks up the reset row on the next cycle.

**Why this is the most important ADR:** This decision is what makes the
architecture event-driven rather than a polling system. Every other
decision in this project builds on the guarantee that if an incident
is created, the Pipeline will eventually process it — regardless of
broker availability at the time of creation.