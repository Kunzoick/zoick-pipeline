package com.zoick.pipeline.service;

import com.zoick.pipeline.entity.ProcessingRecord;
import com.zoick.pipeline.entity.ProcessingStatus;
import com.zoick.pipeline.repository.ProcessingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingWatchdog {

    private final ProcessingRepository processingRepository;
    private final RetryHandler retryHandler;

    @Value("${pipeline.processing-timeout-ms:8000}")
    private long processingTimeoutMs;

    @Scheduled(fixedDelayString = "${pipeline.retry-poll-interval-ms:5000}")
    @Transactional
    public void watch() {
        LocalDateTime threshold = LocalDateTime.now()
                .minusNanos(processingTimeoutMs * 1_000_000L);

        List<ProcessingRecord> stuckRecords = processingRepository
                .findStuckProcessingRecords(ProcessingStatus.PROCESSING, threshold);

        if (stuckRecords.isEmpty()) return;

        log.warn("[PIPELINE] Watchdog found {} stuck records", stuckRecords.size());

        for (ProcessingRecord record : stuckRecords) {
            log.warn("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                            "message=Processing timed out after {}ms — delegating to RetryHandler",
                    record.getCorrelationId(),
                    record.getEventId(),
                    record.getIncidentId(),
                    processingTimeoutMs);

            RuntimeException timeoutException = new RuntimeException(
                    "Processing timeout exceeded " + processingTimeoutMs + "ms");

            String payload = String.format(
                    "{\"eventId\":\"%s\",\"incidentId\":\"%s\",\"correlationId\":\"%s\"}",
                    record.getEventId(),
                    record.getIncidentId(),
                    record.getCorrelationId());

            retryHandler.handleFailure(record, payload, timeoutException);
        }
    }
}