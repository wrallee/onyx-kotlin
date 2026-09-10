# Phase 2: 공개 문서 관리 흐름 - Research

**Researched:** 2026-09-10
**Scope:** D-14 file rollback plus Phase 2 regression verification

## Conclusion

`FileStorageService` writes physical files before its database transaction commits. A later exception rolls back `file_assets` rows but leaves the files. Fix this once at the shared asset-save boundary.

Use Spring's existing transaction synchronization API. Register an `afterCompletion` callback for each new path. Delete it only for `STATUS_ROLLED_BACK`. Delete the same path immediately when copying or saving the row throws.

## Current Flow

1. `AdminController` delegates uploads and connector file updates to `FileStorageService`.
2. `upload` and `updateConnectorFiles` already have `@Transactional`.
3. `store` copies a plain file, then saves its `FileAssetEntity`.
4. `storeZipEntry` streams a ZIP entry, then saves its `FileAssetEntity`.
5. `mergeMetadata` calls `store`, so it uses the same plain-file path.
6. A later ZIP entry, metadata merge, connector update, or enqueue failure can roll back the rows after files exist.

## Minimum Implementation

- Keep both public transaction boundaries unchanged.
- Add one private save helper used by `store` and `storeZipEntry`.
- In the helper, save the row and register cleanup when transaction synchronization is active.
- Delete the new path and rethrow when the save itself fails.
- Wrap plain `Files.copy` with the same immediate partial-file cleanup already used by ZIP streaming.
- Do not add a storage interface, controller change, entity hook, configuration, or dependency.

An entity lifecycle hook is the wrong boundary. It cannot distinguish files created by the current request from pre-existing files.

## Test Strategy

Add one integration test beside the existing ZIP tests in `AdminApiIntegrationTest`.

Build one ZIP with:

- a small normal file;
- metadata for that file;
- a second entry that exceeds the existing 100 MiB extracted-size limit.

Capture the storage directory entries before the request. After the request returns `400`, assert:

- the directory entry set is unchanged;
- `file_assets` contains no rows from the failed request.

This proves immediate cleanup for the failing entry and transaction rollback cleanup for earlier entry and metadata files. Existing success tests prove committed files remain available.

## Existing Contracts

Current Phase 1 evidence found no additional product gap for ADMIN-01, ADMIN-02, ADMIN-03, PUBLIC-01, or PUBLIC-02. Run the focused admin integration class after the D-14 change. Do not modify those flows unless that current test run exposes a failure.

## Official API Check

Spring Framework's current Javadoc exposes:

- `TransactionSynchronizationManager.isSynchronizationActive()`;
- `TransactionSynchronizationManager.registerSynchronization(...)`;
- `TransactionSynchronization.afterCompletion(status)`;
- `TransactionSynchronization.STATUS_ROLLED_BACK`.

These APIs are already available through Spring Boot 4.0.7. No dependency change is required.

## Scope Guard

- Modify only `_kotlin/backend` product and test files.
- Root Python, root Web, `_kotlin/web`, and `_kotlin/model-server` remain read-only.
- Do not delete files referenced before the current request.
- Do not add removal behavior for `idsToRemove`; that is outside D-14.

## Verification Commands

```bash
cd _kotlin/backend
./gradlew test --tests com.onyx.foss.kotlin.api.AdminApiIntegrationTest --no-daemon
./gradlew compileKotlin compileTestKotlin --warning-mode all --no-daemon
```
