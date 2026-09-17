INSERT INTO search_settings(model_name, model_dim, normalize, index_name, status, singleton_marker)
SELECT
    'ibm-granite/granite-embedding-311m-multilingual-r2',
    768,
    TRUE,
    '${opensearchIndex}',
    'PRESENT',
    1
WHERE NOT EXISTS (SELECT 1 FROM search_settings WHERE status = 'PRESENT');

ALTER TABLE search_settings DROP COLUMN provider_type;
ALTER TABLE search_settings DROP COLUMN model_dim;
ALTER TABLE search_settings DROP COLUMN normalize;
ALTER TABLE search_settings DROP COLUMN query_prefix;
ALTER TABLE search_settings DROP COLUMN passage_prefix;
ALTER TABLE search_settings ADD COLUMN cutover_at TIMESTAMP WITH TIME ZONE;

DROP TABLE reindex_port_attempts;
DROP TABLE embedding_providers;
ALTER TABLE connector_credential_pairs DROP COLUMN full_recollect_requested;
