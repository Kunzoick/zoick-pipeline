package com.zoick.pipeline.service;

import com.zoick.pipeline.entity.ProcessingRecord;
import com.zoick.pipeline.entity.ProcessingStatus;
import com.zoick.pipeline.repository.ProcessingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryHandler {
    private final ProcessingRepository processingRepository;
    private final RabbitTemplate rabbitTemplate;
    private final PipelineMetrics pipelineMetrics;
    //@Value("${rabbitmq.queue.dlq}")
    //private String dlqQueue;

    @Value("${rabbitmq.exchange.incident}")
    private String exchange;
    @Value("${rabbitmq.routing-key.dlq}")
    private String dlqRoutingKey;

    private static final int MAX_ATTEMPTS= 3;
    @Transactional
    public void handleFailure(ProcessingRecord record, String payload, Exception cause){
       int attempt= record.getRetryCount() + 1;
       record.setRetryCount(attempt);
       record.setFailureReason(cause.getMessage());
       record.setUpdatedAt(LocalDateTime.now());

       if(attempt >= MAX_ATTEMPTS){
           record.setStatus(ProcessingStatus.FAILED);//max retires exceeded-> route to dlq
           processingRepository.save(record);

           rabbitTemplate.convertAndSend(exchange, dlqRoutingKey, payload);
           pipelineMetrics.incrementFailed();
           log.error("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                           "retryCount={} status=FAILED " +
                           "message=Max retries exceeded, routed to DLQ reason={}",
                   record.getCorrelationId(),
                   record.getEventId(),
                   record.getIncidentId(),
                   attempt,
                   cause.getMessage());
       }else{
           //schedule retry with exp backoff
           long backoffSeconds= (long) Math.pow(2, attempt);
           record.setStatus(ProcessingStatus.PENDING_RETRY);
           record.setNextAttemptAt(LocalDateTime.now().plusSeconds(backoffSeconds));
           processingRepository.save(record);
           log.warn("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                           "attempt={} backoffSeconds={} status=PENDING_RETRY " +
                           "message=Processing failed, scheduled for retry reason={}",
                   record.getCorrelationId(),
                   record.getEventId(),
                   record.getIncidentId(),
                   attempt,
                   backoffSeconds,
                   cause.getMessage());
       }
    }
}
