package com.zoick.pipeline.service;

import com.zoick.pipeline.entity.ProcessingRecord;
import com.zoick.pipeline.entity.ProcessingStatus;
import com.zoick.pipeline.event.IncidentCreatedEvent;
import com.zoick.pipeline.repository.ProcessingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingService {
    private final ProcessingRepository processingRepository;
    private final RetryHandler retryHandler;
    private final ProcessingStatusService processingStatusService;
    private final PipelineMetrics pipelineMetrics;

    @Value("${pipeline.simulate-failure:false}")
    private boolean simulateFailure;
    @Value("${pipeline.simulate-slow-processing-ms:0}")
    private long simulateSlowProcessingMs;
    @Value("${pipeline.simulate-failure-on-attempt:0}")
    private int simulateFailureOnAttempt;


    public void process(IncidentCreatedEvent event) {
        String correlationId = event.getCorrelationId();
        String eventId = event.getEventId();
        String incidentId = event.getIncidentId();
        log.debug("[PIPELINE] simulateFailure={} simulateFailureOnAttempt={} simulateSlowMs={}",
                simulateFailure, simulateFailureOnAttempt, simulateSlowProcessingMs);

        //idempotency check
        Optional<ProcessingRecord> existing = processingRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            ProcessingStatus currentStatus = existing.get().getStatus();
            if (currentStatus == ProcessingStatus.COMPLETED || currentStatus == ProcessingStatus.DEAD) {
                log.info("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                                "status=DUPLICATE message=Event already processed, skipping",
                        correlationId, eventId, incidentId);
                return;
            }
            // if pending_retry-> allow reprocessing vai poller
        }
        //Create Pending record
        ProcessingRecord record = existing.orElseGet(() -> ProcessingRecord.builder()
                .id(UUID.randomUUID().toString()).eventId(eventId).incidentId(incidentId)
                .correlationId(correlationId).status(ProcessingStatus.PENDING).retryCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        processingRepository.save(record);
       /* log.info("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                        "status=PROCESSING message=Starting processing",
                correlationId, eventId, incidentId);
        record.setStatus(ProcessingStatus.PROCESSING);
        record.setUpdatedAt(LocalDateTime.now());
        processingRepository.save(record);
        */
        processingStatusService.transitionToProcessing(record, correlationId, eventId, incidentId);
        // Re-fetch after REQUIRES_NEW commit so outer session has a managed entity
// Without this the outer transaction tries to INSERT again → duplicate key
       // record = processingRepository.findById(record.getId()).orElse(record);

        // testing code block
        try{
            //Simulate slow processing if configured
            if(simulateSlowProcessingMs > 0){
                log.warn("[PIPELINE] correlationId={} incidentId={} " +
                                "message=Simulating slow processing for {}ms",
                        correlationId, incidentId, simulateSlowProcessingMs);
                Thread.sleep(simulateSlowProcessingMs);
                //capture updatedAt before sleep- used to detect watchdog intervention
                LocalDateTime processingStartedAt = record.getUpdatedAt();

                //check if watchdog intervened during sleep
                ProcessingRecord current= processingRepository.findById(record.getId()).orElse(record);
                if(current.getStatus() != ProcessingStatus.PROCESSING || !current.getUpdatedAt().equals(processingStartedAt)){
                    log.warn("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                                    "message=Processing aborted- watchdog changed state to {}",
                            correlationId, eventId, incidentId, current.getStatus());
                    return;
                }
            }
            //simulate failure if configured
            if(simulateFailure && (simulateFailureOnAttempt == 0 || record.getRetryCount() < simulateFailureOnAttempt)){
                throw new RuntimeException("Simulated failure on attempt "+ record.getRetryCount());
        }
        //processing completed
        record.setStatus(ProcessingStatus.COMPLETED);
        record.setUpdatedAt(LocalDateTime.now());
        record.setCompletedAt(LocalDateTime.now());
        processingRepository.save(record);
        pipelineMetrics.incrementCompleted();
        log.info("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                        "status=COMPLETED message=Processing complete",
                correlationId, eventId, incidentId);
    }catch(InterruptedException e){
        Thread.currentThread().interrupt();
        retryHandler.handleFailure(record, buildPayload(event), e);
    }catch(Exception e){
        log.error("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                        "message=Processing failed, delegating to RetryHandler reason={}",
                correlationId, eventId, incidentId, e.getMessage());
        retryHandler.handleFailure(record, buildPayload(event), e);
    }

/**
        try {
            //core processing logic
            log.debug("[PIPELINE] correlationId={} incidentId={} message=Processing incident",
                    correlationId, incidentId);
            record.setStatus(ProcessingStatus.COMPLETED);
            record.setUpdatedAt(LocalDateTime.now());
            record.setCompletedAt(LocalDateTime.now());
            processingRepository.save(record);
            log.info("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                            "status=COMPLETED message=Processing complete",
                    correlationId, eventId, incidentId);

        } catch (Exception e) {
            log.error("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                            "message=Processing failed, delegating to RetryHandler reason={}",
                    correlationId, eventId, incidentId, e.getMessage());
/**
 * delegate to retry handler-> no rethrow, acknowledged message, retry managed at application layer
 * known limitation-> crash between acknowledgment and DB write loses the attempt, write state before acknowledging

            retryHandler.handleFailure(record, buildPayload(event), e);
        }
 **/
    }
    /**
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionToProcessing(ProcessingRecord record,
                                       String correlationId,
                                       String eventId,
                                       String incidentId) {
        record.setStatus(ProcessingStatus.PROCESSING);
        record.setUpdatedAt(LocalDateTime.now());
        processingRepository.save(record);

        log.info("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                        "status=PROCESSING message=Starting processing",
                correlationId, eventId, incidentId);
    }

     **/

    private String buildPayload(IncidentCreatedEvent event) {
        return String.format("{\"eventId\":\"%s\",\"incidentId\":\"%s\",\"correlationId\":\"%s\"}",
                event.getEventId(), event.getIncidentId(), event.getCorrelationId());
    }
}
