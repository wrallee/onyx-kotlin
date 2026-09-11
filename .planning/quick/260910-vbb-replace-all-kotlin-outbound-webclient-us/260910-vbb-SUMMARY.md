---
phase: quick-remove-webflux
plan: "260910-vbb"
subsystem: kotlin-http
tags: [kotlin, spring-restclient, jdk-http-client, blocking-io, opensearch]

requires: []
provides:
  - Blocking Spring RestClient transport for model-server and connector requests
  - Bounded 16 MiB success and error response handling
  - Blocking retry behavior for model-server network and 5xx failures
  - Kotlin runtime without Spring WebFlux or Reactor Netty HTTP
affects: [kotlin-ingestion, model-server, jira, confluence, github, opensearch-tests]

actuals:
  tokens: 14970
  tasks: 3
  commits: 1
plan_head_before: 4d6a6ea5af7734fd7bde9ae0406d96b867bf9c2c

tech-stack:
  added: []
  patterns:
    - JDK HttpClient-backed Spring RestClient with redirects disabled
    - Bounded response buffering before Spring body conversion
    - Direct blocking retry loop with explicit retryable exception classes

key-files:
  created: []
  modified:
    - _kotlin/backend/build.gradle.kts
    - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/ModelServerHttpClient.kt
    - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/RuntimeConfiguration.kt
    - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt
    - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt
    - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoader.kt
    - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt
    - _kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt
    - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ModelServerClientTest.kt
    - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/RemoteConnectorLoadersTest.kt
    - _kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt

key-decisions:
  - "Reuse Spring Web's RestClient and the JDK HTTP transport; add no dependency or client abstraction."
  - "Keep production TLS defaults and trust only the copied OpenSearch test CA in the integration helper."
  - "Use exchange only for status-preserving text POST requests; use retrieve for typed HTTP errors."

patterns-established:
  - "Bounded transport: buffer at most 16 MiB before JSON, text, binary, or exception conversion."
  - "Retry policy: retry only resource access failures and 5xx responses with deterministic exponential backoff."

requirements-completed: [MODEL-01, CONNECTOR-02, CONNECTOR-03, CONNECTOR-04, BUILD-01]

coverage:
  - id: D1
    description: "Model-server embeddings use blocking RestClient with preserved payloads, timeouts, response order, bounds, redirects, and retries."
    requirement: MODEL-01
    verification:
      - kind: unit
        ref: "_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ModelServerClientTest.kt"
        status: pass
    human_judgment: false
  - id: D2
    description: "Jira HTTP behavior remains compatible through the shared blocking client."
    requirement: CONNECTOR-02
    verification:
      - kind: unit
        ref: "_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoaderTest.kt"
        status: pass
    human_judgment: false
  - id: D3
    description: "Confluence HTTP behavior remains compatible through the shared blocking client."
    requirement: CONNECTOR-03
    verification:
      - kind: unit
        ref: "_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoaderTest.kt"
        status: pass
    human_judgment: false
  - id: D4
    description: "GitHub HTTP behavior remains compatible through the shared blocking client."
    requirement: CONNECTOR-04
    verification:
      - kind: unit
        ref: "_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoaderTest.kt"
        status: pass
    human_judgment: false
  - id: D5
    description: "Kotlin production and test code compiles without reactive HTTP imports, Spring WebFlux, or Reactor Netty HTTP."
    requirement: BUILD-01
    verification:
      - kind: other
        ref: "./gradlew clean compileKotlin compileTestKotlin --warning-mode all --no-daemon"
        status: pass
      - kind: integration
        ref: "./gradlew opensearchIntegrationTest --tests com.onyx.foss.kotlin.ingestion.OpenSearchIndexerIntegrationTest --no-daemon"
        status: pass
      - kind: other
        ref: "runtimeClasspath dependencyInsight for spring-webflux and reactor-netty-http"
        status: pass
    human_judgment: false

duration: 2h 58m
completed: 2026-09-11
status: complete
---

# Quick Task 260910-vbb: Replace Kotlin Outbound WebClient Summary

**Blocking Spring RestClient now handles every Kotlin model-server and connector request without WebFlux or Reactor Netty HTTP.**

## Performance

- **Duration:** 2h 58m
- **Started:** 2026-09-10T13:58:11Z
- **Completed:** 2026-09-10T16:55:49Z
- **Tasks:** 3
- **Files modified:** 15

## Accomplishments

- Migrated model-server embedding calls to a bounded JDK-backed RestClient.
- Migrated Jira, Confluence, and GitHub calls while preserving credentials, pagination, downloads, headers, and typed errors.
- Removed the WebFlux starter and all direct Kotlin reactive HTTP, Reactor, and Netty source use.
- Kept the real self-signed OpenSearch TLS assertion through the production OpenSearch client path.

