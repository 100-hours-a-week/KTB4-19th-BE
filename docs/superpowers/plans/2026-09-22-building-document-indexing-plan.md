# 건물 문서 AI 색인 연동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 문서 업로드·수정·삭제 후 최신 건물 문서 목록을 AI 색인 API에 전달하고 색인 실패를 재시도할 수 있게 한다.

**Architecture:** 현재 `RuleDocument` 엔티티만 존재하고 문서 서비스·저장소·관리자 문서 API는 없으므로 문서 기능을 별도 모듈로 추가한다. 외부 AI 호출은 전용 `AiIndexingClient`로 분리하고, 문서 트랜잭션이 끝난 뒤 색인 작업을 기록·실행하여 AI 장애가 문서 저장을 롤백하지 않게 한다.

**Tech Stack:** Spring Boot 4, Spring Data JPA, RestClient, MySQL, JUnit 5, Mockito

**Spec:** `docs/superpowers/specs/2026-09-22-building-document-indexing-design.md`

## Global Constraints

- AI endpoint는 `POST /api/v3/ai/indexing/jobs`를 사용한다.
- 성공 응답은 `202 Accepted`와 빈 본문이다.
- 문서 저장과 외부 AI 호출은 같은 트랜잭션으로 묶지 않는다.
- 색인 요청은 `building_id`, `doc_id`, `title`, `file_key`, `valid_doc_ids`를 계약에 맞게 전달한다.
- 신규·수정 문서가 여러 건이면 마지막 요청에만 `valid_doc_ids`를 포함한다.
- 삭제만 발생하면 `building_id`와 `valid_doc_ids`만 전달한다.

## Review Focus

- 업로드 완료 전 첨부파일: 문서 저장과 색인 요청을 차단하는지 — Task 2 통합 테스트
- AI가 `202`가 아닌 상태를 반환함: 색인 실패로 기록하는지 — Task 1 클라이언트 테스트
- AI 네트워크·5xx 오류: 문서 저장은 유지하고 재시도 대상으로 남기는지 — Task 3 서비스 테스트
- 다른 건물의 문서 ID: `valid_doc_ids`에 섞이지 않는지 — Task 2 서비스 테스트
- 삭제 후 문서가 0건: 빈 `valid_doc_ids`로 정리 요청하는지 — Task 2 서비스 테스트

### Task 1: AI 색인 클라이언트와 설정

**Files:**
- Create: `src/main/java/com/homes/zipsai/conversation/ai/AiIndexingClient.java`
- Create: `src/main/java/com/homes/zipsai/conversation/ai/HttpAiIndexingClient.java`
- Create: `src/main/java/com/homes/zipsai/conversation/ai/AiIndexingRequest.java`
- Modify: `src/main/java/com/homes/zipsai/conversation/ai/HttpAiConverseClient.java` configuration pattern
- Modify: `src/main/resources/application.yml` AI base URL and API key properties
- Test: `src/test/java/com/homes/zipsai/conversation/ai/HttpAiIndexingClientTest.java`

**Interfaces:**
- Consumes: `AiIndexingRequest(long buildingId, String docId, String title, String fileKey, List<String> validDocIds)`
- Produces: `void index(AiIndexingRequest request)`; `202` returns normally, other statuses throw a typed retryable/non-retryable exception.

- [ ] **Step 1: Write failing client tests** for `202`, `400`, `503`, and connection failure using the existing `MockRestServiceServer` pattern from `HttpAiConverseClientTest`.
- [ ] **Step 2: Run the client tests** and verify they fail because the client and request type do not exist.
- [ ] **Step 3: Implement the request record and `RestClient` client** with `POST /api/v3/ai/indexing/jobs`, JSON body, configurable `app.ai.base-url`, and optional `X-API-Key` header.
- [ ] **Step 4: Run the client tests** and verify all status mappings pass.
- [ ] **Step 5: Commit** with `feat: add ai document indexing client`.

### Task 2: Document indexing orchestration

