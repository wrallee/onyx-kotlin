package com.onyx.kotlin.indexing

import com.onyx.kotlin.connector.ConnectorCredentialPairEntity
import com.onyx.kotlin.connector.ConnectorCredentialPairRepository
import com.onyx.kotlin.connector.ConnectorEntity
import com.onyx.kotlin.connector.ConnectorRepository
import com.onyx.kotlin.connector.ConnectorSource
import com.onyx.kotlin.connector.CredentialEntity
import com.onyx.kotlin.connector.CredentialRepository
import com.onyx.kotlin.connector.PairStatus
import com.onyx.kotlin.connector.ConnectorService
import com.onyx.kotlin.connector.PairMetadataRequest
import com.onyx.kotlin.ingestion.AttemptStatus
import com.onyx.kotlin.ingestion.IndexedDocumentEntity
import com.onyx.kotlin.ingestion.IndexedDocumentRepository
import com.onyx.kotlin.ingestion.IngestionAttemptRepository
import com.onyx.kotlin.ingestion.IngestionJobRepository
import com.onyx.kotlin.ingestion.JobState
import com.onyx.kotlin.opensearch.OpenSearchIndexer
import com.onyx.kotlin.security.CredentialCipher
import com.onyx.kotlin.support.H2IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.ObjectMapper

class ReindexCoordinatorIntegrationTest : H2IntegrationTest() {
    @Autowired private lateinit var coordinator: ReindexCoordinator
    @Autowired private lateinit var settings: IndexSettingsService
    @Autowired private lateinit var searchSettings: SearchSettingsRepository
    @Autowired private lateinit var attempts: IngestionAttemptRepository
    @Autowired private lateinit var jobs: IngestionJobRepository
    @Autowired private lateinit var documents: IndexedDocumentRepository
    @Autowired private lateinit var connectors: ConnectorRepository
    @Autowired private lateinit var credentials: CredentialRepository
    @Autowired private lateinit var pairs: ConnectorCredentialPairRepository
    @Autowired private lateinit var connectorService: ConnectorService
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var cipher: CredentialCipher
    @MockitoBean private lateinit var indexer: OpenSearchIndexer

    @BeforeEach
    fun resetDatabase() {
        truncateTables(
            "ingestion_errors", "ingestion_jobs", "ingestion_attempts", "ingestion_checkpoints",
            "indexed_documents", "connector_credential_pairs", "connectors", "credentials", "search_settings",
        )
        settings.current()
    }

    @Test
    fun `full reindex uses one fixed initial and final range then switches`() {
        createPair()
        createPair()

        val futureId = coordinator.startFull(HARRIER)

        assertThat(settings.current().modelName).isEqualTo(GRANITE)
        val initial = attempts.findAllBySearchSettingsIdOrderByIdAsc(futureId)
        assertThat(initial).hasSize(2).allMatch { it.fromBeginning }
        assertThat(initial.map { it.pollRangeEnd }.distinct()).hasSize(1)

        completeLatest(futureId)
        assertThat(coordinator.currentSchedulingBlocked()).isTrue()
        coordinator.advance()

        val cutover = settings.pending()!!.cutoverAt
        assertThat(cutover).isNotNull()
        val delta = latest(futureId)
        assertThat(delta.values).allMatch { !it.fromBeginning && !it.pruneOnly }
        assertThat(delta.values.map { it.pollRangeEnd }.distinct()).containsExactly(cutover)

        completeLatest(futureId)
        coordinator.advance()
        assertThat(latest(futureId).values).allMatch { it.pruneOnly }

        completeLatest(futureId)
        coordinator.advance()
        assertThat(settings.current().modelName).isEqualTo(HARRIER)
    }

    @Test
    fun `retry all queues only the latest failed connectors with the same range`() {
        val first = createPair()
        val second = createPair()
        val futureId = coordinator.startFull(HARRIER)
        val before = latest(futureId)
        failLatest(futureId, first)
        completeLatest(futureId, second)

        coordinator.retryAll()

        val after = latest(futureId)
        assertThat(after.getValue(first).id).isNotEqualTo(before.getValue(first).id)
        assertThat(after.getValue(first).pollRangeEnd).isEqualTo(before.getValue(first).pollRangeEnd)
        assertThat(after.getValue(second).id).isEqualTo(before.getValue(second).id)
    }

    @Test
    fun `sync and switch reuses the compatible past setting`() {
        createPair()
        val past = searchSettings.save(
            SearchSettingsEntity(
                modelName = HARRIER,
                indexName = "onyx-kotlin-chunks-harrier",
                status = IndexModelStatus.PAST,
                singletonMarker = null,
            ),
        )

        val futureId = coordinator.startSync(HARRIER)

        assertThat(futureId).isEqualTo(past.id)
        assertThat(attempts.findAllBySearchSettingsIdOrderByIdAsc(futureId))
            .singleElement()
            .matches { !it.fromBeginning }
    }

