package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.service.MatchListQuery.InvalidListRequest;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchSettingsService.InvalidState;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import com.teamehera.kiikick.match.service.MatchOwnership.ResourceNotFound;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@Order(0)
@RestControllerAdvice(annotations = MatchApiController.class)
public class MatchApiExceptionHandler {

    @ExceptionHandler(ResourceNotFound.class)
    public ResponseEntity<MatchApiResponse.Failure> notFound(HttpServletRequest request) {
        return failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "기록을 찾을 수 없습니다.", Map.of(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MatchController.InvalidRequest.class})
    public ResponseEntity<MatchApiResponse.Failure> invalidRequest(HttpServletRequest request) {
        return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식을 확인해주세요.", Map.of(), request);
    }

    @ExceptionHandler(InvalidListRequest.class)
    public ResponseEntity<MatchApiResponse.Failure> invalidListRequest(InvalidListRequest exception, HttpServletRequest request) {
        return failure(HttpStatus.BAD_REQUEST, exception.getMessage(), "목록 조회 조건을 확인해주세요.", Map.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MatchApiResponse.Failure> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField())
                .distinct()
                .sorted()
                .toList();
        return failure(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED",
                "입력값을 확인해주세요.", Map.of("fields", fields), request);
    }

    @ExceptionHandler(MatchSettings.InvalidSettings.class)
    public ResponseEntity<MatchApiResponse.Failure> invalidSettings(
            MatchSettings.InvalidSettings exception, HttpServletRequest request) {
        return failure(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "입력값을 확인해주세요.",
                Map.of("fields", exception.fields()), request);
    }

    @ExceptionHandler(InvalidState.class)
    public ResponseEntity<MatchApiResponse.Failure> invalidState(HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "INVALID_STATE", "경기 상태를 확인해주세요.", Map.of(), request);
    }

    @ExceptionHandler(MatchController.Unauthenticated.class)
    public ResponseEntity<MatchApiResponse.Failure> unauthenticated(HttpServletRequest request) {
        return failure(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다.", Map.of(), request);
    }

    @ExceptionHandler(MatchMutation.Rejected.class)
    public ResponseEntity<MatchApiResponse.Failure> rejected(
            MatchMutation.Rejected exception, HttpServletRequest request) {
        String message = switch (exception.getMessage()) {
            case "VERSION_CONFLICT", "PRECONDITION_REQUIRED" -> "최신 경기 정보를 확인해주세요.";
            case "IDEMPOTENCY_KEY_REUSED" -> "이미 다른 요청에 사용한 키입니다.";
            case "REQUEST_IN_PROGRESS" -> "요청을 처리 중입니다.";
            case "RESOURCE_NOT_FOUND" -> "기록을 찾을 수 없습니다.";
            case "UNAUTHENTICATED" -> "로그인이 필요합니다.";
            default -> "요청 형식을 확인해주세요.";
        };
        return failure(HttpStatus.valueOf(exception.status()), exception.getMessage(), message,
                exception.currentVersion() == null ? Map.of() : Map.of("currentVersion", exception.currentVersion()),
                request);
    }

    private ResponseEntity<MatchApiResponse.Failure> failure(
            HttpStatus status, String code, String message, Map<String, ?> details, HttpServletRequest request
    ) {
        String persistence = switch (request.getMethod()) {
            case "GET", "HEAD" -> null;
            default -> "NOT_SAVED";
        };
        boolean retryable = "REQUEST_IN_PROGRESS".equals(code);
        var error = new MatchApiResponse.Error(code, message, retryable, persistence, details);
        var response = ResponseEntity.status(status);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(new MatchApiResponse.Failure(error, MatchApiResponse.Meta.create()));
    }
}
