# Phase 1: 현재 구현과 검증 범위 확인 - Pattern Map

**Mapped:** 2026-09-10
**Files analyzed:** 9
**Analogs found:** 8 / 9

## File Classification

Phase 1은 `01-BASELINE.md`만 만든다. 나머지 파일은 이 문서가 후속 단계로 보내는 Kotlin 수정 대상이다.

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `.planning/phases/01-baseline-verification/01-BASELINE.md` | documentation | batch transform | `.planning/phases/01-baseline-verification/01-VALIDATION.md` | exact |
| `_kotlin/backend/Dockerfile` | build config | batch | — | no analog |
| `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt` | service | file I/O + CRUD | same file: `storeZipEntry` cleanup boundary | exact |
| `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt` | integration test | request-response + file I/O | same file: upload and ZIP failure tests | exact |
| `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt` | connector service | request-response + batch | `ConfluenceConnectorLoader.kt` comment aggregation | role-match |
| `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoaderTest.kt` | connector test | request-response | `ConfluenceConnectorLoaderTest.kt` comment contract | exact |
| `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt` | MCP component | request-response + transform | same file: tool error boundary and schema | exact |
| `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpSearchToolTest.kt` | unit test | request-response | same file: invalid filter and alias tests | exact |
| `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpEndpointIntegrationTest.kt` | integration test | request-response | same file: remote discovery and call test | exact |

## Pattern Assignments

### `.planning/phases/01-baseline-verification/01-BASELINE.md` (documentation, batch transform)

**Analog:** `.planning/phases/01-baseline-verification/01-VALIDATION.md`

**Evidence-status pattern** (lines 31-50):

```markdown
| Task ID | Plan | Wave | Requirement | ... | Automated Command | File Exists | Status |
| 01-01-01 | 01 | 1 | BASE-01 | ... | `test -s .../01-BASELINE.md` | ❌ W0 | ⬜ pending |

| Behavior | Requirement | Why Manual | Test Instructions |
| 평가 MCP 서버와 현재 Kotlin artifact의 동일성 | BASE-02 | 외부 배포 artifact와 commit 정보가 없다. | `NOT_RUN`으로 기록하고 Phase 5 live 검증으로 보낸다. |
```

Use one ledger row per requirement. Use these columns from research: `ID`, `source`, `implementation`, `test`, `current status`, `command/result`, `gap`, `follow-up phase`.

### `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt` (service, file I/O + CRUD)

**Analog:** existing cleanup in the same file.

**File cleanup pattern** (lines 196-220):

```kotlin
try {
    Files.newOutputStream(path).use { output ->
        // Copy and validate the entry.
    }
} catch (error: Exception) {
    Files.deleteIfExists(path)
    throw error
}
return StoredZipEntry(
    fileAssets.save(FileAssetEntity(assetId, name, contentType, extractedBytes - priorBytes, path.toString())),
    extractedBytes,
)
```

Extend this existing boundary. Track only files created by the current `upload` or `updateConnectorFiles` call. Delete them when later storage, metadata merge, connector update, or enqueue fails. Do not add a second storage abstraction.

**Test pattern:** `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt` lines 530-634.

```kotlin
val response = request(
    multipart("/manage/admin/connector/file/upload")
        .file(MockMultipartFile("files", "large.zip", "application/zip", oversizedZipFile())),
)

assertThat(response.status).isEqualTo(400)
```

Keep this request-level test. Add post-failure assertions for both asset rows and files created by that request.

### `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt` (connector service, request-response + batch)

**Analog:** `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt`

**Sub-resource aggregation pattern** (lines 378-435):

```kotlin
val comments = if (context.config.boolean("include_comments", true)) comments(context, pageId) else ""
// ...
content = (parsePageHtml(context, html, mutableSetOf()) + comments).trim()

private fun comments(context: Context, pageId: String): String {
    val cql = "type=comment and container='$pageId'${labelFilter(context.config)}"
    val comments = paginate(context, buildCqlPath(cql, COMMENT_EXPAND), DEFAULT_PAGE_SIZE)
        .map { parsePageHtml(context, it.path("body").path("storage").path("value").asString(), mutableSetOf()) }
        .filter(String::isNotBlank)
    return comments.joinToString(separator = "", prefix = if (comments.isEmpty()) "" else "\n") { "Comment:\n$it" }
}
```

Add PR review comments at the existing PR detail conversion boundary. Preserve the current page, cursor, origin, and per-item failure rules.

**MockWebServer pattern:** `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoaderTest.kt` lines 221-247.

