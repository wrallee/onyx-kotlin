package com.onyx.kotlin.ingestion

import com.onyx.kotlin.ingestion.IndexedDocumentRepository
import com.onyx.kotlin.ingestion.IngestionEnumerationRepository
import com.onyx.kotlin.opensearch.OpenSearchIndexer
import com.onyx.kotlin.opensearch.OpenSearchIndexTarget
import org.springframework.stereotype.Service

@Service
class PruningService(
    private val documents: IndexedDocumentRepository,
    private val enumeration: IngestionEnumerationRepository,
    private val indexer: OpenSearchIndexer,
) {
    fun prune(
        target: OpenSearchIndexTarget,
        pairId: Long,
        searchSettingsId: Long,
        attemptId: Long,
        fromBeginning: Boolean,
        completeEnumeration: Boolean,
        beforeDelete: () -> Unit = {},
    ): Int {
        if (!fromBeginning || !completeEnumeration) return 0
        var afterSourceDocumentId = ""
        var removed = 0
        while (true) {
            val removedIds = enumeration.findMissingPage(
                pairId, searchSettingsId, attemptId, afterSourceDocumentId, PRUNE_PAGE_SIZE,
            )
            if (removedIds.isEmpty()) return removed
            beforeDelete()
            indexer.deleteDocuments(target, pairId, removedIds.toSet())
            documents.deleteByCcPairIdAndSearchSettingsIdAndSourceDocumentIdIn(
                pairId, searchSettingsId, removedIds,
            )
            removed += removedIds.size
            if (removedIds.size < PRUNE_PAGE_SIZE) return removed
            afterSourceDocumentId = removedIds.last()
        }
    }

    private companion object {
        const val PRUNE_PAGE_SIZE = 500
    }
}
