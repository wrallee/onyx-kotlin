package com.onyx.kotlin.connector

import tools.jackson.databind.JsonNode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.io.Serializable
import java.time.Instant
import java.util.UUID

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

enum class ConnectorSource(@get:JsonValue val value: String) {
    FILE("file"),
    JIRA("jira"),
    CONFLUENCE("confluence"),
    GITHUB("github");

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): ConnectorSource =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported connector type: $value")
    }
}

enum class PairStatus { SCHEDULED, INITIAL_INDEXING, ACTIVE, PAUSED, DELETING, INVALID }
@Entity
@Table(name = "credentials")
class CredentialEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Enumerated(EnumType.STRING)
    var source: ConnectorSource = ConnectorSource.FILE,
    var name: String? = null,
    @Column(name = "secret_json", nullable = false, columnDefinition = "text")
    var secretJson: String = "",
    @Column(name = "admin_public", nullable = false)
    var adminPublic: Boolean = true,
    @Column(name = "curator_public", nullable = false)
    var curatorPublic: Boolean = true,
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

@Entity
@Table(name = "connectors")
class ConnectorEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    var name: String = "",
    @Enumerated(EnumType.STRING)
    var source: ConnectorSource = ConnectorSource.FILE,
    @Column(name = "input_type", nullable = false)
    var inputType: String = "load_state",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connector_specific_config", nullable = false, columnDefinition = "varchar")
    var connectorSpecificConfig: JsonNode? = null,
    @Column(name = "refresh_freq")
    var refreshFreq: Long? = null,
    @Column(name = "prune_freq")
    var pruneFreq: Long? = null,
    @Column(name = "indexing_start")
    var indexingStart: Instant? = null,
    @Column(nullable = false)
    var deleting: Boolean = false,
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

@Entity
@Table(
    name = "connector_credential_pairs",
    indexes = [Index(name = "idx_cc_pair_connector", columnList = "connector_id")],
)
class ConnectorCredentialPairEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "connector_id", nullable = false)
    var connectorId: Long = 0,
    @Column(name = "credential_id", nullable = false)
    var credentialId: Long = 0,
    var name: String = "",
    @Column(name = "access_type", nullable = false)
    var accessType: String = "public",
    @Enumerated(EnumType.STRING)
    var status: PairStatus = PairStatus.ACTIVE,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "auto_sync_options", columnDefinition = "varchar")
    var autoSyncOptions: JsonNode? = null,
    @Column(name = "processing_mode", nullable = false)
    var processingMode: String = "REGULAR",
    @Column(name = "in_repeated_error_state", nullable = false)
    var inRepeatedErrorState: Boolean = false,
    @Column(name = "last_pruned_at")
    var lastPrunedAt: Instant? = null,
    @Column(name = "ingestion_claim_token")
    var ingestionClaimToken: UUID? = null,
    @Column(name = "ingestion_lease_expires_at")
    var ingestionLeaseExpiresAt: Instant? = null,
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
    @UpdateTimestamp @Column(name = "updated_at")
    var updatedAt: Instant? = null,
)

@Entity
@Table(name = "file_assets")
class FileAssetEntity(
    @Id
    var id: String = "",
    @Column(name = "original_name", nullable = false)
    var originalName: String = "",
    @Column(name = "media_type")
    var mediaType: String? = null,
    @Column(name = "byte_size", nullable = false)
    var byteSize: Long = 0,
    @Column(name = "storage_path", nullable = false)
    var storagePath: String = "",
    @CreationTimestamp @Column(name = "created_at", updatable = false)
    var createdAt: Instant? = null,
)
