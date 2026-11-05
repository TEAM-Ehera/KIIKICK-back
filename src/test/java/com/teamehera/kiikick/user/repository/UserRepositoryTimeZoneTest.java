package com.teamehera.kiikick.user.repository;

import com.teamehera.kiikick.user.dto.UserCreateRequest;
import com.teamehera.kiikick.user.dto.UserUpdateRequest;
import com.teamehera.kiikick.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.UUID;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({UserRepository.class, UserService.class})
class UserRepositoryTimeZoneTest {
    @Autowired UserRepository users;
    @Autowired UserService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    void 단건_목록_검색은_UTC_날짜와_마이크로초를_그대로_반환한다() {
        UUID id=UUID.randomUUID();
        LocalDateTime time=LocalDateTime.of(2026,11,5,0,1,2,123456000);
        jdbc.update("INSERT INTO users (id,nickname,created_at,updated_at,onboarding_completed_at,deletion_requested_at) "
                +"VALUES (?,?,?,?,?,?)",uuidToBytes(id),"utc-fixture",time,time.plusSeconds(1),time.plusSeconds(2),time.plusSeconds(3));
        var user=users.findById(id).orElseThrow();
        assertThat(user.createdAt()).isEqualTo(time);
        assertThat(user.updatedAt()).isEqualTo(time.plusSeconds(1));
        assertThat(user.onboardingCompletedAt()).isEqualTo(time.plusSeconds(2));
        assertThat(user.deletionRequestedAt()).isEqualTo(time.plusSeconds(3));
        assertThat(user.deletedAt()).isNull();
        assertThat(users.findAll()).contains(user);
        assertThat(users.searchByNickname("utc-fixture",10)).contains(user);
        assertThat(service.getById(id).createdAt()).isEqualTo(time);
    }

    @Test
    void nullable_날짜와_User_CRUD_동작을_유지한다() {
        var created=service.create(new UserCreateRequest("utc-user"));
        var user=users.findById(created.id()).orElseThrow();
        assertThat(user.onboardingCompletedAt()).isNull();
        assertThat(user.deletionRequestedAt()).isNull();
        assertThat(user.deletedAt()).isNull();
        assertThat(service.update(created.id(),new UserUpdateRequest("utc-updated")).nickname()).isEqualTo("utc-updated");
        assertThat(service.getAll()).anyMatch(value -> value.id().equals(created.id()));
        service.delete(created.id());
        assertThat(users.findById(created.id())).isEmpty();
        assertThat(users.findAll()).noneMatch(value -> value.id().equals(created.id()));
        assertThat(users.searchByNickname("utc-updated",10)).isEmpty();
    }

    @Test
    void 온보딩과_탈퇴요청_시각은_저장한_UTC값을_보존한다() {
        UUID id=UUID.randomUUID(); users.save(id,null);
        LocalDateTime time=LocalDateTime.of(2026,11,5,2,3,4,654321000);
        assertThat(users.completeOnboarding(id,"utc-user",time)).isEqualTo(1);
        assertThat(users.markDeletionRequested(id,time.plusSeconds(1))).isEqualTo(1);
        var user=users.findById(id).orElseThrow();
        assertThat(user.onboardingCompletedAt()).isEqualTo(time);
        assertThat(user.deletionRequestedAt()).isEqualTo(time.plusSeconds(1));
    }
}
