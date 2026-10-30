-- Metadado técnico opcional: preserva registros e escritores anteriores à correlação.
ALTER TABLE activation_requests
    ADD COLUMN correlation_id VARCHAR(128);
