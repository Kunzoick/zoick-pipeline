package com.zoick.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zoick.pipeline.entity.ProcessingStatus;
import com.zoick.pipeline.event.IncidentCreatedEvent;
import com.zoick.pipeline.repository.ProcessingRepository;
import com.zoick.pipeline.service.PipelineMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class DLQConsumer {
    private final ProcessingRepository processingRepository;
    private final ObjectMapper objectMapper;
    private final PipelineMetrics pipelineMetrics;

    @RabbitListener(queues =  "${rabbitmq.queue.dlq}")
    @Transactional
    public void consume(String rawPayload){
        try {
            IncidentCreatedEvent event= objectMapper.readValue(rawPayload, IncidentCreatedEvent.class);
            processingRepository.findByEventId(event.getEventId()).ifPresent(record -> {
                record.setStatus(ProcessingStatus.DEAD);
                record.setUpdatedAt(java.time.LocalDateTime.now());
                processingRepository.save(record);
            });
            pipelineMetrics.incrementDead();
            log.error("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                            "status=DEAD message=Event reached DLQ — manual intervention required",
                    event.getCorrelationId(),
                    event.getEventId(),
                    event.getIncidentId());
        }catch(Exception e){
            log.error("[PIPELINE] correlationId=unknown " +
                    "message=DLQ message could not be deserialized reason={}", e.getMessage());
        }
    }
}
