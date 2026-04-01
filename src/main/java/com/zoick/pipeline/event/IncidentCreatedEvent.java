package com.zoick.pipeline.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentCreatedEvent {
    private String eventId;
    private String incidentId;
    private String correlationId;
    private String title;
    private String severity;
    private String status;
    private Instant occurredAt;
    private String publishedBy;
}
