package com.mcpcapstone.meettoticket.common.error;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 모든 에러를 { code, message } 형식으로 응답한다.
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    // 첫 번째 검증 실패 메시지를 그대로 보여준다.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message =
                e.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(error -> error.getDefaultMessage())
                        .orElse(ErrorCode.VALIDATION_FAILED.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), message));
    }

    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class,
        org.springframework.web.multipart.MaxUploadSizeExceededException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformed(Exception e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED));
    }
}