```kotlin
server.dispatcher = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        requested += request.path.orEmpty()
        return when (request.requestUrl!!.encodedPath) {
            "/repos/test-org/test-repo/pulls/7" -> json(pull(7, body = "detail body"))
            else -> json("[]")
        }
    }
}

val document = loader().load(config(server), credentials(), null).flatMap { it.documents }.single()
assertEquals("detail body", document.content)
```

Extend this dispatcher with the review-comment endpoint. Assert both the requested path and indexed comment text.

### `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/mcp/McpSearchTool.kt` (MCP component, request-response + transform)

**Analog:** existing tool error boundary and schema in the same file.

**Validation and error pattern** (lines 34-59):

```kotlin
fun callSearch(arguments: Map<String, Any>, defaultDocumentSets: List<String> = emptyList()): McpSchema.CallToolResult = try {
    val query = arguments["query"] as? String ?: throw IllegalArgumentException("query must be a string")
    // Parse all input before calling search.search(...).
    val response = search.search(query, documentSets, limit, searchType, sourceTypes, timeCutoff)
    McpSchema.CallToolResult.builder().structuredContent(response).build()
} catch (error: Exception) {
    McpSchema.CallToolResult.builder()
        .addTextContent(error.message ?: "Search failed")
        .isError(true)
        .build()
}
```

Make `parseSourceTypes` reject any non-string or unknown value before `search.search`. Keep the existing tool-error response.

**Canonical schema pattern** (lines 151-155, 208-236):

```kotlin
const val TOOL_SEARCH_INDEXED_DOCUMENTS = "search_indexed_documents"

val SEARCH_INPUT_SCHEMA: Map<String, Any> = mapOf(
    "type" to "object",
    "properties" to mapOf(
        "document_set_names" to mapOf(
            "type" to "array",
            "items" to mapOf("type" to "string", "minLength" to 1),
            "uniqueItems" to true,
        ),
    ),
    "additionalProperties" to false,
)
```

Keep `search_indexed_documents` and `document_set_names`. Remove only the MCP tool and input aliases. Keep transport query/header fallback outside this schema.

**Unit test pattern:** `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpSearchToolTest.kt` lines 131-147.

```kotlin
val result = tool.callSearch(
    mapOf("query" to "deployment guide", "source_types" to listOf("jira", "not-a-real-source")),
)

assertThat(result.isError() == true).isFalse()
```

Invert this old expectation. Assert `isError`, the message, and that no downstream search call occurs.

**Endpoint test pattern:** `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/mcp/McpEndpointIntegrationTest.kt` lines 41-73.

```kotlin
McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build().use { client ->
    client.initialize()
    assertThat(client.listTools().tools().map(McpSchema.Tool::name)).containsExactlyInAnyOrder(
        "search_indexed_documents",
        "weighted_reciprocal_rank_fusion",
        "get_document_context",
    )
    client.callTool(
        McpSchema.CallToolRequest.builder("search_indexed_documents")
            .arguments(mapOf("query" to "deployment guide", "document_set_names" to listOf("Engineering")))
            .build(),
    )
}
```

Use remote discovery once to verify the public contract. Unit tests cover the removed input alias.

## Shared Patterns

### Fail before side effects

Validate MCP input before search. Validate file work at the shared storage boundary. Return the existing typed MCP error or rethrow the existing service error.

### Connector extension

Fetch the child resource inside the existing connector loader. Use the existing HTTP client, pagination checks, and item failure collection. Verify it with one MockWebServer dispatcher test.

### Test scope

Use JUnit/Mockito for MCP transformation, MockWebServer for GitHub HTTP contracts, and the existing H2 request test for file rollback. Add no framework or fixture.

### Source boundary

All named analogs are git-tracked. Root Python and root Web remain reference-only. `_kotlin/web` needs no change for these backend contracts.

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `_kotlin/backend/Dockerfile` | build config | batch | No tracked Kotlin backend image uses the required wrapper and Java 25 pair. Use `gradle-wrapper.properties` line 3 and `build.gradle.kts` lines 17-26 as the contract. |

Required Docker change: copy `gradlew`, `gradle/wrapper`, and build files before source. Run `./gradlew --no-daemon bootJar`. Use Java 25 in both stages. Preserve the current non-root runtime user and storage ownership.

## Metadata

**Analog search scope:** `.planning`, `_kotlin/backend`, `_kotlin/web`
**Files scanned:** 14
**Pattern extraction date:** 2026-09-10
