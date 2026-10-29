-- Igualdade pelo consumidor seguida da ordem estável da listagem sem filtros.
CREATE INDEX idx_activation_requests_consumer_created_id
    ON activation_requests (consumer_id, created_at DESC, id DESC);

-- O status é opcional; fixá-lo antes das datas permite ler a página na ordem do índice.
CREATE INDEX idx_activation_requests_consumer_status_created_id
    ON activation_requests (consumer_id, status, created_at DESC, id DESC);

-- customer_id é comparado por igualdade exata. No filtro combinado, status pode ser residual.
CREATE INDEX idx_activation_requests_consumer_customer_created_id
    ON activation_requests (consumer_id, customer_id, created_at DESC, id DESC);
