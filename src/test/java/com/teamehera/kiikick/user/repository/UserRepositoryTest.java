package com.teamehera.kiikick.user.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.teamehera.kiikick.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void savesAndFindsUserById() {
        User savedUser = userRepository.saveAndFlush(new User("repository-user"));
        UUID userId = savedUser.getId();

        entityManager.clear();

        Optional<User> foundUser = userRepository.findById(userId);

        assertAll(
                () -> assertNotNull(userId),
                () -> assertTrue(foundUser.isPresent()),
                () -> assertEquals("repository-user", foundUser.orElseThrow().getNickname()),
                () -> assertNotNull(foundUser.orElseThrow().getCreatedAt())
        );
    }
}
