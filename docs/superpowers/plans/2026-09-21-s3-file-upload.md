# S3 File Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add authenticated generic file APIs that issue S3 presigned PUT URLs and confirm uploaded files using the project API definition.

**Architecture:** `FileController` exposes `/api/v1/files`; `FileService` owns validation, ownership, state transitions, and persistence; `S3StorageService` owns AWS SDK calls. URL issuance only writes a `PENDING` row and returns a short-lived URL. Completion performs S3 HEAD verification before changing the row to `UPLOADED`.

**Tech Stack:** Spring Boot 4.1.1, Spring Web MVC, Spring Data JPA, AWS SDK for Java v2 S3, Flyway, existing `ApiResponse<T>` and exception handler.

**Spec:** `docs/superpowers/specs/2026-09-21-s3-document-upload.md`

## Global Constraints

- API paths are exactly `POST /api/v1/files` and `PATCH /api/v1/files/{attachmentId}`.
- Allowed file types are jpg, png, heic, and pdf; maximum size is 10 MiB.
- Presigned PUT URL expires after 300 seconds.
- S3 region is `ap-northeast-2`; bucket is read from `S3_UPLOAD_BUCKET`.
- AWS credentials must come from the default credential chain and never be committed.
- Completion accepts only `fileStatus=UPLOADED` and is idempotent for an already uploaded file.

## Review Focus

- Client-provided MIME type differs from the S3 object metadata: completion uses HEAD metadata and rejects inconsistent or unsupported content.
- A user submits another user’s attachment ID: ownership check returns 403 before state mutation.
- S3 upload never happened or the URL expired: completion returns `UPLOAD_NOT_COMPLETED` and leaves the file unusable.
- An uploaded object exceeds 10 MiB: completion returns `PAYLOAD_TOO_LARGE` and does not mark it uploaded.
- Repeating completion after success: returns the existing uploaded metadata without creating a duplicate row.

