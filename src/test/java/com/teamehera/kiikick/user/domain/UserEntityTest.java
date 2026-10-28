package com.teamehera.kiikick.user.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class UserEntityTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistsAndFindsUser() {
        User user = new User("test-user");

        entityManager.persist(user);
        entityManager.flush();

        UUID userId = user.getId();
        entityManager.clear();

        User foundUser = entityManager.find(User.class, userId);

        assertAll(
                () -> assertNotNull(userId),
                () -> assertNotNull(foundUser),
                () -> assertEquals("test-user", foundUser.getNickname()),
                () -> assertNotNull(foundUser.getCreatedAt())
        );
    }
}
