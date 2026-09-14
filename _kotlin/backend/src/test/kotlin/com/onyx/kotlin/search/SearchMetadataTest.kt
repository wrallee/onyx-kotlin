package com.onyx.kotlin.search

import com.onyx.kotlin.connector.ConnectorSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SearchMetadataTest {
    @Test
    fun `normalizes Jira project status and document type`() {
        val metadata = IndexedMetadata.from(
            ConnectorSource.JIRA,
            mapOf("project" to " ONYX ", "status" to "In Progress"),
        )

        assertThat(metadata).isEqualTo(
            IndexedMetadata(
                projectKey = "onyx",
                status = "in progress",
                documentType = "jira_issue",
            ),
        )
    }

    @Test
    fun `distinguishes merged pull requests and Confluence attachments`() {
        val pullRequest = IndexedMetadata.from(
            ConnectorSource.GITHUB,
            mapOf(
                "repository" to "Example/Repo",
                "object_type" to "PullRequest",
                "state" to "closed",
                "merged" to true,
            ),
        )
        val attachment = IndexedMetadata.from(
            ConnectorSource.CONFLUENCE,
            mapOf("space" to "ENG", "parent_page_id" to "page-1"),
        )

        assertThat(pullRequest.repository).isEqualTo("example/repo")
        assertThat(pullRequest.status).isEqualTo("merged")
        assertThat(pullRequest.documentType).isEqualTo("github_pull_request")
        assertThat(pullRequest.embeddingContext(ConnectorSource.GITHUB)).contains(
            "Source: github",
            "Repository: example/repo",
            "Status: merged",
        )
        assertThat(pullRequest.searchContext(ConnectorSource.GITHUB))
            .isEqualTo("github github_pull_request example/repo merged")
        assertThat(attachment.space).isEqualTo("eng")
        assertThat(attachment.documentType).isEqualTo("confluence_attachment")
    }

    @Test
    fun `normalizes and validates metadata filters`() {
        val filters = SearchMetadataFilters(
            projectKeys = listOf(" ONYX ", "onyx"),
            repositories = listOf("Example/Repo"),
            documentTypes = listOf("GITHUB_PULL_REQUEST"),
        ).normalized()

        assertThat(filters.projectKeys).containsExactly("onyx")
        assertThat(filters.repositories).containsExactly("example/repo")
        assertThat(filters.documentTypes).containsExactly("github_pull_request")
        assertThrows(IllegalArgumentException::class.java) {
            SearchMetadataFilters(documentTypes = listOf("unknown")).normalized()
        }
    }
}
