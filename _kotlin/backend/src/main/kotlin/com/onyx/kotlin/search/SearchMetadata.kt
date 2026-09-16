package com.onyx.kotlin.search

import com.onyx.kotlin.connector.ConnectorSource
import java.util.Locale

enum class IndexedDocumentType(val value: String) {
    JIRA_ISSUE("jira_issue"),
    GITHUB_PULL_REQUEST("github_pull_request"),
    GITHUB_ISSUE("github_issue"),
    GITHUB_FILE("github_file"),
    CONFLUENCE_PAGE("confluence_page"),
    CONFLUENCE_ATTACHMENT("confluence_attachment"),
    FILE("file"),
}

data class IndexedMetadata(
    val projectKey: String? = null,
    val repository: String? = null,
    val space: String? = null,
    val status: String? = null,
    val documentType: String? = null,
) {
    fun embeddingContext(source: ConnectorSource): String = listOfNotNull(
        "Source: ${source.value}",
        documentType?.let { "Document type: $it" },
        projectKey?.let { "Project: $it" },
        repository?.let { "Repository: $it" },
        space?.let { "Space: $it" },
        status?.let { "Status: $it" },
    ).joinToString("\n")

    fun searchContext(source: ConnectorSource?): String? = listOfNotNull(
        source?.value,
        documentType,
        projectKey,
        repository,
        space,
        status,
    ).joinToString(" ").takeIf(String::isNotBlank)

    companion object {
        fun from(source: ConnectorSource, metadata: Map<String, Any?>): IndexedMetadata {
            val documentType = when (source) {
                ConnectorSource.FILE -> IndexedDocumentType.FILE.value
                ConnectorSource.JIRA -> IndexedDocumentType.JIRA_ISSUE.value
                ConnectorSource.CONFLUENCE -> if (
                    metadata["parent_page_id"] != null || metadata["mime_type"] != null
                ) {
                    IndexedDocumentType.CONFLUENCE_ATTACHMENT.value
                } else {
                    IndexedDocumentType.CONFLUENCE_PAGE.value
                }
                ConnectorSource.GITHUB -> when (metadata.string("object_type")?.lowercase(Locale.ROOT)) {
                    "pullrequest" -> IndexedDocumentType.GITHUB_PULL_REQUEST.value
                    "issue" -> IndexedDocumentType.GITHUB_ISSUE.value
                    "file" -> IndexedDocumentType.GITHUB_FILE.value
                    else -> null
                }
            }
            val status = when (source) {
                ConnectorSource.JIRA -> metadata.string("status")
                ConnectorSource.GITHUB -> if (metadata["merged"] == true) "merged" else metadata.string("state")
                else -> null
            }
            return IndexedMetadata(
                projectKey = metadata.string("project").normalized(),
                repository = metadata.string("repository").normalized(),
                space = metadata.string("space").normalized(),
                status = status.normalized(),
                documentType = documentType,
            )
        }

        private fun Map<String, Any?>.string(key: String): String? = (this[key] as? String)
            ?.takeIf(String::isNotBlank)

        private fun String?.normalized(): String? = this?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
    }
}

data class SearchMetadataFilters(
    val projectKeys: List<String> = emptyList(),
    val repositories: List<String> = emptyList(),
    val spaces: List<String> = emptyList(),
    val statuses: List<String> = emptyList(),
    val documentTypes: List<String> = emptyList(),
) {
    fun normalized(): SearchMetadataFilters = SearchMetadataFilters(
        projectKeys = projectKeys.normalizeValues(),
        repositories = repositories.normalizeValues(),
        spaces = spaces.normalizeValues(),
        statuses = statuses.normalizeValues(),
        documentTypes = documentTypes.normalizeValues().also { types ->
            val supported = IndexedDocumentType.entries.mapTo(mutableSetOf(), IndexedDocumentType::value)
            require(types.all(supported::contains)) {
                "Unsupported document types: ${types.filterNot(supported::contains).joinToString()}"
            }
        },
    )

    private fun List<String>.normalizeValues(): List<String> {
        require(none(String::isBlank)) { "metadata filter values must not be blank" }
        return map { it.trim().lowercase(Locale.ROOT) }.distinct()
    }
}
