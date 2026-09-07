# Plan: Kotlin Backend Build Warnings Cleanup

## Issues to Address
When building the Kotlin backend (`compileKotlin`, `compileTestKotlin`), 219 compiler and deprecation warnings are emitted:
1. **Jackson 3 API deprecations (212 warnings)**: `JsonNode.asText()` -> `asString()`, `asText(default)` -> `asString(default)`, and `isTextual` -> `isString` across 7 production files and 10 test files.
2. **Kotlin KT-73255 annotation target warning (2 warnings)**: `@JsonAlias` in `ApiModels.kt` on constructor value parameters without explicit target (`@param:JsonAlias`).
3. **Apache HttpClient 5 / OpenSearch Java 3.10.0 deprecations (2 warnings)**: `RequestConfig.Builder.setConnectTimeout` is deprecated in HttpClient 5 (should use `setConnectionConfigCallback`), and `ClientTlsStrategyBuilder.build()` is deprecated in favor of `buildAsync()`.
4. **Kotlin unnecessary non-null assertion (1 warning)**: Redundant `!!` on smart-casted variable `previousChunkId` in `SearchService.kt`.
5. **Netty test utility deprecation (2 warnings)**: `SelfSignedCertificate` in `OpenSearchIndexerTest.kt` for SSL tests.

## Important Notes
- OpenSearch Java Client 3.10.0 introduces `setConnectionConfigCallback` on `ApacheHttpClient5TransportBuilder`, allowing idiomatic connection-level timeout configuration without using deprecated `RequestConfig.setConnectTimeout`.
- Jackson 3 (`tools.jackson.core:jackson-databind:3.1.4`) renamed textual inspection and extraction methods from `asText()` to `asString()` and `isTextual` to `isString()`. The old methods delegate directly to the new ones and are marked `@Deprecated`.
- In Netty 4.2+, `SelfSignedCertificate` is deprecated as a production safety precaution; suppressing deprecation within the specific test function is standard practice for certificate mock testing.

## Implementation Strategy
1. **API Models (`ApiModels.kt`)**: Add `@param:` target to `@JsonAlias` annotations on data class constructor parameters.
2. **OpenSearch Client Factory (`OpenSearchClientFactory.kt`)**:
   - Use `builder.setConnectionConfigCallback` for connection timeouts.
   - Use `ClientTlsStrategyBuilder.buildAsync()` instead of `build()`.
3. **Search Service (`SearchService.kt`)**: Remove redundant `!!` on `previousChunkId`.
4. **Jackson 3 Method Migration (`asString`, `isString`)**:
   - Batch update production files (`ConfluenceConnectorLoader.kt`, `FileConnectorLoader.kt`, `GithubConnectorLoader.kt`, `JiraConnectorLoader.kt`, `OpenSearchIndexer.kt`, `AdminService.kt`, `FileStorageService.kt`).
   - Batch update test files (`AdminApiIntegrationTest.kt`, `ConnectorModelsTest.kt`, `DocumentSetSyncOutboxIntegrationTest.kt`, `JiraConnectorLoaderTest.kt`, `ModelServerClientTest.kt`, `OpenSearchIndexerIntegrationTest.kt`, `OpenSearchIndexerTest.kt`, `MinMaxNormalizationPipelineTest.kt`, `ZScoreNormalizationPipelineTest.kt`, `CredentialCipherTest.kt`).
5. **Test Utility Deprecation (`OpenSearchIndexerTest.kt`)**: Annotate `accepts self-signed OpenSearch certificate when verification is disabled` with `@Suppress("DEPRECATION")`.

## Tests
- Verify zero warnings with `./backend/gradlew -p backend compileKotlin compileTestKotlin --warning-mode all`.
- Run backend unit and mock tests: `./backend/gradlew -p backend test`.
