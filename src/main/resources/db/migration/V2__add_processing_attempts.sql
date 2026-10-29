ALTER TABLE activation_requests
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX ix_activation_requests_pending
    ON activation_requests (next_attempt_at, created_at)
    WHERE status = 'PENDING';
