package com.teamehera.kiikick.user.controller;

import com.teamehera.kiikick.user.dto.UserCreateRequest;
import com.teamehera.kiikick.user.dto.UserResponse;
import com.teamehera.kiikick.user.dto.UserUpdateRequest;
import com.teamehera.kiikick.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
@RestController
@RequestMapping("/api/v1")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /*
     * POST /api/v1/user
     *
     * @RequestBody: JSON 요청 본문을 DTO로 변환
     * @Valid: 닉네임 검증 실행
     */
    @PostMapping("/user")
    public ResponseEntity<UserResponse> create(
            @Valid @RequestBody UserCreateRequest request
    ) {
        UserResponse response = userService.create(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    /* 개인 사용자 조회
     * GET /api/v1/user/{userId}
     *
     * @PathVariable: URL의 UUID를 Java UUID로 변환
     */
    @GetMapping("/user/{userId}")
    public UserResponse getById(
            @PathVariable("userId") UUID userId
    ) {
        return userService.getById(userId);
    }

    // GET /api/v1/users
    @GetMapping("/users")
    public List<UserResponse> getAll() {
        return userService.getAll();
    }

    // PUT /api/v1/user/{userId} -> 닉네임 수정
    @PutMapping("/user/{userId}")
    public UserResponse update(
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody UserUpdateRequest request
    ) {
        return userService.update(userId, request);
    }

    /*
     * DELETE /api/v1/user/{userId}
     *
     * 성공 시 HTTP 204 No Content 반환
     */
    @DeleteMapping("/user/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("userId") UUID userId
    ) {
        userService.delete(userId);
    }
}
