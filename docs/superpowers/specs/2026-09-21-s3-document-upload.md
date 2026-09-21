# S3 문서 첨부 업로드 설계

## 목표
관리자가 자신이 관리하는 건물의 운영 문서를 S3에 직접 업로드하고, 업로드가 확인된 파일만 `Rule_Documents`에 등록한다.

## 현재 구조
- `Files` 테이블은 S3 key, 원본 파일명, 크기, 타입, 상태를 저장한다. 상태는 `PENDING → UPLOADED → READY`로 진행하며 검증 실패 시 `REJECTED`로 끝난다.
- `Rule_Documents`는 건물, 첨부파일, 제목, 내용, 버전을 연결한다.
- 파일은 공개하지 않고 presigned URL로만 업로드·다운로드한다.
- S3 리전은 `ap-northeast-2`, 버킷은 `zipsai-prod-uploads`를 환경변수로 주입한다.

## API 흐름

### 1. 업로드 URL 발급
`POST /api/v1/managers/me/files/upload-url`

요청:
```json
{
  "originalName": "건물 운영 규칙.pdf",
  "fileType": "application/pdf",
  "fileSize": 1048576
}
```

처리:
- 로그인한 활성 사용자이며 파일 용도가 `RULE_DOCUMENT`인 경우 해당 건물의 관리자 owner인지 확인한다.
- 허용 타입은 `application/pdf`, `image/jpeg`, `image/png`, `image/heic`이다.
- 파일 크기는 10 MiB 이하이다.
- S3 key는 클라이언트 입력을 그대로 사용하지 않고 `buildings/{buildingId}/documents/{uuid}-{safeName}` 형식으로 서버에서 만든다.
- `Files`에 `PENDING` 파일을 저장한다.
- PUT용 presigned URL의 유효시간은 10분이다.

파일 용도는 서버가 요청 경로·도메인 컨텍스트로 결정하며 클라이언트가 임의의 S3 bucket이나 object key를 지정할 수 없다.

응답:
```json
{
  "attachmentId": 10,
  "fileKey": "buildings/3/documents/uuid-file.pdf",
  "uploadUrl": "https://...",
  "expiresAt": "2026-09-21T08:10:00Z",
  "requiredHeaders": {
    "Content-Type": "application/pdf"
  }
}
```

### 2. 클라이언트의 S3 업로드
프론트엔드는 반환된 URL로 파일을 직접 PUT한다. 요청의 `Content-Type`은 URL 발급 시 전달한 값과 같아야 한다.

### 3. 업로드 완료 및 문서 등록
`PATCH /api/v1/files/{attachmentId}/complete`

요청:
```json
{
  "purpose": "RULE_DOCUMENT",
  "documentTitle": "건물 운영 규칙",
  "content": "입주민 생활 및 공용시설 이용 규칙"
}
```

처리:
- attachment 소유자, 파일 용도, 건물 관계와 관리자 권한을 확인한다.
- 파일 상태가 `PENDING`인지 확인한다. 이미 `READY`인 요청은 기존 결과를 반환해 멱등 처리한다.
- S3 `HeadObject`로 객체 존재 여부와 크기를 확인한다.
- MIME Type만 신뢰하지 않고 Magic Bytes, 이미지 디코딩 또는 PDF 파싱으로 실제 형식을 검증한다.
- 같은 건물의 유효한 문서 제목 중복을 검사한다.
- 검증 성공 시 파일 상태를 `READY`로 변경하고 `Rule_Documents`를 생성한다.
- 검증 실패 시 파일 상태를 `REJECTED`로 변경하고 오류 코드를 반환한다.
- 완료되지 않은 파일은 문서로 등록하지 않는다.

### 4. 문서 조회·다운로드
건물 문서 목록/상세 응답에는 DB 메타데이터와 짧은 만료시간의 GET presigned URL을 포함한다. 버킷 public access는 허용하지 않는다.

### 5. 문서 삭제
관리자 권한과 건물 소유권을 확인한 후 문서와 파일을 삭제 처리하고 S3 객체를 삭제한다. S3 삭제가 이미 완료된 경우에도 DB 정리는 성공하도록 삭제를 멱등적으로 처리한다.

## 설정
```properties
app.storage.aws-region=${AWS_REGION}
app.storage.upload-bucket=${S3_UPLOAD_BUCKET}
app.storage.presigned-url-ttl-seconds=600
app.storage.max-file-size=10485760
```

AWS access key와 secret key는 저장소에 넣지 않는다. AWS SDK 기본 credential chain(환경변수, IAM role 등)을 사용한다.

## 오류 정책
- 허용하지 않는 확장자/MIME 또는 10 MiB 초과: validation 오류
- 관리자 권한 또는 건물 소유권 없음: forbidden
- 존재하지 않는 건물/첨부파일/문서: not found
- 제목 중복: `DOCUMENT_TITLE_ALREADY_EXISTS`
- S3 객체가 없거나 크기가 일치하지 않음: `UPLOAD_NOT_COMPLETED`
- 실제 파일 형식 검증 실패: 파일 검증 오류와 함께 `REJECTED` 처리
- URL 발급 후 완료 확정 없이 남은 `PENDING` 파일은 문서 목록에 노출하지 않는다.

## 보안
- S3 key는 서버 생성, 경로 조작 문자는 제거한다.
- presigned URL은 필요한 HTTP method와 Content-Type에 묶는다.
- 업로드 URL과 다운로드 URL 모두 짧은 TTL을 사용한다.
- 비밀값과 실제 업로드 URL은 로그에 남기지 않는다.

## 범위에서 분리한 후속 기능

노션 요구사항에는 알림 목록·미읽음 개수·읽음 처리·SSE·Outbox 이벤트가 포함되어 있다. 이 기능들은 S3 파일 업로드와 독립된 도메인이므로 별도 설계와 구현 계획으로 분리한다. 파일 업로드 완료 이벤트를 알림 Outbox에 직접 연결하지 않으며, 민원 생성·상태 변경 이벤트만 알림 대상이 된다.
