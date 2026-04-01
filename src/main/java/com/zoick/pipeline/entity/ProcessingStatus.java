package com.zoick.pipeline.entity;

public enum ProcessingStatus {
    PENDING,
    PROCESSING,
    PENDING_RETRY,
    COMPLETED,
    FAILED,
    DEAD
}