**Files:**
- Create: `src/main/java/com/homes/zipsai/building/repository/RuleDocumentRepository.java`
- Create: `src/main/java/com/homes/zipsai/building/service/RuleDocumentService.java`
- Create: `src/main/java/com/homes/zipsai/building/controller/RuleDocumentController.java`
- Create: `src/main/java/com/homes/zipsai/building/dto/request/RuleDocumentCreateRequest.java`
- Create: `src/main/java/com/homes/zipsai/building/dto/response/RuleDocumentResponse.java`
- Create: `src/main/java/com/homes/zipsai/building/service/DocumentIndexingService.java`
- Create: `src/main/java/com/homes/zipsai/building/domain/DocumentIndexingStatus.java` if no existing status can represent pending/success/failure
- Test: `src/test/java/com/homes/zipsai/building/DocumentIndexingServiceTest.java`

**Interfaces:**
- Consumes: document save/update/delete events and `AiIndexingClient`
- Produces: current-building `valid_doc_ids`, per-document indexing attempts, and retryable failure state

- [ ] **Step 1: Inspect the existing `RuleDocument` entity and service** and pin the exact save/update/delete entry points in the test setup before changing code.
- [ ] **Step 2: Write failing service tests** for upload-complete indexing, multi-document last-request cleanup, deletion-only cleanup, cross-building filtering, and empty valid document list.
- [ ] **Step 3: Run the service tests** and verify each scenario fails before orchestration exists.
- [ ] **Step 4: Implement document ID collection scoped by building** and invoke the client after the document transaction completes.
- [ ] **Step 5: Persist indexing attempt status** without rolling back the document transaction when the AI client fails.
- [ ] **Step 6: Run service tests** and verify request payloads and failure state.
- [ ] **Step 7: Commit** with `feat: trigger ai indexing after document changes`.

### Task 3: File/document API integration

**Files:**
- Modify: `src/main/java/com/homes/zipsai/common/service/FileService.java`
- Modify: `src/main/java/com/homes/zipsai/common/controller/FileController.java`
- Modify: existing document controller/service files found in Task 2
- Test: existing file API tests and a new document indexing API test if no suitable test exists

**Interfaces:**
- Consumes: existing `PATCH /api/v1/files/{attachmentId}` upload-complete flow
- Produces: document indexing trigger only after the attachment is verified as `UPLOADED`

- [ ] **Step 1: Add failing integration coverage** proving an uncompleted attachment cannot create an indexed rule document and a completed attachment triggers indexing.
- [ ] **Step 2: Implement the smallest integration hook** at the existing upload-complete/document-save boundary; do not change the public FE upload contract.
- [ ] **Step 3: Add delete integration** so a successful document deletion sends cleanup with the remaining IDs.
- [ ] **Step 4: Run focused API tests** and verify document persistence is successful even when the AI client returns a retryable error.
- [ ] **Step 5: Commit** with `feat: integrate document upload indexing flow`.

### Task 4: FE document flow verification

**Files:**
- Modify: `/Users/kang/Downloads/KTB4-19th-FE/src/entities/file/api/fileApi.ts` only if the backend response or status needs a typed field
- Modify: `/Users/kang/Downloads/KTB4-19th-FE/src/pages/document-register/ui/DocumentRegisterPage.tsx` only for indexing progress/error copy
- Test: FE build and existing document-register checks

**Interfaces:**
- Consumes: existing file upload and completion APIs
- Produces: upload success UI that distinguishes S3 upload completion from asynchronous AI indexing failure.

- [ ] **Step 1: Verify the existing FE upload flow** against the unchanged file API contract.
- [ ] **Step 2: Add only the required status copy** if the backend exposes asynchronous indexing status; avoid duplicating AI calls in FE.
- [ ] **Step 3: Run `npm run build`** and confirm the document registration page still builds.
- [ ] **Step 4: Commit** with `feat: reflect document indexing status in upload flow` only if code changes are required.

### Task 5: Full verification and integration review

**Files:**
- Modify: no production files unless verification exposes a defect

- [ ] **Step 1: Run `./gradlew test`.**
- [ ] **Step 2: Run `npm run build` in `/Users/kang/Downloads/KTB4-19th-FE`.**
- [ ] **Step 3: Run `docker compose up -d --build` in the backend repository.**
- [ ] **Step 4: Verify backend and DB container status and exercise the upload-complete request with an AI client stub or local endpoint.**
- [ ] **Step 5: Review the final diff for unrelated controller changes before creating PRs.**
