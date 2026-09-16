package com.onyx.kotlin.indexing

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
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

enum class EmbeddingProviderType(@get:JsonValue val value: String) {
    OPENAI_COMPATIBLE("openai_compatible");

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): EmbeddingProviderType =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported embedding provider: $value")
    }
}

@Entity
@Table(name = "embedding_providers")
class EmbeddingProviderEntity(
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type")
    var providerType: EmbeddingProviderType = EmbeddingProviderType.OPENAI_COMPATIBLE,
    @Column(name = "api_url", nullable = false)
    var apiUrl: String = "",
    @Column(name = "api_key_encrypted")
    var apiKeyEncrypted: String? = null,
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

@Entity
@Table(name = "search_settings")
class SearchSettingsEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "model_name", nullable = false)
    var modelName: String = "",
    @Column(name = "model_dim", nullable = false)
    var modelDim: Int = 0,
    @Column(nullable = false)
    var normalize: Boolean = true,
    @Column(name = "query_prefix")
    var queryPrefix: String? = null,
    @Column(name = "passage_prefix")
    var passagePrefix: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type")
    var providerType: EmbeddingProviderType? = null,
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
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

interface EmbeddingProviderRepository : JpaRepository<EmbeddingProviderEntity, EmbeddingProviderType>

interface SearchSettingsRepository : JpaRepository<SearchSettingsEntity, Long> {
    fun findByStatus(status: IndexModelStatus): SearchSettingsEntity?
    fun existsByProviderTypeAndStatusIn(
        providerType: EmbeddingProviderType,
        statuses: Collection<IndexModelStatus>,
    ): Boolean
}
