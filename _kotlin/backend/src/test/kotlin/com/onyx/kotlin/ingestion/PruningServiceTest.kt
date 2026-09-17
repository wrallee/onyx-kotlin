package com.onyx.kotlin.ingestion

import com.onyx.kotlin.opensearch.OpenSearchIndexer
import com.onyx.kotlin.opensearch.OpenSearchIndexTarget

import tools.jackson.databind.ObjectMapper
import com.onyx.kotlin.connector.ConnectorCredentialPairEntity
import com.onyx.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.kotlin.connector.ConnectorEntity
import com.onyx.kotlin.connector.ConnectorRepository
import com.onyx.kotlin.connector.ConnectorSource
import com.onyx.kotlin.connector.CredentialEntity
import com.onyx.kotlin.connector.CredentialRepository
import com.onyx.kotlin.ingestion.IndexedDocumentEntity
import com.onyx.kotlin.ingestion.IndexedDocumentRepository
import com.onyx.kotlin.ingestion.IngestionAttemptEntity
import com.onyx.kotlin.ingestion.IngestionAttemptRepository
import com.onyx.kotlin.security.CredentialCipher
import com.onyx.kotlin.support.H2IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

class PruningServiceTest : H2IntegrationTest() {
    private val target = OpenSearchIndexTarget("target-index", 1)
    @Autowired private lateinit var pruning: PruningService
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var cipher: CredentialCipher
    @Autowired private lateinit var connectors: ConnectorRepository
    @Autowired private lateinit var credentials: CredentialRepository
    @Autowired private lateinit var pairs: ConnectorCredentialPairRepository
    @Autowired private lateinit var documents: IndexedDocumentRepository
    @Autowired private lateinit var attempts: IngestionAttemptRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @MockitoBean private lateinit var indexer: OpenSearchIndexer

    @BeforeEach
    fun resetDatabase() {
        truncateTables(
            "ingestion_errors", "ingestion_jobs", "ingestion_attempts", "ingestion_checkpoints",
            "indexed_documents", "connector_credential_pairs", "connectors", "credentials",
        )
        jdbc.update("DELETE FROM search_settings WHERE status <> 'PRESENT'")
    }

    @Test
    fun deletesDocumentsMissingFromCompleteEnumeration() {
        val pairId = createPairWithDocuments("seen", "removed")

        val removed = prune(pairId, setOf("seen"), emptySet(), fromBeginning = true, completeEnumeration = true)

        assertThat(removed).isEqualTo(1)
        assertThat(documents.findAll().map { it.sourceDocumentId }).containsExactly("seen")
        verify(indexer).deleteDocuments(target, pairId, setOf("removed"))
    }

    @Test
    fun keepsDocumentWhoseRetrievalReturnedFailure() {
        val pairId = createPairWithDocuments("seen", "failed")

        val removed = prune(
            pairId,
            seenDocumentIds = setOf("seen"),
            failedDocumentIds = setOf("failed"),
            fromBeginning = true,
            completeEnumeration = true,
        )

        assertThat(removed).isZero()
        assertThat(documents.findAll().map { it.sourceDocumentId }).containsExactlyInAnyOrder("seen", "failed")
        verifyNoInteractions(indexer)
    }

    @Test
    fun leavesDatabaseRowsWhenOpenSearchDeletionFails() {
        val pairId = createPairWithDocuments("removed")
        doThrow(IllegalStateException("OpenSearch failed")).`when`(indexer)
            .deleteDocuments(target, pairId, setOf("removed"))

        assertThrows<IllegalStateException> {
            prune(pairId, emptySet(), emptySet(), fromBeginning = true, completeEnumeration = true)
        }

        assertThat(documents.findAll().map { it.sourceDocumentId }).containsExactly("removed")
    }