    @Test
    fun `new pair during reindex joins current and future targets`() {
        val futureId = coordinator.startFull(HARRIER)
        val currentId = settings.current().id
        val connector = connectors.save(
            ConnectorEntity(name = "file", source = ConnectorSource.FILE, connectorSpecificConfig = mapper.createObjectNode()),
        )
        val credential = credentials.save(
            CredentialEntity(source = ConnectorSource.FILE, secretJson = cipher.encrypt(mapper.createObjectNode())),
        )

        val pairId = requireNotNull(
            connectorService.associate(
                requireNotNull(connector.id),
                requireNotNull(credential.id),
                PairMetadataRequest(name = "new pair"),
            ).data,
        )

        assertThat(jobs.findFirstByCcPairIdAndSearchSettingsIdAndStateInOrderById(
            pairId, currentId, listOf(JobState.QUEUED, JobState.RUNNING),
        )).isNotNull()
        assertThat(jobs.findFirstByCcPairIdAndSearchSettingsIdAndStateInOrderById(
            pairId, futureId, listOf(JobState.QUEUED, JobState.RUNNING),
        )).isNotNull()
    }

    @Test
    fun `new pair after cutover uses the fixed cutover range`() {
        val futureId = coordinator.startFull(HARRIER)
        coordinator.advance()
        val cutover = settings.pending()!!.cutoverAt
        val connector = connectors.save(
            ConnectorEntity(name = "file", source = ConnectorSource.FILE, connectorSpecificConfig = mapper.createObjectNode()),
        )
        val credential = credentials.save(
            CredentialEntity(source = ConnectorSource.FILE, secretJson = cipher.encrypt(mapper.createObjectNode())),
        )

        val pairId = requireNotNull(
            connectorService.associate(
                requireNotNull(connector.id),
                requireNotNull(credential.id),
                PairMetadataRequest(name = "late pair"),
            ).data,
        )

        assertThat(latest(futureId).getValue(pairId).pollRangeEnd).isEqualTo(cutover)
    }

    @Test
    fun `cancel removes a queued future reindex`() {
        createPair()
        val futureId = coordinator.startFull(HARRIER)

        coordinator.cancel()

        assertThat(settings.pending()).isNull()
        assertThat(jobs.findAllBySearchSettingsIdAndStateIn(futureId, listOf(JobState.CANCELED)))
            .isEmpty()
    }

    @Test
    fun `progress reports document counts based on existing and target indices`() {
        val pairId = createPair()
        val currentId = settings.current().id!!
        documents.save(
            IndexedDocumentEntity(
                ccPairId = pairId,
                searchSettingsId = currentId,
                sourceDocumentId = "doc-1",
                contentHash = "hash-1",
                metadata = mapper.createObjectNode(),
            ),
        )
        documents.save(
            IndexedDocumentEntity(
                ccPairId = pairId,
                searchSettingsId = currentId,
                sourceDocumentId = "doc-2",
                contentHash = "hash-2",
                metadata = mapper.createObjectNode(),
            ),
        )

        val futureId = coordinator.startFull(HARRIER)
        documents.save(
            IndexedDocumentEntity(
                ccPairId = pairId,
                searchSettingsId = futureId,
                sourceDocumentId = "doc-1",
                contentHash = "hash-1",
                metadata = mapper.createObjectNode(),
            ),
        )

        val progress = coordinator.progress()
        assertThat(progress).isNotNull
        assertThat(progress!!.totalDocuments).isEqualTo(2)
        assertThat(progress.completedDocuments).isEqualTo(1)
        assertThat(progress.total).isEqualTo(2)
        assertThat(progress.completed).isEqualTo(1)
        assertThat(progress.totalConnectors).isEqualTo(1)
        assertThat(progress.waiting).isEqualTo(1)
        assertThat(progress.inProgressConnectors).isEqualTo(0)

        val attempt = attempts.findAllBySearchSettingsIdOrderByIdAsc(futureId).first()
        attempt.status = AttemptStatus.IN_PROGRESS
        attempts.save(attempt)

        val updatedProgress = coordinator.progress()
        assertThat(updatedProgress!!.inProgressConnectors).isEqualTo(1)
    }

    private fun completeLatest(settingsId: Long, pairId: Long? = null) {
        latest(settingsId).values.filter { pairId == null || it.ccPairId == pairId }.forEach { attempt ->
            attempt.status = AttemptStatus.SUCCESS
            attempts.save(attempt)
            jobs.findAll().first { it.attemptId == attempt.id }.let { job ->
                job.state = JobState.SUCCEEDED
                job.activeMarker = null
                jobs.save(job)
            }
        }
    }

    private fun failLatest(settingsId: Long, pairId: Long) {
        val attempt = latest(settingsId).getValue(pairId)
        attempt.status = AttemptStatus.FAILED
        attempts.save(attempt)
        jobs.findAll().first { it.attemptId == attempt.id }.let { job ->
            job.state = JobState.FAILED
            job.activeMarker = null
            jobs.save(job)
        }
    }

    private fun latest(settingsId: Long) = attempts.findAllBySearchSettingsIdOrderByIdAsc(settingsId)
        .associateBy { it.ccPairId }

    private fun createPair(): Long {
        val connector = connectors.save(
            ConnectorEntity(name = "file", source = ConnectorSource.FILE, connectorSpecificConfig = mapper.createObjectNode()),
        )
        val credential = credentials.save(
            CredentialEntity(source = ConnectorSource.FILE, secretJson = cipher.encrypt(mapper.createObjectNode())),
        )
        return requireNotNull(
            pairs.save(
                ConnectorCredentialPairEntity(
                    connectorId = requireNotNull(connector.id),
                    credentialId = requireNotNull(credential.id),
                    name = "file pair",
                    status = PairStatus.ACTIVE,
                ),
            ).id,
        )
    }

    private companion object {
        const val GRANITE = "ibm-granite/granite-embedding-311m-multilingual-r2"
        const val HARRIER = "microsoft/harrier-oss-v1-0.6b"
    }
}
