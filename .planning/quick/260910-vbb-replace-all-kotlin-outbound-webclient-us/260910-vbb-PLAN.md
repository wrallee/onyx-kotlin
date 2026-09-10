---
phase: quick-remove-webflux
plan: "260910-vbb"
type: execute
wave: 1
depends_on: []
files_modified:
  - _kotlin/backend/build.gradle.kts
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/ModelServerHttpClient.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/RuntimeConfiguration.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoader.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt
  - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ModelServerClientTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoaderTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoaderTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoaderTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/RemoteConnectorLoadersTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt
  - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt
autonomous: true
requirements:
  - MODEL-01
  - CONNECTOR-02
  - CONNECTOR-03
  - CONNECTOR-04
  - BUILD-01
estimate:
  tokens: 60000
  raw_tokens: 60000
  tasks: 3
  confidence: low
must_haves:
  truths:
    - "All Kotlin outbound HTTP calls use synchronous Spring RestClient while preserving existing request and response contracts."
    - "Model-server calls preserve configured connect and read timeouts, bounded responses, and retries for network and 5xx failures only."
    - "Jira, Confluence, and GitHub preserve authentication, pagination, binary downloads, response headers, typed HTTP errors, and rate-limit behavior."
    - "Production clients validate TLS, never follow redirects, and bound success and error bodies before conversion."
    - "Kotlin production and test source has no reactive HTTP client or Reactor Netty use, and the WebFlux runtime dependency is absent."
  artifacts:
    - path: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/ModelServerHttpClient.kt"
      provides: "Shared JDK-backed RestClient transport settings and model-server client construction"
    - path: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt"
      provides: "Blocking bounded JSON, text, and binary connector HTTP operations"
    - path: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt"
      provides: "Blocking model-server embedding call with bounded retry"
    - path: "_kotlin/backend/build.gradle.kts"
      provides: "Spring MVC dependency set without the WebFlux starter"
    - path: "_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/RemoteConnectorLoadersTest.kt"
      provides: "Shared connector transport regression coverage"
  key_links:
    - from: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/RuntimeConfiguration.kt"
      to: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt"
      via: "Spring injects one cloneable RestClient.Builder into the blocking connector client."
      pattern: "RestClient\\.Builder"
    - from: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/ModelServerHttpClient.kt"
      to: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt"
      via: "ModelServerClient uses the shared blocking transport settings with model-specific timeout values."
      pattern: "buildModelServerClient"
    - from: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt"
      to: "_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoader.kt"
      via: "Spring RestClient response exceptions retain status, headers, and bounded error text for connector-specific policy."
      pattern: "RestClientResponseException"
---

<objective>
Replace every Kotlin outbound reactive HTTP path with blocking Spring RestClient and remove the WebFlux dependency.

Purpose: Keep the Spring MVC application synchronous while preserving all existing external HTTP behavior.
Output: Blocking model-server and connector clients, migrated tests, and a dependency graph without Spring WebFlux or Reactor Netty.
</objective>

<execution_context>
@/home/wrallee/.codex/gsd-core/workflows/execute-plan.md
@/home/wrallee/.codex/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/PROJECT.md
@.planning/REQUIREMENTS.md
@AGENTS.md
@CONTRIBUTING.md
@_kotlin/backend/build.gradle.kts
@_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/ModelServerHttpClient.kt
@_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/RuntimeConfiguration.kt
@_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt
@_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt
@_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoader.kt
@_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt
@_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt

<interfaces>
Spring Framework 7 RestClient is synchronous and supports `retrieve().body`, `retrieve().toEntity`, request factories, interceptors, and direct `exchange`. `retrieve` maps 4xx and 5xx responses to RestClient exceptions. `exchange` requires explicit status handling.

Use `JdkClientHttpRequestFactory` with one reusable Java `HttpClient`. Set connect timeout on the Java client and read timeout on the request factory. Use `HttpClient.Redirect.NEVER`. The JDK client uses the default SSL context and hostname verification unless tests explicitly configure otherwise.

