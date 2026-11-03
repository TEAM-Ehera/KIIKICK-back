package com.teamehera.kiikick.user.controller;

import com.teamehera.kiikick.user.dto.UserCreateRequest;
import com.teamehera.kiikick.user.dto.UserResponse;
import com.teamehera.kiikick.user.dto.UserUpdateRequest;
import com.teamehera.kiikick.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/*
 * Controller는 HTTP 요청을 받는 진입점이다.
 *
 * 실제 SQL 실행은 Repository가,
 * 비즈니스 로직은 Service가 담당한다.
 */
@Tag(name = "User", description = "사용자 생성·조회·수정·삭제 API")
@RestController
@RequestMapping("/api/v1")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(
            summary = "사용자 생성",
            description = "닉네임을 입력받아 사용자를 생성하고 서버에서 생성한 UUID를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "사용자 생성 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "닉네임 누락, 공백 또는 16자 초과",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(
                                    value = "{\"message\":\"닉네임은 필수입니다.\"}"
                            )
                    )
            )
    })
    @PostMapping("/user")
    public ResponseEntity<UserResponse> create(
            @Valid @RequestBody UserCreateRequest request
    ) {
        UserResponse response = userService.create(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @Operation(
            summary = "개인 사용자 조회",
            description = "UUID로 삭제되지 않은 사용자 한 명을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사용자 조회 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "userId가 올바른 UUID 형식이 아님",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(type = "object")
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자가 존재하지 않거나 이미 삭제됨",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(
                                    value = "{\"message\":\"사용자를 찾을 수 없습니다. id=550e8400-e29b-41d4-a716-446655440000\"}"
                            )
                    )
            )
    })
    @GetMapping("/user/{userId}")
    public UserResponse getById(
            @Parameter(
                    description = "조회할 사용자 UUID",
                    required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @PathVariable("userId") UUID userId
    ) {
        return userService.getById(userId);
    }

    @Operation(
            summary = "전체 사용자 조회",
            description = "soft delete되지 않은 전체 사용자를 조회합니다. 사용자가 없으면 빈 배열을 반환합니다."
    )
    @ApiResponse(
            responseCode = "200",
            description = "전체 사용자 조회 성공",
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = UserResponse.class))
            )
    )
    @GetMapping("/users")
    public List<UserResponse> getAll() {
        return userService.getAll();
    }

    @Operation(
            summary = "개인 사용자 수정",
            description = "사용자의 닉네임을 변경하고 updatedAt을 현재 UTC 시각으로 갱신합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "사용자 수정 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "UUID 형식 오류 또는 닉네임 검증 실패",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(type = "object")
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자가 존재하지 않거나 이미 삭제됨",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(
                                    value = "{\"message\":\"사용자를 찾을 수 없습니다. id=550e8400-e29b-41d4-a716-446655440000\"}"
                            )
                    )
            )
    })
    @PutMapping("/user/{userId}")
    public UserResponse update(
            @Parameter(
                    description = "수정할 사용자 UUID",
                    required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        return userService.update(userId, request);
    }

    @Operation(
            summary = "개인 사용자 삭제",
            description = "행을 삭제하지 않고 deletedAt을 기록하는 soft delete 방식으로 처리합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "204",
                    description = "사용자 삭제 성공",
                    content = @Content
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "userId가 올바른 UUID 형식이 아님",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(type = "object")
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자가 존재하지 않거나 이미 삭제됨",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(
                                    value = "{\"message\":\"사용자를 찾을 수 없습니다. id=550e8400-e29b-41d4-a716-446655440000\"}"
                            )
                    )
            )
    })
    @DeleteMapping("/user/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(
                    description = "삭제할 사용자 UUID",
                    required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @PathVariable("userId") UUID userId
    ) {
        userService.delete(userId);
    }
}
