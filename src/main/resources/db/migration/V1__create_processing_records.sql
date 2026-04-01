CREATE TABLE processing_records (
    id                  VARCHAR(36)     NOT NULL,
    event_id            VARCHAR(36)     NOT NULL,
    incident_id         VARCHAR(36)     NOT NULL,
    correlation_id      VARCHAR(36)     NOT NULL,
    status              VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    retry_count         INT             NOT NULL DEFAULT 0,
    failure_reason      VARCHAR(500)    NULL,
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at        DATETIME        NULL,

    CONSTRAINT pk_processing_records PRIMARY KEY (id),
    CONSTRAINT uq_processing_event_id UNIQUE (event_id)
) ENGINE=InnoDB;

CREATE INDEX idx_processing_incident_id
    ON processing_records (incident_id);

CREATE INDEX idx_processing_status
    ON processing_records (status);