The shared response bound is 16 MiB. Apply it to successful and error responses before Jackson, text, or byte-array conversion. Keep Spring's status-specific `HttpClientErrorException` and `HttpServerErrorException` subclasses so existing 404 and 5xx branches remain typed.

`reactor-core` remains a transitive dependency of MCP and Spring AI. Remove only direct Reactor source use and the WebFlux/Reactor-Netty dependency path.
</interfaces>
</context>

## Issues to Address

- The Servlet application blocks on reactive clients and carries an unused WebFlux server/client stack.
- Model-server retry logic depends on Reactor despite a synchronous caller contract.
- Connector loaders inspect reactive exception types for authentication, rate limits, fallback, and partial-failure behavior.
- Tests use reactive filters and Netty helpers, which prevent dependency removal.

## Important Notes

- Keep `spring-boot-starter-web`; it already supplies Spring RestClient. Add no dependency.
- Reuse one small blocking transport configuration. Do not add a client interface, factory hierarchy, or connector-specific wrapper.
- Preserve the current 16 MiB response bound for JSON, text, binary, and error bodies.
- Preserve default TLS and hostname verification in production. Any trust-all setup stays test-only for the existing OpenSearch container.
- Preserve request headers on the original request and disable automatic redirects. This prevents credential forwarding to another origin.
- The current baseline command for `ModelServerClientTest` and `RemoteConnectorLoadersTest` passed at commit `4d6a6ea5a`.
- Official Spring documentation states that RestClient is synchronous, `retrieve` handles 4xx/5xx by default, and `exchange` requires explicit error handling.
- The API coverage detector returned `detected:false`. This changes transport for existing APIs and adds no API capability.
- The assumption-delta query returned `phase_unresolved`; do not record a fabricated identity-model decision.
- No ORM schema file is in the mutable scope. Do not add a schema push task.

## Implementation strategy

First migrate one complete model-server request through Spring configuration, timeout, response-bound, and retry handling. Then migrate the shared connector client and its typed error consumers. Last, remove reactive test utilities and the WebFlux starter after all source compiles without them.

## Tests

Use existing MockWebServer tests for model-server and connector behavior. Keep the real OpenSearch integration suite for its HTTPS test container. Add only focused cases for network retry, redirect refusal, and the 16 MiB bound.

## Source Coverage Audit

