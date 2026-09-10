# Phase 2: 공개 문서 관리 흐름 - Pattern Map

**Mapped:** 2026-09-10
**Files analyzed:** 2개 수정 대상, 6개 연결 파일
**Analogs found:** 2 / 2

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|---|---|---|---|---|
| `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt` | service | file-I/O, request-response, transaction | 같은 파일의 `storeZipEntry` 실패 정리와 `AdminService` 트랜잭션 경계 | role-match |
| `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt` | integration test | request-response, file-I/O | 같은 파일의 ZIP 성공·제한 실패 테스트 | exact |

## Pattern Assignments

### `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt` (service, file-I/O + transaction)

**Analog:** `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/FileStorageService.kt`

**서비스 트랜잭션 경계** (lines 35-46, 67-117):

```kotlin
@Transactional
fun upload(files: List<MultipartFile>): Map<String, Any?> {
    if (files.isEmpty()) throw ApiException(HttpStatus.BAD_REQUEST, "At least one file is required")
    val uploaded = storeUploads(files.filterNot(MultipartFile::isEmpty))
    // ...
}

@Transactional
fun updateConnectorFiles(
    connectorId: Long,
    newFiles: List<MultipartFile>,
    idsToRemove: List<String>,
): Map<String, Any?> {
    // 저장, connector 변경, enqueue가 같은 요청 트랜잭션에서 실행된다.
}
```

`AdminController`는 lines 106-121에서 두 메서드에 직접 위임한다. 콜백은 controller가 아닌 service 저장 경계에 둔다.

**일반 파일 저장 패턴** (lines 180-194):

```kotlin
private fun store(name: String, contentType: String?, size: Long, input: InputStream): FileAssetEntity {
    val assetId = UUID.randomUUID().toString()
    val path = root.resolve(assetId).normalize()
    if (!path.startsWith(root)) error("Invalid file storage path")
    Files.copy(input, path, StandardCopyOption.REPLACE_EXISTING)
    return fileAssets.save(
        FileAssetEntity(
            id = assetId,
            originalName = name,
            mediaType = contentType,
            byteSize = size,
            storagePath = path.toString(),
        ),
    )
}
```

물리 파일은 DB row보다 먼저 생긴다. `Files.copy` 자체가 실패하면 이 메서드에서 현재 경로를 지워야 한다.

**기존 부분 파일 정리 패턴** (lines 196-220):

```kotlin
try {
    Files.newOutputStream(path).use { output ->
        // ZIP entry를 제한까지 복사한다.
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

이 패턴을 일반 파일 복사 실패에도 그대로 적용한다. 예외를 삼키지 않는다.

**공통 저장 경계** (lines 185-193, 217-220):

두 경로는 `fileAssets.save(FileAssetEntity(...))`를 각각 호출한다. 이 두 호출을 한 private helper로 모은다. helper는 row 저장 전에 새 `path`의 rollback 정리를 등록한 뒤 기존 repository 저장을 호출한다.

프로젝트에는 production `TransactionSynchronization` analog가 없다. Spring 내장 `TransactionSynchronizationManager.registerSynchronization`과 `TransactionSynchronization.STATUS_ROLLED_BACK`을 직접 사용한다. 새 interface, storage abstraction, 설정은 만들지 않는다.

**트랜잭션 참여 패턴:** `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/service/AdminService.kt` lines 180-190, 333-359

```kotlin
@Transactional
fun updateConnector(connectorId: Long, request: ConnectorRequest): Map<String, Any?> {
    val value = lockConnectorForMutation(connectorId)
    // ...
    return connectorSnapshot(connectors.save(value))
}

@Transactional
fun enqueue(request: RunConnectorRequest): StatusResponse {
    // 기존 transaction에 참여한다.
}
```

`FileStorageService.updateConnectorFiles`가 다른 Spring bean인 `AdminService`를 호출한다. 기존 propagation으로 같은 트랜잭션에 참여한다.

**수정하지 않을 연결 파일:**

- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Domain.kt` lines 207-222: `FileAssetEntity`는 저장 경로만 보관한다.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/domain/Repositories.kt` line 149: repository는 표준 `JpaRepository`이다.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/api/AdminController.kt` lines 106-121: API 계약은 이미 서비스에 위임한다.
- `_kotlin/backend/src/main/kotlin/com/onyx/foss/kotlin/ingestion/FileConnectorLoader.kt` lines 41-69: 성공한 asset만 `filePath`로 읽는다.

