package com.onyx.foss.kotlin.opensearch

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.ai.document.Document
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OpenSearchChunkDocument(
    val ccPairId: Long? = null,
    val sourceDocumentId: String? = null,
    val chunkId: Int? = null,
    val title: String? = null,
    val content: String? = null,
    val link: String? = null,
    val metadata: Map<String, Any?>? = null,
    val embedding: List<Double>? = null,
    val sourceType: String? = null,
    val documentSets: List<String>? = null,
    val docUpdatedAt: String? = null,
    val primaryOwners: List<String>? = null,
    val secondaryOwners: List<String>? = null,
    val externalUserEmails: List<String>? = null,
    val externalUserGroupIds: List<String>? = null,
    val isPublic: Boolean? = null,
) {
    fun toSpringAiDocument(id: String, score: Double? = null): Document {
        val meta = mutableMapOf<String, Any>()
        ccPairId?.let { meta["cc_pair_id"] = it }
        meta["source_document_id"] = sourceDocumentId ?: ""
        meta["chunk_id"] = chunkId ?: 0
        meta["title"] = title ?: ""
        link?.let { meta["link"] = it }
        sourceType?.let { meta["source_type"] = it }
        if (!documentSets.isNullOrEmpty()) meta["document_sets"] = documentSets
        docUpdatedAt?.let { meta["doc_updated_at"] = it }
        if (!primaryOwners.isNullOrEmpty()) meta["primary_owners"] = primaryOwners
        if (!secondaryOwners.isNullOrEmpty()) meta["secondary_owners"] = secondaryOwners
        metadata?.let { meta["metadata"] = it }
        meta["is_public"] = isPublic ?: true

        val builder = Document.builder()
            .id(id)
            .text(content ?: "")
            .metadata(meta)
        if (score != null) {
            builder.score(score)
        }
        return builder.build()
    }

    fun toSearchCandidate(id: String, score: Double, mapper: tools.jackson.databind.ObjectMapper? = null): com.onyx.foss.kotlin.ingestion.SearchCandidate {
        val metaNode = when (val raw = metadata) {
            null -> mapper?.createObjectNode() ?: tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
            else -> mapper?.valueToTree(raw) ?: tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
        }
        return com.onyx.foss.kotlin.ingestion.SearchCandidate(
            id = id,
            sourceDocumentId = sourceDocumentId ?: "",
            chunkId = chunkId ?: 0,
            title = title ?: "",
            content = content ?: "",
            link = link,
            metadata = metaNode,
            retrievalScore = score,
        )
    }

    companion object {
        fun fromSpringAiDocument(doc: Document, embedding: List<Double>? = null): OpenSearchChunkDocument {
            val meta = doc.metadata
            @Suppress("UNCHECKED_CAST")
            return OpenSearchChunkDocument(
                ccPairId = (meta["cc_pair_id"] as? Number)?.toLong(),
                sourceDocumentId = meta["source_document_id"] as? String,
                chunkId = (meta["chunk_id"] as? Number)?.toInt(),
                title = meta["title"] as? String,
                content = doc.text,
                link = meta["link"] as? String,
                metadata = meta["metadata"] as? Map<String, Any?>,
                embedding = embedding,
                sourceType = meta["source_type"] as? String,
                documentSets = meta["document_sets"] as? List<String>,
                docUpdatedAt = meta["doc_updated_at"] as? String,
                primaryOwners = meta["primary_owners"] as? List<String>,
                secondaryOwners = meta["secondary_owners"] as? List<String>,
                externalUserEmails = meta["external_user_emails"] as? List<String>,
                externalUserGroupIds = meta["external_user_group_ids"] as? List<String>,
                isPublic = meta["is_public"] as? Boolean,
            )
        }
    }
}