| Source | ID | Feature or requirement | Task | Status | Notes |
|--------|----|------------------------|------|--------|-------|
| GOAL | — | Replace every Kotlin outbound reactive client with blocking RestClient and remove WebFlux | 1, 2, 3 | COVERED | Quick-task description |
| REQ | MODEL-01 | Preserve embedding request and response order, errors, and model-server contract | 1 | COVERED | Existing and added MockWebServer cases |
| REQ | CONNECTOR-02 | Preserve Jira validation, Cloud cursor, Server offset, bulk fetch, typed errors, and rate limits | 2 | COVERED | Shared client plus Jira suite |
| REQ | CONNECTOR-03 | Preserve Confluence Cloud and Server pages, attachments, comments, pagination, fallback, and Retry-After | 2 | COVERED | Shared client plus Confluence suite |
| REQ | CONNECTOR-04 | Preserve GitHub repositories, branches, files, pull requests, issues, cursors, and rate reset | 2 | COVERED | Shared client plus GitHub suite |
| REQ | BUILD-01 | Clean Kotlin production and test compilation has no target warnings | 3 | COVERED | Clean compile and dependency checks |
| RESEARCH | — | RestClient is Spring's synchronous fluent client; retrieve preserves typed 4xx/5xx errors | 1, 2 | COVERED | Spring Framework 7 reference |
| RESEARCH | — | JdkClientHttpRequestFactory supports Java HttpClient and explicit read timeout | 1 | COVERED | Spring Framework 7 API |
| CONTEXT | C-01 | The application remains Spring MVC and Servlet based | 1, 2 | COVERED | No reactive server path is added |
| CONTEXT | C-02 | Convert all Kotlin outbound WebClient paths, including production and tests | 1, 2, 3 | COVERED | Live source inventory defines the files |
| CONTEXT | C-03 | Preserve timeout, authentication, retry, redirect, response-limit, and HTTP-error behavior | 1, 2 | COVERED | Focused behaviors and threat mitigations |
| CONTEXT | C-04 | Remove the WebFlux starter and reactive-only Reactor Netty use | 3 | COVERED | Source and dependency gates |
| CONTEXT | C-05 | Keep the full existing model-server, Jira, Confluence, and GitHub capability surface | 1, 2 | COVERED | No endpoint or method is removed |
| CONTEXT | C-06 | Add no dependency or abstraction unless unavoidable | 1, 2, 3 | COVERED | Reuse Spring Web, JDK HTTP, and existing test dependencies |

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1: Send one model-server embedding request through blocking RestClient</name>
  <files>_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/ModelServerHttpClient.kt, _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/RuntimeConfiguration.kt, _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt, _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ModelServerClientTest.kt</files>
  <behavior>
    - Test 1: Query and passage requests still POST the same JSON fields to `/encoder/bi-encoder-embed` and return embeddings in input order.
    - Test 2: The configured connect and read timeouts terminate blocked model-server calls.
    - Test 3: A network failure or 5xx response retries at most `embedMaxRetries` times with the configured deterministic exponential backoff, then returns success or rethrows the last failure.
    - Test 4: A 4xx response and a response beyond 16 MiB do not retry.
    - Test 5: A redirect response is not followed.
  </behavior>
  <action>Change the runtime bean to `RestClient.Builder`. In `ModelServerHttpClient.kt`, configure the builder with a reusable Java `HttpClient` and `JdkClientHttpRequestFactory`. Apply model-server connect and read timeout values. Set redirect policy to `NEVER`. Keep the JDK default TLS context and hostname checks. Add the smallest shared response interceptor needed to buffer at most 16 MiB before Spring converts success or error bodies; keep status-specific RestClient exceptions and do not retry an oversize response.

Replace the reactive chain in `ModelServerClient` with one blocking RestClient POST. Keep the exact request JSON and embedding conversion. Replace Reactor retry with a direct bounded loop. Retry only `ResourceAccessException` and 5xx `RestClientResponseException` results. Keep the configured retry count, initial backoff, doubling schedule, and zero jitter. Preserve thread interruption by restoring the interrupt flag and rethrowing. Update `ModelServerClientTest` first, using MockWebServer disconnection, delay, status, redirect, and body cases. Do not introduce Spring Retry or a new client abstraction.</action>
  <verify>
    <automated>cd _kotlin/backend &amp;&amp; ./gradlew test --tests "com.onyx.foss.kotlin.ingestion.ModelServerClientTest" --no-daemon</automated>
  </verify>
  <done>One embedding path works end to end through RestClient with the same payload, bounded response, timeouts, retries, and terminal failures.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: Preserve all Jira, Confluence, and GitHub connector HTTP behavior</name>
  <files>_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt, _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoader.kt, _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt, _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt, _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoaderTest.kt, _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoaderTest.kt, _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoaderTest.kt, _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/RemoteConnectorLoadersTest.kt</files>
  <behavior>
    - Test 1: GET JSON, GET with headers, POST JSON, GET bytes, and POST text keep their current status, header, content-type, and body contracts.
    - Test 2: Basic and bearer credentials remain on the original Jira, Confluence, and GitHub requests; redirected targets receive no request.
    - Test 3: Jira keeps project and JQL validation, Cloud tenant discovery and cursor search, Server offset search, bulk POST, JSON-failure splitting, and typed 400/401/403/404/429 handling.
    - Test 4: Confluence keeps Cloud and Server fallback, page, attachment, comment, binary download, pagination, page-size recovery, and Retry-After behavior.
    - Test 5: GitHub keeps repository, branch, file, pull-request, issue, cursor, and rate-reset behavior.
    - Test 6: Responses above the old 256 KiB default still pass through 16 MiB, while success and error bodies beyond 16 MiB fail before conversion.
  </behavior>
  <action>Build one thread-safe RestClient in `RemoteJsonClient` from the injected builder. Reuse the blocking JDK transport configuration with 30-second connect and read timeouts, redirect refusal, default TLS verification, and the 16 MiB bound. Translate the five existing methods directly: JSON GET, response-aware JSON GET, JSON POST, binary GET, and status-preserving text POST. Use `retrieve` where 4xx and 5xx must throw. Use `exchange` only for `postText`, which intentionally returns every HTTP status, and read its bounded body explicitly.

