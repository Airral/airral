-- V8: Cross-instance lease for external job sync.
-- Production can run multiple backend instances, so the sync worker needs a DB
-- lease instead of relying only on an in-memory flag.

CREATE TABLE external_job_sync_locks (
    lock_name    VARCHAR(100) PRIMARY KEY,
    locked_by    VARCHAR(255),
    locked_until TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_external_job_sync_locks_until ON external_job_sync_locks(locked_until);
