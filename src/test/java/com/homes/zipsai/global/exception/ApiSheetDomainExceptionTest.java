package com.homes.zipsai.global.exception;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ApiSheetDomainExceptionTest {

    @Test
    void domainExceptionMetadataMatchesApiSheet() {
        List<Expected> cases = List.of(
                new Expected(
                        "payload-size-limit",
                        PayloadTooLargeException.fileSizeLimitExceeded(),
                        400,
                        "PAYLOAD_TOO_LARGE",
                        "파일 용량이 허용 범위를 초과했습니다.",
                        Map.of("field", "fileSize", "reason", "파일 크기는 10MB 이하여야 합니다.")),
                new Expected(
                        "uploaded-size",
                        PayloadTooLargeException.uploadedFileSizeExceeded(),
                        400,
                        "PAYLOAD_TOO_LARGE",
                        "파일 용량이 허용 범위를 초과했습니다.",
                        Map.of("field", "fileSize", "reason", "실제 업로드된 파일 크기가 10MB를 초과했습니다.")),
                new Expected(
                        "attachment-not-found",
                        new NotFoundException(NotFoundException.Resource.ATTACHMENT),
                        404,
                        "ATTACHMENT_NOT_FOUND",
                        "요청한 첨부파일을 찾을 수 없습니다.",
                        fieldReason("attachmentId")),
                new Expected(
                        "building-not-found",
                        new NotFoundException(NotFoundException.Resource.BUILDING),
                        404,
                        "BUILDING_NOT_FOUND",
                        "요청한 건물을 찾을 수 없습니다.",
                        fieldReason("buildingId")),
                new Expected(
                        "complaint-not-found",
                        new NotFoundException(NotFoundException.Resource.COMPLAINT),
                        404,
                        "COMPLAINT_NOT_FOUND",
                        "요청한 민원을 찾을 수 없습니다.",
                        fieldReason("complaintId")),
                new Expected(
                        "conversation-not-found",
                        new NotFoundException(NotFoundException.Resource.CONVERSATION),
                        404,
                        "CONVERSATION_NOT_FOUND",
                        "요청한 대화를 찾을 수 없습니다.",
                        fieldReason("conversationId")),
                new Expected(
                        "document-not-found",
                        new NotFoundException(NotFoundException.Resource.DOCUMENT),
                        404,
                        "DOCUMENT_NOT_FOUND",
                        "요청한 문서를 찾을 수 없습니다.",
                        fieldReason("documentId")),
                new Expected(
                        "invitation-code-not-found",
                        new NotFoundException(NotFoundException.Resource.INVITATION_CODE_BY_CODE),
                        404,
                        "INVITATION_CODE_NOT_FOUND",
                        "요청한 초대코드를 찾을 수 없습니다.",
                        fieldReason("code")),
                new Expected(
                        "invitation-code-id-not-found",
                        new NotFoundException(NotFoundException.Resource.INVITATION_CODE_BY_ID),
                        404,
                        "INVITATION_CODE_NOT_FOUND",
                        "요청한 초대코드를 찾을 수 없습니다.",
                        fieldReason("codeId")),
                new Expected(
                        "notification-not-found",
                        new NotFoundException(NotFoundException.Resource.NOTIFICATION),
                        404,
                        "NOTIFICATION_NOT_FOUND",
                        "요청한 알림을 찾을 수 없습니다.",
                        fieldReason("userNotiId")),
                new Expected(
                        "room-not-found",
                        new NotFoundException(NotFoundException.Resource.ROOM),
                        404,
                        "ROOM_NOT_FOUND",
                        "요청한 호실을 찾을 수 없습니다.",
                        fieldReason("roomId")),
                new Expected(
                        "terms-not-found",
                        new NotFoundException(NotFoundException.Resource.TERMS),
                        404,
                        "TERMS_NOT_FOUND",
                        "요청한 약관 문서를 찾을 수 없습니다.",
                        fieldReason("termsType")),
                new Expected(
                        "active-conversation-limit",
                        new ConflictException(ConflictException.Reason.ACTIVE_CONVERSATION_LIMIT_EXCEEDED),
                        409,
                        "ACTIVE_CONVERSATION_LIMIT_EXCEEDED",
                        "진행 중인 대화 수가 제한을 초과했습니다.",
                        Map.of("field", "conversationId", "reason", "진행 중인 대화를 종료한 후 다시 시도해 주세요.")),
                new Expected(
                        "building-already-exists",
                        new ConflictException(ConflictException.Reason.BUILDING_ALREADY_EXISTS),
                        409,
                        "BUILDING_ALREADY_EXISTS",
                        "이미 등록된 건물이 있습니다.",
                        Map.of("field", "buildingId", "reason", "관리자당 1개의 건물만 등록할 수 있습니다.")),
                new Expected(
                        "complaint-already-created",
                        new ConflictException(ConflictException.Reason.COMPLAINT_ALREADY_CREATED),
                        409,
                        "COMPLAINT_ALREADY_CREATED",
                        "이미 민원이 접수된 대화입니다.",
                        Map.of("field", "conversationId", "reason", "하나의 대화에서는 민원을 한 번만 접수할 수 있습니다.")),
                new Expected(
                        "conversation-closed",
                        new ConflictException(ConflictException.Reason.CONVERSATION_CLOSED),
                        409,
                        "CONVERSATION_CLOSED",
                        "종료된 대화에는 메시지를 보낼 수 없습니다.",
                        Map.of("field", "conversationId", "reason", "이미 종료된 대화입니다.")),
                new Expected(
                        "document-title-already-exists",
                        new ConflictException(ConflictException.Reason.DOCUMENT_TITLE_ALREADY_EXISTS),
                        409,
                        "DOCUMENT_TITLE_ALREADY_EXISTS",
                        "이미 등록된 문서 제목입니다.",
                        Map.of("field", "documentTitle", "reason", "같은 건물에 동일한 제목의 문서가 이미 존재합니다.")),
                new Expected(
                        "document-update-conflict",
                        new ConflictException(ConflictException.Reason.DOCUMENT_UPDATE_CONFLICT),
                        409,
                        "DOCUMENT_UPDATE_CONFLICT",
                        "문서가 최신 버전이 아닙니다.",
                        Map.of("field", "documentId", "reason", "최신 버전이 아니거나 다른 관리자가 먼저 변경했습니다.")),
                new Expected(
                        "invitation-cancel-not-allowed",
                        new ConflictException(ConflictException.Reason.INVITATION_CANCEL_NOT_ALLOWED),
                        409,
                        "INVITATION_CANCEL_NOT_ALLOWED",
                        "현재 호실 상태에서는 초대를 취소할 수 없습니다.",
                        Map.of("field", "roomId", "reason", "취소할 수 있는 활성 초대코드가 없거나 현재 호실 상태가 올바르지 않습니다.")),
                new Expected(
                        "invitation-code-already-used",
                        new ConflictException(ConflictException.Reason.INVITATION_CODE_ALREADY_USED),
                        409,
                        "INVITATION_CODE_ALREADY_USED",
                        "이미 사용된 초대코드입니다.",
                        Map.of("field", "code", "reason", "이미 사용 처리된 초대코드입니다.")),
                new Expected(
                        "invitation-code-expired",
                        new ConflictException(ConflictException.Reason.INVITATION_CODE_EXPIRED),
                        409,
                        "INVITATION_CODE_EXPIRED",
                        "초대코드가 만료되었습니다.",
                        Map.of("field", "code", "reason", "초대코드가 만료되었거나 유효기간이 지났습니다.")),
                new Expected(
                        "resident-not-found-in-room",
                        new ConflictException(ConflictException.Reason.RESIDENT_NOT_FOUND_IN_ROOM),
                        409,
                        "RESIDENT_NOT_FOUND_IN_ROOM",
                        "퇴실 처리할 입주민이 없습니다.",
                        Map.of("field", "roomId", "reason", "현재 호실에 퇴실 처리할 입주민이 없습니다.")),
                new Expected(
                        "room-already-exists",
                        new ConflictException(ConflictException.Reason.ROOM_ALREADY_EXISTS),
                        409,
                        "ROOM_ALREADY_EXISTS",
                        "이미 등록된 호실이 있습니다.",
                        Map.of("field", "roomNos", "reason", "같은 건물에 동일한 호실 번호가 이미 존재합니다.")),
                new Expected(
                        "room-connection-conflict",
                        new ConflictException(ConflictException.Reason.ROOM_CONNECTION_CONFLICT),
                        409,
                        "ROOM_CONNECTION_CONFLICT",
                        "세대 연결을 완료할 수 없습니다.",
                        Map.of("field", "code", "reason", "초대코드가 이미 사용되었거나 계정이 다른 호실에 연결되어 있습니다.")),
                new Expected(
                        "room-occupied",
                        new ConflictException(ConflictException.Reason.ROOM_OCCUPIED),
                        409,
                        "ROOM_OCCUPIED",
                        "입주 중인 호실에는 초대코드를 발급할 수 없습니다.",
                        Map.of("field", "roomId", "reason", "입주 중인 호실은 퇴실 처리 후에만 초대코드를 발급할 수 있습니다.")),
                new Expected(
                        "upload-not-completed",
                        new ConflictException(ConflictException.Reason.UPLOAD_NOT_COMPLETED),
                        409,
                        "UPLOAD_NOT_COMPLETED",
                        "파일 업로드가 완료되지 않았습니다.",
                        Map.of("field", "attachmentId", "reason", "S3 객체가 존재하지 않거나 업로드가 확정되지 않았습니다."))
                ,
                new Expected(
                        "email-already-exists",
                        new ConflictException(ConflictException.Reason.EMAIL_ALREADY_EXISTS),
                        409,
                        "EMAIL_ALREADY_EXISTS",
                        "이미 사용 중인 이메일입니다.",
                        Map.of("field", "email", "reason", "이미 가입된 이메일입니다.")),
                new Expected(
                        "role-already-assigned",
                        new ConflictException(ConflictException.Reason.ROLE_ALREADY_ASSIGNED),
                        409,
                        "ROLE_ALREADY_ASSIGNED",
                        "이미 역할이 설정된 계정입니다.",
                        Map.of("field", "userRole", "reason", "역할은 최초 1회만 설정할 수 있습니다."))
        );

        for (Expected expected : cases) {
            assertAll(
                    expected.name(),
                    () -> assertEquals(expected.status(), expected.exception().status),
                    () -> assertEquals(expected.code(), expected.exception().code),
                    () -> assertEquals(expected.message(), expected.exception().getMessage()),
                    () -> assertEquals(expected.details(), expected.exception().details));
        }
    }

    private static Map<String, String> fieldReason(String field) {
        return Map.of("field", field, "reason", "해당 리소스가 존재하지 않거나 삭제되었습니다.");
    }

    private record Expected(
            String name,
            ApiException exception,
            int status,
            String code,
            String message,
            Map<String, ?> details
    ) {
    }
}
