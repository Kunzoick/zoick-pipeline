package com.zoick.pipeline.service;

import com.zoick.pipeline.entity.ProcessingRecord;
import com.zoick.pipeline.entity.ProcessingStatus;
import com.zoick.pipeline.repository.ProcessingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessingStatusService {
    private final ProcessingRepository processingRepository;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transitionToProcessing(ProcessingRecord record, String correlationId, String eventId, String incidentId){
        record.setStatus(ProcessingStatus.PROCESSING);
        record.setUpdatedAt(LocalDateTime.now());
        processingRepository.save(record);

        log.info("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                        "status=PROCESSING message=Starting processing",
                correlationId, eventId, incidentId);
    }
}
