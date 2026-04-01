package com.zoick.pipeline.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    @Value("${rabbitmq.exchange.incident}")
    private String incidentExchange;

    @Value("${rabbitmq.queue.processing}")
    private String processingQueue;

    @Value("${rabbitmq.queue.dlq}")
    private String dlqQueue;

    @Value("${rabbitmq.routing-key.incident-created}")
    private String incidentCreatedRoutingKey;

    @Value("${rabbitmq.routing-key.dlq}")
    private String dlqRoutingKey;

    // Exchange
    @Bean
    public TopicExchange incidentExchange() {
        return new TopicExchange(incidentExchange, true, false);
    }

    // Processing queue with DLQ config
    @Bean
    public Queue processingQueue() {
        return QueueBuilder.durable(processingQueue)
                .withArgument("x-dead-letter-exchange", incidentExchange)
                .withArgument("x-dead-letter-routing-key", dlqRoutingKey)
                .build();
    }

    // Dead letter queue
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(dlqQueue).build();
    }

    // Bindings
    @Bean
    public Binding processingQueueBinding() {
        return BindingBuilder
                .bind(processingQueue())
                .to(incidentExchange())
                .with(incidentCreatedRoutingKey);
    }

    @Bean
    public Binding dlqBinding() {
        return BindingBuilder
                .bind(deadLetterQueue())
                .to(incidentExchange())
                .with(dlqRoutingKey);
    }

    // Message converter
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // RabbitTemplate
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        template.setMandatory(true);
        return template;
    }
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(ConnectionFactory connectionFactory){
        SimpleRabbitListenerContainerFactory factory= new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setMessageConverter(messageConverter());
        return factory;
    }
}