package com.homes.zipsai.global.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

    public enum Resource {
        ATTACHMENT(
                "ATTACHMENT_NOT_FOUND",
                "요청한 첨부파일을 찾을 수 없습니다.",
                "attachmentId"),
        BUILDING(
                "BUILDING_NOT_FOUND",
                "요청한 건물을 찾을 수 없습니다.",
                "buildingId"),
        COMPLAINT(
                "COMPLAINT_NOT_FOUND",
                "요청한 민원을 찾을 수 없습니다.",
                "complaintId"),
        CONVERSATION(
                "CONVERSATION_NOT_FOUND",
                "요청한 대화를 찾을 수 없습니다.",
                "conversationId"),
        DOCUMENT(
                "DOCUMENT_NOT_FOUND",
                "요청한 문서를 찾을 수 없습니다.",
                "documentId"),
        INVITATION_CODE_BY_CODE(
                "INVITATION_CODE_NOT_FOUND",
                "요청한 초대코드를 찾을 수 없습니다.",
                "code"),
        INVITATION_CODE_BY_ID(
                "INVITATION_CODE_NOT_FOUND",
                "요청한 초대코드를 찾을 수 없습니다.",
                "codeId"),
        NOTIFICATION(
                "NOTIFICATION_NOT_FOUND",
                "요청한 알림을 찾을 수 없습니다.",
                "userNotiId"),
        ROOM(
                "ROOM_NOT_FOUND",
                "요청한 호실을 찾을 수 없습니다.",
                "roomId"),
        TERMS(
                "TERMS_NOT_FOUND",
                "요청한 약관 문서를 찾을 수 없습니다.",
                "termsType");

        private final String code;
        private final String message;
        private final String field;

        Resource(String code, String message, String field) {
            this.code = code;
            this.message = message;
            this.field = field;
        }
    }

    public NotFoundException() {
        super(
                HttpStatus.NOT_FOUND,
                "요청한 리소스를 찾을 수 없습니다.",
                "RESOURCE_NOT_FOUND",
                Map.of());
    }

    public NotFoundException(Resource resource) {
        super(
                HttpStatus.NOT_FOUND,
                resource.message,
                resource.code,
                fieldReason(resource.field, "해당 리소스가 존재하지 않거나 삭제되었습니다."));
    }
}
