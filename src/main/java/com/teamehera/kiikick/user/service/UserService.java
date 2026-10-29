package com.teamehera.kiikick.user.service;

import com.teamehera.kiikick.global.exception.UserNotFoundException;
import com.teamehera.kiikick.user.domain.User;
import com.teamehera.kiikick.user.dto.UserCreateRequest;
import com.teamehera.kiikick.user.dto.UserResponse;
import com.teamehera.kiikick.user.dto.UserUpdateRequest;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/*
 * Service는 비즈니스 로직을 담당한다.
 *
 * Controller에서 요청을 전달받고,
 * Repository를 호출해서 필요한 작업을 수행한다.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    // Spring이 UserRepository를 주입해 준다.
    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 사용자 생성
    @Transactional
    public UserResponse create(UserCreateRequest request) {

        // 1. 새로운 사용자 UUID 생성
        UUID id = UUID.randomUUID();

        // 2. Repository에 INSERT 요청
        userRepository.save(id, request.nickname());

        // 3. 방금 저장한 사용자를 다시 조회
        //    DB에서 설정된 created_at, updated_at도 가져온다.
        User savedUser = findActiveUser(id);

        // 4. 응답 DTO로 변환
        return UserResponse.from(savedUser);
    }

    // 사용자 단건 조회
    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {

        User user = findActiveUser(id);

        return UserResponse.from(user);
    }

    // 사용자 전체 조회
    @Transactional(readOnly = true)
    public List<UserResponse> getAll() {

        return userRepository.findAll()
                .stream()
                .map(UserResponse::from)
                .toList();
    }

    // 사용자 닉네임 수정
    @Transactional
    public UserResponse update(UUID id, UserUpdateRequest request) {

        // UPDATE가 실제로 변경한 행의 수를 확인한다.
        int updatedRows = userRepository.updateNickname(
                id,
                request.nickname()
        );

        if (updatedRows == 0) {
            throw new UserNotFoundException(id);
        }

        // 수정된 최신 사용자 정보 반환
        return UserResponse.from(findActiveUser(id));
    }

    // 사용자 Soft Delete
    @Transactional
    public void delete(UUID id) {

        int deletedRows = userRepository.softDelete(id);

        if (deletedRows == 0) {
            throw new UserNotFoundException(id);
        }
    }

    // 여러 메서드에서 공통으로 사용하는 활성 사용자 조회
    private User findActiveUser(UUID id) {

        return userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
    }
}
