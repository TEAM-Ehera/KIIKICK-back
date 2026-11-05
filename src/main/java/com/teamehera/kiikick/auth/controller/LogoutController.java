package com.teamehera.kiikick.auth.controller;

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

@RestController
@RequestMapping("/api/v1/auth")
public class LogoutController {
    private final LogoutService service;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    public LogoutController(LogoutService service,AuthApiResponseFactory responses,ObjectMapper json) {
        this.service=service; this.responses=responses; this.json=json;
    }

    @PostMapping(value="/logout",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser actor,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value=HttpHeaders.IF_MATCH,required=false) String ifMatch,
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
