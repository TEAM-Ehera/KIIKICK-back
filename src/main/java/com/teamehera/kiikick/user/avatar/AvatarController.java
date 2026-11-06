package com.teamehera.kiikick.user.avatar;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import com.teamehera.kiikick.auth.dto.AuthApiErrorResponse;
import com.teamehera.kiikick.auth.dto.AuthApiResponse;
import com.teamehera.kiikick.auth.security.AuthenticatedUser;
import com.teamehera.kiikick.user.service.MyProfileService.MissingUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestController
@RequestMapping("/api/v1/users/me/avatar")
@SecurityRequirement(name = "bearerAuth")
public class AvatarController {
    private final AvatarService avatars;
    private final AuthApiResponseFactory responses;

    public AvatarController(AvatarService avatars, AuthApiResponseFactory responses) {
        this.avatars = avatars;
        this.responses = responses;
    }

    @Operation(summary = "내 프로필 사진 등록 또는 교체", description = "file 파트에 5 MiB 이하 JPEG 또는 PNG")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthApiResponse<AvatarResponse>> upload(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestPart("file") MultipartFile file) {
        var body = responses.success(new AvatarResponse(avatars.upload(actor.userId(), file)));
        return ResponseEntity.ok().header("X-Request-Id", body.meta().requestId())
                .cacheControl(CacheControl.noStore()).body(body);
    }

    @Operation(summary = "내 프로필 사진 제거")
    @DeleteMapping
    public ResponseEntity<Void> remove(@AuthenticationPrincipal AuthenticatedUser actor) {
        avatars.remove(actor.userId());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({AvatarImage.Invalid.class, MissingServletRequestPartException.class})
    ResponseEntity<AuthApiErrorResponse> invalid() {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", false);
    }

    @ExceptionHandler({AvatarImage.TooLarge.class, MaxUploadSizeExceededException.class})
    ResponseEntity<AuthApiErrorResponse> tooLarge() {
        return error(HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE", false);
    }

    @ExceptionHandler(MissingUser.class)
    ResponseEntity<AuthApiErrorResponse> missingUser() {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", false);
    }

    @ExceptionHandler({AvatarStorage.Unavailable.class, DataAccessException.class, TransactionException.class})
    ResponseEntity<AuthApiErrorResponse> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", true);
    }

    private ResponseEntity<AuthApiErrorResponse> error(HttpStatus status, String code, boolean retryable) {
        var meta = responses.meta();
        var response = ResponseEntity.status(status).header("X-Request-Id", meta.requestId())
                .cacheControl(CacheControl.noStore());
        if (status == HttpStatus.UNAUTHORIZED) response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return response.body(new AuthApiErrorResponse(new AuthApiErrorResponse.AuthApiError(
                code, "프로필 사진 요청을 처리할 수 없습니다.", retryable, "NOT_SAVED", Map.of()), meta));
    }

    public record AvatarResponse(String avatarUrl) {}
}
