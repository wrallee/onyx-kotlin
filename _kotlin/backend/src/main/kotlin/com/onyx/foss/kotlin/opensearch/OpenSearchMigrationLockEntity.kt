package com.onyx.foss.kotlin.opensearch

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "opensearch_index_migration_lock")
class OpenSearchIndexMigrationLockEntity(
    @Id
    var id: Short = 1,
)
