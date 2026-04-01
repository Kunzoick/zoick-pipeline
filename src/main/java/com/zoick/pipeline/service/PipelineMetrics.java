package com.zoick.pipeline.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
@Component
public class PipelineMetrics {
    private final Counter completedCounter;
    private final Counter failedCounter;
    private final Counter deadCounter;

    public PipelineMetrics(MeterRegistry registry){
        this.completedCounter= Counter.builder("pipeline.incidents.completed")
                .description("Number of incindets successfully processed").register(registry);
        this.failedCounter= Counter.builder("pipeline.incidents.failed")
                .description("Number of incindets that exceeded max retries and routed to DLQ").register(registry);
        this.deadCounter= Counter.builder("pipeline.incidents.dead")
                .description("Number of incindets marked as DEAD after reaching DLQ").register(registry);
    }
    public void incrementCompleted(){ completedCounter.increment();}
    public void incrementFailed(){ failedCounter.increment();}
    public void incrementDead(){ deadCounter.increment();}
}
