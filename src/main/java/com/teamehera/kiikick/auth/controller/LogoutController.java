package com.teamehera.kiikick.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.teamehera.kiikick.auth.dto.AuthApiErrorResponse;
import com.teamehera.kiikick.auth.dto.LogoutRequest;
import com.teamehera.kiikick.auth.security.AuthenticatedUser;
import com.teamehera.kiikick.auth.service.LogoutService;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import com.teamehera.kiikick.match.service.MatchMutation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

@Tag(name = "Auth / 세션")
@RestController
@RequestMapping("/api/v1/auth")
public class LogoutController {
    private final LogoutService service;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    public LogoutController(LogoutService service,AuthApiResponseFactory responses,ObjectMapper json) {
        this.service=service; this.responses=responses; this.json=json;
    }

    @Operation(operationId = "logoutAuthSession", summary = "본인 세션 로그아웃", description = "{}는 현재 세션, sessionId 지정은 다른 본인 세션. null은 422. 타인·미존재는 같은 403, 이미 폐기된 본인은 204. Refresh만 차단하고 Access는 만료까지 최대 15분 유효. If-Match 사용 안 함. 멱등 보존 1일은 임시 설정.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "본인 세션 폐기·반복 폐기 성공. 본문 없음", content = @Content, headers = @Header(name = "X-Request-Id", schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 신규 요청의 If-Match 미지원", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"INVALID_REQUEST\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "401", description = "UNAUTHENTICATED / TOKEN_EXPIRED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"UNAUTHENTICATED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")), headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string", example = "Bearer"))),
        @ApiResponse(responseCode = "403", description = "FORBIDDEN: 타인·미존재 세션", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_REUSED / REQUEST_IN_PROGRESS", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"IDEMPOTENCY_KEY_REUSED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "415", description = "application/json 필요", content = @Content),
        @ApiResponse(responseCode = "422", description = "VALIDATION_FAILED: 멱등 키·본문·sessionId 검증", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":false,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}"))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE: 폐기·멱등 저장 롤백", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthApiErrorResponse.class), examples = @ExampleObject(value = "{\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"요청을 처리할 수 없습니다.\",\"retryable\":true,\"persistence\":\"NOT_SAVED\",\"details\":{}},\"meta\":{\"requestId\":\"req-error-example\",\"serverTime\":\"2026-11-06T12:00:00.123456+09:00\"}}")))
    })
    @PostMapping(value="/logout",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> logout(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser actor,
            @Parameter(description = "같은 동작 재전송에는 동일 UUID 유지. 다른 요청에 재사용하면 409", required = true, schema = @Schema(type = "string", format = "uuid", example = "00000016-0000-4000-8000-000000000016")) @RequestHeader("Idempotency-Key") String key,
            @Parameter(hidden = true) @RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String ifMatch,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json", schema = @Schema(implementation = com.teamehera.kiikick.auth.dto.LogoutRequest.class), examples = @ExampleObject(value = "{}")))
            @RequestBody byte[] body) {
        LogoutRequest request=json.readValue(body,LogoutRequest.class);
        if (request==null) throw new LogoutRequest.InvalidInput("body");
        var id=LogoutRequest.uuid(key,"Idempotency-Key");
        var meta=responses.meta();
        String headers=json.writeValueAsString(Map.of("X-Request-Id",meta.requestId(),"Cache-Control","no-store"));
        var saved=service.logout(actor,request.sessionId(),id,ifMatch,body,headers);
        var result=ResponseEntity.status(saved.status());
        json.readTree(saved.headersJson()).properties().forEach(e -> result.header(e.getKey(),e.getValue().asText()));
        return result.build();
    }

    @ExceptionHandler({LogoutRequest.InvalidInput.class,JacksonException.class,
            MissingRequestHeaderException.class,HttpMessageNotReadableException.class})
    public ResponseEntity<AuthApiErrorResponse> invalid(Exception exception) {
        String field=exception instanceof MissingRequestHeaderException ? "Idempotency-Key" : "body";
        for(Throwable cause=exception;cause!=null;cause=cause.getCause()) {
            if(cause instanceof LogoutRequest.InvalidInput input) {field=input.field();break;}
        }
        return error(422,"VALIDATION_FAILED","로그아웃 요청 형식을 확인해주세요.",false,
                Map.of("fields",List.of(field)));
    }

    @ExceptionHandler(MatchMutation.Rejected.class)
    public ResponseEntity<AuthApiErrorResponse> idempotency(MatchMutation.Rejected exception) {
        return error(exception.status(),exception.getMessage(),"로그아웃 요청을 처리할 수 없습니다.",
                "REQUEST_IN_PROGRESS".equals(exception.getMessage()),Map.of());
    }

    @ExceptionHandler(VerificationApiException.class)
    public ResponseEntity<AuthApiErrorResponse> failed(VerificationApiException exception) {
        return error(exception.status().value(),exception.code(),exception.getMessage(),exception.retryable(),Map.of());
    }

    private ResponseEntity<AuthApiErrorResponse> error(int status,String code,String message,
            boolean retryable,Map<String,Object> details) {
        var meta=responses.meta();
        var response=ResponseEntity.status(status).header("X-Request-Id",meta.requestId());
        if(status==401) response.header(HttpHeaders.WWW_AUTHENTICATE,"Bearer");
        return response.body(new AuthApiErrorResponse(
                new AuthApiErrorResponse.AuthApiError(code,message,retryable,"NOT_SAVED",details),meta));
    }
}
