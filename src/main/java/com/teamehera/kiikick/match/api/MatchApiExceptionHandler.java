package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.service.MatchListQuery.InvalidListRequest;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchResultQuery.ResultNotReady;
import com.teamehera.kiikick.match.service.MatchSettingsService.InvalidState;
import com.teamehera.kiikick.match.service.QuarterLifecycleService.ActiveQuarterExists;
import com.teamehera.kiikick.match.service.QuarterLifecycleService.QuarterNotEnded;
import com.teamehera.kiikick.global.exception.InvalidWorkoutPayloadException;
import com.teamehera.kiikick.workout.service.QuarterSummaryCommands;
import com.teamehera.kiikick.workout.service.QuarterWorkoutUpload;
import jakarta.servlet.http.HttpServletRequest;
import com.teamehera.kiikick.analytics.service.AnalyticsQuery;
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

    @ExceptionHandler(AnalyticsQuery.InvalidAnalytics.class)
    public ResponseEntity<MatchApiResponse.Failure> invalidAnalytics(
            AnalyticsQuery.InvalidAnalytics exception, HttpServletRequest request) {
        return invalidSettings(new MatchSettings.InvalidSettings(java.util.List.of(exception.getMessage())), request);
    }

    @ExceptionHandler(AnalyticsQuery.ConsentRequired.class)
    public ResponseEntity<MatchApiResponse.Failure> analyticsConsentRequired(HttpServletRequest request) {
        return failure(HttpStatus.FORBIDDEN, "CONSENT_REQUIRED", "양쪽 사용자의 비교 동의가 필요합니다.", Map.of(), request);
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

    @ExceptionHandler(com.teamehera.kiikick.match.service.QuarterPositionService.InvalidPositionCode.class)
    public ResponseEntity<MatchApiResponse.Failure> invalidPositionCode(HttpServletRequest request) {
        return failure(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "입력값을 확인해주세요.",
                Map.of("fields", java.util.List.of("positionCode")), request);
    }

    @ExceptionHandler(MatchSettings.InvalidSettings.class)
    public ResponseEntity<MatchApiResponse.Failure> invalidSettings(
            MatchSettings.InvalidSettings exception, HttpServletRequest request) {
        return failure(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", "입력값을 확인해주세요.",
                Map.of("fields", exception.fields()), request);
    }

    @ExceptionHandler(com.teamehera.kiikick.match.service.QuarterSettingsService.CursorSnapshotChanged.class)
    public ResponseEntity<MatchApiResponse.Failure> cursorSnapshotChanged(HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "CURSOR_SNAPSHOT_CHANGED", "첫 페이지부터 다시 조회해주세요.",
                Map.of(), request);
    }

    @ExceptionHandler(InvalidState.class)
    public ResponseEntity<MatchApiResponse.Failure> invalidState(HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "INVALID_STATE", "경기 상태를 확인해주세요.", Map.of(), request);
    }

    @ExceptionHandler(ActiveQuarterExists.class)
    public ResponseEntity<MatchApiResponse.Failure> activeQuarterExists(HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "ACTIVE_QUARTER_EXISTS", "진행 중인 쿼터를 먼저 완료해주세요.",
                Map.of(), request);
    }

    @ExceptionHandler(QuarterNotEnded.class)
    public ResponseEntity<MatchApiResponse.Failure> quarterNotEnded(HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "QUARTER_NOT_ENDED", "쿼터 측정을 먼저 마감해주세요.",
                Map.of(), request);
    }

    @ExceptionHandler(QuarterWorkoutUpload.WorkoutAlreadyExists.class)
    public ResponseEntity<MatchApiResponse.Failure> workoutAlreadyExists(HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "WORKOUT_ALREADY_EXISTS", "이미 다른 운동 원본이 저장되어 있습니다.",
                Map.of(), request);
    }

    @ExceptionHandler(QuarterWorkoutUpload.InvalidInput.class)
    public ResponseEntity<MatchApiResponse.Failure> invalidWorkoutField(
            QuarterWorkoutUpload.InvalidInput exception, HttpServletRequest request) {
        return invalidSettings(new MatchSettings.InvalidSettings(java.util.List.of(exception.getMessage())), request);
    }

    @ExceptionHandler(InvalidWorkoutPayloadException.class)
    public ResponseEntity<MatchApiResponse.Failure> invalidWorkoutDate(HttpServletRequest request) {
        return invalidSettings(new MatchSettings.InvalidSettings(java.util.List.of("body")), request);
    }

    @ExceptionHandler(QuarterWorkoutController.PayloadTooLarge.class)
    public ResponseEntity<MatchApiResponse.Failure> workoutTooLarge(HttpServletRequest request) {
        return failure(HttpStatus.valueOf(413), "PAYLOAD_TOO_LARGE", "운동 원본 크기 제한을 초과했습니다.",
                Map.of(), request);
    }

    @ExceptionHandler(QuarterSummaryCommands.WorkoutRequired.class)
    public ResponseEntity<MatchApiResponse.Failure> workoutRequired(HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "WORKOUT_REQUIRED", "운동 원본을 먼저 업로드해주세요.", Map.of(), request);
    }

    @ExceptionHandler(QuarterSummaryCommands.CalculationInProgress.class)
    public ResponseEntity<MatchApiResponse.Failure> calculationInProgress(HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "CALCULATION_IN_PROGRESS", "운동 요약 계산을 진행 중입니다.", Map.of(), request);
    }

    @ExceptionHandler(ResultNotReady.class)
    public ResponseEntity<MatchApiResponse.Failure> resultNotReady(HttpServletRequest request) {
        return failure(HttpStatus.CONFLICT, "RESULT_NOT_READY", "완료된 기록의 결과만 조회할 수 있습니다.", Map.of(), request);
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
