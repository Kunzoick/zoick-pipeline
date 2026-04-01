package com.zoick.pipeline.service;

import com.zoick.pipeline.entity.ProcessingRecord;
import com.zoick.pipeline.entity.ProcessingStatus;
import com.zoick.pipeline.event.IncidentCreatedEvent;
import com.zoick.pipeline.repository.ProcessingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryPoller {
    private final ProcessingRepository processingRepository;
    private final ObjectMapper objectMapper;
    private final ProcessingService processingService;

    @Scheduled(fixedDelayString = "${pipeline.retry-poll-interval-ms:5000}")
    public void poll(){
        List<ProcessingRecord> eligible= processingRepository.findEligibleForRetry(ProcessingStatus.PENDING_RETRY,
                LocalDateTime.now());
        if(eligible.isEmpty()) return;
        log.debug("[PIPELINE] RetryPoller found {} eligible records", eligible.size());

        for(ProcessingRecord record : eligible){
            try{
                IncidentCreatedEvent event = IncidentCreatedEvent.builder().eventId(record.getEventId())
                        .incidentId(record.getIncidentId()).correlationId(record.getCorrelationId())
                        .build();
                log.info("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                                "attempt={} message=Retrying processing",
                        record.getCorrelationId(),
                        record.getEventId(),
                        record.getIncidentId(),
                        record.getRetryCount() + 1);
                processingService.process(event);
            }catch(Exception e){
                log.error("[PIPELINE] correlationId={} eventId={} " +
                                "message=RetryPoller failed to reprocess reason={}",
                        record.getCorrelationId(), record.getEventId(), e.getMessage());
            }
        }
    }
}
