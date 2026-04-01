# ADR-001 — Message Broker Choice: RabbitMQ over Kafka

**Status:** Accepted
**Project:** zoick-pipeline — Async Incident Processing Pipeline

---
## Context
The pipeline requires a message broker to decouple incident creation in the
Trust API from asynchronous processing in the Pipeline service. The broker
must support:

- Reliable message delivery between two independent Spring Boot services
- Dead letter queue routing for poison messages and retry exhaustion
- Per-message acknowledgement for at-least-once delivery guarantees
- Local development without cloud infrastructure
- A management UI for inspecting queue state during development and testing

Two candidates were evaluated: RabbitMQ and Apache Kafka.
---

## Decision
RabbitMQ was chosen as the message broker.
---

## Alternatives Considered
### Apache Kafka
Kafka is a distributed log platform designed for high-throughput event
streaming at scale. It was rejected for the following reasons:

**Setup complexity.** Kafka requires ZooKeeper (or KRaft in newer versions),
a broker cluster, and topic configuration before a single message can be
published. RabbitMQ runs as a single Docker container with a management UI
available immediately.

**Feature mismatch.** Kafka does not have native dead letter queue support.
DLQ behaviour in Kafka requires additional consumer logic, separate topics,
and explicit routing code. RabbitMQ supports dead letter exchanges and
routing keys natively — a queue can be configured with `x-dead-letter-exchange`
and `x-dead-letter-routing-key` arguments and poison message routing is
handled by the broker automatically.

**Operational overhead.** Kafka is designed for partitioned, replicated,
ordered logs consumed by multiple independent consumer groups at high volume.
This project has one producer and one consumer. Kafka's architecture provides
no benefit at this scale and adds significant operational complexity.

**Learning curve.** Kafka's consumer group model, partition assignment, and
offset management introduce concepts that are not relevant to this use case.
RabbitMQ's queue-based model maps directly to the requirements: publish a
message, consume it once, acknowledge or reject.

### In-process event publishing (no broker)
Spring's `ApplicationEventPublisher` was considered for local event handling.
Rejected because it provides no durability, no retry, no dead letter routing,
and no decoupling between services. In-process events disappear if the
application crashes.
---

## Consequences
**Positive:**
- Single Docker container, running in under 30 seconds
- Native DLQ support via dead letter exchange arguments
- Management UI at port 15672 for real-time queue inspection during testing
- AMQP protocol support via Spring AMQP — well-integrated with Spring Boot
- Per-message acknowledgement supports at-least-once delivery guarantees
- Publisher confirms available for guaranteed broker receipt verification

**Negative:**
- RabbitMQ does not provide the persistent ordered log that Kafka offers.
  If replay of historical messages were required, Kafka would be the
  correct choice.
- Guest user restricted to loopback connections — requires a named user
  for WSL2/Docker environments where the client and broker are on different
  virtual network interfaces.

**Accepted tradeoff:** RabbitMQ is the ideal tool for this use case.
Kafka would be the correct choice for a high-throughput event streaming
system requiring message replay, consumer group fan-out, or ordered
partition processing. Neither requirement exists in this project.