    @Test
    fun `pruning one search setting keeps another setting documents`() {
        val pairId = createPairWithDocuments("removed")
        val futureId = createFutureSetting()
        documents.save(
            IndexedDocumentEntity(
                ccPairId = pairId,
                searchSettingsId = futureId,
                sourceDocumentId = "removed",
                title = "removed",
                contentHash = "removed",
                metadata = mapper.createObjectNode(),
            ),
        )

        prune(pairId, emptySet(), emptySet(), fromBeginning = true, completeEnumeration = true)

        assertThat(documents.countByCcPairIdAndSearchSettingsId(pairId, 1)).isZero()
        assertThat(documents.countByCcPairIdAndSearchSettingsId(pairId, futureId)).isEqualTo(1)
    }

    @Test
    fun doesNotPruneAfterIncrementalCheckpointRun() {
        val pairId = createPairWithDocuments("existing")

        val removed = prune(
            pairId,
            emptySet(),
            emptySet(),
            fromBeginning = false,
            completeEnumeration = true,
        )

        assertThat(removed).isZero()
        assertThat(documents.findAll().map { it.sourceDocumentId }).containsExactly("existing")
        verifyNoInteractions(indexer)
    }

    @Test
    fun deletesInBoundedPages() {
        val pairId = createPairWithDocuments(*(1..1001).map { "document-${it.toString().padStart(4, '0')}" }.toTypedArray())

        val removed = prune(pairId, emptySet(), emptySet(), fromBeginning = true, completeEnumeration = true)

        assertThat(removed).isEqualTo(1001)
        val pageSizes = mockingDetails(indexer).invocations
            .filter { it.method.name == "deleteDocuments" }
            .map { (it.arguments[2] as Set<*>).size }
        assertThat(pageSizes).containsExactly(500, 500, 1)
        assertThat(documents.countByCcPairId(pairId)).isZero()
    }

    private fun createPairWithDocuments(vararg sourceDocumentIds: String): Long {
        val connector = connectors.save(
            ConnectorEntity(
                name = "file",
                source = ConnectorSource.FILE,
                connectorSpecificConfig = mapper.createObjectNode(),
            ),
        )
        val credential = credentials.save(
            CredentialEntity(
                source = ConnectorSource.FILE,
                secretJson = cipher.encrypt(mapper.createObjectNode()),
            ),
        )
        val pair = pairs.save(
            ConnectorCredentialPairEntity(
                connectorId = requireNotNull(connector.id),
                credentialId = requireNotNull(credential.id),
                name = "file pair",
            ),
        )
        sourceDocumentIds.forEach { sourceDocumentId ->
            documents.save(
                IndexedDocumentEntity(
                    ccPairId = requireNotNull(pair.id),
                    searchSettingsId = 1,
                    sourceDocumentId = sourceDocumentId,
                    title = sourceDocumentId,
                    contentHash = sourceDocumentId,
                    metadata = mapper.createObjectNode(),
                ),
            )
        }
        return requireNotNull(pair.id)
    }

    private fun createFutureSetting(): Long {
        jdbc.update(
            """
                INSERT INTO search_settings(model_name, index_name, status, singleton_marker)
                VALUES ('microsoft/harrier-oss-v1-0.6b', 'target-index', 'FUTURE', 1)
            """.trimIndent(),
        )
        return requireNotNull(
            jdbc.queryForObject("SELECT id FROM search_settings WHERE status = 'FUTURE'", Long::class.java),
        )
    }

    private fun prune(
        pairId: Long,
        seenDocumentIds: Set<String>,
        failedDocumentIds: Set<String>,
        fromBeginning: Boolean,
        completeEnumeration: Boolean,
    ): Int {
        val attemptId = requireNotNull(
            attempts.save(IngestionAttemptEntity(ccPairId = pairId, searchSettingsId = 1)).id,
        )
        (seenDocumentIds + failedDocumentIds).forEach { sourceDocumentId ->
            jdbc.update(
                "INSERT INTO ingestion_enumerated_documents(attempt_id, source_document_id) VALUES (?, ?)",
                attemptId,
                sourceDocumentId,
            )
        }
        return pruning.prune(target, pairId, 1, attemptId, fromBeginning, completeEnumeration)
    }
}
