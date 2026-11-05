package com.teamehera.kiikick.match.api;

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
    public ResponseEntity<MatchApiResponse.Failure> notFound() {
        return failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "기록을 찾을 수 없습니다.", Map.of());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<MatchApiResponse.Failure> invalidRequest() {
        return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식을 확인해주세요.", Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<MatchApiResponse.Failure> validation(MethodArgumentNotValidException exception) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField())
                .distinct()
                .sorted()
                .toList();
        return failure(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED",
                "입력값을 확인해주세요.", Map.of("fields", fields));
    }

    private ResponseEntity<MatchApiResponse.Failure> failure(
            HttpStatus status, String code, String message, Map<String, ?> details
    ) {
        var error = new MatchApiResponse.Error(code, message, false, null, details);
        return ResponseEntity.status(status)
                .body(new MatchApiResponse.Failure(error, MatchApiResponse.Meta.create()));
    }
}
