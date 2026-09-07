package com.onyx.foss.kotlin.service

import org.springframework.ai.document.Document
import org.springframework.ai.rag.Query
import org.springframework.ai.rag.retrieval.join.DocumentJoiner

class ScoreNormalizationDocumentJoiner(
    val weights: List<Double> = listOf(0.5, 0.5),
) : DocumentJoiner {

    override fun join(documents: Map<Query, List<List<Document>>>): List<Document> {
        val allLists = documents.values.flatten()
        if (allLists.isEmpty()) return emptyList()
        if (allLists.size == 1) return allLists.first()

        val normalizedScoreMaps = allLists.map { normalizeScores(it) }
        val sources = linkedMapOf<String, Document>()
        allLists.flatten().forEach { doc ->
            sources.putIfAbsent(doc.id, doc)
        }

        return sources.values.map { doc ->
            var compositeScore = 0.0
            normalizedScoreMaps.forEachIndexed { idx, scoreMap ->
                val weight = weights.getOrElse(idx) { 1.0 / allLists.size }
                compositeScore += weight * (scoreMap[doc.id] ?: 0.0)
            }
            doc.mutate().score(compositeScore).build()
        }.sortedWith(compareByDescending<Document> { it.score ?: 0.0 }.thenBy { it.id })
    }

    private fun normalizeScores(docs: List<Document>): Map<String, Double> {
        if (docs.isEmpty()) return emptyMap()
        val scores = docs.mapNotNull { it.score }
        if (scores.isEmpty()) return docs.associate { it.id to 0.0 }
        val min = scores.minOrNull() ?: 0.0
        val max = scores.maxOrNull() ?: 0.0
        return docs.associate { doc ->
            val score = doc.score ?: 0.0
            val normalized = if (max == min) 1.0 else (score - min) / (max - min)
            doc.id to normalized
        }
    }
}

class ReciprocalRankFusionDocumentJoiner(
    val weights: List<Double> = listOf(0.5, 0.5),
    val k: Int = 60,
) : DocumentJoiner {

    override fun join(documents: Map<Query, List<List<Document>>>): List<Document> {
        val allLists = documents.values.flatten()
        if (allLists.isEmpty()) return emptyList()

        val rrfScores = mutableMapOf<String, Double>()
        val idToItem = mutableMapOf<String, Document>()
        val idToSourceIndex = mutableMapOf<String, Int>()
        val idToSourceRank = mutableMapOf<String, Int>()

        allLists.forEachIndexed { sourceIdx, resultList ->
            val weight = weights.getOrElse(sourceIdx) { 1.0 }
            resultList.forEachIndexed { index, item ->
                val rank = index + 1
                val itemId = item.id
                rrfScores[itemId] = (rrfScores[itemId] ?: 0.0) + (weight / (k + rank))
                if (itemId !in idToItem) {
                    idToItem[itemId] = item
                    idToSourceIndex[itemId] = sourceIdx
                    idToSourceRank[itemId] = rank
                }
            }
        }

        return rrfScores.keys.sortedWith(
            compareByDescending<String> { rrfScores[it] ?: 0.0 }
                .thenBy { idToSourceRank[it] ?: Int.MAX_VALUE }
                .thenBy { idToSourceIndex[it] ?: Int.MAX_VALUE },
        ).map { id ->
            val item = idToItem.getValue(id)
            item.mutate().score(rrfScores[id]).build()
        }
    }
}
