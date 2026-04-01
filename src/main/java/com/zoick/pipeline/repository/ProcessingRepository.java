package com.zoick.pipeline.repository;

import com.zoick.pipeline.entity.ProcessingRecord;
import com.zoick.pipeline.entity.ProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProcessingRepository extends JpaRepository<ProcessingRecord, String> {
    //idempotency check- this is the deduplication key
    Optional<ProcessingRecord> findByEventId(String eventId);
    // useful for admin queries and observability
    Optional<ProcessingRecord> findByIncidentId(String incidentId);
    //retry poller query-> only picks up records whose backoff window has passed
    @Query("""
        SELECT r FROM ProcessingRecord r
        WHERE r.status = :status
        AND (r.nextAttemptAt IS NULL OR r.nextAttemptAt <= :now)
        ORDER BY r.createdAt ASC
        """)
    List<ProcessingRecord> findEligibleForRetry(ProcessingStatus status, LocalDateTime now);

    @Query("""
        SELECT r FROM ProcessingRecord r
        WHERE r.status = :status
        AND r.updatedAt < :threshold
        ORDER BY r.updatedAt ASC
        """)
    List<ProcessingRecord> findStuckProcessingRecords(ProcessingStatus status, LocalDateTime threshold);
}