Change loader catches and helpers to Spring's blocking response exception types without changing their status, header, or response-text branches. Keep status-specific 404 handling, Jira JSON decode recovery, Confluence Retry-After and page-size recovery, and GitHub rate-reset waits. Replace test builders and Atlassian URL-rewrite filters with RestClient request interceptors; keep rewrites test-only. Extend `RemoteConnectorLoadersTest` for redirect refusal and the exact 16 MiB boundary. Do not change endpoint construction, authentication values, pagination, checkpoint, sleep, or heartbeat logic.</action>
  <verify>
    <automated>cd _kotlin/backend &amp;&amp; ./gradlew test --tests "com.onyx.foss.kotlin.ingestion.JiraConnectorLoaderTest" --tests "com.onyx.foss.kotlin.ingestion.ConfluenceConnectorLoaderTest" --tests "com.onyx.foss.kotlin.ingestion.GithubConnectorLoaderTest" --tests "com.onyx.foss.kotlin.ingestion.RemoteConnectorLoadersTest" --no-daemon</automated>
  </verify>
  <done>All existing connector HTTP verbs, endpoints, credentials, pagination, downloads, retries, fallbacks, and typed errors pass through one blocking bounded client.</done>
</task>

<task type="auto">
  <name>Task 3: Remove reactive test utilities and the WebFlux dependency</name>
  <files>_kotlin/backend/build.gradle.kts, _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt, _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt</files>
  <action>Remove the stale reactive import from `OpenSearchIndexerTest`. Move its self-signed-certificate assertion to the existing real OpenSearch integration container so the same production OpenSearch client path remains covered without a Reactor Netty test server. Replace the integration test's raw HTTP helper with RestClient and an existing Apache HttpComponents request factory configured to trust only that test container; keep basic authentication, request bodies, response parsing, and status-specific 400 assertion unchanged. Do not add a production trust-all client.

Delete the WebFlux starter from Gradle only after all production and test source uses blocking APIs. Add no replacement dependency. Keep transitive `reactor-core` because MCP and Spring AI require it. Remove every direct Kotlin import and use of reactive Spring HTTP, Reactor publisher/retry, and Netty test/client classes. Confirm the WebFlux and Reactor Netty artifacts no longer resolve from `runtimeClasspath`.</action>
  <verify>
    <automated>cd _kotlin/backend &amp;&amp; ./gradlew clean compileKotlin compileTestKotlin --warning-mode all --no-daemon &amp;&amp; ./gradlew test --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerTest" --no-daemon &amp;&amp; ./gradlew opensearchIntegrationTest --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerIntegrationTest" --no-daemon</automated>
  </verify>
  <done>Kotlin production and tests compile and pass without direct reactive HTTP or Netty code, and runtimeClasspath contains no Spring WebFlux or Reactor Netty transport.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| Kotlin backend to model server | Model configuration and request text cross into an external HTTP process. |