## Implementation Commit

- `8eb846fac` — Replace WebClient with RestClient and remove the reactive HTTP runtime.

## Files Created/Modified

- `_kotlin/backend/build.gradle.kts` - Removes the WebFlux starter.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/ModelServerHttpClient.kt` - Configures the bounded JDK RestClient transport.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/RuntimeConfiguration.kt` - Supplies the RestClient builder.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/IngestionWorker.kt` - Sends blocking embedding requests and applies bounded retry.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/RemoteJsonClient.kt` - Implements blocking JSON, text, and binary connector operations.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoader.kt` - Handles Spring blocking HTTP exceptions.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoader.kt` - Preserves fallback and rate-limit branches with blocking exceptions.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoader.kt` - Preserves rate-reset branches with blocking exceptions.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ModelServerClientTest.kt` - Covers payloads, timeouts, retry classes, redirects, and bounds.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/JiraConnectorLoaderTest.kt` - Uses RestClient request rewriting.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ConfluenceConnectorLoaderTest.kt` - Uses RestClient request rewriting.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/GithubConnectorLoaderTest.kt` - Uses the blocking test client.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/RemoteConnectorLoadersTest.kt` - Covers blocking response types, redirects, and response bounds.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerTest.kt` - Removes the reactive TLS test server.
- `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/OpenSearchIndexerIntegrationTest.kt` - Tests TLS with the real container and a test-only CA trust store.

## Decisions Made

- Used the existing Spring MVC dependency and JDK HTTP client. No dependency was added.
- Applied one 16 MiB transport bound to success and error bodies before conversion.
- Kept production TLS defaults. The integration helper trusts only the OpenSearch container CA.

## Verification

| Gate | Result |
| --- | --- |
| Clean Kotlin production and test compilation | Passed |
| Full unit suite | Passed: 330 tests, 0 failures, 0 errors |
| OpenSearch integration suite | Passed: 8 tests, 0 failures, 0 errors |
| Reactive Spring, Reactor, and Netty source scan | Passed: no matches |
| `spring-webflux` runtime dependency check | Passed: no matching dependency |
| `reactor-netty-http` runtime dependency check | Passed: no matching dependency |
| Declared-file diff review | Passed: 15 declared files only |

## TDD Gate Compliance

- Task 1 RED failed against the reactive client because blocking exception assertions did not match.
- Task 1 GREEN passed the focused model-server suite and the tracer feedback rerun.
- Task 2 RED failed against the reactive client because blocking connector exception assertions did not match.
- Task 2 GREEN passed the focused Jira, Confluence, GitHub, and shared transport suites.
- Compact RED evidence records are stored beside the plan and remain uncommitted.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Made new JUnit test methods return Unit**

- **Found during:** Task 1 RED
- **Issue:** Expression-body test methods returned assertion objects, so Gradle did not discover them as JUnit tests.
- **Fix:** Declared the affected test methods with an explicit `Unit` return type.
- **Files modified:** `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/ingestion/ModelServerClientTest.kt`
- **Verification:** The focused model-server suite discovered and ran all nine tests.
- **Committed in:** `8eb846fac`

**2. [Rule 2 - Missing critical functionality] Close bounded responses on checked read failures**

- **Found during:** Task 2 GREEN
- **Issue:** The response wrapper closed the upstream body for runtime failures only. Checked I/O failures could leave it open.
- **Fix:** Close the upstream response for every exception raised during bounded buffering.
- **Files modified:** `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/config/ModelServerHttpClient.kt`
- **Verification:** Clean compilation, 330 unit tests, and the OpenSearch integration suite passed.
- **Committed in:** `8eb846fac`

---

**Total deviations:** 2 auto-fixed: one Rule 1 bug and one Rule 2 correctness fix.
**Impact on plan:** Both fixes were required for reliable tests and response resource safety. No capability or dependency was added.

## Issues Encountered

The original reactive oversize-response RED case did not terminate after cancellation. The focused typed-exception RED case established the required failing boundary without changing production code.

## User Setup Required

None - no external service configuration is required.

## Known Stubs

None.

## Next Phase Readiness

All implementation and verification gates pass. The branch is ready for orchestrator consolidation into one implementation commit and one planning commit.

## Self-Check: PASSED

- The summary exists and has `status: complete`.
- The consolidated implementation commit exists.
- The measured plan base and consolidated commit count match the plan ledger.
- Coverage metadata parses with five automatically verified deliverables and no errors.

---
*Phase: quick-remove-webflux*
*Completed: 2026-09-11*
