package com.homes.zipsai.global.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public enum Reason {
        ACTIVE_CONVERSATION_LIMIT_EXCEEDED(
                "ACTIVE_CONVERSATION_LIMIT_EXCEEDED",
                "진행 중인 대화 수가 제한을 초과했습니다.",
                "conversationId",
                "진행 중인 대화를 종료한 후 다시 시도해 주세요."),
        BUILDING_ALREADY_EXISTS(
                "BUILDING_ALREADY_EXISTS",
                "이미 등록된 건물이 있습니다.",
                "buildingId",
                "관리자당 1개의 건물만 등록할 수 있습니다."),
        COMPLAINT_ALREADY_CREATED(
                "COMPLAINT_ALREADY_CREATED",
                "이미 민원이 접수된 대화입니다.",
                "conversationId",
                "하나의 대화에서는 민원을 한 번만 접수할 수 있습니다."),
        CONVERSATION_CLOSED(
                "CONVERSATION_CLOSED",
                "종료된 대화에는 메시지를 보낼 수 없습니다.",
                "conversationId",
                "이미 종료된 대화입니다."),
        DOCUMENT_TITLE_ALREADY_EXISTS(
                "DOCUMENT_TITLE_ALREADY_EXISTS",
                "이미 등록된 문서 제목입니다.",
                "documentTitle",
                "같은 건물에 동일한 제목의 문서가 이미 존재합니다."),
        DOCUMENT_UPDATE_CONFLICT(
                "DOCUMENT_UPDATE_CONFLICT",
                "문서가 최신 버전이 아닙니다.",
                "documentId",
                "최신 버전이 아니거나 다른 관리자가 먼저 변경했습니다."),
        EMAIL_ALREADY_EXISTS(
                "EMAIL_ALREADY_EXISTS",
                "이미 사용 중인 이메일입니다.",
                "email",
                "이미 가입된 이메일입니다."),
        INVITATION_CANCEL_NOT_ALLOWED(
                "INVITATION_CANCEL_NOT_ALLOWED",
                "현재 호실 상태에서는 초대를 취소할 수 없습니다.",
                "roomId",
                "취소할 수 있는 활성 초대코드가 없거나 현재 호실 상태가 올바르지 않습니다."),
        INVITATION_CODE_ALREADY_USED(
                "INVITATION_CODE_ALREADY_USED",
                "이미 사용된 초대코드입니다.",
                "code",
                "이미 사용 처리된 초대코드입니다."),
        INVITATION_CODE_EXPIRED(
                "INVITATION_CODE_EXPIRED",
                "초대코드가 만료되었습니다.",
                "code",
                "초대코드가 만료되었거나 유효기간이 지났습니다."),
        RESIDENT_NOT_FOUND_IN_ROOM(
                "RESIDENT_NOT_FOUND_IN_ROOM",
                "퇴실 처리할 입주민이 없습니다.",
                "roomId",
                "현재 호실에 퇴실 처리할 입주민이 없습니다."),
        ROLE_ALREADY_ASSIGNED(
                "ROLE_ALREADY_ASSIGNED",
                "이미 역할이 설정된 계정입니다.",
                "userRole",
                "역할은 최초 1회만 설정할 수 있습니다."),
        ROOM_ALREADY_EXISTS(
                "ROOM_ALREADY_EXISTS",
                "이미 등록된 호실이 있습니다.",
                "roomNos",
                "같은 건물에 동일한 호실 번호가 이미 존재합니다."),
        ROOM_CONNECTION_CONFLICT(
                "ROOM_CONNECTION_CONFLICT",
                "세대 연결을 완료할 수 없습니다.",
                "code",
                "초대코드가 이미 사용되었거나 계정이 다른 호실에 연결되어 있습니다."),
        ROOM_OCCUPIED(
                "ROOM_OCCUPIED",
                "입주 중인 호실에는 초대코드를 발급할 수 없습니다.",
                "roomId",
                "입주 중인 호실은 퇴실 처리 후에만 초대코드를 발급할 수 있습니다."),
        UPLOAD_NOT_COMPLETED(
                "UPLOAD_NOT_COMPLETED",
                "파일 업로드가 완료되지 않았습니다.",
                "attachmentId",
                "S3 객체가 존재하지 않거나 업로드가 확정되지 않았습니다.");

        private final String code;
        private final String message;
        private final String field;
        private final String detail;

        Reason(String code, String message, String field, String detail) {
            this.code = code;
            this.message = message;
            this.field = field;
            this.detail = detail;
        }
    }

    public ConflictException(Reason reason) {
        super(
                HttpStatus.CONFLICT,
                reason.message,
                reason.code,
                fieldReason(reason.field, reason.detail));
    }
}