| Kotlin backend to Jira, Confluence, and GitHub | Credentials and connector requests cross the public or configured network boundary. |
| Remote response to Kotlin parser | Untrusted status, headers, and bodies enter retry, error, and Jackson conversion logic. |
| Test process to OpenSearch container | Tests use a known self-signed certificate and fixed local credentials. |

## STRIDE Threat Register — ASVS Level 1

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-QVBB-01 | Information Disclosure | Connector authentication headers | high | mitigate | Set credentials only on each original request, disable redirects in the shared Java client, and assert the redirected target gets no request. |
| T-QVBB-02 | Spoofing | Production HTTPS transport | high | mitigate | Use the JDK default trust store and hostname verification. Keep insecure TLS configuration only inside the OpenSearch integration helper. |
| T-QVBB-03 | Denial of Service | Success and error response bodies | high | mitigate | Enforce the existing 16 MiB bound before JSON, text, binary, or exception-body conversion and test both accepted and rejected boundaries. |
| T-QVBB-04 | Denial of Service | Model-server retry loop | medium | mitigate | Bound attempts by `embedMaxRetries`, retain deterministic exponential backoff, retry only network and 5xx failures, and preserve interruption. |
| T-QVBB-05 | Tampering | HTTP status and error-body translation | medium | mitigate | Preserve Spring status-specific exceptions with bounded headers and body text; keep connector status branches under focused tests. |
| T-QVBB-06 | Information Disclosure | Error propagation | low | accept | Existing connector messages use bounded upstream error text for diagnosis; this migration adds no logging or credential values. |
</threat_model>

<verification>
1. Run `cd _kotlin/backend &amp;&amp; ./gradlew clean compileKotlin compileTestKotlin --warning-mode all --no-daemon`.
2. Run `cd _kotlin/backend &amp;&amp; ./gradlew test --no-daemon`.
3. Run `cd _kotlin/backend &amp;&amp; ./gradlew opensearchIntegrationTest --tests "com.onyx.foss.kotlin.ingestion.OpenSearchIndexerIntegrationTest" --no-daemon`.
4. Run `! rg -n '(org\\.springframework\\.web\\.reactive|org\\.springframework\\.http\\.client\\.reactive|reactor\\.(core|netty|util\\.retry)|io\\.netty)' _kotlin/backend/src/main/kotlin _kotlin/backend/src/test/kotlin`.
5. Run `cd _kotlin/backend &amp;&amp; ./gradlew dependencyInsight --dependency spring-webflux --configuration runtimeClasspath --no-daemon | rg 'No dependencies matching given input'`.
6. Run `cd _kotlin/backend &amp;&amp; ./gradlew dependencyInsight --dependency reactor-netty-http --configuration runtimeClasspath --no-daemon | rg 'No dependencies matching given input'`.
7. Review `git diff --stat` and `git diff -- _kotlin/backend` to confirm only the declared transport and test changes exist.
</verification>

<success_criteria>
- Every Kotlin outbound model-server and connector request uses blocking Spring RestClient.
- Model-server connect timeout, read timeout, request fields, response order, retry count, backoff, and error conditions remain unchanged.
- Jira, Confluence, and GitHub authentication, capabilities, pagination, retry, fallback, and typed error behavior remain unchanged.
- Production HTTP verifies TLS, refuses redirects, and bounds success and error bodies at 16 MiB.
- All Kotlin production and test source is free of reactive HTTP, Reactor retry/publisher, and Netty use.
- Gradle keeps Spring MVC, removes the WebFlux starter, and resolves no Spring WebFlux or Reactor Netty HTTP transport.
- Focused tests, full unit tests, clean compile, and the OpenSearch integration suite pass.
</success_criteria>

<output>
Create `.planning/quick/260910-vbb-replace-all-kotlin-outbound-webclient-us/260910-vbb-SUMMARY.md` when done.
</output>