### Task 1: Add storage dependency and configuration

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/com/homes/zipsai/common/config/StorageProperties.java`
- Create: `src/main/resources/application-local.properties` entries
- Test: `src/test/java/com/homes/zipsai/common/config/StoragePropertiesTest.java`

**Interfaces:**
- Produces `StorageProperties` with region, bucket, URL TTL, and max size.

- [ ] Add AWS SDK S3 and presigner dependencies using the AWS SDK BOM.
- [ ] Bind `app.storage.aws-region`, `app.storage.upload-bucket`, `app.storage.presigned-url-ttl-seconds`, and `app.storage.max-file-size`.
- [ ] Provide local defaults using `AWS_REGION`, `S3_UPLOAD_BUCKET`, 300, and 10485760.
- [ ] Add a properties binding test that loads the configured values.
- [ ] Run `./gradlew test --tests '*StoragePropertiesTest'`.

### Task 2: Implement S3 storage adapter

**Files:**
- Create: `src/main/java/com/homes/zipsai/common/service/S3StorageService.java`
- Create: `src/main/java/com/homes/zipsai/common/service/S3StorageConfiguration.java`
- Test: `src/test/java/com/homes/zipsai/common/service/S3StorageServiceTest.java`

**Interfaces:**
- `PresignedUpload prepareUpload(String key, String contentType, Duration ttl)`.
- `ObjectMetadata head(String key)`.
- `void delete(String key)`.

- [ ] Build `S3Client` and `S3Presigner` from the configured region and default credentials.
- [ ] Generate PUT presigned URLs with the exact `Content-Type` header and 300-second expiry.
- [ ] Implement HEAD and delete calls without logging secrets or full URLs.
- [ ] Translate missing S3 objects to a typed storage-not-found result consumed by the service.
- [ ] Mock AWS clients in unit tests for URL generation, HEAD, and delete.
- [ ] Run the focused storage tests.

### Task 3: Complete file state model and repository

**Files:**
- Modify: `src/main/java/com/homes/zipsai/common/domain/FileStatus.java`
- Modify: `src/main/java/com/homes/zipsai/common/domain/File.java`
- Create: `src/main/java/com/homes/zipsai/common/repository/FileRepository.java`
- Modify: `src/main/resources/db/migration/V2__domain_schema.sql` only if enum/status constraints require it
- Test: `src/test/java/com/homes/zipsai/common/domain/FileTest.java`

**Interfaces:**
- `File.markUploaded(int actualSize, String actualType)`.
- `File.markRejected()`.
- Repository methods `findById`, `save`, and ownership query support used by the service.

- [ ] Add `READY` and `REJECTED` enum values while preserving existing database compatibility; use `UPLOADED` as the API completion state.
- [ ] Add domain methods that prevent invalid backward transitions.
- [ ] Keep `fileKey`, original name, and persisted metadata immutable except for verified completion metadata.
- [ ] Add tests for PENDING→UPLOADED and invalid repeated/rejected transitions.
- [ ] Run domain tests and Flyway validation.

### Task 4: Implement file service and validation

**Files:**
- Create: `src/main/java/com/homes/zipsai/common/service/FileService.java`
- Create: `src/main/java/com/homes/zipsai/common/dto/FileUploadRequest.java`
- Create: `src/main/java/com/homes/zipsai/common/dto/FileUploadResponse.java`
- Create: `src/main/java/com/homes/zipsai/common/dto/FileCompleteRequest.java`
- Create: `src/main/java/com/homes/zipsai/common/dto/FileCompleteResponse.java`
- Modify: `src/main/java/com/homes/zipsai/global/exception/ConflictException.java`
- Create or modify validation exception classes under `src/main/java/com/homes/zipsai/global/exception/`
- Test: `src/test/java/com/homes/zipsai/common/service/FileServiceTest.java`

**Interfaces:**
- `FileUploadResponse createUpload(AuthPrincipal principal, FileUploadRequest request)`.
- `FileCompleteResponse complete(AuthPrincipal principal, long attachmentId, FileCompleteRequest request)`.

- [ ] Validate required fields, filename length ≤255, supported extension/type, positive size, and requested size ≤10 MiB.
- [ ] Generate a UUID-based key under `documents/rules/` and persist a PENDING file before returning the URL.
- [ ] On completion, verify ownership, requested status, S3 object existence, actual size ≤10 MiB, and actual content type.
- [ ] Mark the file uploaded only after all checks succeed; keep the operation idempotent.
- [ ] Return the API-defined response fields: attachmentId, uploadUrl/expiresIn/requiredHeaders for creation and attachmentId/fileKey/originalName/fileSize/fileType/fileStatus for completion.
- [ ] Add tests for validation, ownership, missing S3 object, oversized object, and idempotent completion.
- [ ] Run focused service tests.

### Task 5: Expose controllers and error responses

**Files:**
- Create: `src/main/java/com/homes/zipsai/common/controller/FileController.java`
- Modify: `src/main/java/com/homes/zipsai/global/exception/ApiExceptionHandler.java` only if required for documented status codes
- Test: `src/test/java/com/homes/zipsai/common/controller/FileControllerTest.java`

**Interfaces:**
- `POST /api/v1/files` returns `ResponseEntity<ApiResponse<FileUploadResponse>>` with 201.
- `PATCH /api/v1/files/{attachmentId}` returns `ResponseEntity<ApiResponse<FileCompleteResponse>>` with 200.

- [ ] Require the existing authenticated principal and delegate all business logic to `FileService`.
- [ ] Validate `fileStatus` equals `UPLOADED`.
- [ ] Map validation, unauthorized, forbidden, not-found, conflict, too-large, rate-limit, and internal errors through the existing common response format.
- [ ] Add MockMvc tests for success and documented error status/body shapes.
- [ ] Run controller tests and the full test suite.

### Task 6: Configuration and verification documentation

**Files:**
- Modify: `src/main/resources/application-local.properties`
- Modify: `.env.example` only if it already tracks non-secret backend variables
- Modify: `docs/superpowers/specs/2026-09-21-s3-document-upload.md`

- [ ] Document `AWS_REGION=ap-northeast-2` and `S3_UPLOAD_BUCKET=zipsai-prod-uploads` without adding credentials.
- [ ] Document the frontend sequence: POST URL → PUT S3 with required headers → PATCH complete.
- [ ] Run `./gradlew check` and confirm no secrets, warnings, or generated files are staged.
- [ ] Commit with `feat: add presigned S3 file upload APIs`.
