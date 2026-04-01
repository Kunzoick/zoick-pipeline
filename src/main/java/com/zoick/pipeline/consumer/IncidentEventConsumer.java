package com.zoick.pipeline.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.zoick.pipeline.event.IncidentCreatedEvent;
import com.zoick.pipeline.service.ProcessingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class IncidentEventConsumer{
    private static final Logger log= LoggerFactory.getLogger(IncidentEventConsumer.class);
    private final ProcessingService processingService;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${rabbitmq.queue.processing}")
    public void consume(String rawPayload, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException{
        IncidentCreatedEvent event;
        //Deserialize first-> if this fails it is a poison message
        //Nack without requeue- routes to DLQ via dead letter args on queue
        try{
            event= objectMapper.readValue(rawPayload, IncidentCreatedEvent.class);
        }catch (Exception e){
            log.error("[PIPELINE] correlationId=unknown " +
                            "message=Deserialization failed — routing to DLQ reason={}",
                    e.getMessage());
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        try{
            MDC.put("correlationId", event.getCorrelationId());
            MDC.put("incidentId", event.getIncidentId());
            log.info("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                            "severity={} message=Event received",
                    event.getCorrelationId(),
                    event.getEventId(),
                    event.getIncidentId(),
                    event.getSeverity());

            // Write ProcessingRecord BEFORE acknowledging
            // If app crashes here, message is redelivered
            // Idempotency check on eventId makes redelivery safe
            processingService.process(event);
            //Only acknowledge After successful DB write
            channel.basicAck(deliveryTag, false);
            log.debug("[PIPELINE] correlationId={} eventId={} " +
                            "message=Message acknowledged",
                    event.getCorrelationId(),
                    event.getEventId());
        }catch(DataAccessException e){
            // Database failure — requeue, do not route to DLQ
            log.error("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                            "message=Database unavailable — requeuing reason={}",
                    event.getCorrelationId(),
                    event.getEventId(),
                    event.getIncidentId(),
                    e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        } catch (Exception e) {
            // Processing failure — requeue for retry handler to manage
            log.error("[PIPELINE] correlationId={} eventId={} incidentId={} " +
                            "message=Processing failed — requeuing reason={}",
                    event.getCorrelationId(),
                    event.getEventId(),
                    event.getIncidentId(),
                    e.getMessage());
            channel.basicNack(deliveryTag, false, true);
        }finally {
            MDC.clear();
        }
    }
}