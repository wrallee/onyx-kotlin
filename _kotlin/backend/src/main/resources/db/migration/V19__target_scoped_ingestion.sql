ALTER TABLE ingestion_attempts ADD COLUMN search_settings_id BIGINT REFERENCES search_settings(id) ON DELETE CASCADE;
ALTER TABLE ingestion_jobs ADD COLUMN search_settings_id BIGINT REFERENCES search_settings(id) ON DELETE CASCADE;
ALTER TABLE indexed_documents ADD COLUMN search_settings_id BIGINT REFERENCES search_settings(id) ON DELETE CASCADE;

UPDATE ingestion_attempts SET search_settings_id = (SELECT id FROM search_settings WHERE status = 'PRESENT');
UPDATE ingestion_jobs SET search_settings_id = (SELECT id FROM search_settings WHERE status = 'PRESENT');
UPDATE indexed_documents SET search_settings_id = (SELECT id FROM search_settings WHERE status = 'PRESENT');

ALTER TABLE ingestion_attempts ALTER COLUMN search_settings_id SET NOT NULL;
ALTER TABLE ingestion_jobs ALTER COLUMN search_settings_id SET NOT NULL;
ALTER TABLE indexed_documents ALTER COLUMN search_settings_id SET NOT NULL;

ALTER TABLE ingestion_jobs DROP CONSTRAINT uq_ingestion_job_active_pair;
ALTER TABLE ingestion_jobs ADD CONSTRAINT uq_ingestion_job_active_target
    UNIQUE (cc_pair_id, search_settings_id, active_marker);
ALTER TABLE indexed_documents DROP CONSTRAINT uq_indexed_document_source;
ALTER TABLE indexed_documents ADD CONSTRAINT uq_indexed_document_target_source
    UNIQUE (cc_pair_id, search_settings_id, source_document_id);

ALTER TABLE connector_credential_pairs DROP COLUMN ingestion_claim_token;
ALTER TABLE connector_credential_pairs DROP COLUMN ingestion_lease_expires_at;

CREATE TABLE ingestion_checkpoints_target (
    cc_pair_id BIGINT NOT NULL REFERENCES connector_credential_pairs(id) ON DELETE CASCADE,
    search_settings_id BIGINT NOT NULL REFERENCES search_settings(id) ON DELETE CASCADE,
    checkpoint_json VARCHAR NOT NULL DEFAULT '{}',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (cc_pair_id, search_settings_id)
);
INSERT INTO ingestion_checkpoints_target (cc_pair_id, search_settings_id, checkpoint_json, updated_at)
SELECT cc_pair_id, (SELECT id FROM search_settings WHERE status = 'PRESENT'), checkpoint_json, updated_at
FROM ingestion_checkpoints;
DROP TABLE ingestion_checkpoints;
ALTER TABLE ingestion_checkpoints_target RENAME TO ingestion_checkpoints;
