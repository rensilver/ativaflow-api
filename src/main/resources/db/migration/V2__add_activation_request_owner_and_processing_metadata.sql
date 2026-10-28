-- consumer_id deve vir da identidade autenticada, nunca do payload da API.
-- Sem backfill: esta migração pressupõe activation_requests vazia.
ALTER TABLE activation_requests
    ADD COLUMN consumer_id VARCHAR(100) NOT NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN claimed_by VARCHAR(100),
    ADD COLUMN last_error_code VARCHAR(100),
    ADD COLUMN reconciliation_required BOOLEAN NOT NULL DEFAULT FALSE,

    ADD CONSTRAINT ck_activation_requests_consumer_id
        CHECK (consumer_id ~ '[^[:space:]]'),
    ADD CONSTRAINT ck_activation_requests_version
        CHECK (version >= 0),
    ADD CONSTRAINT ck_activation_requests_attempt_count
        CHECK (attempt_count >= 0);
