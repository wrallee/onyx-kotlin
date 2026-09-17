package com.onyx.kotlin.documentset

import com.onyx.kotlin.indexing.SearchRuntimeSettings
import com.onyx.kotlin.ingestion.IndexedDocumentRepository
import com.onyx.kotlin.opensearch.OpenSearchIndexer
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

@Service
class DocumentSetIndexSyncService(
    private val documents: IndexedDocumentRepository,
    private val documentSets: DocumentSetRepository,
    private val indexer: OpenSearchIndexer,
) {
    fun syncPair(pairId: Long, runtime: SearchRuntimeSettings, renew: () -> Boolean = { true }): Boolean {
        val names = documentSets.findNamesByCcPairId(pairId)
        var afterSourceDocumentId = ""
        while (true) {
            val page = documents.findAllByCcPairIdAndSearchSettingsIdAndSourceDocumentIdGreaterThanOrderBySourceDocumentId(
                pairId, runtime.settingsId, afterSourceDocumentId, PageRequest.of(0, DOCUMENT_PAGE_SIZE),
            )
            if (page.isEmpty()) return true
            if (!renew()) return false
            indexer.updateDocumentSets(runtime.index, pairId, page.map { it.sourceDocumentId }.toSet(), names)
            if (page.size < DOCUMENT_PAGE_SIZE) return true
            afterSourceDocumentId = page.last().sourceDocumentId
        }
    }

    private companion object {
        const val DOCUMENT_PAGE_SIZE = 500
    }
}
