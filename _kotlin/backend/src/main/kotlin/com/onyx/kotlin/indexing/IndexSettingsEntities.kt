package com.onyx.kotlin.indexing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

enum class IndexModelStatus { PRESENT, FUTURE, PAST }

@Entity
@Table(name = "search_settings")
class SearchSettingsEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "model_name", nullable = false)
    var modelName: String = "",
    @Column(name = "index_name", nullable = false)
    var indexName: String = "",
    @Enumerated(EnumType.STRING)
    var status: IndexModelStatus = IndexModelStatus.PRESENT,
    @Column(name = "singleton_marker")
    var singletonMarker: Short? = 1,
    @Column(name = "reindex_started_at")
    var reindexStartedAt: Instant? = null,
    @Column(name = "cancel_requested_at")
    var cancelRequestedAt: Instant? = null,
    @Column(name = "cutover_at")
    var cutoverAt: Instant? = null,
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

interface SearchSettingsRepository : JpaRepository<SearchSettingsEntity, Long> {
    fun findByStatus(status: IndexModelStatus): SearchSettingsEntity?
    fun findFirstByModelNameAndStatusOrderByIdAsc(modelName: String, status: IndexModelStatus): SearchSettingsEntity?
}
