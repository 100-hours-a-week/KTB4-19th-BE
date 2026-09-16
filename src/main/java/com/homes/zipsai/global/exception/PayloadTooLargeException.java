package com.homes.zipsai.global.exception;

public class PayloadTooLargeException extends BadRequestException {

    private PayloadTooLargeException(String reason) {
        super(
                "파일 용량이 허용 범위를 초과했습니다.",
                "PAYLOAD_TOO_LARGE",
                fieldReason("fileSize", reason));
    }

    public static PayloadTooLargeException fileSizeLimitExceeded() {
        return new PayloadTooLargeException("파일 크기는 10MB 이하여야 합니다.");
    }

    public static PayloadTooLargeException uploadedFileSizeExceeded() {
        return new PayloadTooLargeException("실제 업로드된 파일 크기가 10MB를 초과했습니다.");
    }
}