JPA lifecycle hook이나 entity callback은 요청 전에 있던 파일도 건드릴 수 있다. 이 단계에는 맞지 않는다.

---

### `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt` (integration test, request-response + file-I/O)

**Analog:** `_kotlin/backend/src/test/kotlin/com/onyx/foss/kotlin/api/AdminApiIntegrationTest.kt`

**테스트 구성 패턴** (lines 24-72):

```kotlin
@AutoConfigureMockMvc
class AdminApiIntegrationTest : H2IntegrationTest() {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var storedFiles: FileStorageService

    @BeforeEach
    fun resetDatabase() {
        truncateTables(
            // 기존 관리 테이블
        )
    }
}
```

`H2IntegrationTest` lines 11-22는 실제 Spring context와 `onyx.storage.root=/tmp/onyx-kotlin-tests`를 사용한다. 별도 test context를 만들지 않는다.

**기존 ZIP 요청과 파일 확인 패턴** (lines 564-576):

```kotlin
val response = request(
    multipart("/manage/admin/connector/file/upload")
        .file(MockMultipartFile("files", "files.zip", "application/zip", zipFile())),
)

assertThat(response.status).isEqualTo(200)
val metadataId = response.body.path("zip_metadata_file_id").asString()
assertThat(Files.readString(storedFiles.filePath(metadataId))).contains("one.txt")
```

**기존 제한 실패 패턴** (lines 626-657):

```kotlin
val response = request(
    multipart("/manage/admin/connector/file/upload")
        .file(MockMultipartFile("files", "large.zip", "application/zip", oversizedZipFile())),
)

assertThat(response.status).isEqualTo(400)
```

**최소 테스트 삽입점:** `zipUploadRejectsEntriesLargerThanTheUploadLimit` 바로 뒤에 rollback 테스트 한 건을 둔다.

테스트 ZIP은 작은 entry를 먼저 저장한 뒤 두 번째 entry에서 100 MiB 제한을 넘긴다. 요청 전후 storage root의 파일명 집합이 같고 `file_assets` row가 없음을 확인한다. 기존 파일 집합을 먼저 snapshot하면 과거 테스트 파일을 지우거나 `resetDatabase`를 확장할 필요가 없다.

이 한 시나리오는 다음 흐름을 함께 검증한다.

1. 첫 entry의 물리 파일과 row 저장
2. 다음 entry 실패
3. 요청 트랜잭션 rollback
4. 첫 entry rollback 정리와 현재 부분 entry의 즉시 정리

성공 경로는 lines 564-624의 기존 테스트가 계속 보호한다. 별도 unit test나 mock transaction test는 추가하지 않는다.

## Shared Patterns

### 트랜잭션 경계

**Source:** `FileStorageService.kt` lines 35-46, 67-117

**Apply to:** 파일 upload와 connector file update

공개 entry point의 기존 `@Transactional`을 유지한다. 새 파일만 저장 시점에 callback에 등록한다.

### 파일 쓰기 오류

**Source:** `FileStorageService.kt` lines 200-216

**Apply to:** 일반 파일과 ZIP entry

현재 쓰던 경로만 `Files.deleteIfExists`로 지운 뒤 원래 예외를 다시 던진다.

### API 통합 테스트

**Source:** `AdminApiIntegrationTest.kt` lines 47-72, 564-657

**Apply to:** D-14 rollback 시나리오

MockMvc 요청으로 실제 transaction interceptor, JPA rollback, 물리 storage를 함께 검사한다.

## No Analog Found

| File / Concern | Role | Data Flow | Reason |
|---|---|---|---|
| production transaction rollback callback | service utility | transaction, file-I/O | `_kotlin/backend` production 코드에는 `TransactionSynchronization` 사용이 없다. Spring 내장 API를 직접 쓴다. |
| storage-root test helper | test utility | file-I/O | 기존 helper가 없다. 한 테스트에서 `Files.list` snapshot을 직접 사용한다. 공용 helper는 만들지 않는다. |

## Metadata

**Analog search scope:** `_kotlin/backend/src/main/kotlin`, `_kotlin/backend/src/test/kotlin`
**Files scanned:** 구조 검색 6개, transaction/cleanup 전역 검색
**Tracked-source gate:** 인용한 모든 analog가 `git ls-files`에 등록되어 있다.
**Pattern extraction date:** 2026-09-10
